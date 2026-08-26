package com.openai.codex.jetbrains.bridge

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** A replayable WebSocket payload whose large form lives in a private temporary file. */
internal sealed interface RelayPayload : AutoCloseable {
    val size: Long
    fun openStream(): InputStream
}

internal class MemoryRelayPayload(private val bytes: ByteArray) : RelayPayload {
    override val size: Long = bytes.size.toLong()

    override fun openStream(): InputStream = ByteArrayInputStream(bytes)

    override fun close() = Unit
}

private class FileRelayPayload(
    private val path: Path,
    override val size: Long,
) : RelayPayload {
    override fun openStream(): InputStream = BufferedInputStream(Files.newInputStream(path))

    override fun close() {
        runCatching { Files.deleteIfExists(path) }
    }
}

/**
 * Buffers small protocol payloads in memory and spills larger payloads to the
 * bridge's private state directory. Ownership transfers to [finish].
 */
internal class RelayPayloadSpool(
    private val directory: Path,
    private val memoryThreshold: Int = DEFAULT_MEMORY_THRESHOLD,
) : AutoCloseable {
    private var memory: ByteArrayOutputStream? = ByteArrayOutputStream(minOf(memoryThreshold, COPY_BUFFER_SIZE))
    private var file: Path? = null
    private var fileOutput: OutputStream? = null
    private var completed = false
    private var byteCount = 0L

    val size: Long get() = byteCount

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        check(!completed) { "payload spool already completed" }
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        if (fileOutput == null && byteCount + length > memoryThreshold) spillToFile()
        (fileOutput ?: memory!!).write(bytes, offset, length)
        byteCount += length
    }

    fun append(payload: RelayPayload) {
        payload.openStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                write(buffer, 0, count)
            }
        }
    }

    fun finish(): RelayPayload {
        check(!completed) { "payload spool already completed" }
        completed = true
        val currentFile = file
        return if (currentFile == null) {
            MemoryRelayPayload(memory!!.toByteArray())
        } else {
            fileOutput!!.close()
            fileOutput = null
            file = null
            FileRelayPayload(currentFile, byteCount)
        }
    }

    override fun close() {
        if (completed) return
        completed = true
        runCatching { fileOutput?.close() }
        fileOutput = null
        file?.let { path -> runCatching { Files.deleteIfExists(path) } }
        file = null
        memory = null
    }

    private fun spillToFile() {
        Files.createDirectories(directory)
        val path = Files.createTempFile(directory, "relay-payload-", ".tmp")
        val output = BufferedOutputStream(
            Files.newOutputStream(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
        )
        memory!!.writeTo(output)
        memory = null
        file = path
        fileOutput = output
    }

    private companion object {
        const val DEFAULT_MEMORY_THRESHOLD = 256 * 1024
        const val COPY_BUFFER_SIZE = 64 * 1024
    }
}

internal fun RelayPayload.readBytes(maxBytes: Int): ByteArray? {
    if (size > maxBytes || size > Int.MAX_VALUE) return null
    val result = ByteArray(size.toInt())
    openStream().use { input ->
        var offset = 0
        while (offset < result.size) {
            val count = input.read(result, offset, result.size - offset)
            if (count < 0) error("payload ended before its declared size")
            offset += count
        }
        check(input.read() < 0) { "payload exceeded its declared size" }
    }
    return result
}

internal fun RelayPayload.copy(maxBytes: Int): RelayPayload =
    MemoryRelayPayload(checkNotNull(readBytes(maxBytes)) { "control frame payload exceeded its protocol limit" })

/**
 * Materializes only a message selected by its top-level method scanner. The
 * transport has already streamed it to a private spool, so this does not add a
 * second fixed message-size ceiling to dynamic-tool or initialization payloads.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun RelayPayload.jsonObjectOrNull(): JsonObject? = runCatching {
    openStream().use { Json.decodeFromStream<JsonObject>(it) }
}.getOrNull()

/**
 * Reads only the top-level JSON-RPC `method` string. Other values are skipped
 * as a stream, so multi-megabyte catalog responses never become a JSON tree.
 */
internal object JsonRpcMethodScanner {
    fun find(payload: RelayPayload): String? = runCatching {
        payload.openStream().buffered().use(::find)
    }.getOrNull()

