package com.zcode.ideaplugin.protocol.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * ZCode Protocol 消息模型
 *
 * 协议格式（0.16.1 实测，参考 zcode-protocol-spec-0.16.1.md）：
 * - 请求:    {"id": <num>, "method": "<ns/name>", "params": {...}}
 * - 响应:    {"id": <num>, "result": {...}} 或 {"id": <num>, "error": {...}}
 * - 通知:    {"method": "<name>", "params": {...}}  （无 id）
 * - 反向请求: {"id": "server-N", "method": "<name>", "params": {...}}  （id 是字符串）
 *
 * ⚠️ 关键约束：消息**不带** `jsonrpc` 字段（0.16+ 硬约束，否则 -32600）
 */

/** 协议消息的密封层级 */
sealed class ProtocolMessage {
    /** 客户端→服务器的请求 */
    data class Request(
        val id: Long,
        val method: String,
        val params: JsonObject = JsonObject(emptyMap())
    ) : ProtocolMessage()

    /** 服务器→客户端的响应 */
    data class Response(
        val id: Long,
        val result: JsonElement? = null,
        val error: ProtocolError? = null
    ) : ProtocolMessage()

    /** 服务器→客户端的反向请求（需要客户端应答，id 是 "server-N" 形式） */
    data class ServerRequest(
        val id: String,
        val method: String,
        val params: JsonObject = JsonObject(emptyMap())
    ) : ProtocolMessage()

    /** 客户端→服务器对反向请求的应答 */
    data class ServerResponse(
        val id: String,
        val result: JsonElement? = null,
        val error: ProtocolError? = null
    ) : ProtocolMessage()

    /** 通知（无 id，单向） */
    data class Notification(
        val method: String,
        val params: JsonObject = JsonObject(emptyMap())
    ) : ProtocolMessage()
}

data class ProtocolError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

/** 常见错误码 */
object ErrorCodes {
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val SESSION_UNAVAILABLE = -32004
}
