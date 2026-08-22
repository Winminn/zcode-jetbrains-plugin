package com.zcode.ideaplugin.ui

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * ZCode 客户端（桌面版）设置文件读写：~/.zcode/v2/setting.json
 *
 * 「工作区记忆（自动记忆）」开关与客户端共用这一份——桌面端 设置→常规 的开关、
 * 插件设置页的开关、app-server 的 session/requestRuntimePreferences 应答三处同源：
 * 插件切换时写回此文件，客户端下次读配置同样生效，反之亦然。
 *
 * 字段默认值对齐 zcode.cjs 的 zod schema：
 *   memoryEnabled=false / nativeSearchEnhancementsEnabled=true /
 *   askUserQuestionAutoResolutionEnabled=true
 * 该文件还存了客户端的窗口尺寸、最近项目等大量无关状态——写入时只改目标字段，
 * 其余键原样保留。
 */
object ZCodeClientSettingStore {

    private val LOCK = Any()

    private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** requestRuntimePreferences 应答所需三项 */
    data class RuntimePrefs(
        val memoryEnabled: Boolean = false,
        val nativeSearchEnhancementsEnabled: Boolean = true,
        val askUserQuestionAutoResolutionEnabled: Boolean = true,
    )

    fun settingPath(home: String = System.getProperty("user.home")): File =
        File(File(home, ".zcode/v2"), "setting.json")

    /** 读三项运行时偏好（文件缺失/损坏/字段缺失时用 CLI 侧同款默认值） */
    fun readRuntimePrefs(home: String = System.getProperty("user.home")): RuntimePrefs = synchronized(LOCK) {
        val root = readRoot(home) ?: return RuntimePrefs()
        RuntimePrefs(
            memoryEnabled = root.booleanField("memoryEnabled") ?: false,
            nativeSearchEnhancementsEnabled = root.booleanField("nativeSearchEnhancementsEnabled") ?: true,
            askUserQuestionAutoResolutionEnabled = root.booleanField("askUserQuestionAutoResolutionEnabled") ?: true,
        )
    }

    /** 只改 memoryEnabled 一个字段，其余键原样保留；tmp + 原子 move 防写坏 */
    fun writeMemoryEnabled(enabled: Boolean, home: String = System.getProperty("user.home")): Boolean =
        writeBooleanField("memoryEnabled", enabled, home)

    /**
     * 内置浏览器「忽略证书校验」开关（与 ZCode 客户端共用；zod schema 默认 false）。
     * 客户端侧消费它给自己的 BrowserView 传 --ignore-certificate-errors；
     * 插件侧消费它同步 JCEF 启动参数（见 ZCodeBrowserSettingStore.applyInsecureCertificatesToRegistry）。
     */
    fun readEmbeddedBrowserInsecure(home: String = System.getProperty("user.home")): Boolean = synchronized(LOCK) {
        val root = readRoot(home) ?: return false
        root.booleanField("embeddedBrowserAllowInsecureCertificates") ?: false
    }

    fun writeEmbeddedBrowserInsecure(enabled: Boolean, home: String = System.getProperty("user.home")): Boolean =
        writeBooleanField("embeddedBrowserAllowInsecureCertificates", enabled, home)

    /** 单布尔字段写入（保留其余键；文件缺失写最小片段——客户端按 zod schema 读缺失键走默认值）*/
    private fun writeBooleanField(key: String, value: Boolean, home: String): Boolean = synchronized(LOCK) {
        val file = settingPath(home)
        val root = readRoot(home)
        val newRoot = if (root == null) {
            buildJsonObject { put(key, value) }
        } else {
            buildJsonObject {
                root.forEach { (k, v) -> if (k != key) put(k, v) }
                put(key, value)
            }
        }
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(prettyJson.encodeToString(JsonObject.serializer(), newRoot), Charsets.UTF_8)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun readRoot(home: String): JsonObject? = try {
        val f = settingPath(home)
        if (f.isFile) Json.parseToJsonElement(f.readText(Charsets.UTF_8)).jsonObject else null
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.booleanField(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
}
