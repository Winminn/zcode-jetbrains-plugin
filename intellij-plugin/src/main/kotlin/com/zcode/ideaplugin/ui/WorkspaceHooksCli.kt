package com.zcode.ideaplugin.ui

import com.zcode.ideaplugin.env.ZCodeEnvChecker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 工作区钩子管理：经官方 zcode CLI 的 `hooks trust` 子命令读状态/授予/撤销，
 * 开关与删除直接写 `<workspace>/.zcode/config.json`（CLI 无 toggle/delete 子命令；
 * 官方 toggleWorkspaceHookReviewItem/deleteHook 的语义也是写这个文件，
 * 且只允许写这个文件）。删除只移除登记项，脚本文件本体保留在磁盘。
 *
 * app-server 的 JSON-RPC 面没有任何「列钩子」方法（只有 workspace/hooks/trustGrant，
 * 且要单条 digest），hooks 桌面服务在 app-server 未注册——一次性 CLI 子进程是
 * 离官方同款数据最近的只读通道（status 由 zcode.cjs 自己重新发现 canonical snapshot）。
 */
object WorkspaceHooksCli {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 变更串行锁：op 分发在 pooled 线程可并发，而 setEnabled 是 config.json 的
     * 读-改-写（且 writeString 非原子，CLI 可能读到写一半的文件）；grant/revoke
     * 走 CLI（内部 tmp+rename）也排队。status 一并入锁，保证读到完整文件、
     * 且「变更+回读 status」整体原子，前端乐观更新的对账响应不串序。
     */
    private val lock = Any()

    /** `hooks trust status --json` 原始结果（workspacePath/bundleDigest/items[] 全字段） */
    fun status(workspace: String, timeoutMs: Long = 20_000): JsonObject = synchronized(lock) {
        val out = runCli(timeoutMs, "status", "--workspace", workspace, "--json")
        json.parseToJsonElement(out).jsonObject
    }

    /** 按 digest 授予；返回授予后的权威 status（grant 输出是人类文本，不直接消费） */
    fun grant(workspace: String, hookDigests: List<String>, timeoutMs: Long = 20_000): JsonObject = synchronized(lock) {
        require(hookDigests.isNotEmpty())
        val args = buildList {
            add("grant"); add("--workspace"); add(workspace)
            for (d in hookDigests) { add("--hook-digest"); add(d) }
        }
        runCli(timeoutMs, *args.toTypedArray())
        status(workspace, timeoutMs)
    }

    /** 授予当前配置束的全部**启用中**钩子（--all-current 实测跳过 enabled:false；防 stale 带 bundleDigest） */
    fun grantAllCurrent(workspace: String, bundleDigest: String, timeoutMs: Long = 20_000): JsonObject = synchronized(lock) {
        runCli(timeoutMs,
            "grant", "--workspace", workspace, "--all-current", "--bundle-digest", bundleDigest)
        status(workspace, timeoutMs)
    }

    /** 撤销；digests 空 = --all */
    fun revoke(workspace: String, hookDigests: List<String>, timeoutMs: Long = 20_000): JsonObject = synchronized(lock) {
        val args = buildList {
            add("revoke"); add("--workspace"); add(workspace)
            if (hookDigests.isEmpty()) {
                add("--all")
            } else {
                for (d in hookDigests) { add("--hook-digest"); add(d) }
            }
        }
        runCli(timeoutMs, *args.toTypedArray())
        status(workspace, timeoutMs)
    }

