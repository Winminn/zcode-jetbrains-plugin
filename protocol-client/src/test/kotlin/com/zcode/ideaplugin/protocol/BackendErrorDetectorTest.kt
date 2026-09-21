package com.zcode.ideaplugin.protocol

import kotlin.test.*

/**
 * BackendErrorDetector 单元测试
 *
 * 样本取自 2026-08-18 idea.log 实录（429 token_quota_exceeded，
 * app-server stderr 的 vercel.ai APICallError dump 首行）。
 */
class BackendErrorDetectorTest {

    /** 2026-08-18 实录首行（脱敏 id） */
    private val quotaLine =
        "APICallError [AI_APICallError]: Error code: 429 - {'error': {'code': 'token_quota_exceeded', " +
            "'message': 'Token Plan Person monthly quota limit exceeded', 'type': 'quota_exceeded'}, 'id': 'as-xxx'}"

    @Test
    fun `解析 429 配额超限首行`() {
        val d = BackendErrorDetector()
        val err = d.feed(quotaLine)
        assertNotNull(err)
        assertEquals(429, err.statusCode)
        assertEquals("token_quota_exceeded", err.code)
        assertEquals("Token Plan Person monthly quota limit exceeded", err.message)
        assertTrue(err.isQuotaError)
    }

    @Test
    fun `非 APICallError 行不上报`() {
        val d = BackendErrorDetector()
        assertNull(d.feed("    at async postToApi (zcode.cjs:1593:22301)"))
        assertNull(d.feed("statusCode: 429,"))
        assertNull(d.feed("普通日志行"))
    }

    @Test
    fun `dump 后续行不含 Error code 前缀不上报`() {
        // dump 的第二行起只有堆栈/字段行，不匹配 APICallError + Error code 组合
        val d = BackendErrorDetector()
        assertNull(d.feed("APICallError at some other place without Error code prefix"))
        assertNull(d.feed("  statusCode: 429,"))
    }

    @Test
    fun `同签名在去重窗口内只上报一次`() {
        var t = 0L
        val d = BackendErrorDetector(dedupeWindowMs = 60_000, now = { t })
        assertNotNull(d.feed(quotaLine))
        // 重试再次打出同样 dump（窗口内）：不重复上报
        t = 5_000
        assertNull(d.feed(quotaLine))
        // 窗口过后再报
        t = 61_000
        assertNotNull(d.feed(quotaLine))
    }

    @Test
    fun `不同签名不受去重影响`() {
        var t = 0L
        val d = BackendErrorDetector(dedupeWindowMs = 60_000, now = { t })
        assertNotNull(d.feed(quotaLine))
        t = 1_000
        val other = "APICallError [AI_APICallError]: Error code: 401 - {'error': {'code': 'authentication_error', " +
            "'message': 'invalid api key', 'type': 'authentication_error'}}"
        val err = d.feed(other)
        assertNotNull(err)
        assertEquals(401, err.statusCode)
        assertEquals("authentication_error", err.code)
        assertFalse(err.isQuotaError)
    }

    @Test
    fun `非配额 429 归为普通错误`() {
        val d = BackendErrorDetector()
        val err = d.feed(
            "APICallError [AI_APICallError]: Error code: 429 - {'error': {'code': 'rate_limit_exceeded', " +
                "'message': 'Too many requests', 'type': 'rate_limit_error'}}"
        )
        assertNotNull(err)
        assertFalse(err.isQuotaError)
    }

    // ===== 崩溃级进程异常（官方 [zcode-process-exception] 契约）=====

    private val peLine =
        """[zcode-process-exception] {"version":"0.16.5","errorId":"e-abc123","kind":"uncaughtException",""" +
            """"origin":"bootstrap.zcode_protocol","name":"TypeError","message":"x is not a function",""" +
            """"stack":"at ...","occurredAt":"2026-09-21T00:00:00.000Z"}"""

    @Test
    fun `解析进程崩溃契约行`() {
        val d = BackendErrorDetector()
        val pe = d.parseProcessException(peLine)
        assertNotNull(pe)
        assertEquals("e-abc123", pe.errorId)
        assertEquals("uncaughtException", pe.kind)
        assertEquals("bootstrap.zcode_protocol", pe.origin)
        assertEquals("TypeError", pe.name)
        assertEquals("x is not a function", pe.message)
    }

    @Test
    fun `同 errorId 只上报一次`() {
        val d = BackendErrorDetector()
        assertNotNull(d.parseProcessException(peLine))
        assertNull(d.parseProcessException(peLine))
    }

    @Test
    fun `非契约行与坏 JSON 返回 null`() {
        val d = BackendErrorDetector()
        assertNull(d.parseProcessException("普通崩溃堆栈行"))
        assertNull(d.parseProcessException("[zcode-process-exception] {broken json"))
        assertNull(d.parseProcessException("""[zcode-process-exception] {"kind":"x"}""")) // 缺 errorId
    }

    @Test
    fun `APICallError 与进程契约互不干扰`() {
        val d = BackendErrorDetector()
        assertNotNull(d.parseProcessException(peLine))
        assertNotNull(d.feed(quotaLine))
        assertNull(d.feed(peLine)) // 契约行不误入 APICallError 通道
    }
}
