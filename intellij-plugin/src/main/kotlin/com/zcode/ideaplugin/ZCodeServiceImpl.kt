package com.zcode.ideaplugin

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.zcode.ideaplugin.protocol.ZCodeProtocolClient
import com.zcode.ideaplugin.ui.ZCodeToolWindowPanel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * ZCode 项目级服务实现
 *
 * 管理协议客户端（全局一个 app-server 子进程）与多个标签面板（多标签页）。
 * 用 @Service 注解（现代方式），不在 plugin.xml 里声明。
 */
@Service(Service.Level.PROJECT)
class ZCodeServiceImpl(private val project: Project) : ZCodeService, com.intellij.openapi.Disposable {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance("ZCodePlugin")

    companion object {
        /** 活跃 Service 实例（多项目并开各一个）；宿主探针聚合判定用，dispose 移除 */
        private val activeInstances = java.util.concurrent.CopyOnWriteArrayList<ZCodeServiceImpl>()
    }

    init {
        activeInstances.add(this)
        // browser-use 宿主探针注入 EnvChecker（环境自检第四项，非阻断）。
        // 闭包不捕获实例、读活跃实例聚合（多项目并开时任一实例健康即健康，
        // 避免后开项目覆盖先开项目、dispose 后悬垂引用）：
        // - 全部实例的 app-server 未拉起 → null（未初始化，不评判）
        // - 有实例已拉起但 handler 未注册 → CODE_HANDLER_MISSING
        // - JCEF 已起但 CDP 调试端口不可达 → CODE_CEF_DOWN（宿主浏览器能力废）
        com.zcode.ideaplugin.env.ZCodeEnvChecker.setBrowserHostProbe {
            val live = activeInstances.filter { it.isStarted() }
            when {
                live.isEmpty() -> null
                live.any { !it.browserHandlerRegistered } -> com.zcode.ideaplugin.env.BrowserHostStatus(
                    false, "app-server 已启动但 browser-use 宿主 handler 未注册",
                    com.zcode.ideaplugin.env.BrowserHostStatus.CODE_HANDLER_MISSING,
                )
                com.intellij.ui.jcef.JBCefApp.isStarted() &&
                    !com.zcode.ideaplugin.ui.ZCodeBrowserExecutor.hasReachableCdpEndpoint() ->
                    com.zcode.ideaplugin.env.BrowserHostStatus(
                        false, "JCEF 已启动但 CDP 调试端口不可达",
                        com.zcode.ideaplugin.env.BrowserHostStatus.CODE_CEF_DOWN,
                    )
                else -> com.zcode.ideaplugin.env.BrowserHostStatus(true, null)
            }
        }
    }

    @Volatile
    private var client: ZCodeProtocolClient? = null

    private val lock = ReentrantLock()

    /** 所有已注册面板（多标签页，每个标签一个）*/
    private val panels = java.util.concurrent.CopyOnWriteArrayList<ZCodeToolWindowPanel>()

    /** 当前激活面板（标签切换时更新；外部推送与 askUser fallback 的目标）*/
    @Volatile
    private var activePanel: ZCodeToolWindowPanel? = null

    // ============ 全局共享内嵌浏览器（跨会话标签，协议单一 idea-iab）============
    @Volatile
    private var sharedBrowserPanel: com.zcode.ideaplugin.ui.ZCodeBrowserPanel? = null

    /** 浏览器当前挂载（分栏展开）的面板；收起时保留 owner（实例与页面常驻）*/
    @Volatile
    private var embeddedBrowserOwner: ZCodeToolWindowPanel? = null

    // ============ AskUserQuestion / ExitPlanMode 协调（跨标签共享）============
    // 协议客户端的 userInputRequestHandler 是单例，必须全局只注册一次。
    // serverRequestId → 等待项：前端用户选择后 complete future。
    // 服务端对未应答的 interaction/requestUserInput 会指数退避重发（新 id、同内容），
    // 重试 id 通过 contentKey 识别后共享同一 future：用户应答一次、所有 id 同时应答，
    // 不重复弹窗（保持用户已选状态）。
    private class PendingUserInput(
        val contentKey: String,
        val future: CompletableFuture<JsonObject>,
        val targetPanel: ZCodeToolWindowPanel?,
    )

    private val pendingUserInputs = ConcurrentHashMap<String, PendingUserInput>()

    @Volatile
    private var userInputHandlerRegistered = false

