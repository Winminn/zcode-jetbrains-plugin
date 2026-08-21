package com.zcode.ideaplugin.protocol

/**
 * 日志脱敏（idea.log 防秘钥泄露）
 *
 * 背景（2026-08-21 外部用户日志核查）：日志出口会打印消息 JSON 片段（收到 JS 消息 /
 * sendToJs 预览 / state patch / DIAG-CTX / app-server stdout 转发等）。虽然插件自身
 * 不打印 apiKey，但用户可能把凭证内联在 MCP 配置的 args/url（--token=xxx、?key=xxx）、
 * 粘贴进 prompt、或经 [webview-console] 转发——统一在出口处过一遍本工具兜底。
 *
 * 覆盖四类模式（大小写不敏感）：
 *   1. 键值参数：token=xxx / api-key:xxx / ?secret=xxx 等（词边界，不误伤 keyboard=）
 *   2. JSON 字段："apiKey":"xxx" / "token":"xxx"（精确字段名，不误伤 "inputTokens" 等
 *      contextUsage 计数字段）
 *   3. 前缀式 key：sk-abcdef123456（OpenAI 风格）
 *   4. Bearer 头：Bearer eyJhbGci...
 *
 * 用法约定：先 redact 再 take 截断（截断后半截秘钥无法被模式识别，先截后脱会漏）。
 * 纯函数、无可变状态，多线程直接调用。
 */
object LogRedactor {

    private const val MASK = "***"

    /** 模式 1：键值参数（CLI 参数 / URL query / 冒号形式）。词边界 + 精确关键词 */
    private val KV_RE = Regex(
        """\b(api[_-]?key|key|token|secret|password|passwd|authorization|auth)\s*[=:]\s*[^\s&"',;}\\]+""",
        RegexOption.IGNORE_CASE,
    )

    /** 模式 2：JSON 字符串字段（精确字段名，避免命中 *Tokens 计数字段） */
    private val JSON_FIELD_RE = Regex(
        """("(?:api[_-]?key|token|secret|password|passwd|authorization|auth)")\s*:\s*"[^"]*"""",
        RegexOption.IGNORE_CASE,
    )

    /** 模式 3：前缀式 API key（sk- 开头、长度 >= 6 的凭据体） */
    private val SK_RE = Regex("""\bsk-[A-Za-z0-9_-]{6,}\b""")

    /** 模式 4：Bearer 凭证头 */
    private val BEARER_RE = Regex("""\bBearer\s+[A-Za-z0-9._\-]+""", RegexOption.IGNORE_CASE)

    /** 对任意日志文本做秘钥脱敏，返回替换为 *** 的副本 */
    fun redact(input: String): String = input
        .replace(BEARER_RE) { m -> m.value.takeWhile { !it.isWhitespace() } + " " + MASK }
        .replace(SK_RE) { m -> m.value.substringBefore("-") + "-***" }
        .replace(JSON_FIELD_RE) { m -> m.groupValues[1] + ":\"" + MASK + "\"" }
        .replace(KV_RE) { m ->
            // 保留 key 名 + 分隔符（含原文大小写与中间空白），只掩值
            val sepIdx = m.value.indexOfFirst { it == '=' || it == ':' }
            m.value.substring(0, sepIdx + 1) + MASK
        }
}
