package com.zcode.ideaplugin.ui

import com.zcode.ideaplugin.env.ZCodeEnvChecker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 工作区钩子管理：经官方 zcode CLI 的 `hooks trust` 子命令读状态/授予/撤销，
 * 开关直接写 `<workspace>/.zcode/config.json`（CLI 无 toggle 子命令；官方
 * toggleWorkspaceHookReviewItem 的语义也是写这个文件，且只允许写这个文件）。
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
        if (sourcePath.replace('\\', '/') != ".zcode/config.json") {
            throw IllegalStateException("仅支持修改 .zcode/config.json 中的钩子（实际来源 $sourcePath）")
        }
        val parts = reviewItemId.split('-')
        require(parts.size >= 6) { "无法解析 reviewItemId: $reviewItemId" }
        val event = parts[parts.size - 3]
        val groupIdx = parts[parts.size - 2].toInt()
        val hookIdx = parts[parts.size - 1].toInt()

        val configPath = Path.of(workspace, sourcePath)
        if (!Files.isRegularFile(configPath)) throw IllegalStateException("配置文件不存在：$configPath")
        val config = json.parseToJsonElement(Files.readString(configPath)).jsonObject

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

        // false=写 enabled:false，true=删字段（缺省即启用）
        val newHook: JsonObject = if (enabled) {
            JsonObject(hook.filterKeys { it != "enabled" })
        } else {
            JsonObject(hook + ("enabled" to JsonPrimitive(false)))
        }
        // 逐层重建路径上的对象，其余字段不动
        val newGroupHooks = JsonArray(groupHooks.toMutableList().also { it[hookIdx] = newHook })
        val newGroup = JsonObject(group + ("hooks" to newGroupHooks))
        val newEventArr = JsonArray(eventArr.toMutableList().also { it[groupIdx] = newGroup })
        val newConfig = JsonObject(config + ("hooks" to JsonObject(
            config["hooks"]!!.jsonObject + ("events" to JsonObject(
                events + (event to newEventArr),
            )),
        )))
        Files.writeString(configPath, jsonPretty(newConfig))
        status(workspace)
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
    fun resolveScriptPath(workspace: String, displayCommand: String): Path? {
        val tokens = tokenizeCommand(displayCommand)
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
