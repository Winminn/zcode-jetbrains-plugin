package com.zcode.ideaplugin.ui

import com.zcode.ideaplugin.protocol.Credentials
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 子智能体磁盘扫描器（设置页「子智能体」数据源，与 ZCode 客户端数据打通）
 *
 * 对齐 zcode.cjs 的 agent 发现语义（Itn 函数 + 官方文档）：
 *   user    <storageRoot>/agents 目录下的 .md 文件（storageRoot = ~/.zcode，dataBaseDir 感知）
 *   project <项目根及 git 根>/.zcode/agents 目录下的 .md 文件
 *
 * 文件 = 带 frontmatter 的 Markdown，正文即系统提示词。字段（camelCase 大小写敏感，
 * 不认识的字段被 zcode.cjs 静默忽略）：
 *   name/description 必填（缺失时文件被忽略）；model（缺省/inherit=跟随主 Agent）、
 *   thoughtLevel、color、tools/disallowedTools（列表，留空=继承全部工具）、maxTurns、
 *   injectAgentsMd（默认 true）、mcpServers
 *
 * 禁用清单：~/.zcode/v2/agents-state.json 的 disabledAgentIds（读取时过滤）。
 * 修改定义后需新建会话才生效（不热更新，ZCode 客户端同语义）。
 */
object AgentScanner {

    data class AgentDef(
        val name: String,
        val description: String,
        /** null 或 "inherit" = 跟随主 Agent 当前模型 */
        val model: String?,
        val thoughtLevel: String?,
        /** 预设色（zcode.cjs 实测枚举），仅身份标识 */
        val color: String?,
        /** 空 = 继承主会话全部工具（含 MCP）；非空 = 仅列表内工具 */
        val tools: List<String>,
        val disallowedTools: List<String>,
        val maxTurns: Int?,
        val injectAgentsMd: Boolean,
        val mcpServers: List<String>,
        /** Markdown 正文 = 系统提示词 */
        val systemPrompt: String,
        /** .md 绝对路径 */
        val path: String,
        /** user | project */
        val scope: String,
    )

    /** ZCode 支持的颜色标记（zcode.cjs 实测枚举） */
    val COLORS = listOf("blue", "green", "red", "orange", "yellow", "purple", "pink", "cyan")

    /** name 校验（zcode.cjs 实测正则，与文件名一致） */
    val NAME_RE = Regex("^[a-z0-9][a-z0-9._-]{0,127}$")

