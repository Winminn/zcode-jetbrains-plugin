package com.zcode.ideaplugin.cua

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * 电脑控制截图的本地持久化与历史回填。
 *
 * 背景（2026-10-10 真机实锤）：CUA 工具（get_app_state 等）的截图走 v4 行
 * output.display.media 到达实时流（V4FrameMapper 透传），webview 工具卡可渲染；
 * 但会话重开/轮末重拉走 session/messages 历史装配，服务端持久化投影把图剥成
 * "[Attached image/jpeg: MCP image]" 占位文本——截图只在实时可见，重载即丢。
 *
 * 修复 = 两侧补链：
 *   - 落盘：pushStreamEvent 必经点拦截 kind=result 且带 display.media 的事件，
 *     按 (sessionId, toolCallId) 写 JSON 文件（本类 persist）；
 *   - 回填：resumeAndReadMessages 历史装配时对 tool part 按 callID 查文件，
 *     存在即把 media 数组注回 part.state（injectIntoMessages）。
 *
 * 存储：~/.zcode/cli/zcgui-cua-media/<sessionId>/<toolCallId>.json（与官方
 * image-cache 同区，随 CLI 存储生命周期）。单文件即一次截图的 media 数组
 * （~180KB JPEG base64）；会话目录超 100 文件按 mtime 删最旧，全局超 40 个
 * 会话目录删最旧目录——防无限增长（极简配额，不做精确 LRU）。
 */
object CuaMediaStore {

    private val log = Logger.getInstance(CuaMediaStore::class.java)

    private const val FILES_PER_SESSION = 100
    private const val MAX_SESSION_DIRS = 40

    /** media 数组元素允许的最大 base64 长度（防异常大文件；正常截图 ~180K 字符） */
    private const val MAX_ITEM_CHARS = 400_000

    fun root(): File = File(File(System.getProperty("user.home"), ".zcode"), "cli/zcgui-cua-media")

    fun persist(sessionId: String, toolCallId: String, media: JsonArray) {
        try {
            if (sessionId.isBlank() || toolCallId.isBlank() || media.isEmpty()) return
            val dir = File(root(), sessionId)
            dir.mkdirs() ?: return
            val tmp = File(dir, "$toolCallId.json.tmp")
            tmp.writeText(media.toString(), Charsets.UTF_8)
            val target = File(dir, "$toolCallId.json")
            if (!tmp.renameTo(target)) {
                // Windows 上目标已存在时 renameTo 可能失败：回退直写
                target.writeText(media.toString(), Charsets.UTF_8)
                tmp.delete()
            }
            pruneSession(dir)
        } catch (e: Exception) {
            log.warn("CuaMediaStore persist failed: ${e.message}")
        }
    }

    /** 历史装配回填：给 messages 里能命中落盘文件的 tool part 注入 state.media。 */
    fun injectIntoMessages(sessionId: String, messages: JsonArray): JsonArray {
        val sessionDir = File(root(), sessionId)
        if (!sessionDir.isDirectory) return messages
        var changed = false
        val out = messages.map { msgEl ->
            val msg = msgEl as? JsonObject ?: return@map msgEl
            val parts = msg["parts"] as? JsonArray ?: return@map msgEl
            var msgChanged = false
            val newParts = parts.map { partEl ->
                val part = partEl as? JsonObject ?: return@map partEl
                if (part["type"]?.jsonPrimitive?.content != "tool") return@map partEl
                val callId = part["callID"]?.jsonPrimitive?.contentOrNull
                    ?: part["call_id"]?.jsonPrimitive?.contentOrNull
                    ?: return@map partEl
                val state = part["state"] as? JsonObject ?: return@map partEl
                if (state.containsKey("media")) return@map partEl
                val media = load(sessionDir, callId) ?: return@map partEl
                msgChanged = true
                buildJsonObject {
                    part.forEach { (k, v) -> put(k, v) }
                    put("state", buildJsonObject {
                        state.forEach { (k, v) -> put(k, v) }
                        put("media", media)
                    })
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

    private fun load(sessionDir: File, toolCallId: String): JsonArray? {
        val f = File(sessionDir, "$toolCallId.json")
        if (!f.isFile) return null
        return try {
            val arr = kotlinx.serialization.json.Json.parseToJsonElement(
                f.readText(Charsets.UTF_8),
            ).jsonArray
            // 形状防御：只保留 {mimeType, data} 形态且限制大小（文件写给我们自己，
            // 但防手改/半截写）
            val cleaned = buildJsonArray {
                for (el in arr) {
                    val o = el as? JsonObject ?: continue
                    val mime = o["mimeType"]?.jsonPrimitive?.contentOrNull ?: continue
                    val data = o["data"]?.jsonPrimitive?.contentOrNull ?: continue
                    if (!mime.startsWith("image/")) continue
                    if (data.length > MAX_ITEM_CHARS) continue
                    add(buildJsonObject {
                        put("mimeType", mime)
                        put("data", data)
                    })
                }
            }
            if (cleaned.isEmpty()) null else cleaned
        } catch (e: Exception) {
            log.warn("CuaMediaStore load failed for $toolCallId: ${e.message}")
            null
        }
    }

    private fun pruneSession(dir: File) {
        try {
            val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return
            if (files.size <= FILES_PER_SESSION) return
            files.sortedBy { it.lastModified() }
                .take(files.size - FILES_PER_SESSION)
                .forEach { it.delete() }
        } catch (e: Exception) {
            log.warn("CuaMediaStore pruneSession failed: ${e.message}")
        }
    }

    /** 全局配额（落盘时顺带执行；全扫便宜——目录数即会话数）。 */
    fun pruneGlobal() {
        try {
            val r = root()
            val dirs = r.listFiles { f -> f.isDirectory } ?: return
            if (dirs.size <= MAX_SESSION_DIRS) return
            dirs.sortedBy { it.lastModified() }
                .take(dirs.size - MAX_SESSION_DIRS)
                .forEach { it.deleteRecursively() }
        } catch (e: Exception) {
            log.warn("CuaMediaStore pruneGlobal failed: ${e.message}")
        }
    }
}
