package com.zcode.ideaplugin.ui

import java.io.File
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory

/**
 * MemoryFileScanner 自动记忆目录定位单测（临时目录注入 homeDir，不依赖真机）
 *
 * 核心回归：CLI 会改写目录名前缀（中文目录名 → project，实锤案例
 * 新平台访问环境 → project-7b2bd5221263438c），定位必须按哈希后缀匹配而非前缀。
 */
class MemoryFileScannerTest {

    private val tmp = createTempDirectory("memory-scanner-test").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    /** sha256(小写路径) 前 16 位 hex —— 与 CLI 目录名哈希同式 */
    private fun hash16(path: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(path.lowercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

    /** 在模拟 home 下造一个项目的记忆目录，返回其路径 */
    private fun makeMemoryDir(slug: String, factCount: Int = 1): File {
        val dir = File(tmp, ".zcode/cli/memories/projects/$slug/memory")
        dir.mkdirs()
        File(dir, "MEMORY.md").writeText("# 索引\n- [demo](demo.md)\n")
        repeat(factCount) { i ->
            File(dir, "fact-$i.md").writeText("# 事实 $i\n\n内容\n")
        }
        return dir
    }

    @Test
    fun `中文目录名被CLI改写前缀时仍按哈希后缀命中`() {
        // 实锤案例：C:\Users\Administrator\DataGripProjects\新平台访问环境
        // → CLI 目录 project-7b2bd5221263438c（前缀不是目录名小写）
        val projectPath = "C:\\Users\\Administrator\\DataGripProjects\\新平台访问环境"
        val real = makeMemoryDir("project-7b2bd5221263438c")
        // 另一个无关项目目录，验证不会误中
        makeMemoryDir("other-project-0000000000000000")

        val located = MemoryFileScanner.locate(projectPath, tmp.absolutePath)
        assertNotNull(located, "应能定位")
        assertNotNull(located.dir, "应命中实际目录")
        assertEquals(real.absolutePath, located.dir, "命中的应是哈希后缀匹配的目录")
        assertTrue(located.dir!!.endsWith("project-7b2bd5221263438c${File.separatorChar}memory"), "末尾 slug 应一致")

        // list() 同源：自动记忆条目应被扫出（设置页读取不到的回归点）
        val autoFiles = MemoryFileScanner.list(projectPath, tmp.absolutePath)
            .filter { it.kind == "auto" }
        assertTrue(autoFiles.isNotEmpty(), "自动记忆条目应非空（哈希后缀匹配修复）")
        assertTrue(autoFiles.any { it.name == "MEMORY.md" }, "应含索引文件")
        assertTrue(autoFiles.all { it.exists }, "条目均应存在")
    }

    @Test
    fun `常规英文目录名按原生分隔符哈希命中`() {
        // basePath 是 VFS 正斜杠形态，CLI 哈希原料是 Windows 反斜杠形态
        val projectPath = "C:/work/demo-app"
        val slug = "demo-app-${hash16("C:\\work\\demo-app")}"
        val real = makeMemoryDir(slug, factCount = 2)

        val located = MemoryFileScanner.locate(projectPath, tmp.absolutePath)
        assertNotNull(located)
        assertEquals(real.absolutePath, located.dir, "正斜杠 basePath 应经反斜杠变体命中")

        val expectedSlug = "demo-app-${hash16("C:\\work\\demo-app")}"
        assertTrue(located.expectedDir.endsWith("$expectedSlug${File.separatorChar}memory"))
    }

    @Test
    fun `未建过记忆时 dir 为 null 且期望目录可展示`() {
        val projectPath = "D:/nope/empty-project"
        val located = MemoryFileScanner.locate(projectPath, tmp.absolutePath)
        assertNotNull(located, "projects 根不存在也应返回信息供前端展示")
        assertEquals(null, located.dir, "未命中")
        assertTrue(located.expectedDir.contains("empty-project-"), "期望目录前缀取目录名小写")
        assertTrue(located.projectsRoot.replace('\\', '/').endsWith("/.zcode/cli/memories/projects"))
    }

    @Test
    fun `目录名前缀含连字符的多段名不干扰哈希后缀匹配`() {
        // 前缀本身含 - 的目录名（如 84c4937a…-607d37f3 形态），endsWith 判定不受影响
        val projectPath = "C:/work/ab"
        val hash = hash16("C:\\work\\ab")
        makeMemoryDir("84c4937a6b75ed4c1ca77c4d2778b034f434e6d9-$hash")

        val located = MemoryFileScanner.locate(projectPath, tmp.absolutePath)
        assertNotNull(located?.dir, "多段前缀目录应命中")
    }

    @Test
    fun `指令记忆清单与缺失项`() {
        val files = MemoryFileScanner.list("C:/work/demo-app", tmp.absolutePath)
        val global = files.filter { it.scope == "global" }
        assertEquals(1, global.size, "全局指令记忆固定 1 条")
        assertFalse(global[0].exists, "临时 home 下全局 AGENTS.md 不存在")
        val project = files.filter { it.scope == "project" && it.kind == "instructions" }
        assertEquals(1, project.size, "项目指令记忆固定 1 条")
    }
}
