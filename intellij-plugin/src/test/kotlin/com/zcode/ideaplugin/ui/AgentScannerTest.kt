package com.zcode.ideaplugin.ui

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AgentScanner 单元测试（user.home 重定向到临时目录，全隔离不碰真机数据）
 *
 * 覆盖：frontmatter 解析（标量+块列表+内联列表）、写盘 round-trip、改名删旧、
 * 禁用清单过滤、name/description 缺失忽略、name 正则、作用域目录
 */
class AgentScannerTest {

    private lateinit var realHome: String
    private lateinit var home: File

    @BeforeTest
    fun setup() {
        realHome = System.getProperty("user.home")
        home = createTempDir("zcode-agent-test")
        System.setProperty("user.home", home.absolutePath)
    }

    @AfterTest
    fun teardown() {
        System.setProperty("user.home", realHome)
        home.deleteRecursively()
    }

    private fun sampleDef(
        name: String = "my-agent",
        tools: List<String> = emptyList(),
    ) = AgentScanner.AgentDef(
        name = name,
        description = "测试描述",
        model = null,
        thoughtLevel = null,
        color = "blue",
        tools = tools,
        disallowedTools = emptyList(),
        maxTurns = null,
        injectAgentsMd = true,
        mcpServers = emptyList(),
        systemPrompt = "你是测试子智能体",
        path = "",
        scope = "user",
    )

    @Test
    fun `保存后扫描 round-trip（标量字段 + 块列表 tools）`() {
        val def = sampleDef(tools = listOf("Read", "Bash"))
        assertTrue(AgentScanner.save("user", def, null))

        val scanned = AgentScanner.scan(null)
        assertEquals(1, scanned.size)
        val got = scanned[0]
        assertEquals("my-agent", got.name)
        assertEquals("测试描述", got.description)
        assertEquals("blue", got.color)
        assertEquals(listOf("Read", "Bash"), got.tools)
        assertTrue(got.injectAgentsMd)
        assertEquals("你是测试子智能体", got.systemPrompt)
        assertEquals("user", got.scope)
        assertNull(got.model, "inherit/缺省 model 解析为 null")
        assertTrue(File(got.path).isFile, "定义文件应存在")
    }

    @Test
    fun `改名写新文件删旧文件`() {
        assertTrue(AgentScanner.save("user", sampleDef(name = "old-name"), null))
        assertTrue(AgentScanner.save("user", sampleDef(name = "new-name"), originalName = "old-name"))

        val scanned = AgentScanner.scan(null)
        assertEquals(1, scanned.size)
        assertEquals("new-name", scanned[0].name)
        assertFalse(File(home, ".zcode/agents/old-name.md").exists(), "旧文件应被删除")
    }

    @Test
    fun `删除后清单为空`() {
        AgentScanner.save("user", sampleDef(), null)
        assertTrue(AgentScanner.delete("user", "my-agent"))
        assertTrue(AgentScanner.scan(null).isEmpty())
    }

    @Test
    fun `name 或 description 缺失的文件被忽略（zcode 语义）`() {
        val dir = File(home, ".zcode/agents").apply { mkdirs() }
        File(dir, "no-desc.md").writeText(
            """
            ---
            name: "no-desc"
            ---
            正文
            """.trimIndent()
        )
        File(dir, "no-name.md").writeText(
            """
            ---
            description: "缺名字"
            ---
            正文
            """.trimIndent()
        )
        assertTrue(AgentScanner.scan(null).isEmpty())
    }

    @Test
    fun `内联列表与 model 字段解析`() {
        val dir = File(home, ".zcode/agents").apply { mkdirs() }
        File(dir, "inline.md").writeText(
            """
            ---
            name: "inline"
            description: "内联列表"
            model: "GLM-5.2"
            color: "purple"
            tools: ["Read", "Grep"]
            injectAgentsMd: false
            ---
            正文提示词
            """.trimIndent()
        )
        val got = AgentScanner.scan(null).single()
        assertEquals("GLM-5.2", got.model)
        assertEquals("purple", got.color)
        assertEquals(listOf("Read", "Grep"), got.tools)
        assertFalse(got.injectAgentsMd)
    }

    @Test
    fun `disabledAgentIds 过滤禁用条目`() {
        AgentScanner.save("user", sampleDef(), null)
        val stateDir = File(home, ".zcode/v2").apply { mkdirs() }
        File(stateDir, "agents-state.json").writeText("""{"builtInModelOverrides":{},"builtInThoughtLevelOverrides":{},"disabledAgentIds":["my-agent"]}""")
        assertTrue(AgentScanner.scan(null).isEmpty(), "禁用条目应被过滤")
    }

    @Test
    fun `项目级作用域扫描`() {
        val project = createTempDir("zcode-agent-proj")
        try {
            val def = sampleDef(name = "proj-agent").copy(scope = "project")
            assertTrue(AgentScanner.save("project", def, null, projectBasePath = project.absolutePath))
            val scanned = AgentScanner.scan(project.absolutePath)
            assertEquals(1, scanned.size)
            assertEquals("project", scanned[0].scope)
            assertTrue(scanned[0].path.replace('\\', '/').endsWith(".zcode/agents/proj-agent.md"))
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun `name 正则对齐 zcode（小写字母数字开头，允许点下划线连字符）`() {
        assertTrue(AgentScanner.NAME_RE.matches("abc"))
        assertTrue(AgentScanner.NAME_RE.matches("a1-b.c_d"))
        assertTrue(AgentScanner.NAME_RE.matches("9lives"))
        assertFalse(AgentScanner.NAME_RE.matches("Abc"), "大写不允许")
        assertFalse(AgentScanner.NAME_RE.matches("-abc"), "开头须字母或数字")
        assertFalse(AgentScanner.NAME_RE.matches("a b"), "空格不允许")
        assertFalse(AgentScanner.NAME_RE.matches("中文"), "非 ASCII 不允许")
    }
}
