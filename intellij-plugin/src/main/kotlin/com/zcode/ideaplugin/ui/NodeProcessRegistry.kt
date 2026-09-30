package com.zcode.ideaplugin.ui

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit

/**
 * 常驻 app-server 进程引用（进程管理面板数据源）：主进程与润色专用进程共用，
 * role 区分（main / enhance）。
 */
data class LiveAppServer(val pid: Long, val startedAtMillis: Long, val role: String)

/**
 * Node 进程管理（设置页「进程」tab 数据源）。结构参考 cc-gui 的 NodeProcessRegistry
 * （内存登记 + 全量扫描兜底、kill 前重建快照做归属守卫），但孤立判定比它保守一档：
 * 官方 ZCode 桌面客户端 spawn 的 app-server 与插件命令行**同形**（node zcode.cjs
 * app-server），按指纹直接判孤立会把官方客户端的进程标进去，一键清理即误杀。
 *
 * 三分类：
 *  - appServer：各项目常驻进程（主进程 + 润色进程），来自存活 client 的内存引用，零误报；
 *  - descendant：常驻进程的直接子进程（__zcode-plugin-host、无头浏览器、vite 等），
 *    只取一层——多层会把无头浏览器的 renderer/GPU 家族整树捞进来刷屏；
 *  - orphan：疑似孤立——指纹命中（命令行含 zcode + app-server）且不在已知集合，且
 *    父进程=本 IDE JVM（换代泄漏）或父进程已死（IDE 崩溃/强杀遗留）。官方桌面端/
 *    终端 CLI 的同形进程父进程存活且非本 JVM → 不判孤立；父 pid 不可解析同样不判
 *    （无法归属，宁漏勿误）。
 *
 * kill 守卫（对齐 cc-gui）：执行前重建快照，pid 不在集合内一律拒绝——webview 端
 * payload 不可信，防伪造请求杀任意进程。
 */
object NodeProcessRegistry {

    private val log = Logger.getInstance("ZCodePlugin")

