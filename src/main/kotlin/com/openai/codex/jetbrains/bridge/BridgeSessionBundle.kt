package com.openai.codex.jetbrains.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Narrow owner for the private capability files, loopback relay and one
 * supervised launch. No executable is discovered or started by the JVM.
 */
internal class BridgeSessionBundle private constructor(
    private val stateDirectory: Path,
    private val relayTokenFile: Path,
    private val appServerTokenFile: Path,
    private val relayFailureMarker: Path,
    private val launchScriptFile: Path,
    private val terminalCommand: String,
    private val relay: WebSocketRelay,
) : Disposable {
    private val disposed = AtomicBoolean(false)

    fun launchCommand(): String = terminalCommand

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        relay.close()
        runCatching { Files.deleteIfExists(relayTokenFile) }
        runCatching { Files.deleteIfExists(appServerTokenFile) }
        runCatching { Files.deleteIfExists(relayFailureMarker) }
        runCatching { Files.deleteIfExists(launchScriptFile) }
        runCatching { Files.deleteIfExists(stateDirectory.resolve("app-server.log")) }
        runCatching { Files.deleteIfExists(stateDirectory.resolve("app-server.err")) }
        runCatching { Files.deleteIfExists(stateDirectory) }
    }

    companion object {
        fun create(project: Project, root: Path): BridgeSessionBundle {
            val state = Files.createTempDirectory("codex-jetbrains-bridge-")
            val relayToken = state.resolve("remote.token")
            val appServerToken = state.resolve("app-server.token")
            val relayFailureMarker = state.resolve("relay-failed")
            Files.writeString(relayToken, token())
            Files.writeString(appServerToken, token())
            restrict(state, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
            restrict(relayToken, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            restrict(appServerToken, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            val snapshots = JetBrainsFileSnapshots()
            val validator = FileChangeValidator(root, snapshots)
            val presenter = JetBrainsNativeDiffPresenter(project)
            val coordinator = FileChangeApprovalCoordinator(validator, presenter)
            val openDiffs = OpenDiffCoordinator(
                OpenDiffValidator(root, snapshots),
                JetBrainsOpenDiffPresenter(project),
                JetBrainsOpenDiffWriter(project),
            )
            val appServerPort = freePort()
            val relay = WebSocketRelay(
                appServerPort = appServerPort,
                relayToken = Files.readString(relayToken),
                appServerToken = Files.readString(appServerToken),
                approvals = coordinator,
                openDiffs = openDiffs,
                failureMarker = relayFailureMarker,
                // The foreground shell consumes a pre-ready failure marker before
                // its trap removes this directory. Project disposal owns the
                // remaining defensive cleanup path.
                onClosed = {},
            )
            val launch = BridgeLaunchCommand.install(
                BridgeLaunchSpec(
                    relayEndpoint = relay.endpoint,
                    appServerEndpoint = "ws://127.0.0.1:$appServerPort",
                    relayTokenFile = relayToken,
                    appServerTokenFile = appServerToken,
                    relayFailureMarker = relayFailureMarker,
                    stateDirectory = state,
                ),
            )
            val bundle = BridgeSessionBundle(
                state,
                relayToken,
                appServerToken,
                relayFailureMarker,
                launch.scriptFile,
                launch.terminalCommand,
                relay,
            )
            relay.start()
            return bundle
        }

        private fun token(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(32).also(SecureRandom()::nextBytes),
        )

        private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

        private fun restrict(path: Path, permissions: Set<PosixFilePermission>) {
            runCatching { Files.setPosixFilePermissions(path, permissions) }
        }
    }
}

private class JetBrainsFileSnapshots : FileSnapshotStore, OpenDiffSnapshotStore {
    override fun read(path: Path): String? = if (Files.isRegularFile(path)) Files.readString(path) else null

    override fun hasUnsavedDocument(path: Path): Boolean {
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return false
        val document: Document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return false
        return FileDocumentManager.getInstance().isDocumentUnsaved(document)
    }
}
