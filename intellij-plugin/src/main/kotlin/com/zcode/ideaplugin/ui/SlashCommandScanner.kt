package com.zcode.ideaplugin.ui

import java.io.File
import java.nio.charset.Charset

/**
 * 斜杠命令/技能磁盘扫描器（输入框 / 快捷选择数据源）
 *
 * 扫描顺序与 ZCode 客户端发现顺序一致（先扫先得，同名后到者被忽略）：
 *   1. 用户级  ~/.zcode/skills、~/.agents/skills、~/.zcode/commands、~/.agents/commands
 *   2. 工作区级 项目根 .zcode/skills、.agents/skills、.zcode/commands、.agents/commands
 *   3. 插件贡献 仅实际启用的插件（见 scanPluginResources），注册为 `插件名:名称`
 *   4. CLI 内置命令兜底（仅 compact/init 2 个，服务端 send 可解析；
 *      定义在 zcode.cjs 内磁盘无文件，app-server 协议也无 commands/list RPC，
 *      只能随插件内置清单）
 *
 * - SKILL.md：解析 frontmatter（name/description/userInvocable），name 缺省用目录名，
 *   userInvocable: false 过滤（与 cc-gui SlashCommandRegistry 一致）
 * - 命令 .md：文件名（去 .md）为名，嵌套目录冒号连接（review/code.md → review:code）
 * - frontmatter 解析失败/IO 异常跳过该文件，不影响其他结果
 */
object SlashCommandScanner {

    /** 一条可展示的斜杠命令 */
    data class SlashCommand(
        val name: String,
        val description: String?,
        /** skill=技能（SKILL.md）| command=命令（.md）*/
        val kind: String,
        /** user / workspace / plugin / builtin */
        val source: String,
    )

    private val FRONTMATTER_RE = Regex("^---\\s*\\r?\\n([\\s\\S]*?)\\r?\\n---")
    private const val MAX_READ = 4096

    /**
     * CLI 内置命令提示清单（仅列服务端 send 可解析执行的；goal 虽在官方客户端 `/`
     * 补全中出现，但属客户端本地功能，发到 app-server 不被解析只会原文透传给模型，不列）。
     * name+summary 从 zcode.cjs bundle 提取，版本升级时校准：
     * grep -o 'name:"[a-z-]*",summary:"[^"]*"' zcode.cjs
     */
    private val BUILTIN_COMMANDS = listOf(
        "compact" to "Compact the current conversation with optional instructions.",
        "init" to "Create or update workspace AGENTS.md instructions.",
    )

    /** 扫描全部来源，返回按名去重后的列表（先扫先得）；home 参数供测试注入隔离目录 */
    fun scan(projectBasePath: String?, home: String? = System.getProperty("user.home")): List<SlashCommand> {
        val result = LinkedHashMap<String, SlashCommand>()
        if (home == null) return emptyList()

        // 1. 用户级
        scanSkillDir(File(home, ".zcode/skills"), "user", result)
        scanSkillDir(File(home, ".agents/skills"), "user", result)
        scanCommandDir(File(home, ".zcode/commands"), "user", result)
        scanCommandDir(File(home, ".agents/commands"), "user", result)

        // 2. 工作区级（项目根）
        if (!projectBasePath.isNullOrBlank()) {
            val base = File(projectBasePath)
            scanSkillDir(File(base, ".zcode/skills"), "workspace", result)
            scanSkillDir(File(base, ".agents/skills"), "workspace", result)
            scanCommandDir(File(base, ".zcode/commands"), "workspace", result)
            scanCommandDir(File(base, ".agents/commands"), "workspace", result)
        }

        // 3. 插件贡献
        scanPluginResources(File(home, ".zcode/cli/plugins"), result)

        // 4. CLI 内置命令（最后合入：用户/插件自定义同名命令优先展示）
        BUILTIN_COMMANDS.forEach { (name, summary) ->
            putIfAbsent(result, name, summary, "command", "builtin")
        }

        return result.values.toList()
    }

