package com.zcode.ideaplugin.ui

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory

/**
 * SlashCommandScanner 单元测试（临时目录隔离，不依赖本机真机数据）
 */
class SlashCommandScannerTest {

    private val tmp = createTempDirectory("slash-scan-test").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    @Test
    fun `内置命令兜底合入且标记 builtin`() {
        val commands = SlashCommandScanner.scan(tmp.absolutePath)
        val init = commands.firstOrNull { it.name == "init" }
        assertNotNull(init, "/init 应在结果中（CLI 内置命令）")
        assertEquals("command", init.kind)
        assertEquals("builtin", init.source)
        assertTrue(init.description!!.contains("AGENTS.md"), "内置命令应带 CLI summary 描述")
    }

    @Test
    fun `自定义同名命令优先于内置`() {
        val cmdDir = File(tmp, ".zcode/commands").apply { mkdirs() }
        File(cmdDir, "init.md").writeText("---\ndescription: 我的初始化\n---\n自定义内容")

        val commands = SlashCommandScanner.scan(tmp.absolutePath)
        val init = commands.firstOrNull { it.name == "init" }
        assertNotNull(init)
        assertEquals("workspace", init.source, "磁盘扫描先于内置兜底，自定义 init 应胜出")
        assertEquals("我的初始化", init.description)
        assertEquals(1, commands.count { it.name == "init" }, "同名命令应去重为一条")
    }

    @Test
    fun `内置清单仅服务端可解析的两条`() {
        val commands = SlashCommandScanner.scan(tmp.absolutePath)
        val builtinNames = commands.filter { it.source == "builtin" }.map { it.name }.sorted()
        assertEquals(listOf("compact", "init"), builtinNames, "内置提示只列服务端 send 可解析的命令（goal 属客户端本地功能，不列）")
    }

    @Test
    fun `工作区嵌套命令冒号连接`() {
        val cmdDir = File(tmp, ".zcode/commands/review").apply { mkdirs() }
        File(cmdDir, "code.md").writeText("审查当前代码变更")

        val commands = SlashCommandScanner.scan(tmp.absolutePath)
        val nested = commands.firstOrNull { it.name == "review:code" }
        assertNotNull(nested, "review/code.md 应映射为 review:code")
        assertEquals("command", nested.kind)
    }

    // ============ 插件贡献（home 注入 tmp 隔离，不受本机真实插件影响）============

    /** 在 tmp 下造一个插件：data/<名>@<市场> 启用标记 + cache/<市场>/<名>/<版本> 安装内容 */
    private fun makePlugin(name: String, version: String, enabled: Boolean, files: Map<String, String>) {
        val base = File(tmp, ".zcode/cli/plugins")
        val versionDir = File(base, "cache/mp1/$name/$version").apply { mkdirs() }
        if (enabled) File(base, "data").apply { mkdirs() }.resolve("$name@mp1").mkdirs()
        files.forEach { (rel, content) ->
            val f = File(versionDir, rel).apply { parentFile.mkdirs() }
            f.writeText(content)
        }
    }

    @Test
    fun `市场清单与未启用插件不进补全`() {
        // marketplaces/ 是市场源完整清单（未安装插件），cache 里未启用插件同样不可用
        File(tmp, ".zcode/cli/plugins/marketplaces/mp1/plugins/hookify/commands").apply { mkdirs() }
            .resolve("help.md").writeText("市场清单里的 help")
        makePlugin("ghost", "1.0.0", enabled = false, mapOf("commands/spooky.md" to "未启用插件的命令"))

        val commands = SlashCommandScanner.scan(tmp.absolutePath, tmp.absolutePath)
        assertTrue(commands.none { it.name == "help" || it.name == "hookify:help" }, "市场清单命令不应出现")
        assertTrue(commands.none { it.name.contains("ghost") }, "未启用插件不应贡献命令")
    }

    @Test
    fun `启用插件命令与技能带插件名前缀`() {
        makePlugin(
            "myplug", "1.0.0", enabled = true,
            mapOf(
                "commands/deploy.md" to "---\ndescription: 部署\n---\n内容",
                "skills/packer/SKILL.md" to "---\nname: packer\ndescription: 打包\n---\n内容",
            )
        )

        val items = SlashCommandScanner.scan(tmp.absolutePath, tmp.absolutePath)
        val cmd = items.firstOrNull { it.name == "myplug:deploy" }
        assertNotNull(cmd, "插件命令应为 插件名:命令名 形式")
        assertEquals("command", cmd.kind)
        assertEquals("plugin", cmd.source)
        assertEquals("部署", cmd.description)
        assertTrue(items.none { it.name == "deploy" }, "插件命令不应以裸名注册")

        val skill = items.firstOrNull { it.name == "myplug:packer" }
        assertNotNull(skill, "插件技能应为 插件名:技能名 形式")
        assertEquals("skill", skill.kind)
    }

    @Test
    fun `多版本共存取最新版本目录`() {
        makePlugin("ver", "0.9.0", enabled = true, mapOf("commands/old.md" to "旧版命令"))
        makePlugin("ver", "0.10.0", enabled = true, mapOf("commands/new.md" to "新版命令"))

        val items = SlashCommandScanner.scan(tmp.absolutePath, tmp.absolutePath)
        assertNotNull(items.firstOrNull { it.name == "ver:new" }, "应加载 0.10.0 的命令")
        assertTrue(items.none { it.name == "ver:old" }, "旧版本命令不应出现（0.10.0 > 0.9.0 按数值比较）")
    }
}
