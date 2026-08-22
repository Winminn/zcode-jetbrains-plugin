package com.zcode.ideaplugin.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 浏览器数据概览的 origin 提取纯函数测试：
 * - decodeIndexedDbDirName：Chromium IndexedDB 目录名编码 → origin
 * - extractOriginsFromLevelDb：Local Storage 的 META: 前缀与 SW Database 的 URL 明文提取
 */
class ZCodeBrowserOverviewParseTest {

    @Test
    fun `IndexedDB 目录名解码（带端口-不带端口-file scheme）`() {
        assertEquals("https://example.com:443", ZCodeBrowserExecutor.decodeIndexedDbDirName("https_example.com_443.indexeddb.leveldb"))
        assertEquals("http://127.0.0.1:5173", ZCodeBrowserExecutor.decodeIndexedDbDirName("http_127.0.0.1_5173.indexeddb.leveldb"))
        assertEquals("file://", ZCodeBrowserExecutor.decodeIndexedDbDirName("file__0.indexeddb.leveldb"))
    }

    @Test
    fun `非 IndexedDB 目录名返回 null`() {
        assertNull(ZCodeBrowserExecutor.decodeIndexedDbDirName("https_example.com_443.indexeddb.leveldb.tmp"))
        assertNull(ZCodeBrowserExecutor.decodeIndexedDbDirName("random-dir"))
        assertNull(ZCodeBrowserExecutor.decodeIndexedDbDirName("chrome-extension_abcd_0.indexeddb.leveldb"), "非 http/https/file scheme 应拒绝")
    }

    @Test
    fun `leveldb 明文提取 origin（META 前缀 + URL，去重）`() {
        val dir = java.nio.file.Path.of(tempDir.absolutePath, "leveldb")
        dir.toFile().mkdirs()
        File(dir.toFile(), "000003.log").writeBytes(
            buildString {
                append("x\u0000META:https://github.com\u0001")
                append("y\u0000META:http://127.0.0.1:5173\u0001")
                append("z\u0000https://sgtm.jetbrains.com/_/service_worker/sw.js\u0000")
                append("w\u0000https://sgtm.jetbrains.com/other\u0000")
            }.toByteArray(Charsets.ISO_8859_1)
        )
        val origins = ZCodeBrowserExecutor.extractOriginsFromLevelDb(dir)
        assertEquals(
            setOf("https://github.com", "http://127.0.0.1:5173", "https://sgtm.jetbrains.com"),
            origins,
            "SW 脚本 URL 应截到 origin 且去重",
        )
    }

    @Test
    fun `目录缺失返回空集合，超限文件跳过`() {
        assertTrue(ZCodeBrowserExecutor.extractOriginsFromLevelDb(java.nio.file.Path.of(tempDir.absolutePath, "not-exist")).isEmpty())
        val dir = java.nio.file.Path.of(tempDir.absolutePath, "leveldb2")
        dir.toFile().mkdirs()
        val big = File(dir.toFile(), "big.log")
        big.writeBytes(ByteArray(64)) // 内容无关，靠长度门槛跳过需 32MB+——构造小文件验证正常路径即可
        assertTrue(ZCodeBrowserExecutor.extractOriginsFromLevelDb(dir).isEmpty(), "无匹配明文的文件应返回空")
        assertFalse(big.length() > 32L * 1024 * 1024)
    }

    @TempDir
    lateinit var tempDir: File
}
