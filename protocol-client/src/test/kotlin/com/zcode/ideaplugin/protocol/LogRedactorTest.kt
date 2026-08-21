package com.zcode.ideaplugin.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LogRedactor 脱敏规则测试：四类模式命中 + contextUsage 计数字段等误伤防护
 */
class LogRedactorTest {

    // ---- 模式 1：键值参数 ----

    @Test
    fun `kv cli arg`() {
        assertEquals("mcp-server --token=*** run", LogRedactor.redact("mcp-server --token=abc123XYZ run"))
    }

    @Test
    fun `kv url query`() {
        assertEquals("https://x.com/mcp?key=***&foo=1", LogRedactor.redact("https://x.com/mcp?key=xyz789&foo=1"))
    }

    @Test
    fun `kv api-key dash form`() {
        assertEquals("--api-key=***", LogRedactor.redact("--api-key=secretvalue"))
    }

    @Test
    fun `kv colon form and uppercase`() {
        assertEquals("TOKEN:***", LogRedactor.redact("TOKEN:abcdef123"))
    }

    // ---- 模式 2：JSON 字符串字段 ----

    @Test
    fun `json apiKey field`() {
        assertEquals("""{"options":{"apiKey":"***"}}""", LogRedactor.redact("""{"options":{"apiKey":"sk-realkey99"}}"""))
    }

    @Test
    fun `json token field mixed case`() {
        assertEquals("\"Token\":\"***\"", LogRedactor.redact("\"Token\":\"value\""))
    }

    // ---- 模式 3：前缀式 key ----

    @Test
    fun `sk prefix key`() {
        assertEquals("key is sk-***", LogRedactor.redact("key is sk-abcdef123456"))
    }

    // ---- 模式 4：Bearer ----

    @Test
    fun `bearer header`() {
        assertEquals("Bearer ***", LogRedactor.redact("Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig"))
    }

    @Test
    fun `authorization with bearer`() {
        val out = LogRedactor.redact("Authorization: Bearer eyJhbGciOiJI.sig")
        assertFalse(out.contains("eyJ"), "credential body must be masked: $out")
    }

    // ---- 误伤防护 ----

    @Test
    fun `context usage token counters untouched`() {
        val sample = """{"inputTokens":135610,"cacheWriteTokens":16662,"totalInputTokens":135610}"""
        assertEquals(sample, LogRedactor.redact(sample))
    }

    @Test
    fun `keyboard and enabled untouched`() {
        assertEquals("keyboard=us enabled=true", LogRedactor.redact("keyboard=us enabled=true"))
    }

    @Test
    fun `plain text untouched`() {
        val sample = "op=send sessionId=sess_ab12 text=\"深入理解项目代码\""
        assertEquals(sample, LogRedactor.redact(sample))
    }

    // ---- 组合场景 ----

    @Test
    fun `combined mcp args and url`() {
        val input = "args=[--transport=stdio, --token=tok123abc] url=https://mcp.x.io/sse?api_key=k9xyz"
        val out = LogRedactor.redact(input)
        assertFalse(out.contains("tok123abc"), "token value must be masked: $out")
        assertFalse(out.contains("k9xyz"), "api_key value must be masked: $out")
        assertTrue(out.contains("--transport=stdio"), "non-sensitive arg must survive: $out")
    }
}