    /** 设置页「可用工具」勾选项 = CLI 内置工具（勾选自定义后 MCP 工具不可用，文档语义） */
    val BUILTIN_TOOLS = listOf(
        "Read", "Grep", "Glob", "Bash", "Edit", "Write", "WebFetch", "WebSearch", "TodoWrite"
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val LOCK = Any()

    /** 扫描全部作用域，返回列表（user 在前 project 在后，各自按名排序） */
    fun scan(projectBasePath: String?): List<AgentDef> {
        val disabled = disabledAgentIds()
        val out = ArrayList<AgentDef>()
        scanAgentDir(File(Credentials.storageRoot().toFile(), "agents"), "user", disabled, out)
        if (!projectBasePath.isNullOrBlank()) {
            val roots = linkedSetOf(File(projectBasePath))
            findGitRoot(projectBasePath)?.let { roots.add(it) }
            roots.forEach { base ->
                scanAgentDir(File(base, ".zcode/agents"), "project", disabled, out)
            }
        }
        // 同名时项目级覆盖用户级（对齐 zcode.cjs 后读覆盖先读的合并语义），展示层不再去重
        return out.distinctBy { "${it.scope}/${it.name}" }
    }

    /**
     * 保存（新建/更新/改名）。name 变更 = 写新文件 + 删旧文件。
     * @param originalName 编辑前名称（null = 新建）；与 name 相同 = 原地更新
     * @return 成功与否（IO 异常/目录不可写返回 false）
     */
    fun save(scope: String, def: AgentDef, originalName: String?, projectBasePath: String? = null): Boolean = synchronized(LOCK) {
        val dir = scopeDir(scope, projectBasePath) ?: return false
        if (!dir.exists() && !dir.mkdirs()) return false
        val target = File(dir, "${def.name}.md")
        try {
            atomicWrite(target, renderMarkdown(def))
            // 改名清理旧文件（先写新成功再删旧，失败留旧文件不影响新定义生效）
            if (originalName != null && originalName != def.name) {
                val old = File(dir, "$originalName.md")
                if (old.exists()) old.delete()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 删除定义文件 */
    fun delete(scope: String, name: String, projectBasePath: String? = null): Boolean = synchronized(LOCK) {
        val dir = scopeDir(scope, projectBasePath) ?: return false
        val f = File(dir, "$name.md")
        f.exists() && f.delete()
    }

    /** 作用域目录（project 依赖项目路径，由调用方保证 basePath 非空） */
    private fun scopeDir(scope: String, projectBasePath: String? = null): File? = when (scope) {
        "user" -> File(Credentials.storageRoot().toFile(), "agents")
        "project" -> projectBasePath?.let { File(it, ".zcode/agents") }
        else -> null
    }

    private fun scanAgentDir(dir: File, scope: String, disabled: Set<String>, out: MutableList<AgentDef>) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".md") } ?: return
        files.sortedBy { it.name.lowercase() }.forEach { f ->
            val def = parseAgentFile(f, scope) ?: return@forEach
            if (def.name in disabled) return@forEach
            out.add(def)
        }
    }

    /** 解析单个定义文件（frontmatter 缺 name/description 时整个忽略，对齐 zcode.cjs 语义） */
    private fun parseAgentFile(f: File, scope: String): AgentDef? {
        return try {
            val text = f.readText()
            val (fm, body) = splitFrontmatter(text)
            val name = fm["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val description = fm["description"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            AgentDef(
                name = name,
                description = description,
                model = fm["model"]?.trim()?.takeIf { it.isNotEmpty() && it != "inherit" },
                thoughtLevel = fm["thoughtlevel"]?.trim()?.takeIf { it.isNotEmpty() },
                color = fm["color"]?.trim()?.takeIf { it.isNotEmpty() },
                tools = fm.list("tools"),
                disallowedTools = fm.list("disallowedtools"),
                maxTurns = fm["maxturns"]?.trim()?.toIntOrNull(),
                injectAgentsMd = fm["injectagentsmd"]?.trim()?.lowercase() != "false",
                mcpServers = fm.list("mcpservers"),
                systemPrompt = body.trim(),
                path = f.absolutePath,
                scope = scope,
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * frontmatter 解析（比 SlashCommandScanner.parseFrontmatter 多支持 YAML 块列表）：
     *   tools:            → key 无值，后续缩进 "- item" 行聚合成列表
     *     - Read
     *     - Bash
     */
    private class FmMap(private val scalars: Map<String, String>, private val lists: Map<String, List<String>>) {
        operator fun get(key: String): String? = scalars[key]
        fun list(key: String): List<String> = lists[key] ?: emptyList()
    }

    private fun splitFrontmatter(text: String): Pair<FmMap, String> {
        val m = Regex("^---\\r?\\n(.*?)\\r?\\n---\\r?\\n?(.*)$", RegexOption.DOT_MATCHES_ALL).find(text)
            ?: return FmMap(emptyMap(), emptyMap()) to text
        val scalars = LinkedHashMap<String, String>()
        val lists = LinkedHashMap<String, List<String>>()
        var pendingListKey: String? = null
        val pending = ArrayList<String>()
        for (line in m.groupValues[1].lineSequence()) {
            val indent = line.takeWhile { it == ' ' }.length
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (indent > 0 && trimmed.startsWith("- ") && pendingListKey != null) {
                pending.add(trimmed.removePrefix("- ").trim().removeSurrounding("\""))
                continue
            }
            pendingListKey?.let { lists[it] = pending.toList() }
            pendingListKey = null; pending.clear()
            val idx = trimmed.indexOf(':')
            if (idx <= 0) continue
            val key = trimmed.substring(0, idx).trim().lowercase()
            var value = trimmed.substring(idx + 1).trim()
            if (value.length >= 2 && value.first() == value.last() && (value.first() == '"' || value.first() == '\'')) {
                value = value.substring(1, value.length - 1)
            }
            if (key in scalars) continue // 首值生效
            if (value.isEmpty()) {
                pendingListKey = key // 可能是块列表的 key（也可能就是空值，收尾时存空列表）
            } else if (value.startsWith("[") && value.endsWith("]")) {
                lists[key] = value.removeSurrounding("[", "]").split(",").map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }
            } else {
                scalars[key] = value
            }
        }
        pendingListKey?.let { lists[it] = pending.toList() }
        return FmMap(scalars, lists) to m.groupValues[2]
    }

    /** 序列化为 ZCode 兼容的 frontmatter + 正文 */
    private fun renderMarkdown(def: AgentDef): String {
        val sb = StringBuilder("---\n")
        sb.append("name: \"").append(escapeYaml(def.name)).append("\"\n")
        sb.append("description: \"").append(escapeYaml(def.description)).append("\"\n")
        def.color?.let { sb.append("color: \"").append(it).append("\"\n") }
        def.model?.let { sb.append("model: \"").append(it).append("\"\n") }
        def.thoughtLevel?.let { sb.append("thoughtLevel: \"").append(it).append("\"\n") }
        if (def.tools.isNotEmpty()) {
            sb.append("tools:\n")
            def.tools.forEach { sb.append("  - \"").append(it).append("\"\n") }
        }
        if (def.disallowedTools.isNotEmpty()) {
            sb.append("disallowedTools:\n")
            def.disallowedTools.forEach { sb.append("  - \"").append(it).append("\"\n") }
        }
        if (def.mcpServers.isNotEmpty()) {
            sb.append("mcpServers:\n")
            def.mcpServers.forEach { sb.append("  - \"").append(it).append("\"\n") }
        }
        def.maxTurns?.let { sb.append("maxTurns: ").append(it).append("\n") }
        sb.append("injectAgentsMd: ").append(def.injectAgentsMd).append("\n")
        sb.append("---\n\n")
        sb.append(def.systemPrompt.trimEnd()).append("\n")
        return sb.toString()
    }

    private fun escapeYaml(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** ~/.zcode/v2/agents-state.json 的 disabledAgentIds（文件缺失/损坏按空处理） */
    private fun disabledAgentIds(): Set<String> {
        return try {
            val f = File(Credentials.storageRoot().toFile(), "v2/agents-state.json")
            if (!f.isFile) return emptySet()
            val root = json.parseToJsonElement(f.readText()).jsonObject
            root["disabledAgentIds"]?.jsonArray
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?.toSet()
                ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /** 向上探测 git 根（含 .git 的最近祖先） */
    private fun findGitRoot(start: String): File? {
        var dir: File? = File(start).absoluteFile
        while (dir != null) {
            if (File(dir, ".git").exists()) return dir
            dir = dir.parentFile
        }
        return null
    }
}