    override fun getClient(): ZCodeProtocolClient {
        client?.let { if (it.isAlive()) return it }
        return lock.withLock {
            client?.let { if (it.isAlive()) return it }
            // 环境三件套（node/zcode.cjs/凭证）由 EnvChecker 解析：配置路径优先 → 自动探测；
            // 失败抛 EnvCheckException（带 EnvStatus），Panel 层转成前端可识别的环境错误
            val env = com.zcode.ideaplugin.env.ZCodeEnvChecker.resolveForStart()
            val newClient = ZCodeProtocolClient.start(
                zcodePath = env.zcodePath,
                credentials = env.credentials,
                nodePath = env.nodePath,
            )
            // requestRuntimePreferences 应答：三项与 ZCode 客户端共用 ~/.zcode/v2/setting.json
            // （设置页「工作区记忆」开关写的也是这份）。每次应答即时读文件——
            // 切换开关后新建会话立即生效，无需重启 app-server；memoryEnabled=false 时
            // CLI 强制 memory:{enabled:false}，MEMORY.md 自动记忆不注入上下文
            newClient.runtimePreferencesResponder = { _, _ ->
                val p = com.zcode.ideaplugin.ui.ZCodeClientSettingStore.readRuntimePrefs()
                com.zcode.ideaplugin.protocol.model.RuntimePreferences(
                    nativeSearchEnhancementsEnabled = p.nativeSearchEnhancementsEnabled,
                    memoryEnabled = p.memoryEnabled,
                    askUserQuestionAutoResolutionEnabled = p.askUserQuestionAutoResolutionEnabled,
                )
            }
            client = newClient
            // 协议就绪即注册反向请求 handler（幂等）。注册点放在这里而非仅面板初始化：
            // 面板初始化时环境未就绪会抛 EnvCheckException 跳过注册，若不在此补注册，
            // 用户配好环境后 handler 永远缺席（Mac 首启 PATH 探测失败即触发过）
            registerProtocolHandlersLocked(newClient)
            newClient
        }
    }

    /**
     * 在刚启动的 client 上注册反向请求 handler（幂等，可在任何 getClient 成功后调用）。
     * 不调用 getClient（防重入），不抛异常（注册失败仅记日志，不影响协议链路）。
     */
    private fun registerProtocolHandlersLocked(c: ZCodeProtocolClient) {
        try {
            if (!userInputHandlerRegistered) {
                c.userInputRequestHandler = { serverRequestId, params ->
                    handleUserInputRequest(serverRequestId, params)
                }
                userInputHandlerRegistered = true
                log.info("[askUser] userInputRequestHandler 已在 Service 层注册（多标签共享）")
            }
            if (!browserHandlerRegistered) {
                val executor = com.zcode.ideaplugin.ui.ZCodeBrowserExecutor(project)
                browserExecutor = executor
                c.browserListHandler = { executor.listBrowsers() }
                c.browserExecuteHandler = { params -> executor.execute(params) }
                browserHandlerRegistered = true
                log.info("[browser-use] 宿主 handler 已注册（interaction/browserList + browserExecute）")
            }
        } catch (e: Exception) {
            log.warn("协议 handler 注册失败（下轮 getClient 重试）: ${e.message}")
        }
    }

    override fun isStarted(): Boolean = client?.isAlive() == true

    override fun shutdown() {
        lock.withLock {
            client?.close()
            client = null
        }
    }

    override fun registerPanel(panel: ZCodeToolWindowPanel) {
        if (panel !in panels) panels.add(panel)
        if (activePanel == null) activePanel = panel
    }

    override fun unregisterPanel(panel: ZCodeToolWindowPanel) {
        panels.remove(panel)
        if (activePanel === panel) {
            activePanel = panels.lastOrNull()
        }
    }

    override fun setActivePanel(panel: ZCodeToolWindowPanel) {
        activePanel = panel
    }

    override fun getActivePanel(): ZCodeToolWindowPanel? = activePanel

    override fun getSharedBrowserPanel(): com.zcode.ideaplugin.ui.ZCodeBrowserPanel? = sharedBrowserPanel

    override fun getOrCreateSharedBrowserPanel(): com.zcode.ideaplugin.ui.ZCodeBrowserPanel? {
        sharedBrowserPanel?.let { return it }
        return synchronized(this) {
            sharedBrowserPanel?.let { return it }
            // 收起按钮作用于「当前挂载 owner」——闭包读实时状态，跨标签迁移后仍然正确
            val panel = com.zcode.ideaplugin.ui.ZCodeBrowserPanel(project, onClose = {
                embeddedBrowserOwner?.hideEmbeddedBrowser()
            })
            sharedBrowserPanel = panel
            log.info("[browser-use] 全局共享浏览器面板已创建（跨会话标签）")
            panel
        }
    }

