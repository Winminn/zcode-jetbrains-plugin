package com.zcode.ideaplugin.ui

import java.io.File
import java.security.MessageDigest

/**
 * 记忆文件扫描器（设置页「记忆」条目数据源）
 *
 * 两类记忆：
 *   1. 指令记忆（instructions，缺失可创建默认模板）
 *      - 全局   ~/.zcode/AGENTS.md   所有项目的会话读取
 *      - 项目根 AGENTS.md            仅当前项目的会话读取
 *   2. 自动记忆（auto，ZCode 自动生成，只读展示）
 *      ~/.zcode/cli/memories/projects/<前缀>-<hash16>/memory/
 *        MEMORY.md = 索引（每条记忆一行），其余 *.md = 单条事实
 *      展示顺序跟索引走、标题取索引链接文本；未被索引引用的文件标 orphaned；
 *      hash16 = sha256(项目绝对路径小写、原生分隔符形态) 前 16 位 hex；前缀通常为
 *      项目目录名小写，但 CLI 会改写不安全字符（中文目录名 → project），定位须按
 *      哈希后缀匹配（详见 findMemoryDir；文档：zcode.z.ai/cn/docs/memory）
 */
object MemoryFileScanner {

    /** 一条可展示的记忆文件（指令记忆缺失项也返回）*/
    data class MemoryFile(
        /** 文件名，如 AGENTS.md、MEMORY.md */
        val name: String,
        /** global=全局 / project=项目 */
        val scope: String,
        /** instructions=指令记忆（可创建）/ auto=ZCode 自动提取的事实记忆 */
        val kind: String,
        /** 绝对路径 */
        val path: String,
        /** 是否已存在 */
        val exists: Boolean,
        val sizeBytes: Long? = null,
        val lastModified: Long? = null,
    /** 展示说明（历史字段：后端拼好的中文，前端已改按 scope/kind 走 i18n，不再直接渲染）*/
    val description: String,
    /** auto 事实文件展示摘要：MEMORY.md 索引链接文本优先，frontmatter description / # 标题兜底 */
    val title: String? = null,
    /** auto 事实文件未被 MEMORY.md 索引引用（前端标「找不到引用」，排在有引用条目之后）*/
    val orphaned: Boolean = false,
)

    /**
     * 自动记忆目录定位结果（设置页展示，排查「有记忆但读取不到」用）
     *
     * @param expectedDir 按当前项目路径推算的期望目录（前缀取目录名小写，仅参考——
     *   CLI 会改写非 ASCII 等目录名前缀，权威判据是末尾 16 位哈希）
     */
    data class MemoryDirInfo(
        /** 记忆根目录（所有项目共用）：~/.zcode/cli/memories/projects */
        val projectsRoot: String,
        /** 期望目录（哈希主形态=原生分隔符小写路径）*/
        val expectedDir: String,
        /** 实际命中的记忆目录；null = 该项目路径下 CLI 未建过记忆 */
        val dir: String?,
    )

    /** 目录定位（与 list() 的自动记忆扫描同源），无打开项目时返回 null；homeDir 注入供测试 */
    fun locate(projectBasePath: String?, homeDir: String? = null): MemoryDirInfo? {
        val home = homeDir ?: System.getProperty("user.home") ?: return null
        if (projectBasePath.isNullOrBlank()) return null
        val projectsRoot = File(home, ".zcode/cli/memories/projects")
        val projectName = File(projectBasePath).name.lowercase()
        val primaryKey = memoryKey(projectBasePath.replace('/', File.separatorChar))
        return MemoryDirInfo(
            projectsRoot = projectsRoot.absolutePath,
            expectedDir = File(projectsRoot, "$projectName-$primaryKey/memory").absolutePath,
            dir = findMemoryDir(home, projectBasePath)?.absolutePath,
        )
    }

