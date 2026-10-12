package com.zcode.ideaplugin.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 模型调用轨迹读取器（model-io jsonl 解析）。
 *
 * 语义对齐官方 packages/services/src/zcode-agent/modelTrajectory.ts：
 * 过滤 / delta 展开 / 排序 / 截尾 / tool 消息关联 / callSource 分类。
 */
class ModelIoTrajectoryReaderTest {

    @TempDir
    lateinit var tempDir: File

    private val sessionId = "sess_abc123"

    private fun read(root: File, limit: Int = 200) =
        ModelIoTrajectoryReader.readFrom(listOf(root), sessionId, limit)

    private fun writeModelIo(root: File, mode: String = "rollout", lines: List<String>) {
        val dir = File(root, mode).apply { mkdirs() }
        // 真实 model-io 是 JSONL（一条一行）；模板字符串的换行压掉以同构
        File(dir, "model-io-$sessionId.jsonl").writeText(lines.joinToString("\n") { it.replace("\n", "") } + "\n")
    }

    private fun recordJson(
        requestId: String,
        startedAt: String,
        messages: String,
        extra: String = "",
    ): String = """
        {"type":"model_io","sessionId":"$sessionId","requestId":"$requestId","attempt":1,
         "startedAt":"$startedAt","completedAt":"$startedAt","durationMs":1200,
         "turnId":"turn_1","traceId":"trace_1","querySource":"main_turn",
         "model":{"modelId":"glm-5.3","providerId":"bigmodel","role":"main","source":"builtin"},
         "request":{"toolNames":["Read","Edit"],$messages$extra},
         "response":{"finishReason":"stop","text":"hi","reasoningText":"think",
           "toolCalls":[{"type":"tool-call","toolCallId":"c1","toolName":"Read","input":{"p":1}}],
           "usage":{"inputTokens":100,"outputTokens":50,"totalTokens":150,
                    "cacheReadTokens":10,"reasoningTokens":20},
           "responseId":"resp_1","modelId":"glm-5.3"}}
    """.trimIndent()

    @Test
    fun `sanitize 会话段与官方一致`() {
        assertEquals("sess_abc-123", ModelIoTrajectoryReader.sanitizeSessionSegment("sess_abc-123"))
        assertEquals("a-b-c", ModelIoTrajectoryReader.sanitizeSessionSegment("a/b\\c"))
        assertEquals("a-b", ModelIoTrajectoryReader.sanitizeSessionSegment("--a!!b--"))
        assertEquals(80, ModelIoTrajectoryReader.sanitizeSessionSegment("x".repeat(100)).length)
    }

    @Test
    fun `单条完整记录解析`() {
        val messages = """
            "messages":[{"role":"system","content":"sys"},{"role":"user","content":"hello"},
             {"role":"assistant","content":[{"type":"text","text":"hi"},{"type":"reasoning","text":"think"},
              {"type":"tool-call","toolCallId":"c1","toolName":"Read","input":{"p":1}}]}]
        """.trimIndent()
        writeModelIo(tempDir, lines = listOf(recordJson("r1", "2026-10-12T01:00:00.000Z", messages)))

        val result = read(tempDir)
        assertFalse(result.truncated)
        assertEquals(1, result.records.size)
        val r = result.records[0]
        assertEquals("r1", r.requestId)
        assertEquals(1200L, r.durationMs)
        assertEquals("glm-5.3", r.model.modelId)
        assertEquals("bigmodel", r.model.providerId)
        assertEquals("main", r.callSource?.kind)
        assertEquals(3, r.request.messages.size)
        assertEquals(listOf("Read", "Edit"), r.request.toolNames)
        val resp = requireNotNull(r.response)
        assertEquals("stop", resp.finishReason)
        assertEquals("hi", resp.text)
        assertEquals(150L, resp.usage?.totalTokens)
        assertEquals(1, resp.toolCalls.size)
        assertEquals("Read", resp.toolCalls[0].toolName)
        // assistant 数组 content 各 part 类型保留
        val assistant = r.request.messages[2]
        assertEquals(listOf("text", "reasoning", "tool-call"), assistant.parts.map { it.kind })
        assertEquals("""{"p":1}""", r.response?.toolCalls?.get(0)?.input.toString())
    }

    @Test
    fun `非 model_io 行与坏行与其他会话行跳过`() {
        writeModelIo(
            tempDir,
            lines = listOf(
                "not-json",
                """{"type":"other","sessionId":"$sessionId"}""",
                """{"type":"model_io","sessionId":"sess_other","requestId":"x","startedAt":"2026-10-12T00:00:00Z","request":{}}""",
                recordJson("r1", "2026-10-12T01:00:00.000Z", "\"messages\":[]"),
            ),
        )
        val result = read(tempDir)
        assertEquals(1, result.records.size)
        assertEquals("r1", result.records[0].requestId)
        // totalCount = 文件全部行数（含坏行/非本会话行），口径是"文件里有多少条"而非"解析出多少条"
        assertEquals(4, result.totalCount)
    }

