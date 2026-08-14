package com.zcode.ideaplugin.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * ZCode 凭证读取
 *
 * 规格书「凭证注入」：
 * - 从 ~/.zcode/v2/config.json 读 enabled + kind=anthropic 的 provider
 * - 三件套 env：ZCODE_MODEL / ZCODE_BASE_URL / ANTHROPIC_API_KEY
 * - canonical model id 不加 provider 前缀（直接用 models 的 key）
 * - 显式 env 优先于 config（本类不覆盖已有 env）
 */
data class ZCodeCredentials(
    val model: String,
    val baseURL: String,
    val apiKey: String
) {
    /** 转成环境变量 map（用于注入子进程） */
    fun toEnvMap(): Map<String, String> = mapOf(
        "ZCODE_MODEL" to model,
        "ZCODE_BASE_URL" to baseURL,
        "ANTHROPIC_API_KEY" to apiKey
    )
}

object Credentials {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 从 ~/.zcode/v2/config.json 读凭证
     * @throws IllegalStateException 配置缺失或无效
     */
    fun load(configPath: Path = defaultConfigPath()): ZCodeCredentials {
        require(configPath.exists()) {
            "ZCode 配置文件不存在：$configPath（请先用 ZCode 客户端登录一次）"
        }

        val config = json.parseToJsonElement(configPath.readText()).jsonObject
        val providers = config["provider"]?.jsonObject
            ?: throw IllegalStateException("config.json 缺少 provider 字段")

        // 找第一个 enabled + anthropic 的 provider
        for ((_, provider) in providers) {
            val pv = provider.jsonObject
            val enabled = pv["enabled"]?.jsonPrimitive?.content?.toBoolean() ?: false
            val kind = pv["kind"]?.jsonPrimitive?.content ?: ""
            if (!enabled || kind != "anthropic") continue

            val options = pv["options"]?.jsonObject ?: continue
            val baseURL = options["baseURL"]?.jsonPrimitive?.content ?: continue
            val apiKey = options["apiKey"]?.jsonPrimitive?.content ?: continue

            val modelsObj = pv["models"]?.jsonObject ?: continue
            val model = modelsObj.keys.firstOrNull() ?: continue

            return ZCodeCredentials(model = model, baseURL = baseURL, apiKey = apiKey)
        }

        throw IllegalStateException("config.json 没有找到 enabled 的 anthropic provider")
    }

    /** 默认配置路径：~/.zcode/v2/config.json */
    fun defaultConfigPath(): Path {
        val home = System.getProperty("user.home")
        return Path.of(home, ".zcode", "v2", "config.json")
    }
}

/** 默认的 zcode.cjs 路径（Windows 标准 Electron 安装位置） */
object ZCodeLocator {
    /** 标准 Windows 安装路径 */
    fun windowsDefault(): Path = Path.of(
        System.getenv("LOCALAPPDATA") ?: "C:\\Users\\Public",
        "Programs", "ZCode", "resources", "glm", "zcode.cjs"
    )

    /** 标准 macOS 安装路径 */
    fun macDefault(): Path = Path.of(
        "/Applications/ZCode.app/Contents/Resources/glm/zcode.cjs"
    )

    /** 标准 Linux 安装路径 */
    fun linuxDefault(): Path = Path.of(
        "/opt/ZCode/app/resources/glm/zcode.cjs"
    )

    /** 自动按操作系统探测 */
    fun detect(): Path {
        val os = System.getProperty("os.name").lowercase()
        val candidate = when {
            os.contains("win") -> windowsDefault()
            os.contains("mac") || os.contains("darwin") -> macDefault()
            else -> linuxDefault()
        }
        require(candidate.exists()) {
            "ZCode CLI 未找到：$candidate（请确认 ZCode 已安装）"
        }
        return candidate
    }
}