    override fun getEmbeddedBrowserOwner(): ZCodeToolWindowPanel? = embeddedBrowserOwner

    override fun setEmbeddedBrowserOwner(panel: ZCodeToolWindowPanel?) {
        embeddedBrowserOwner = panel
    }

    override fun dispose() {
        // 项目级服务销毁：从宿主探针聚合集中摘除（先于释放浏览器实例）
        activeInstances.remove(this)
        // 释放共享浏览器实例（所有标签共用这一个）
        sharedBrowserPanel?.let {
            try {
                com.intellij.openapi.util.Disposer.dispose(it)
            } catch (e: Exception) {
                log.warn("释放共享浏览器面板失败: ${e.message}")
            }
        }
        sharedBrowserPanel = null
        embeddedBrowserOwner = null
    }

    override fun findPanelForSession(sessionId: String): ZCodeToolWindowPanel? =
        panels.firstOrNull { it.isSubscribedTo(sessionId) }

    override fun pushToWebview(msg: JsonObject) {
        val p = activePanel ?: run {
            log.warn("pushToWebview 无激活面板，丢弃消息: ${msg["op"]}")
            return
        }
        p.pushToWebview(msg)
    }

    override fun ensureUserInputHandler() {
        // 注册统一在 getClient() 启动成功后执行（registerProtocolHandlersLocked），
        // 这里只需确保协议客户端已拉起
        getClient()
    }

    // ============ browser-use 宿主执行器（AI 浏览器工具 → JCEF 面板）============

    @Volatile
    private var browserExecutor: com.zcode.ideaplugin.ui.ZCodeBrowserExecutor? = null

    @Volatile
    private var browserHandlerRegistered = false

    override fun ensureBrowserExecutor() {
        // 注册统一在 getClient() 启动成功后执行（registerProtocolHandlersLocked）
        getClient()
    }

    override fun getBrowserExecutor(): com.zcode.ideaplugin.ui.ZCodeBrowserExecutor? = browserExecutor

    /**
     * 收到 interaction/requestUserInput：解析问题、推弹窗到目标面板、阻塞等用户应答。
     * 在协议客户端的独立线程执行，可安全阻塞。
     */
    private fun handleUserInputRequest(serverRequestId: String, params: JsonObject): JsonObject {
        log.info("[askUser] 收到 interaction/requestUserInput: $serverRequestId")
        log.info("[askUser] params: ${params.toString().take(600)}")

        val toolName = params["toolName"]?.jsonPrimitive?.content ?: "AskUserQuestion"
        // ExitPlanMode 识别：toolName 为主，interaction:"plan_approval" 兜底
        val isPlanApproval = toolName == "ExitPlanMode" ||
            params["interaction"]?.jsonPrimitive?.contentOrNull == "plan_approval"
        // 内容指纹：toolName + 问题/计划文本，用于识别服务端重试（同内容、新 id）
        val contentKey = "$toolName|${params["input"]?.toString() ?: params["questions"]?.toString() ?: ""}"

        // 弹窗目标：优先按 sessionId 精确路由（该字段是否存在取决于服务端实现），
        // 否则 fallback 到当前激活标签
        val sessionId = params["sessionId"]?.jsonPrimitive?.contentOrNull
        val targetPanel = (sessionId?.let { findPanelForSession(it) }) ?: activePanel
        if (targetPanel == null) {
            log.warn("[askUser] 无可用面板，直接 decline: $serverRequestId")
            return buildJsonObject { put("action", "decline") }
        }

        val existing = pendingUserInputs.values.firstOrNull { it.contentKey == contentKey }
        val future: CompletableFuture<JsonObject>
        if (existing != null) {
            // 服务端重试同一请求：共享旧 future（各 handler 线程向自己的 id 应答），
            // 不重复弹窗——重复推送会重建弹窗、重置用户已选状态
            pendingUserInputs[serverRequestId] =
                PendingUserInput(contentKey, existing.future, existing.targetPanel)
            future = existing.future
            log.info("[askUser] 服务端重试同一请求，共享等待: $serverRequestId")
        } else {
            future = CompletableFuture()
            pendingUserInputs[serverRequestId] = PendingUserInput(contentKey, future, targetPanel)

            // ExitPlanMode 走专门的计划审批通道：params = {toolName:"ExitPlanMode", input:{plan:"..."}}
            // 它没有 questions 数组，而是 input.plan 直接是计划 markdown 文本。
            if (isPlanApproval) {
                val input = params["input"]?.let { it as? JsonObject }
                val plan = input?.get("plan")?.jsonPrimitive?.content ?: ""
                val askMsg = buildJsonObject {
                    put("op", "exitPlanApproval")
                    put("requestId", serverRequestId)
                    put("plan", plan)
                }
                targetPanel.pushToWebview(askMsg)
                log.info("[askUser] ExitPlanMode 计划审批已推给前端，等待用户批准/拒绝...")
            } else {
                // 普通 AskUserQuestion：{op:"askUser", requestId, questions, toolName}
                val questions = params["questions"] ?: kotlinx.serialization.json.JsonArray(emptyList())
                val askMsg = buildJsonObject {
                    put("op", "askUser")
                    put("requestId", serverRequestId)
                    put("toolName", toolName)
                    put("questions", questions)
                }
                targetPanel.pushToWebview(askMsg)
                log.info("[askUser] 已推给前端，等待用户选择...")
            }
        }

        // 阻塞等用户选择（在协议客户端的独立线程，不阻塞 reader/EDT）。
        // 超时必须立即 decline 并关闭弹窗：悬空的等待线程 5 分钟后向服务端补发
        // 迟到的 decline，会被当作"用户拒绝了计划"（引发重复 ExitPlanMode）。
        return try {
            future.get(5, java.util.concurrent.TimeUnit.MINUTES)
        } catch (e: java.util.concurrent.TimeoutException) {
            log.warn("[askUser] 等待用户应答超时（5 分钟），自动 decline: $serverRequestId")
            pendingUserInputs.entries.removeIf { it.value.future === future }
            targetPanel.pushToWebview(buildJsonObject { put("op", "askUserAck") })
            buildJsonObject { put("action", "decline") }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            pendingUserInputs.entries.removeIf { it.value.future === future }
            buildJsonObject { put("action", "decline") }
        }
    }