    /** 指令记忆固定清单 + 自动记忆目录扫描；homeDir 注入供测试 */
    fun list(projectBasePath: String?, homeDir: String? = null): List<MemoryFile> {
        val home = homeDir ?: System.getProperty("user.home") ?: return emptyList()
        val result = mutableListOf<MemoryFile>()

        result.add(inspect(File(home, ".zcode/AGENTS.md"), "global", "instructions", "所有项目的 ZCode 会话自动读取"))
        if (!projectBasePath.isNullOrBlank()) {
            val base = File(projectBasePath)
            result.add(inspect(File(base, "AGENTS.md"), "project", "instructions", "当前项目的 ZCode 会话自动读取"))
            result.addAll(scanAutoMemories(home, projectBasePath))
        }
        return result
    }

    /** 写入默认模板（父目录自动创建）。已存在时不覆盖，直接返回 true */
    fun createWithTemplate(file: MemoryFile): Boolean {
        val f = File(file.path)
        if (f.isFile) return true
        return try {
            f.parentFile?.mkdirs()
            f.writeText(templateFor(file), Charsets.UTF_8)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 自动记忆目录条目：MEMORY.md 索引排最前，事实文件按索引顺序跟随；
     * 标题取索引行链接文本（如「三台主机 SSH 访问」），未被索引引用的文件标
     * orphaned 排末尾（按修改时间倒序），摘要退回 frontmatter description。
     */
    private fun scanAutoMemories(home: String, projectBasePath: String): List<MemoryFile> {
        val dir = findMemoryDir(home, projectBasePath) ?: return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("md", ignoreCase = true) }
            ?: return emptyList()
        val (index, facts) = files.partition { it.name.equals("MEMORY.md", ignoreCase = true) }
        val indexItems = index.map {
            inspect(it, "project", "auto", "记忆索引（每条记忆一行，指向同目录事实文件）")
        }
        val indexOrder = index.firstOrNull()?.let { parseMemoryIndex(it) } ?: emptyMap()
        if (indexOrder.isEmpty()) {
            // 无索引可对照：全部按修改时间倒序，orphan 概念不适用
            return indexItems + facts.sortedByDescending { it.lastModified() }.map {
                val title = factSummary(it)
                inspect(it, "project", "auto", title ?: "").copy(title = title)
            }
        }
        // 有引用组严格按索引出现顺序（同毫秒文件的 lastModified 不可靠）；索引引用但文件缺失的自然跳过
        val referenced = indexOrder.entries.mapNotNull { (nameLower, title) ->
            facts.firstOrNull { it.name.lowercase() == nameLower }?.let { f ->
                inspect(f, "project", "auto", title).copy(title = title)
            }
        }
        val orphans = facts.filter { it.name.lowercase() !in indexOrder.keys }
            .sortedByDescending { it.lastModified() }
            .map { f ->
                val title = factSummary(f)
                inspect(f, "project", "auto", title ?: "").copy(title = title, orphaned = true)
            }
        return indexItems + referenced + orphans
    }

    /**
     * 解析 MEMORY.md 索引：文件名小写 → 链接文本标题（保持索引出现顺序）。
     * 行形如 `- [三台主机 SSH 访问](hosts-ssh-access.md) — 摘要…`；目标路径取末段
     * 文件名（含 % 编码时先 URL 解码，+ 先转义防吞）。
     */
    private fun parseMemoryIndex(indexFile: File): Map<String, String> = try {
        val lineRegex = Regex("""^\s*[-*+]\s+\[([^\]]+)\]\(([^)\s]+)\)""")
        indexFile.readLines(Charsets.UTF_8).mapNotNull { line ->
            lineRegex.find(line)?.let { m ->
                var target = m.groupValues[2].substringAfterLast('/').substringAfterLast('\\')
                if (target.contains('%')) {
                    target = try {
                        java.net.URLDecoder.decode(target.replace("+", "%2B"), Charsets.UTF_8)
                    } catch (_: Exception) {
                        target
                    }
                }
                target.lowercase() to m.groupValues[1].trim()
            }
        }.toMap()
    } catch (_: Exception) {
        emptyMap()
    }

    /**
     * 定位自动记忆目录。
     *
     * 目录名 = <前缀>-<hash16>，hash16 = sha256(原生分隔符形态的路径小写) 前 16 位 hex
     * （实测反推，如 zcode-idea-plugin-e0a18fbbbd5c65a8）。前缀通常取项目目录名小写，
     * 但 CLI 会改写不安全字符——中文目录名整个替换成 project（实锤：新平台访问环境
     * → project-7b2bd5221263438c），因此目录名前缀不可依赖，改为哈希后缀匹配：
     * hash16 由完整路径决定，撞车概率 2^-64，以 -<hash16> 结尾即命中。
     * IDE 的 project.basePath 是 VFS 正斜杠形态，与 CLI 哈希原料（Windows 反斜杠）
     * 两种形态都算。最后保留目录名前缀兜底（哈希规则变化时的最后手段）。
     */
    private fun findMemoryDir(home: String, projectBasePath: String): File? {
        val projectsRoot = File(home, ".zcode/cli/memories/projects")
        if (!projectsRoot.isDirectory) return null
        val candidates = projectsRoot.listFiles { f -> f.isDirectory }?.toList() ?: return null

        for (key in pathVariants(projectBasePath).map { memoryKey(it) }) {
            val hit = candidates.firstOrNull { it.name == key || it.name.endsWith("-$key") } ?: continue
            val memory = File(hit, "memory")
            if (memory.isDirectory) return memory
        }

        val projectName = File(projectBasePath).name.lowercase()
        return candidates.filter { it.name.lowercase().startsWith("$projectName-") }
            .map { File(it, "memory") }
            .firstOrNull { it.isDirectory }
    }

    /** basePath 的各分隔符形态（正斜杠 VFS 原样 / 原生分隔符 / 反斜杠转正斜杠），去重 */
    private fun pathVariants(projectBasePath: String): List<String> = linkedSetOf(
        projectBasePath,
        projectBasePath.replace('/', File.separatorChar),
        projectBasePath.replace('\\', '/'),
    ).toList()

    /** 项目路径 → 记忆目录 key：sha256(小写路径) 前 16 位 hex */
    private fun memoryKey(projectBasePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(projectBasePath.lowercase().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * 提取事实记忆的展示摘要（截断 80 字符）。
     * CLI 写的文件是 frontmatter 形态（--- + name/description），description 即该条
     * 记忆的一句话摘要，优先取；Markdown 标题形态（# xxx）兜底。只扫前 20 行。
     */
    private fun factSummary(f: File): String? = try {
        var desc: String? = null
        var heading: String? = null
        var inFrontmatter = false
        for ((i, line) in f.readLines(Charsets.UTF_8).withIndex()) {
            if (i > 20) break
            if (i == 0 && line.trim() == "---") { inFrontmatter = true; continue }
            if (inFrontmatter) {
                if (line.trim() == "---") break
                if (desc == null && line.startsWith("description:")) {
                    desc = line.removePrefix("description:").trim().take(80).ifEmpty { null }
                }
            } else if (heading == null && line.startsWith("# ")) {
                heading = line.removePrefix("# ").trim().take(80).ifEmpty { null }
                break
            }
        }
        desc ?: heading
    } catch (_: Exception) {
        null
    }

    private fun inspect(f: File, scope: String, kind: String, description: String): MemoryFile {
        val exists = f.isFile
        return MemoryFile(
            name = f.name,
            scope = scope,
            kind = kind,
            path = f.absolutePath,
            exists = exists,
            sizeBytes = if (exists) f.length() else null,
            lastModified = if (exists) f.lastModified() else null,
            description = description,
        )
    }

    /** 指令记忆默认模板 */
    private fun templateFor(file: MemoryFile): String = if (file.scope == "global") {
        "# 全局记忆\n\n" +
            "<!-- 全局记忆文件（~/.zcode/AGENTS.md）：所有项目的 ZCode 会话自动读取 -->\n\n"
    } else {
        "# 项目记忆\n\n" +
            "<!-- 项目级记忆（AGENTS.md）：当前项目的 ZCode 会话自动读取 -->\n\n"
    }
}
