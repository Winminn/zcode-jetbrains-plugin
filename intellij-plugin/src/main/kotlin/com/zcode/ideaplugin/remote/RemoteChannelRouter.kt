package com.zcode.ideaplugin.remote

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.zcode.ideaplugin.protocol.relay.ChannelCodec
import com.zcode.ideaplugin.protocol.relay.RelayBridge
import com.zcode.ideaplugin.protocol.relay.Relay
import com.zcode.ideaplugin.protocol.relay.RelayClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 手机远程会话桥接路由（IDE 宿主侧）：
 * L4 控制面委托 [RelayBridge]（纯协议，workspace 聚合注入 IDE 打开的项目）；
 * L6 channel 分发按 M0 报告映射表接入 app-server 转发（M3 逐步实现），
 * 未知 channel/方法一律 "Method not found"（H5 有容错）。
 *
 * 线程：L4 在 relay WS 回调线程（纯 JSON 构造）；channel 调用由
 * [ZCodeRemoteService] 的执行器异步后转发 app-server。
 */
class RemoteChannelRouter {

    private val log = Logger.getInstance("ZCodePlugin")

    /** 回合运行中判定（ZCodeRemoteService 注入）：bootstrap 任务行 displayStatus 覆写用——
     *  session/list 快照滞后于回合事件，运行中的会话在官方客户端转圈而快照恒已完成 */
    @Volatile var isSessionRunning: ((String) -> Boolean)? = null

    val bridge = RelayBridge(workspacesProvider = ::ideWorkspaces, tasksProvider = ::ideTasks)

    /** @return true=bridge-open（调用方需在应答后推 channel Initialize）*/
    fun handlePayload(payload: JsonObject, sender: RelayClient.PayloadSender): Boolean =
        bridge.handlePayload(payload, sender)

    fun handleChannelRequest(
        bridgeSessionId: String,
        request: ChannelCodec.ChannelRequest,
        responder: RelayClient.ChannelResponder,
        channelHandler: (project: Project?, request: ChannelCodec.ChannelRequest, responder: RelayClient.ChannelResponder) -> Unit,
    ) {
        if (request.channel !in KNOWN_CHANNELS) {
            log.info("remote channel miss: ${request.channel}.${request.method} id=${request.id}")
            responder.error("Method not found: ${request.channel}.${request.method}")
            return
        }
        log.info("remote channel call: ${request.channel}.${request.method} id=${request.id}")
        val project = projectForBridge(bridgeSessionId)
        try {
            channelHandler(project, request, responder)
        } catch (e: Exception) {
            // 应答必须清偿：H5 对每个请求有超时，挂起会拖垮整条 bridge（实测 22:03
            // 装机 INTERNAL error 风暴即断连后在途请求清算）
            log.warn("remote channel handler failed: ${request.channel}.${request.method} id=${request.id}: ${e.message?.take(150)}")
            runCatching { responder.error("handler error: ${e.message?.take(120)}") }
        }
    }

    fun handleEventListen(bridgeSessionId: String, listenerId: Long, channel: String?, event: String?) {
        bridge.handleEventListen(bridgeSessionId, listenerId, channel, event)
        log.info("remote event listen: #$listenerId $channel.$event (subs=${bridge.subscriptionsFor(bridgeSessionId).size})")
    }

    fun handleEventDispose(listenerId: Long) = bridge.handleEventDispose(listenerId)

    /** bridge → 项目（workspaceKey = 项目 basePath） */
    private fun projectForBridge(bridgeSessionId: String): Project? {
        val key = bridge.workspaceKeyOf(bridgeSessionId) ?: return null
        return ProjectManager.getInstance().openProjects.firstOrNull {
            it.basePath?.let { p -> p.equals(key, ignoreCase = true) } == true
        }
    }

    private fun ideWorkspaces(): List<RelayBridge.RelayWorkspace> {
        val projects = ProjectManager.getInstance().openProjects
        val list = projects.mapNotNull { p ->
            p.basePath?.let { RelayBridge.RelayWorkspace(path = it, label = p.name) }
        }
        if (list.isEmpty()) {
            // IDE 无打开项目（理论少见）：占位防 H5 空列表异常
            return listOf(RelayBridge.RelayWorkspace(path = System.getProperty("user.dir"), label = "IDEA"))
        }
        return list
    }

    /** 首页任务行（bootstrap/workspace-list 用官方 HAR 定案扁平形状：archived/
     *  createdAt/displayStatus/provider/taskId/title/updatedAt/workspaceKind/
     *  workspaceLabel/workspacePath）。bootstrap 时 app-server 多半未启动返回空，
     *  H5 由 controller/tasks-index 订阅帧补齐（对齐官方多源合并） */
    private fun ideTasks(): List<JsonObject> {
        val items = ArrayList<JsonObject>()
        for (impl in com.zcode.ideaplugin.ZCodeServiceImpl.activeProjectServices()) {
            if (!impl.isStarted()) continue
            val c = runCatching { impl.getClient() }.getOrNull() ?: continue
            val ws = impl.ownerProject.basePath ?: continue
            // 归档/软删过滤（IDE 历史列表同口径）：session/list 不认 tasks-index 归档
            val hidden = runCatching { c.hiddenSessionIds() }.getOrDefault(emptySet())
            for (s in runCatching { c.listSessions(ws) }.getOrDefault(emptyList())) {
                if (s.sessionId in hidden) continue
                items.add(buildJsonObject {
                    put("archived", s.archivedAt != null)
                    put("createdAt", s.createdAt)
                    put("displayStatus", if (s.status == "running" || isSessionRunning?.invoke(s.sessionId) == true) "running" else "completed")
                    put("provider", "glm")
                    put("taskId", s.sessionId)
                    put("title", s.title.ifBlank { "session" })
                    put("updatedAt", s.updatedAt)
                    put("workspaceKind", "local")
                    put("workspaceLabel", impl.ownerProject.name)
                    // 正斜杠统一（app-server 返回反斜杠；H5 端工作区匹配为严格字符串比较）
                    put("workspacePath", (s.workspace?.workspacePath ?: ws).replace('\\', '/'))
                })
            }
        }
        return items
    }

    companion object {
        /** 白名单 = handler 已实现集（单一权威源，RemoteChannelHandlers.CHANNELS 派生，
         *  杜绝双源漂移）+ M0 方法面预留未实现集。预留项过白名单后走 handler else
         *  分支回 Method not found——与 miss 同响应，仅日志 call/miss 形态不同 */
        val KNOWN_CHANNELS = RemoteChannelHandlers.CHANNELS + setOf(
            "system", "broadcast", "plugins", "memory", "commands", "hooks", "file",
        )
    }
}