    private fun find(input: InputStream): String? {
        val cursor = ByteCursor(input)
        if (cursor.nextNonWhitespace() != '{'.code) return null
        val firstKey = cursor.nextNonWhitespace()
        if (firstKey == '}'.code) return null
        cursor.unread(firstKey)
        while (true) {
            if (cursor.nextNonWhitespace() != '"'.code) return null
            val key = cursor.readString(MAX_TOKEN_CHARS)
            if (cursor.nextNonWhitespace() != ':'.code) return null
            val first = cursor.nextNonWhitespace()
            if (key == "method") {
                if (first != '"'.code) return null
                return cursor.readString(MAX_TOKEN_CHARS)
            }
            cursor.skipValue(first, 0)
            when (cursor.nextNonWhitespace()) {
                ','.code -> Unit
                '}'.code -> return null
                else -> return null
            }
        }
    }

    private class ByteCursor(private val input: InputStream) {
        private var pushed = NO_BYTE

        fun read(): Int {
            val value = pushed
            return if (value == NO_BYTE) input.read() else value.also { pushed = NO_BYTE }
        }

        fun unread(value: Int) {
            check(pushed == NO_BYTE) { "only one byte of pushback is supported" }
            pushed = value
        }

        fun nextNonWhitespace(): Int {
            while (true) {
                val value = read()
                if (value < 0 || value !in WHITESPACE) return value
            }
        }

        fun readString(limit: Int): String? {
            val value = StringBuilder()
            var overflow = false
            while (true) {
                when (val next = read()) {
                    -1 -> error("unterminated JSON string")
                    '"'.code -> return if (overflow) null else value.toString()
                    '\\'.code -> {
                        val escaped = read()
                        if (escaped < 0) error("unterminated JSON escape")
                        val decoded = when (escaped) {
                            '"'.code, '\\'.code, '/'.code -> escaped.toChar()
                            'b'.code -> '\b'
                            'f'.code -> '\u000c'
                            'n'.code -> '\n'
                            'r'.code -> '\r'
                            't'.code -> '\t'
                            'u'.code -> readUnicodeEscape()
                            else -> error("invalid JSON escape")
                        }
                        if (value.length < limit) value.append(decoded) else overflow = true
                    }
                    else -> if (value.length < limit) value.append(next.toChar()) else overflow = true
                }
            }
        }

        fun skipString() {
            while (true) {
                when (read()) {
                    -1 -> error("unterminated JSON string")
                    '"'.code -> return
                    '\\'.code -> {
                        val escaped = read()
                        if (escaped < 0) error("unterminated JSON escape")
                        if (escaped == 'u'.code) repeat(4) { check(read() >= 0) }
                    }
                }
            }
        }

        fun skipValue(first: Int, depth: Int) {
            check(depth <= MAX_JSON_DEPTH) { "JSON nesting is too deep" }
            when (first) {
                '"'.code -> skipString()
                '{'.code -> skipObject(depth + 1)
                '['.code -> skipArray(depth + 1)
                -1 -> error("missing JSON value")
                else -> skipPrimitive()
            }
        }

        private fun skipObject(depth: Int) {
            var next = nextNonWhitespace()
            if (next == '}'.code) return
            while (true) {
                check(next == '"'.code) { "invalid JSON object key" }
                skipString()
                check(nextNonWhitespace() == ':'.code) { "missing JSON object colon" }
                skipValue(nextNonWhitespace(), depth)
                next = nextNonWhitespace()
                if (next == '}'.code) return
                check(next == ','.code) { "invalid JSON object separator" }
                next = nextNonWhitespace()
            }
        }

        private fun skipArray(depth: Int) {
            var next = nextNonWhitespace()
            if (next == ']'.code) return
            while (true) {
                skipValue(next, depth)
                next = nextNonWhitespace()
                if (next == ']'.code) return
                check(next == ','.code) { "invalid JSON array separator" }
                next = nextNonWhitespace()
            }
        }

        private fun skipPrimitive() {
            while (true) {
                val next = read()
                if (next < 0) return
                if (next == ','.code || next == '}'.code || next == ']'.code || next in WHITESPACE) {
                    unread(next)
                    return
                }
            }
        }

        private fun readUnicodeEscape(): Char {
            var value = 0
            repeat(4) {
                val next = read()
                value = (value shl 4) or when (next) {
                    in '0'.code..'9'.code -> next - '0'.code
                    in 'a'.code..'f'.code -> next - 'a'.code + 10
                    in 'A'.code..'F'.code -> next - 'A'.code + 10
                    else -> error("invalid JSON unicode escape")
                }
            }
            return value.toChar()
        }

        private companion object {
            const val NO_BYTE = -2
        }
    }

    private const val MAX_TOKEN_CHARS = 256
    private const val MAX_JSON_DEPTH = 128
    private val WHITESPACE = setOf(' '.code, '\t'.code, '\r'.code, '\n'.code)
}