    /** 技能目录：<root>/<skill-name>/SKILL.md */
    private fun scanSkillDir(dir: File, source: String, result: LinkedHashMap<String, SlashCommand>) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { skillDir ->
            if (!skillDir.isDirectory) return@forEach
            val skillFile = File(skillDir, "SKILL.md")
            if (!skillFile.isFile) return@forEach
            try {
                val fm = parseFrontmatter(skillFile.readText(Charsets.UTF_8).take(MAX_READ))
                if (!isUserInvocable(fm)) return@forEach
                val name = fm["name"] ?: skillDir.name
                putIfAbsent(result, name, fm["description"], "skill", source)
            } catch (_: Exception) {
                // frontmatter 解析失败跳过（不中断整体扫描）
            }
        }
    }

    /**
     * 命令目录：递归扫描 .md，嵌套目录冒号连接；
     * namespace 非空时注册为 `命名空间:名称`（插件命令用，与 CLI 规范名对齐）
     */
    private fun scanCommandDir(
        dir: File,
        source: String,
        result: LinkedHashMap<String, SlashCommand>,
        prefix: String = "",
        namespace: String? = null,
    ) {
        if (!dir.isDirectory) return
        dir.listFiles()?.sorted()?.forEach { f ->
            if (f.isDirectory) {
                val childPrefix = if (prefix.isEmpty()) f.name else "$prefix:${f.name}"
                scanCommandDir(f, source, result, childPrefix, namespace)
            } else if (f.isFile && f.extension.equals("md", ignoreCase = true)) {
                val nested = if (prefix.isEmpty()) f.nameWithoutExtension else "$prefix:${f.nameWithoutExtension}"
                val name = namespace?.let { "$it:$nested" } ?: nested
                if (result.containsKey(name)) return@forEach
                try {
                    val fm = parseFrontmatter(f.readText(Charsets.UTF_8).take(MAX_READ))
                    putIfAbsent(result, name, fm["description"], "command", source)
                } catch (_: Exception) {
                    putIfAbsent(result, name, null, "command", source)
                }
            }
        }
    }

    /**
     * 插件贡献：只认实际启用的插件（对齐 `zcode plugins list` 的 enabled 集合）。
     * ~/.zcode/cli/plugins 下三棵树性质不同（全树扫描会把市场清单当可用项）：
     *   data/<插件名>@<市场名>/      启用插件初始化时创建的数据目录 = 启用判据（实测与 CLI 输出一致）
     *   cache/<市场>/<插件>/<版本>/  安装内容（多版本共存取最新）
     *   marketplaces/<市场>/        市场源完整清单（未安装插件的仓库内容），不进补全
     * 插件项注册为 `插件名:名称`（对齐 CLI 规范名，如 browser-use:control-browser）
     */
    private fun scanPluginResources(root: File, result: LinkedHashMap<String, SlashCommand>) {
        enabledPluginVersionDirs(root).forEach { (pluginName, versionDir) ->
            // skills/<name>/SKILL.md（直下一层，对齐 CLI 的 skills 计数语义）
            File(versionDir, "skills").listFiles()?.filter { it.isDirectory }?.forEach { skillDir ->
                val skillFile = File(skillDir, "SKILL.md")
                if (!skillFile.isFile) return@forEach
                try {
                    val fm = parseFrontmatter(skillFile.readText(Charsets.UTF_8).take(MAX_READ))
                    if (!isUserInvocable(fm)) return@forEach
                    val name = fm["name"] ?: skillDir.name
                    putIfAbsent(result, "$pluginName:$name", fm["description"], "skill", "plugin")
                } catch (_: Exception) { }
            }
            scanCommandDir(File(versionDir, "commands"), "plugin", result, namespace = pluginName)
        }
    }

    /** 启用插件 → 其最新版本目录（SkillScanner 共用）；data/ 目录名形如 插件名@市场名 */
    internal fun enabledPluginVersionDirs(root: File): List<Pair<String, File>> {
        val dataDir = File(root, "data")
        val cacheDir = File(root, "cache")
        if (!dataDir.isDirectory || !cacheDir.isDirectory) return emptyList()
        return dataDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.contains('@') }
            .mapNotNull { data ->
                val pluginName = data.name.substringBefore('@')
                val marketplace = data.name.substringAfter('@')
                if (pluginName.isEmpty() || marketplace.isEmpty()) return@mapNotNull null
                newestVersionDir(File(cacheDir, "$marketplace/$pluginName"))?.let { pluginName to it }
            }
    }

    /** 版本目录取语义最新（点分数值比较，0.10.0 > 0.9.0；非数字段按 0）*/
    private fun newestVersionDir(pluginRoot: File): File? =
        pluginRoot.listFiles().orEmpty()
            .filter { it.isDirectory }
            .maxWithOrNull { a, b -> compareVersionNames(a.name, b.name) }

    private fun compareVersionNames(a: String, b: String): Int {
        val va = a.split('.').map { it.toIntOrNull() ?: 0 }
        val vb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(va.size, vb.size)) {
            val c = va.getOrElse(i) { 0 }.compareTo(vb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun putIfAbsent(
        result: LinkedHashMap<String, SlashCommand>,
        name: String,
        description: String?,
        kind: String,
        source: String,
    ) {
        if (!result.containsKey(name)) {
            result[name] = SlashCommand(name, description?.takeIf { it.isNotBlank() }, kind, source)
        }
    }

    /** 解析 YAML frontmatter 首行值（name/description 等单行标量；SkillScanner 共用） */
    internal fun parseFrontmatter(text: String): Map<String, String> {
        val m = FRONTMATTER_RE.find(text) ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (line in m.groupValues[1].lineSequence()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim().lowercase()
            if (key in map) continue // 首值生效
            var value = line.substring(idx + 1).trim()
            // 去掉首尾引号
            if (value.length >= 2 && value.first() == value.last() && (value.first() == '"' || value.first() == '\'')) {
                value = value.substring(1, value.length - 1)
            }
            map[key] = value
        }
        return map
    }

    private fun isUserInvocable(fm: Map<String, String>): Boolean {
        val v = fm["userinvocable"]?.lowercase() ?: return true // 默认可调用
        return v !in setOf("false", "no", "0")
    }
}