    private fun runCli(timeoutMs: Long, vararg args: String): String {
        val env = ZCodeEnvChecker.check()
        val cmd = buildList {
            add(env.node.path ?: throw IllegalStateException("Node.js 路径不可用"))
            add(env.cli.path ?: throw IllegalStateException("zcode.cjs 路径不可用"))
            add("hooks"); add("trust")
            addAll(args)
        }
        val pb = ProcessBuilder(cmd)
        pb.environment()["NO_COLOR"] = "1"
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val err = proc.errorStream.bufferedReader(Charsets.UTF_8).readText()
        if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            proc.destroyForcibly()
            throw IllegalStateException("zcode hooks 命令超时（${timeoutMs}ms）：${args.firstOrNull()}")
        }
        if (proc.exitValue() != 0) {
            throw IllegalStateException("zcode hooks ${args.firstOrNull()} 失败：$err".trim())
        }
        return out.trim()
    }

    /**
     * 单条钩子启停：写 .zcode/config.json 中该 hook 的 enabled 字段
     * （false=写 enabled:false，true=删字段——缺省即启用）。
     *
     * @param reviewItemId 形如 workspace-hook-0-PreToolUse-0-0；末两段=事件组序号/钩子序号
     * @param sourcePath status 里该条的来源；只支持 .zcode/config.json（官方同款约束）
     */
    fun setEnabled(workspace: String, reviewItemId: String, sourcePath: String, enabled: Boolean): JsonObject = synchronized(lock) {
        parseHookLoc(reviewItemId)
        requireConfigSource(sourcePath)

        val configPath = Path.of(workspace, sourcePath)
        if (!Files.isRegularFile(configPath)) throw IllegalStateException("配置文件不存在：$configPath")
        val config = json.parseToJsonElement(Files.readString(configPath)).jsonObject
        Files.writeString(configPath, jsonPretty(setHookEnabledInConfig(config, reviewItemId, enabled)))
        status(workspace)
    }

    /**
     * 删除单条钩子登记项：从 .zcode/config.json 的 hooks.events 树移除并逐级剪枝，
     * 脚本文件本体不动（误删可重新登记挽回；官方 deleteHook 也只写配置）。
     * 不连带 revoke——信任存储里的 digest 成为无害悬空记录（官方同款）。
     *
     * @param reviewItemId 形如 workspace-hook-0-PreToolUse-0-0；末三段=事件/组序号/钩子序号
     * @param sourcePath status 里该条的来源；只支持 .zcode/config.json（官方同款约束）
     */
    fun delete(workspace: String, reviewItemId: String, sourcePath: String): JsonObject = synchronized(lock) {
        parseHookLoc(reviewItemId)
        requireConfigSource(sourcePath)

        val configPath = Path.of(workspace, sourcePath)
        if (!Files.isRegularFile(configPath)) throw IllegalStateException("配置文件不存在：$configPath")
        val config = json.parseToJsonElement(Files.readString(configPath)).jsonObject
        Files.writeString(configPath, jsonPretty(removeHookFromConfig(config, reviewItemId)))
        status(workspace)
    }

    /** reviewItemId 末三段定位：事件名 / 事件组序号 / 组内钩子序号 */
    private data class HookLoc(val event: String, val groupIdx: Int, val hookIdx: Int)

    private fun parseHookLoc(reviewItemId: String): HookLoc {
        val parts = reviewItemId.split('-')
        require(parts.size >= 6) { "无法解析 reviewItemId: $reviewItemId" }
        val groupIdx = parts[parts.size - 2].toIntOrNull()
            ?: throw IllegalStateException("无法解析 reviewItemId: $reviewItemId")
        val hookIdx = parts[parts.size - 1].toIntOrNull()
            ?: throw IllegalStateException("无法解析 reviewItemId: $reviewItemId")
        return HookLoc(parts[parts.size - 3], groupIdx, hookIdx)
    }

    private fun requireConfigSource(sourcePath: String) {
        if (sourcePath.replace('\\', '/') != ".zcode/config.json") {
            throw IllegalStateException("仅支持修改 .zcode/config.json 中的钩子（实际来源 $sourcePath）")
        }
    }

    // ============ 用户级钩子（~/.zcode/cli/config.json；无信任准入，enabled 即生效） ============
    //
    // 与工作区的差异（官方 hooksService 源码核实）：
    //  - 不走 zcode CLI（trust status 只列工作区钩子），直读直写用户配置，无 ~1s 进程延迟；
    //  - 不计算 digest、不查信任存储，没有信任/评审环节（fromUserZCodeSource 无 trustState）；
    //  - 相对命令路径的解析基准是用户 home（官方 workingDirectory=resolveUserHomeDir()）；
    //  - 配置顶层还有 skills/mcp/plugins 等段，写时必须整树保留。
    // 行 id 仍取 6 段形如 user-hook-0-PreToolUse-0-0，末三段定位规则与工作区一致，复用树操作。

    private val userLock = Any()

    /** 用户级配置路径：~/.zcode/cli/config.json */
    fun userConfigPath(home: Path = Path.of(System.getProperty("user.home"))): Path =
        home.resolve(".zcode/cli/config.json")

    /** 用户级钩子清单（同构工作区 status 的 items 形态；scope=user，trustState 固定 user_scope） */
    fun userHooksList(home: Path = Path.of(System.getProperty("user.home"))): JsonObject = synchronized(userLock) {
        val path = userConfigPath(home)
        val items = mutableListOf<JsonObject>()
        if (Files.isRegularFile(path)) {
            val config = json.parseToJsonElement(Files.readString(path)).jsonObject
            val events = config["hooks"]?.jsonObject?.get("events")?.jsonObject
            if (events != null) {
                for ((event, groupsVal) in events) {
                    val groups = groupsVal as? JsonArray ?: continue
                    groups.forEachIndexed { g, groupVal ->
                        val groupObj = groupVal as? JsonObject ?: return@forEachIndexed
                        val matcher = groupObj["matcher"]?.jsonPrimitive?.contentOrNull
                        (groupObj["hooks"] as? JsonArray)?.forEachIndexed { h, hookVal ->
                            val hookObj = hookVal as? JsonObject ?: return@forEachIndexed
                            val command = hookObj["command"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                            val args = (hookObj["args"] as? JsonArray)
                                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                                ?: emptyList()
                            items += buildJsonObject {
                                put("reviewItemId", "user-hook-0-$event-$g-$h")
                                put("event", event)
                                if (matcher != null) put("matcher", matcher)
                                put("displayCommand", (listOf(command) + args).joinToString(" "))
                                put("sourcePath", path.toString())
                                put("configuredEnabled", hookObj["enabled"]?.jsonPrimitive?.contentOrNull != "false")
                                put("hookDeclarationDigest", "")
                                put("trustState", "user_scope")
                            }
                        }
                    }
                }
            }
        }
        buildJsonObject {
            put("scope", "user")
            put("userConfigPath", path.toString())
            put("items", JsonArray(items))
        }
    }

    private fun requireUserConfigSource(sourcePath: String, home: Path) {
        if (Path.of(sourcePath).normalize() != userConfigPath(home).normalize()) {
            throw IllegalStateException("仅支持修改用户配置（实际来源 $sourcePath）")
        }
    }

    private fun rewriteUserConfig(
        home: Path,
        reviewItemId: String,
        transform: (JsonObject) -> JsonObject,
    ): JsonObject = synchronized(userLock) {
        parseHookLoc(reviewItemId)
        val path = userConfigPath(home)
        if (!Files.isRegularFile(path)) throw IllegalStateException("用户配置文件不存在：$path")
        val config = json.parseToJsonElement(Files.readString(path)).jsonObject
        Files.writeString(path, jsonPretty(transform(config)))
        userHooksList(home)
    }

    /** 用户级钩子启停（false=写 enabled:false，true=删字段；缺省即启用） */
    fun userSetEnabled(
        reviewItemId: String,
        sourcePath: String,
        enabled: Boolean,
        home: Path = Path.of(System.getProperty("user.home")),
    ): JsonObject {
        requireUserConfigSource(sourcePath, home)
        return rewriteUserConfig(home, reviewItemId) { setHookEnabledInConfig(it, reviewItemId, enabled) }
    }

    /** 用户级钩子删除（剪枝语义同工作区；只删登记项，脚本文件保留） */
    fun userDelete(
        reviewItemId: String,
        sourcePath: String,
        home: Path = Path.of(System.getProperty("user.home")),
    ): JsonObject {
        requireUserConfigSource(sourcePath, home)
        return rewriteUserConfig(home, reviewItemId) { removeHookFromConfig(it, reviewItemId) }
    }

    /**
     * 从 config JSON 树移除指定钩子并逐级剪枝（纯函数，无文件/CLI 依赖）：
     * 组内 hooks 空 → 删事件组；事件数组空 → 删事件键；events 空 → 删 events；
     * hooks 对象空 → 删 hooks。其余字段（含根 hooks.enabled）原样保留。
     */
    internal fun removeHookFromConfig(config: JsonObject, reviewItemId: String): JsonObject {
        val t = locateHook(config, reviewItemId)
        val events = config["hooks"]!!.jsonObject["events"]!!.jsonObject

        val remainingHooks = JsonArray(t.groupHooks.filterIndexed { i, _ -> i != t.hookIdx })
        val remainingGroups = t.eventArr.toMutableList()
        if (remainingHooks.isEmpty()) {
            remainingGroups.removeAt(t.groupIdx)
        } else {
            remainingGroups[t.groupIdx] = JsonObject(t.group + ("hooks" to remainingHooks))
        }

        val hooksObj = config["hooks"]!!.jsonObject
        val newHooksObj = if (remainingGroups.isEmpty()) {
            // 事件数组空 → 删事件键
            val eventless = events.filterKeys { it != t.event }
            when {
                // events 空 → 删 events
                eventless.isEmpty() -> JsonObject(hooksObj.filterKeys { it != "events" })
                else -> JsonObject(hooksObj + ("events" to JsonObject(eventless)))
            }
        } else {
            JsonObject(hooksObj + ("events" to JsonObject(events + (t.event to JsonArray(remainingGroups)))))
        }
        // hooks 对象空（events 已无且无 enabled 等其余字段）→ 删 hooks
        return if (newHooksObj.isEmpty()) {
            JsonObject(config.filterKeys { it != "hooks" })
        } else {
            JsonObject(config + ("hooks" to newHooksObj))
        }
    }

    /**
     * 设置单条钩子 enabled（纯函数）：false=写 enabled:false，true=删字段（缺省即启用）。
     * 逐层重建路径上的对象，其余字段不动。工作区/用户两份 config 共用。
     */
    internal fun setHookEnabledInConfig(config: JsonObject, reviewItemId: String, enabled: Boolean): JsonObject {
        val t = locateHook(config, reviewItemId)
        val newHook: JsonObject = if (enabled) {
            JsonObject(t.hook.filterKeys { it != "enabled" })
        } else {
            JsonObject(t.hook + ("enabled" to JsonPrimitive(false)))
        }
        val newGroupHooks = JsonArray(t.groupHooks.toMutableList().also { it[t.hookIdx] = newHook })
        val newGroup = JsonObject(t.group + ("hooks" to newGroupHooks))
        val newEventArr = JsonArray(t.eventArr.toMutableList().also { it[t.groupIdx] = newGroup })
        val hooksObj = config["hooks"]!!.jsonObject
        val events = hooksObj["events"]!!.jsonObject
        return JsonObject(config + ("hooks" to JsonObject(
            hooksObj + ("events" to JsonObject(events + (t.event to newEventArr))),
        )))
    }

    /** 按 reviewItemId 末三段在 config 树中定位到具体钩子（含沿途节点，供移除/启停复用） */
    private class HookTarget(
        val event: String,
        val eventArr: JsonArray,
        val groupIdx: Int,
        val group: JsonObject,
        val groupHooks: JsonArray,
        val hookIdx: Int,
        val hook: JsonObject,
    )

    private fun locateHook(config: JsonObject, reviewItemId: String): HookTarget {
        val (event, groupIdx, hookIdx) = parseHookLoc(reviewItemId)
        val events = config["hooks"]?.jsonObject?.get("events")?.jsonObject
            ?: throw IllegalStateException("配置中找不到钩子事件：$reviewItemId")
        val eventArr = events[event]?.jsonArray
            ?: throw IllegalStateException("配置中找不到事件 $event：$reviewItemId")
        val group = eventArr.getOrNull(groupIdx)?.jsonObject
            ?: throw IllegalStateException("配置中找不到事件组：$reviewItemId")
        val groupHooks = group["hooks"]?.jsonArray
            ?: throw IllegalStateException("配置中找不到钩子数组：$reviewItemId")
        val hook = groupHooks.getOrNull(hookIdx)?.jsonObject
            ?: throw IllegalStateException("配置中找不到该钩子：$reviewItemId")
        return HookTarget(event, eventArr, groupIdx, group, groupHooks, hookIdx, hook)
    }

    private fun jsonPretty(obj: JsonObject): String =
        Json { prettyPrint = true; ignoreUnknownKeys = true }.encodeToString(JsonObject.serializer(), obj) + "\n"

    // ============ 命令 → 脚本文件解析（命令行点击「打开钩子文件」用） ============

    /** 已知解释器/壳（basename 去扩展名小写比较；cmd 额外跳过 /c|/k） */
    private val RUNNERS = setOf(
        "bash", "sh", "zsh", "fish", "dash", "ksh",
        "node", "deno", "bun",
        "python", "python3", "py", "ruby", "perl", "php",
        "powershell", "pwsh", "cmd", "wsl",
    )

    /** runner 的内联执行参数：其后跟脚本文本而非文件路径（-e 语义随 runner：bash 是 errexit，node/ruby 是 eval） */
    private fun inlineFlags(runner: String): Set<String> = when (runner) {
        "node", "deno", "bun", "ruby", "perl" -> setOf("-e", "--eval")
        "php" -> setOf("-r")
        "python", "python3", "py" -> setOf("-c")
        "powershell", "pwsh" -> setOf("-c", "-command")
        else -> setOf("-c", "--command") // 各 shell
    }

    /**
     * 从 displayCommand（如 `bash "E:/ws/.zcode/hooks/a.sh" --x`）解析钩子脚本文件。
     *
     * 规则：按空白切词（尊重单/双引号）；首 token 是已知 runner 时跳过 runner、
     * cmd 的 /c|/k 与前导 flag（遇内联执行参数直接判无文件），取下一个 token；
     * 首 token 不是 runner（如 `./a.sh` 直跑）则它本身即目标。相对路径按 workspace
     * 解析；目标不是普通文件返回 null（调用方回退打开来源配置文件）。
     */
    fun resolveScriptPath(workspace: String, displayCommand: String): Path? {        val tokens = tokenizeCommand(displayCommand)
        if (tokens.isEmpty()) return null

        val runnerBase = java.io.File(tokens[0]).name.substringBeforeLast('.').lowercase()
        var idx = 0
        if (runnerBase in RUNNERS) {
            idx = 1
            if (runnerBase == "cmd" && idx < tokens.size && tokens[idx].lowercase() in setOf("/c", "/k")) idx++
            val inline = inlineFlags(runnerBase)
            while (idx < tokens.size && tokens[idx].startsWith("-")) {
                if (tokens[idx] in inline) return null
                idx++
            }
        }
        val target = tokens.getOrNull(idx) ?: return null

        val path = if (isAbsolute(target)) Path.of(target) else Path.of(workspace, target)
        return path.normalize().takeIf { Files.isRegularFile(it) }
    }

    /** 用户级钩子的脚本解析：相对路径基准为用户 home（官方 workingDirectory=home） */
    fun resolveUserScriptPath(displayCommand: String): Path? =
        resolveScriptPath(System.getProperty("user.home"), displayCommand)

    private fun isAbsolute(p: String): Boolean =
        p.startsWith("/") || p.startsWith("\\") || (p.length >= 3 && p[1] == ':' && (p[2] == '/' || p[2] == '\\'))

    /** 简单切词：双/单引号内的空白不切词，去掉外层引号；其余字符（含反斜杠）原样保留 */
    private fun tokenizeCommand(cmd: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var hasToken = false
        for (ch in cmd) {
            when {
                quote != null -> {
                    if (ch == quote) quote = null else { cur.append(ch); hasToken = true }
                }
                ch == '"' || ch == '\'' -> { quote = ch; hasToken = true }
                ch.isWhitespace() -> {
                    if (hasToken) { out.add(cur.toString()); cur.setLength(0); hasToken = false }
                }
                else -> { cur.append(ch); hasToken = true }
            }
        }
        if (hasToken) out.add(cur.toString())
        return out
    }
}
