package com.openai.codex.jetbrains.protocol

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

sealed class InboundMessage {
    data class Response(
        val id: JsonElement,
        val result: JsonElement?,
        val error: RpcError?,
    ) : InboundMessage()

    data class Request(
        val id: JsonElement,
        val method: String,
        val params: JsonElement,
    ) : InboundMessage()

    data class Notification(
        val method: String,
        val params: JsonElement,
    ) : InboundMessage()
}

data class RpcError(val code: Int, val message: String, val data: JsonElement?)

class MalformedMessageException(message: String, cause: Throwable? = null) : Exception(message, cause)

object JsonlProtocol {
    private val gson = Gson()

    fun parse(line: String): InboundMessage {
        val root = try {
            JsonParser.parseString(line)
        } catch (error: RuntimeException) {
            throw MalformedMessageException("Invalid JSONL message", error)
        }
        if (!root.isJsonObject) throw MalformedMessageException("JSONL message must be an object")
        val obj = root.asJsonObject
        val id = obj.get("id")
        val method = obj.get("method")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

        if (method != null && id != null && !id.isJsonNull) {
            return InboundMessage.Request(id.deepCopy(), method, obj.get("params") ?: JsonObject())
        }
        if (method != null) {
            return InboundMessage.Notification(method, obj.get("params") ?: JsonObject())
        }
        if (id != null && !id.isJsonNull) {
            val error = obj.getAsJsonObject("error")?.let {
                RpcError(
                    code = it.get("code")?.asInt ?: -1,
                    message = it.get("message")?.asString ?: "Unknown app-server error",
                    data = it.get("data"),
                )
            }
            if (!obj.has("result") && error == null) {
                throw MalformedMessageException("Response contains neither result nor error")
            }
            return InboundMessage.Response(id.deepCopy(), obj.get("result"), error)
        }
        throw MalformedMessageException("Message contains neither method nor response id")
    }

    fun request(id: Long, method: String, params: JsonElement): String {
        val obj = JsonObject()
        obj.addProperty("method", method)
        obj.addProperty("id", id)
        obj.add("params", params)
        return gson.toJson(obj)
    }

    fun notification(method: String, params: JsonElement): String {
        val obj = JsonObject()
        obj.addProperty("method", method)
        obj.add("params", params)
        return gson.toJson(obj)
    }

    fun response(id: JsonElement, result: JsonElement): String {
        val obj = JsonObject()
        obj.add("id", id)
        obj.add("result", result)
        return gson.toJson(obj)
    }

    fun errorResponse(id: JsonElement, code: Int, message: String): String {
        val obj = JsonObject()
        obj.add("id", id)
        obj.add("error", JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        })
        return gson.toJson(obj)
    }

    fun idKey(id: JsonElement): String = gson.toJson(id)
}