    /** 进程条目：service 仅 appServer 类携带（kill 时路由到归属项目的定向关闭） */
    private class Entry(
        val pid: Long,
        val kind: String, // appServer | descendant | orphan
        val label: String,
        val service: com.zcode.ideaplugin.ZCodeServiceImpl? = null,
        val role: String? = null,
        val project: String? = null,
        val parentPid: Long? = null,
        val startedAt: Long? = null,
        val commandLine: String? = null,
        /** 进程真身短名（appServer 行补显 node.exe——label 是项目名，不补显会让人以为 node 进程不在了，真机实测反馈） */
        val process: String? = null,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("pid", pid)
            put("kind", kind)
            put("label", label)
            role?.let { put("role", it) }
            project?.let { put("project", it) }
            parentPid?.let { put("parentPid", it) }
            startedAt?.let { put("startedAt", it) }
            commandLine?.let { put("commandLine", it) }
            process?.let { put("process", it) }
        }
    }

    /** 全量快照（设置页打开/手动刷新时按需拉取，无后台轮询） */
    fun snapshotJson(): JsonObject {
        val entries = collectEntries()
        val totals = entries.groupingBy { it.kind }.eachCount()
        return buildJsonObject {
            put("op", "nodeProcesses")
            put("snapshotAt", System.currentTimeMillis())
            put("totals", buildJsonObject {
                put("appServer", totals["appServer"] ?: 0)
                put("descendant", totals["descendant"] ?: 0)
                put("orphan", totals["orphan"] ?: 0)
            })
            put("processes", JsonArray(entries.map { it.toJson() }))
        }
    }

    /**
     * 按 pid 结束进程（所有权守卫：先重建快照校验，不在集合内拒绝）。
     * appServer 走归属项目的定向关闭（连带插件侧簿记，下次 getClient 懒重建）；
     * descendant/orphan 按 pid 杀进程树。返回 (是否成功, 错误说明)。
     */
    fun killByPid(pid: Long): Pair<Boolean, String?> {
        val target = collectEntries().firstOrNull { it.pid == pid }
            ?: return false to "pid $pid not in latest snapshot"
        return when (target.kind) {
            "appServer" -> {
                val svc = target.service
                    ?: return false to "owning project service unavailable"
                svc.killAppServerByPid(pid) to null
            }
            else -> {
                val ok = killProcessTreeByPid(pid)
                if (ok) log.info("[process-mgr] killed ${target.kind} pid=$pid (${target.label})")
                ok to if (ok) null else "kill command failed for pid $pid"
            }
        }
    }

    private fun collectEntries(): List<Entry> {
        val currentJvmPid = ProcessHandle.current().pid()
        val entries = mutableListOf<Entry>()
        val knownPids = mutableSetOf<Long>()

        // 1) 常驻 app-server：跨项目聚合（多项目并开各一个，多标签共享同项目进程）
        val servers = mutableListOf<Triple<com.zcode.ideaplugin.ZCodeServiceImpl, LiveAppServer, ProcessHandle?>>()
        for (svc in com.zcode.ideaplugin.ZCodeServiceImpl.activeProjectServices()) {
            for (s in svc.liveAppServers()) {
                val handle = runCatching { ProcessHandle.of(s.pid).orElse(null) }.getOrNull()
                servers.add(Triple(svc, s, handle))
                knownPids.add(s.pid)
                entries.add(
                    Entry(
                        pid = s.pid, kind = "appServer", label = svc.ownerProject.name,
                        service = svc, role = s.role, project = svc.ownerProject.name,
                        startedAt = s.startedAtMillis,
                        process = handle?.let { shortCommandName(it) } ?: "node.exe",
                    )
                )
            }
        }
        // 2) 常驻进程的直接子进程（browser-use 宿主 / 无头浏览器 / vite 等，仅一层）。
        //    conhost.exe 是 Windows 控制台宿主（随 console 子进程自动出现），非功能子进程，滤掉；
        //    node 子进程按命令行角色改名（裸 "node.exe" 让人误读，真机实测反馈）
        for ((svc, server, handle) in servers) {
            val children = runCatching { handle?.children()?.toList() }.getOrNull().orEmpty()
            for (child in children) {
                val cpid = runCatching { child.pid() }.getOrNull() ?: continue
                if (!knownPids.add(cpid)) continue
                val shortName = shortCommandName(child)
                if (shortName.equals("conhost.exe", ignoreCase = true)) continue
                entries.add(
                    Entry(
                        pid = cpid, kind = "descendant", label = friendlyChildLabel(child, shortName),
                        service = svc, project = svc.ownerProject.name, parentPid = server.pid,
                        startedAt = startInstantMs(child),
                    )
                )
            }
        }
        // 3) 疑似孤立：全量扫描 + 指纹 + 归属三重过滤（见类注释）
        runCatching {
            for (handle in ProcessHandle.allProcesses()) {
                val pid = runCatching { handle.pid() }.getOrNull() ?: continue
                if (pid == currentJvmPid || pid in knownPids) continue
                val cmdline = runCatching { handle.info().commandLine().orElse(null) }.getOrNull()
                    ?: continue
                if (!isOurFingerprint(cmdline)) continue
                val parent = runCatching { handle.parent() }.getOrNull()
                val parentPid = parent?.map { it.pid() }?.orElse(-1L) ?: -1L
                val parentAlive = parent?.map { it.isAlive }?.orElse(false) ?: false
                // 父=本 JVM：本 IDE 泄漏（存活子进程未入账本）；父已死：IDE 崩溃遗留。
                // 父 pid 不可解析（≤0）：无法归属，保守不判
                val orphan = parentPid == currentJvmPid || (parentPid > 0 && !parentAlive)
                if (!orphan) continue
                knownPids.add(pid)
                entries.add(
                    Entry(
                        pid = pid, kind = "orphan", label = shortCommandName(handle),
                        parentPid = parentPid.takeIf { it > 0 },
                        startedAt = startInstantMs(handle), commandLine = cmdline,
                    )
                )
            }
        }.onFailure { log.warn("[process-mgr] orphan scan failed: ${it.message}") }
        return entries
    }

    /** 命令行指纹：zcode CLI 的 app-server 形态（node <…/zcode.cjs> app-server） */
    private fun isOurFingerprint(commandLine: String): Boolean {
        val lower = commandLine.lowercase()
        return "zcode" in lower && "app-server" in lower
    }

    /** 可执行文件短名（行内标签用；取不到回退 "node"） */
    private fun shortCommandName(handle: ProcessHandle): String {
        val cmd = runCatching { handle.info().command().orElse(null) }.getOrNull() ?: return "node"
        return cmd.replace('\\', '/').substringAfterLast('/').ifBlank { "node" }
    }

    /**
     * 子进程行内标签：裸可执行名信息量不足（plugin-host 也是 node.exe，真机实测被
     * 误读成"主进程不见了"），按命令行角色改可读名；识别不了保持原短名。
     */
    private fun friendlyChildLabel(handle: ProcessHandle, shortName: String): String {
        if (!shortName.equals("node.exe", ignoreCase = true) && !shortName.equals("node", ignoreCase = true)) {
            return shortName
        }
        val cmdline = runCatching { handle.info().commandLine().orElse(null) }.getOrNull() ?: return "node"
        return when {
            cmdline.contains("__zcode-plugin-host") -> "zcode-plugin-host"
            Regex("""[\\/]vite[\\/]""").containsMatchIn(cmdline) || cmdline.contains("vite.js") -> "vite"
            else -> "node"
        }
    }

    /** 进程启动时刻（epoch ms；平台取不到为 null，前端显示占位符） */
    private fun startInstantMs(handle: ProcessHandle): Long? =
        runCatching { handle.info().startInstant().orElse(null)?.toEpochMilli() }.getOrNull()

    /**
     * 按 pid 杀进程树（descendant/orphan 无 Process 句柄，不能走 client 的
     * destroyProcessTree）：Windows taskkill /T /F 连树强杀；Unix 先 TERM，宽限后
     * 仍存活升级 KILL。同步阻塞（pooled 线程调用），以最终存活状态为准。
     */
    private fun killProcessTreeByPid(pid: Long): Boolean {
        val isWin = System.getProperty("os.name").lowercase().contains("win")
        return try {
            if (isWin) {
                ProcessBuilder("taskkill", "/PID", pid.toString(), "/T", "/F")
                    .redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
            } else {
                ProcessBuilder("sh", "-c", "kill -TERM $pid")
                    .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
                ProcessHandle.of(pid).orElse(null)?.takeIf { it.isAlive }?.let {
                    ProcessBuilder("sh", "-c", "kill -9 $pid")
                        .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
                }
            }
            val alive = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            !alive
        } catch (e: Exception) {
            log.warn("[process-mgr] kill pid=$pid failed: ${e.message}")
            false
        }
    }
}