    override fun completeUserInput(
        requestId: String,
        action: String,
        answer: JsonElement?,
        answers: JsonObject?,
    ): JsonObject {
        val pending = pendingUserInputs.remove(requestId)
            ?: return buildJsonObject {
                put("op", "error")
                put("message", "未找到待处理的用户输入请求: $requestId")
            }
        val future = pending.future

        // 构建应答 result（格式：interaction/requestUserInput 的 result）
        // ExitPlanMode 审批的 answer 语义（zcode.cjs 常量，严格相等比较）：
        // - 小写 "approve" = 批准退出计划模式
        // - 有值但 ≠ "approve" = 反馈式拒绝：AI 留在计划模式按意见文本继续修改
        //   （审批弹窗「继续规划」按钮的通道）
        // - 空 = 兜底按批准处理（保持旧行为，防 undefined 误判）
        val isPlanApproval = pending.contentKey.startsWith("ExitPlanMode|")
        val normalizedAnswer = if (isPlanApproval && action != "decline" && action != "cancel"
            && (answer == null || (answer is JsonPrimitive && answer.contentOrNull.isNullOrBlank()))
        ) {
            JsonPrimitive("approve")
        } else {
            answer // "approve"、意见文本、optionId 或自由文本，原样透传
        }

        val result = if (action == "decline" || action == "cancel") {
            buildJsonObject { put("action", "decline") }
        } else {
            // accept + content：AskUserQuestion 答案（zcode.cjs normalizeAskUserQuestionAnswers）
            // - 多问题：content.answers = {问题文本: 值}（按问题文本回填，丢 key 即答案全失）
            // - 单问题：content.answer = 原始值（字符串 trim；数组服务端 join(", ") 后回填）。
            //   旧版把答案整体 JSON.stringify 成字符串塞 answer——多问题场景服务端匹配不到
            //   任何 key，answers 丢失（AI 认为用户没选）；数组也被当作字面量字符串
            buildJsonObject {
                put("action", "accept")
                put("content", buildJsonObject {
                    if (answers != null && !answers.isEmpty()) {
                        put("answers", answers)
                    } else {
                        put("answer", normalizedAnswer ?: JsonPrimitive(""))
                    }
                })
            }
        }

        future.complete(result)
        // 服务端重试的其他 id 共享此 future，一并清理
        pendingUserInputs.entries.removeIf { it.value.future === future }
        log.info("[askUser] 用户已选择，应答服务器: action=$action answer=$normalizedAnswer")
        return buildJsonObject { put("op", "askUserAck") }
    }
}

/** 便捷扩展：project.zCodeService() */
fun Project.zCodeService(): ZCodeService = service<ZCodeServiceImpl>()
