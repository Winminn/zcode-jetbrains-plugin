package com.zcode.ideaplugin.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * 网络代理配置（与 ZCode 客户端同源共享）：~/.zcode/v2/setting.json 的
 * httpProxy / httpProxyNoProxy / httpProxyCaCertPath 三键。
 *
 * 官方客户端把这三键在 spawn zcode.cjs 时翻译成完整代理环境变量组
 * （app.asar 主进程 buildAgentRuntimeEnv，2026-09 逆向实证）：
 * - HTTP_PROXY = HTTPS_PROXY = ALL_PROXY = ZCODE_HTTP_PROXY = <url>
 * - NO_PROXY = no_proxy = ZCODE_NO_PROXY = <列表>
 * - NODE_EXTRA_CA_CERTS = ZCODE_AGENT_CA_CERT = <证书路径>
 *
 * zcode.cjs 侧主模型请求只认 ZCODE_HTTP_PROXY（env 桥接 gxe 进 network 配置），
 * 普通 HTTPS_PROXY 仅 WebFetch 工具兜底——所以注入必须打满整套变量组，与客户端
 * 行为完全一致。setting.json 恒在 home 入口不随 dataBaseDir 迁移（与凭证链同判据），
 * 插件侧写入口（ZCodeClientSettingStore）、客户端设置页写同一份，双向生效。
 */
data class ProxyConfig(
    /** 代理地址，如 http://127.0.0.1:7890；空 = 未配置（直连） */
    val httpProxy: String? = null,
    /** no-proxy 列表（逗号分隔：localhost,127.0.0.1,*.internal） */
    val noProxy: String? = null,
    /** 自定义 CA 证书文件路径（企业代理解密场景） */
    val caCertPath: String? = null,
) {
    /** 是否配置了任何代理相关字段 */
    val isEmpty: Boolean get() = httpProxy.isNullOrBlank() && noProxy.isNullOrBlank() && caCertPath.isNullOrBlank()

    /**
     * 日志摘要（userinfo 已脱敏）：app-server/CLI spawn 注入后打印，
     * 排障问题「代理到底注入了没」的直接答案；未配置返回固定文案
     */
    val logSummary: String
        get() = if (isEmpty) "<no proxy, direct>" else buildList {
            redacted?.let { add("proxy=$it") }
            noProxy?.trim()?.takeIf { it.isNotEmpty() }?.let { add("noProxy=$it") }
            caCertPath?.trim()?.takeIf { it.isNotEmpty() }?.let { add("caCert=$it") }
        }.joinToString(", ")

    /** 代理地址脱敏值（展示用） */
    val redacted: String? get() = normalizeProxyUrl(httpProxy)?.let { ProxyConfig.redactProxyUrl(it) }

    /**
     * 转成子进程环境变量组（对齐官方 buildAgentRuntimeEnv；空字段不注入）。
     * httpProxy 无协议前缀自动补 http://（客户端 normalizeProxyValue 同规则）。
     */
    fun toEnvMap(): Map<String, String> = buildMap {
        normalizeProxyUrl(httpProxy)?.let { url ->
            put("HTTP_PROXY", url)
            put("HTTPS_PROXY", url)
            put("ALL_PROXY", url)
            put("ZCODE_HTTP_PROXY", url)
        }
        normalizeNoProxy(noProxy)?.let { list ->
            put("NO_PROXY", list)
            put("no_proxy", list)
            put("ZCODE_NO_PROXY", list)
        }
        caCertPath?.trim()?.takeIf { it.isNotEmpty() }?.let { p ->
            put("NODE_EXTRA_CA_CERTS", p)
            put("ZCODE_AGENT_CA_CERT", p)
        }
    }

    companion object {
        /** 代理地址归一：无 协议:// 前缀补 http://（官方 normalizeProxyValue 同规则） */
        fun normalizeProxyUrl(raw: String?): String? {
            val t = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return if (PROTOCOL_PREFIX.containsMatchIn(t)) t else "http://$t"
        }

        /** no-proxy 归一：逗号分隔逐项 trim 去空再拼回（官方 normalizeNoProxyValue 同规则） */
        fun normalizeNoProxy(raw: String?): String? {
            val joined = raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(",")
            return joined?.takeIf { it.isNotEmpty() }
        }

        /**
         * 日志脱敏：代理 URL 的 userinfo 段（http://user:pass@host:port 认证形态）打码，
         * host:port 保留——排障需要的就是它。无 userinfo 原样返回。
         */
        fun redactProxyUrl(url: String?): String? {
            val t = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return USERINFO_RE.replace(t, "//***@")
        }

        private val PROTOCOL_PREFIX = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)
        private val USERINFO_RE = Regex("//[^/@]+@")
    }
}

/** setting.json 代理三键读取（写入走插件 ui 层 ZCodeClientSettingStore，同文件原子写） */
object ProxyConfigStore {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 读 home 入口的 ~/.zcode/v2/setting.json。
     * 文件缺失/损坏/无代理键 → 全空 ProxyConfig（等价未配置，spawn 不注入）。
     */
    fun read(home: String = System.getProperty("user.home") ?: "."): ProxyConfig {
        return try {
            val setting = Path.of(home, ".zcode", "v2", "setting.json")
            if (!setting.isRegularFile()) return ProxyConfig()
            val root = json.parseToJsonElement(setting.readText()).jsonObject
            ProxyConfig(
                httpProxy = root.strField("httpProxy"),
                noProxy = root.strField("httpProxyNoProxy"),
                caCertPath = root.strField("httpProxyCaCertPath"),
            )
        } catch (_: Exception) {
            ProxyConfig()
        }
    }

    private fun kotlinx.serialization.json.JsonObject.strField(key: String): String? =
        this[key]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
}