    @Test
    fun `delta 展开按前一条拼接 tail 基线不拼`() {
        val full = """
            "messagesKind":"full","messages":[{"role":"user","content":"m1"},{"role":"assistant","content":"a1"},{"role":"user","content":"m2"}]
        """.trimIndent()
        val delta = """
            "messagesKind":"delta","messageOffset":2,"messages":[{"role":"assistant","content":"a2"}]
        """.trimIndent()
        val tail = """
            "messagesKind":"tail","messageOffset":0,"messages":[{"role":"user","content":"fresh"}]
        """.trimIndent()
        writeModelIo(
            tempDir,
            lines = listOf(
                recordJson("r1", "2026-10-12T01:00:00.000Z", full),
                recordJson("r2", "2026-10-12T02:00:00.000Z", delta),
                recordJson("r3", "2026-10-12T03:00:00.000Z", tail),
            ),
        )
        val result = read(tempDir)
        assertEquals(3, result.records.size)
        assertEquals(3, result.records[0].request.messages.size)
        // r2 = r1 前 2 条 + 1 条增量
        val r2 = result.records[1]
        assertEquals(3, r2.request.messages.size)
        assertEquals("a2", r2.request.messages[2].parts[0].text)
        // r3 是 tail 基线：不拼更早历史
        val r3 = result.records[2]
        assertEquals(1, r3.request.messages.size)
        assertEquals("fresh", r3.request.messages[0].parts[0].text)
    }

    @Test
    fun `按 startedAt 排序且截尾保留最近`() {
        val empty = "\"messages\":[]"
        writeModelIo(
            tempDir,
            lines = listOf(
                recordJson("late", "2026-10-12T03:00:00.000Z", empty),
                recordJson("early", "2026-10-12T01:00:00.000Z", empty),
                recordJson("mid", "2026-10-12T02:00:00.000Z", empty),
            ),
        )
        val result = read(tempDir, limit = 2)
        assertTrue(result.truncated)
        assertEquals(listOf("mid", "late"), result.records.map { it.requestId })
        // totalCount 数文件全部行（含被截尾不展示的）；展示数单独由 records.size 表达
        assertEquals(3, result.totalCount)
        assertEquals(2, result.records.size)
    }

    @Test
    fun `tool 消息顶层字段还原为 tool-result part`() {
        val messages = """
            "messages":[{"role":"assistant","content":[{"type":"tool-call","toolCallId":"c9","toolName":"Bash","input":{}}]},
             {"role":"tool","toolCallId":"c9","toolName":"Bash","isError":true,"content":"boom"}]
        """.trimIndent()
        writeModelIo(tempDir, lines = listOf(recordJson("r1", "2026-10-12T01:00:00.000Z", messages)))
        val result = read(tempDir)
        val toolMsg = result.records[0].request.messages[1]
        assertEquals("tool", toolMsg.role)
        val part = toolMsg.parts.single()
        assertEquals("tool-result", part.kind)
        assertEquals("c9", part.toolCallId)
        assertEquals("Bash", part.toolName)
        // 错误文本包 error-text 信封
        val output = part.output.toString()
        assertTrue(output.contains("error-text"))
        assertTrue(output.contains("boom"))
    }

    @Test
    fun `callSource 分类与标题生成推断`() {
        fun one(querySource: String?, modelRole: String? = "main", firstSystem: String? = null): String {
            val sys = if (firstSystem != null) """[{"role":"system","content":"$firstSystem"}]""" else "[]"
            val qs = querySource?.let { """"querySource":"$it",""" } ?: ""
            val model = if (modelRole != null) """{"modelId":"m","role":"$modelRole"}""" else """{"modelId":"m"}"""
            return """
                {"type":"model_io","sessionId":"$sessionId","requestId":"r","startedAt":"2026-10-12T01:00:00.000Z",
                 ${qs}"model":$model,"request":{"messages":$sys},"response":{}}
            """.trimIndent()
        }

        fun classify(line: String) = run {
            writeModelIo(tempDir, lines = listOf(line))
            read(tempDir).records.single().callSource
        }

        assertEquals("main", classify(one("main_turn"))?.kind)
        assertEquals("subagent", classify(one("subagent"))?.kind)
        assertEquals("compact", classify(one("compact"))?.kind)
        assertEquals("compact", classify(one(null, modelRole = "compact"))?.kind)
        assertEquals("sidecar", classify(one("promptEnhance"))?.kind)
        assertEquals("subagent", classify(one(null, modelRole = "subagent"))?.kind)
        assertEquals(
            "session_title",
            classify(
                one(null, firstSystem = "Generate a concise title for this coding session. xyz")
            )?.querySource,
        )
        assertEquals("main", classify(one(null))?.kind)
    }

    @Test
    fun `debug 与 rollout 双目录都扫`() {
        writeModelIo(tempDir, "debug", listOf(recordJson("r-debug", "2026-10-12T01:00:00.000Z", "\"messages\":[]")))
        writeModelIo(tempDir, "rollout", listOf(recordJson("r-rollout", "2026-10-12T02:00:00.000Z", "\"messages\":[]")))
        val result = read(tempDir)
        assertEquals(listOf("r-debug", "r-rollout"), result.records.map { it.requestId })
        assertEquals(2, result.sourceFiles.size)
    }

    @Test
    fun `无文件返回空轨迹`() {
        val result = read(tempDir)
        assertFalse(result.truncated)
        assertTrue(result.records.isEmpty())
        assertEquals(0, result.totalCount)
        assertTrue(result.sourceFiles.isEmpty())
    }

    @Test
    fun `错误记录带 error 信封`() {
        val line = """
            {"type":"model_io","sessionId":"$sessionId","requestId":"r1","startedAt":"2026-10-12T01:00:00.000Z",
             "model":{"modelId":"m"},"request":{"messages":[]},
             "error":{"name":"APICallError","message":"HTTP 429: quota","stack":"at x"}}
        """.trimIndent()
        writeModelIo(tempDir, lines = listOf(line))
        val err = read(tempDir).records.single().error
        assertEquals("APICallError", err?.name)
        assertEquals("HTTP 429: quota", err?.message)
    }
}
