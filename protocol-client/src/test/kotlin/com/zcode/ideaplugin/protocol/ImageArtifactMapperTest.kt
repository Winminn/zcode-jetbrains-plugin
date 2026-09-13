package com.zcode.ideaplugin.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 用户图片 part 读回适配测试（2026-08-26 协议实测样本锁形状）
 *
 * 样本来自 scripts/diag-image-readback.py 抓包（会话 sess_54f0fc9c...，1x1 PNG），
 * 服务端把附件落盘为 image-<sha256(uri)[:32]>.png，hash 已人工比对磁盘文件名一致。
 */
class ImageArtifactMapperTest {

    // 真实读回样本（diag-image-readback.json）
    private val realUri =
        "zcode-artifact://sess_54f0fc9c-2667-4abe-a79b-f303137b6802/tool-result-31710185-7924-41e6-bb58-45e18710e34e"
    private val realSid = "sess_54f0fc9c-2667-4abe-a79b-f303137b6802"
    private val realFile = "image-7ee3a46c63884e81193fab6fe0f69322.png"

    @Test
    fun `cacheFileName 用真实样本锁 sha256 规则`() {
        assertEquals(realFile, ImageArtifactMapper.cacheFileName(realUri, "image/png"))
    }

    @Test
    fun `extOf 按 mime 映射扩展名`() {
        assertEquals("png", ImageArtifactMapper.extOf("image/png"))
        assertEquals("jpg", ImageArtifactMapper.extOf("image/jpeg"))
        assertEquals("jpg", ImageArtifactMapper.extOf("image/jpg"))
        assertEquals("gif", ImageArtifactMapper.extOf("image/gif"))
        assertEquals("webp", ImageArtifactMapper.extOf("image/webp"))
        assertNull(ImageArtifactMapper.extOf("application/pdf"))
        assertNull(ImageArtifactMapper.extOf("image/svg+xml")) // svg 不落盘，无转换
        // 参数分号取类型段（对齐 zcode.cjs aEn 与编辑附件落盘的同语义；2026-09-13 收口）
        assertEquals("png", ImageArtifactMapper.extOf("image/png;base64"))
    }

    @Test
    fun `非 artifact uri 不转换`() {
        assertNull(ImageArtifactMapper.cacheFileName("data:image/png;base64,xxx", "image/png"))
        assertNull(ImageArtifactMapper.cacheFileName("http://x/y.png", "image/png"))
        assertNull(ImageArtifactMapper.sessionIdOf("not-a-uri"))
    }

    @Test
    fun `sessionIdOf 取 uri host 段`() {
        assertEquals(realSid, ImageArtifactMapper.sessionIdOf(realUri))
        assertNull(ImageArtifactMapper.sessionIdOf("zcode-artifact://"))
    }

    private fun msg(vararg partsJson: String): String =
        """{"info":{"role":"user","id":"m1"},"parts":[${partsJson.joinToString(",")}]}"""

    @Test
    fun `mapMessages 替换 file 图片 part 的 url`() {
        val raw = msg(
            """{"type":"text","text":"看图"}""",
            """{"type":"file","mime":"image/png","url":"$realUri","id":"p1"}""",
        )
        val messages = Json.parseToJsonElement("[$raw]").jsonArray
        val out = ImageArtifactMapper.mapMessages(messages) { sid, f ->
            assertEquals(realSid, sid); assertEquals(realFile, f); "http://127.0.0.1:1/zcode-image/$sid/$f"
        }
        val part = out[0].jsonObject["parts"]!!.jsonArray[1].jsonObject
        assertEquals("file", part["type"]!!.jsonPrimitive.content)
        assertEquals("http://127.0.0.1:1/zcode-image/$realSid/$realFile", part["url"]!!.jsonPrimitive.content)
        // 其余字段原样保留
        assertEquals("p1", part["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `非图片 mime 与 data url 的 file part 不动`() {
        val raw = msg(
            """{"type":"file","mime":"application/pdf","url":"$realUri"}""",
            """{"type":"file","mime":"image/png","url":"data:image/png;base64,QQ=="}""",
        )
        val messages = Json.parseToJsonElement("[$raw]").jsonArray
        assertSame(messages, ImageArtifactMapper.mapMessages(messages) { _, _ -> "http://x" })
    }

    @Test
    fun `urlProvider 拒绝时原样返回（fail-soft 且引用不变）`() {
        val raw = msg("""{"type":"file","mime":"image/png","url":"$realUri"}""")
        val messages = Json.parseToJsonElement("[$raw]").jsonArray
        assertSame(messages, ImageArtifactMapper.mapMessages(messages) { _, _ -> null })
    }

    @Test
    fun `uri 换算落空时按受控命名 filename 兜底（编辑 ref 重发的图无 cache 落盘）`() {
        // 真机实锤 2026-09-12（sess_34858bc0）：v4 编辑 ref 重发的图产生新 artifact uri，
        // zcode.cjs 不为它写 image-cache——但服务端把插件传的 ref basename 存进了
        // part.filename，按它兜底命中
        val refBasename = "image-9843a724acf83cf2549de89d31f4920e.png"
        val raw = msg(
            """{"type":"file","mime":"image/png","url":"$realUri","filename":"$refBasename"}""",
        )
        val messages = Json.parseToJsonElement("[$raw]").jsonArray
        val out = ImageArtifactMapper.mapMessages(messages) { sid, f ->
            if (f == realFile) null // uri 换算的 cache 文件不存在
            else "http://127.0.0.1:1/zcode-image/$sid/$f"
        }
        val part = out[0].jsonObject["parts"]!!.jsonArray[0].jsonObject
        assertEquals("http://127.0.0.1:1/zcode-image/$realSid/$refBasename", part["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `filename 非受控命名不兜底（原样返回）`() {
        val raw = msg(
            """{"type":"file","mime":"image/png","url":"$realUri","filename":"../../evil.png"}""",
            """{"type":"file","mime":"image/png","url":"$realUri","filename":"截图.png"}""",
        )
        val messages = Json.parseToJsonElement("[$raw]").jsonArray
        assertSame(messages, ImageArtifactMapper.mapMessages(messages) { _, _ -> null })
    }

    @Test
    fun `无 parts 或 assistant 消息安全跳过`() {
        val messages = Json.parseToJsonElement(
            """[{"info":{"role":"assistant"},"parts":[{"type":"text","text":"hi"}]},{"info":{"role":"user"}},{"whatever":1}]"""
        ).jsonArray
        assertSame(messages, ImageArtifactMapper.mapMessages(messages) { _, _ -> "http://x" })
        assertTrue(messages.size == 3)
    }
}
