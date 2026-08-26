package com.zcode.ideaplugin.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * 用户消息图片 part 的读回适配（2026-08-26 协议实测 + zcode.cjs 源码确认）
 *
 * session/messages 读回的用户图片 part 形态是 `type:"file"`（不是发送时的 image）：
 * ```
 * { type: "file", mime: "image/png",
 *   url: "zcode-artifact://<sessionId>/tool-result-<uuid>",
 *   metadata: { image: {...}, sizeBytes, ... }, id, sessionID, messageID }
 * ```
 * `zcode-artifact://` 是私有协议，<img> 无法加载。zcode.cjs 会把附件图片落盘到
 * `~/.zcode/cli/image-cache/<sessionId>/image-<sha256(uri) 前 32 位 hex>.<ext>`
 * （vOt 函数，实测文件名与 hash 完全一致）。本转换器把这类 part 的 url 换成
 * 调用方提供的可渲染 URL（内置 HttpServer 的 /zcode-image/ 端点）。
 *
 * 全程 fail-soft：mime 非图片 / uri 形态不符 / urlProvider 拒绝（白名单不过、
 * server 未启动）时 part 保持原样，不影响其余消息。
 */
object ImageArtifactMapper {

    private const val ARTIFACT_SCHEME = "zcode-artifact://"

    /** mime → image-cache 落盘扩展名（对齐 zcode.cjs aEn；未知格式不落盘 → 无扩展名不转换）*/
    internal fun extOf(mime: String): String? = when (mime.substringAfter(';').trim().lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> null
    }

    /**
     * artifact uri → image-cache 落盘文件名（image-<sha256(uri)[:32]>.<ext>）。
     * uri 非 zcode-artifact 形态 / mime 不落盘格式 → null。
     */
    fun cacheFileName(uri: String, mime: String): String? {
        if (!uri.startsWith(ARTIFACT_SCHEME)) return null
        val ext = extOf(mime) ?: return null
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(uri.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
        return "image-$hash.$ext"
    }

    /**
     * uri → 所属会话目录名（artifact uri 的 host 段；sess_ 形态本就满足目录净化规则）。
     * 无 host 段返回 null。
     */
    fun sessionIdOf(uri: String): String? {
        if (!uri.startsWith(ARTIFACT_SCHEME)) return null
        val rest = uri.removePrefix(ARTIFACT_SCHEME)
        val host = rest.substringBefore('/', "")
        return host.ifEmpty { null }
    }

    /**
     * 遍历消息数组，把用户消息里 `type:"file"` 且 mime 以 "image/" 开头的 part 的
     * url 换成 urlProvider 给出的可渲染地址。urlProvider 返回 null 的 part 原样保留。
     * （注意：Kotlin 块注释可嵌套，注释里不能写 image/星号 的字面量）
     *
     * @param urlProvider (sessionId, fileName) → 可渲染 URL（如 http://127.0.0.1:port/zcode-image/...），
     *   实现方负责白名单与存在性校验（见 ZCodeWebviewServer.imageUrl）
     * @return 转换后的消息数组（无命中时原引用直接返回，避免无谓拷贝）
     */
    fun mapMessages(
        messages: JsonArray,
        urlProvider: (sessionId: String, fileName: String) -> String?,
    ): JsonArray {
        var changed = false
        val out = messages.map { msgEl ->
            val msg = msgEl as? JsonObject ?: return@map msgEl
            val parts = msg["parts"] as? JsonArray ?: return@map msgEl
            var msgChanged = false
            val newParts = parts.map { partEl ->
                val part = partEl as? JsonObject ?: return@map partEl
                if (part["type"]?.jsonPrimitive?.content != "file") return@map partEl
                val mime = part["mime"]?.jsonPrimitive?.content ?: return@map partEl
                if (!mime.startsWith("image/")) return@map partEl
                val uri = part["url"]?.jsonPrimitive?.content ?: return@map partEl
                val fileName = cacheFileName(uri, mime) ?: return@map partEl
                val sid = sessionIdOf(uri) ?: return@map partEl
                val url = urlProvider(sid, fileName) ?: return@map partEl
                msgChanged = true
                buildJsonObject {
                    part.forEach { (k, v) -> put(k, v) }
                    put("url", url)
                }
            }
            if (!msgChanged) return@map msgEl
            changed = true
            buildJsonObject {
                msg.forEach { (k, v) -> put(k, v) }
                put("parts", JsonArray(newParts))
            }
        }
        return if (changed) JsonArray(out) else messages
    }

    /** image-cache 根目录下文件是否存在（urlProvider 实现方用，避免前端 404 图占位）*/
    fun cacheFileExists(imageCacheRoot: File, sessionId: String, fileName: String): Boolean =
        File(File(imageCacheRoot, sessionId), fileName).isFile
}
