package com.zcode.ideaplugin.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 当前文件上下文附件解析（CurrentFileAttachment）单元测试。
 *
 * 锁定：选区切片（含单行/越界钳制/超长截断）、整文件内联上限与 localPath 降级、
 * 二进制兜底、GBK 有损读、MIME 映射、sizeBytes 按 UTF-8 字节计。
 */
class CurrentFileAttachmentTest {

    @TempDir
    lateinit var dir: Path

    private fun lines(n: Int) = (1..n).joinToString("\n") { "line_$it" }

    @Test
    fun `选区切片只携带区间内行`() {
        val a = CurrentFileAttachment.resolve("E:/proj/App.kt", 10, 20, lines(30))
        assertEquals("file", a.kind)
        assertEquals("E:/proj/App.kt", a.filename)
        // 自描述格式：头行带完整路径 + 原始行号区间 + 总行数，正文 N\t 前缀（同 Read 工具输出）
        val expected = "[Selected code from E:/proj/App.kt, lines 10-20 of 30]\n" +
            (10..20).joinToString("\n") { "$it\tline_$it" }
        assertEquals(expected, a.textContent)
        assertNull(a.localPath)
        // sizeBytes 按 UTF-8 字节计（与 textContent 一致）
        assertEquals(a.textContent!!.toByteArray(Charsets.UTF_8).size.toLong(), a.sizeBytes)
    }

    @Test
    fun `单行选区 lineStart=lineEnd`() {
        val a = CurrentFileAttachment.resolve("E:/proj/App.kt", 7, 7, lines(30))
        assertEquals("[Selected code from E:/proj/App.kt, line 7 of 30]\n7\tline_7", a.textContent)
    }

    @Test
    fun `行号越界钳制到文件实际范围`() {
        // chip 推送与发送之间文件被改短（200ms 防抖窗口）：钳制不炸、不抛异常
        val a = CurrentFileAttachment.resolve("E:/proj/App.kt", 50, 60, lines(30))
        assertEquals("[Selected code from E:/proj/App.kt, line 30 of 30]\n30\tline_30", a.textContent)
    }

    @Test
    fun `选区超 500 行截断并补说明行`() {
        val a = CurrentFileAttachment.resolve("E:/proj/App.kt", 1, 600, lines(600))
        val content = a.textContent!!
        assertTrue(content.startsWith("[Selected code from E:/proj/App.kt, lines 1-600 of 600]\n1\tline_1\n"))
        assertTrue(content.contains("500\tline_500"))
        assertTrue(!content.contains("line_501"))
        assertTrue(content.lines().last().contains("truncated to first 500 lines"))
    }

    @Test
    fun `整文件小文件全量内联`() {
        val text = lines(50)
        val a = CurrentFileAttachment.resolve("E:/proj/App.kt", null, null, text)
        val expected = "[Full content of E:/proj/App.kt, 50 lines]\n" +
            (1..50).joinToString("\n") { "$it\tline_$it" }
        assertEquals(expected, a.textContent)
        assertNull(a.localPath)
    }

    @Test
    fun `整文件超 128KB 降级 localPath 引用`() {
        val big = "x".repeat(129 * 1024)
        val a = CurrentFileAttachment.resolve("E:/proj/big.log", null, null, big)
        assertNull(a.textContent)
        assertEquals("E:/proj/big.log", a.localPath)
    }

    @Test
    fun `fullText=null（二进制不可读）走 localPath 兜底`() {
        val a = CurrentFileAttachment.resolve("E:/proj/pic.png", null, null, null)
        assertNull(a.textContent)
        assertEquals("E:/proj/pic.png", a.localPath)
    }

    @Test
    fun `readTextLossy 读 UTF-8 中文文件`() {
        val f = dir.resolve("utf8.kt")
        Files.writeString(f, "// 中文注释\nfun main() {}", Charsets.UTF_8)
        val text = CurrentFileAttachment.readTextLossy(f)
        assertNotNull(text)
        assertTrue(text.contains("中文注释"))
    }

    @Test
    fun `readTextLossy 对非 UTF-8 文件回退平台编码仍可读`() {
        // 含 ASCII 标记的 GBK 字节：断言标记（跨平台稳定）而非中文（依赖平台默认编码）
        val f = dir.resolve("gbk.txt")
        Files.write(f, "marker_line_gbk\n中文内容".toByteArray(charset("GBK")))
        val text = CurrentFileAttachment.readTextLossy(f)
        assertNotNull(text)
        assertTrue(text.contains("marker_line_gbk"))
    }

    @Test
    fun `MIME 映射：常见类型命中，缺省 text-plain`() {
        assertEquals("text/markdown", CurrentFileAttachment.mimeTypeFor("E:/a/README.md"))
        assertEquals("application/json", CurrentFileAttachment.mimeTypeFor("E:/a/b.json"))
        assertEquals("text/html", CurrentFileAttachment.mimeTypeFor("E:/a/index.html"))
        assertEquals("text/plain", CurrentFileAttachment.mimeTypeFor("E:/a/App.kt"))
        assertEquals("text/plain", CurrentFileAttachment.mimeTypeFor("E:/a/noext"))
    }
}
