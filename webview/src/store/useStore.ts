/**
 * 全局状态（Zustand）
 *
 * 阶段 2.1-2.4 状态：
 *   - connectionStatus / projectPath
 *   - sessions / currentSessionId / messages
 *   - 流式：streaming（turn 是否进行中）、streamingMessageId、waitingSince
 *
 * 流式生命周期：
 *   sendMessage → subscribe（确保不丢事件）→ send → turn.started
 *   → model.streaming（累加 delta）→ tool.updated（更新状态）
 *   → turn.completed/failed → 重新拉 messages（确保数据一致）
 */

import { create } from 'zustand'
import { onMessage, onStreamEvent, onStreamBatch, sendToJava, initBridge, isInJcef, getWorkspacePath, getInitialSessionId } from '@/ipc/bridge'
import type { JavaResponse, SessionInfo, ZCodeMessage, StreamEvent, ModelOption, TodoItem, AgentItem, FileChangeItem, QuotaData, ModelUsageData, ToolUsageData, UsageRange, ContextBreakdownItem, ThoughtLevelInfo, SubagentActivity, SubagentInfo, ToolUpdatedPayload } from '@/types/messages'
import { applyStreamEvent, isSubagentToolEvent, applySubagentToolEvent, markActivityOutcome, asSubagentLifecycle } from '@/utils/streamReducer'
import type { SubagentLifecyclePayload } from '@/utils/streamReducer'
import { parseTodos, parseAgents, parseFileChanges, mergeAgentItems } from '@/utils/parseStatus'

export type ConnectionStatus = 'connecting' | 'connected' | 'mock' | 'error'

/** 排队消息（对话进行中 Enter 入队，回合结束自动发送；text 为拼好技能/文件引用的最终文本）*/
export interface QueuedMessage {
  id: string
  text: string
  queuedAt: number
}

interface StoreState {
  // 连接
  connectionStatus: ConnectionStatus
  lastError: string | null
  projectPath: string

  // 会话
  sessions: SessionInfo[]
  currentSessionId: string | null
  currentWorkspacePath: string

  // 消息
  messages: ZCodeMessage[]
  loadingMessages: boolean

  // 状态面板（对齐 cc-gui StatusPanel，从 messages 解析）
  todos: TodoItem[]
  agents: AgentItem[]
  fileChanges: FileChangeItem[]

  // 子代理（流式实时聚合 + session/subagents RPC 权威列表 + 详情弹窗）
  /** 流式期间从 tool.updated(source=subagent) 实时聚合的活动（键 = Agent 工具 callID）*/
  subagentActivities: SubagentActivity[]
  /** session/subagents RPC 权威列表（running + ended）*/
  subagents: SubagentInfo[]
  /** 打开详情弹窗的子代理聚合键（= 父会话 Agent 工具 callID）*/
  subagentDetail: string | null
  /** 子会话完整消息缓存（childSessionId → messages，详情弹窗"原始过程"）*/
  childMessages: Record<string, ZCodeMessage[]>
  childMessagesLoading: boolean
  childMessagesError: string | null
  /** 已注册子会话（childSessionId → 聚合键）：spawned 通知/转发事件/RPC 三处注册，*/
  /** 注册后其原生事件流被实时归约（不再被 currentSessionId 过滤丢弃）*/
  childSessionKeys: Record<string, string>
  /** 子会话实时归约消息（childSessionId → messages，运行中详情弹窗完整对话源）*/
  childLiveMessages: Record<string, ZCodeMessage[]>
  /** 子会话各自的流式消息 id（applyStreamEvent 的 streamingMessageId）*/
  childStreamingIds: Record<string, string | null>

  // 流式状态
  /** 当前 turn 是否进行中（发送后→turn.completed 前）*/
  streaming: boolean
  /** 流式中 assistant 消息的 id（turn.started 创建）*/
  streamingMessageId: string | null
  /** 开始等待的时间戳（WaitingIndicator 计时用）*/
  waitingSince: number | null
  /** 排队消息（streaming 中 Enter 入队，回合结束自动发队头）*/
  queuedMessages: QueuedMessage[]

  // 模型切换（config.json provider 注册表）
  models: ModelOption[]
  /** 当前会话选择的模型（localStorage 记忆）*/
  currentModel: { modelId: string; providerId: string } | null
  /** 已为该会话下发过 setModel（避免每次 messages 刷新重复下发）*/
  modelAppliedForSession: string | null

  // 运行时设置（session/read → settings：思考级别 + 权限模式）
  /** 思考级别（available 因模型而异，服务端权威）*/
  thoughtLevel: ThoughtLevelInfo | null
  /** 当前权限模式（build/edit/plan/yolo）*/
  currentMode: string | null
  /** 进入 plan 前的模式（缺陷E：ExitPlanMode 批准后即时恢复用，权威值由 state.updated/loadSettings 校正）*/
  prePlanMode: string | null
  /** 已为该会话下发过 setThoughtLevel（applyThoughtLevelIfReady 防重入）*/
  thoughtLevelAppliedForSession: string | null

  // 上下文用量（session/read → runtime.contextUsage）
  contextUsage: { used: number; size: number; hitRate: number } | null
  /** 上下文构成明细（session/read → runtime.breakdown）*/
  contextBreakdown: ContextBreakdownItem[] | null

  // 额度（glm plan usage API → 设置视图 + 圆环 popover 用）
  quota: QuotaData | null
  quotaLoading: boolean
  /** quota 上次成功拉取时间戳（圆环 popover 缓存 TTL 用）*/
  quotaFetchedAt: number

  // 用量明细曲线（model-usage / tool-usage）
  modelUsage: ModelUsageData | null
  toolUsage: ToolUsageData | null
  usageRange: UsageRange
  customStart: string | null
  customEnd: string | null
  /** 用量查询局部错误（凭证/HTTP 失败，不污染全局 lastError）*/
  usageError: string | null

  // AskUserQuestion 弹窗
  askUser: { requestId: string; toolName: string; questions: import('@/types/messages').AskUserQuestion[] } | null

  // ExitPlanMode 计划审批弹窗（服务端 interaction/requestUserInput，params = {input:{plan}}）
  exitPlanApproval: { requestId: string; plan: string } | null

  // actions
  init: () => void
  loadSessions: () => void
  selectSession: (session: SessionInfo) => void
  sendMessage: (text: string) => void
  createSession: () => void
  deleteSession: (sessionId: string) => void
  stopStreaming: () => void
  /** 重命名会话（CLI 协议无 rename op，仅前端 localStorage 持久化）*/
  renameSession: (sessionId: string, title: string) => void
  /** 拉取可切换的模型列表（config.json）*/
  loadModels: () => void
  /** 切换当前会话模型（session/setModel）*/
  setModel: (modelId: string, providerId: string) => void
  /** 把 localStorage 记忆的模型下发给指定会话（models 列表已就绪时才生效）*/
  applyModelIfReady: (sessionId: string) => void
  /** 拉取当前会话的运行时设置（mode + thoughtLevel）*/
  loadSettings: () => void
  /** 切换思考级别（session/setThoughtLevel，localStorage 记忆）*/
  setThoughtLevel: (level: string) => void
  /** 切换权限模式（session/setMode，不记忆——模式是即时意图，避免 plan 粘性）*/
  setMode: (mode: string) => void
  /** 把 localStorage 记忆的思考级别下发给指定会话（available 就绪且值仍有效时才生效）*/
  applyThoughtLevelIfReady: (sessionId: string) => void
  /** 拉取当前会话的上下文用量 */
  loadUsage: () => void
  /** 拉取额度（设置视图 + 圆环 popover 用）*/
  loadQuota: () => void
  /** 设置用量明细时间范围并重拉 model/tool 曲线 */
  setUsageRange: (range: UsageRange) => void
  /** 设置自定义日期范围并重拉 */
  setUsageDates: (start: string, end: string) => void
  /** 按当前 usageRange 拉取 model-usage + tool-usage */
  loadUsageData: () => void
  /** 清除错误（错误栏关闭按钮）*/
  clearError: () => void
  /** 拉取当前会话的子代理列表（session/subagents RPC，权威状态）*/
  loadSubagents: () => void
  /** 打开子代理详情弹窗（key = Agent 工具 callID）*/
  openSubagentDetail: (key: string) => void
  /** 关闭子代理详情弹窗 */
  closeSubagentDetail: () => void
  /** 拉取子会话完整消息（详情弹窗"原始过程"；仅对已结束子代理自动调用）*/
  loadChildMessages: (childSessionId: string) => void
  /** 手动停止运行中的子代理（session/stop 子会话，中止其当前 turn）*/
  stopSubagent: (childSessionId: string, agentId?: string) => void
  /** 删除一条排队消息 */
  removeQueuedMessage: (id: string) => void
  /** 立即发送排队消息：移到队头 + 中断当前回合（turn 结束事件到达后自动发出）*/
  sendQueuedNow: (id: string) => void
  /** 回合结束（streaming→false）后自动发送队头 */
  flushQueue: () => void
}

let bridgeInitialized = false
/** 自动新建会话防重入标志（listSessions 多次触发时不重复创建）*/
let autoCreateInFlight = false

export const useStore = create<StoreState>((set, get) => ({
  connectionStatus: 'connecting',
  lastError: null,
  projectPath: '',

  sessions: [],
  currentSessionId: null,
  currentWorkspacePath: '',

  messages: [],
  loadingMessages: false,

  todos: [],
  agents: [],
  fileChanges: [],

  subagentActivities: [],
  subagents: [],
  subagentDetail: null,
  childMessages: {},
  childMessagesLoading: false,
  childMessagesError: null,
  childSessionKeys: {},
  childLiveMessages: {},
  childStreamingIds: {},

  streaming: false,
  streamingMessageId: null,
  waitingSince: null,
  queuedMessages: [],
  askUser: null,
  exitPlanApproval: null,

  models: [],
  currentModel: null,
  modelAppliedForSession: null,
  thoughtLevel: null,
  currentMode: null,
  prePlanMode: null,
  thoughtLevelAppliedForSession: null,
  contextUsage: null,
  contextBreakdown: null,
  quota: null,
  quotaLoading: false,
  quotaFetchedAt: 0,
  modelUsage: null,
  toolUsage: null,
  usageRange: '7d',
  customStart: null,
  customEnd: null,
  usageError: null,

  init: () => {
    if (bridgeInitialized) return
    bridgeInitialized = true

    initBridge()
    onMessage((msg: JavaResponse) => handleResponse(msg, set, get))
    // 批量流式事件（Java 端 16ms 节流合并）：一次处理整批，只 set 一次
    onStreamBatch((sid: string, events: StreamEvent[]) => handleStreamBatch(sid, events, set, get))
    // 单事件兜底（mock 模式 + Java 端关键事件走 streamEvent 单推）
    onStreamEvent((sid: string, event: StreamEvent) => handleStreamEvent(sid, event, set, get))

    const inJcef = isInJcef()
    const ws = getWorkspacePath()
    set({ connectionStatus: inJcef ? 'connected' : 'mock', projectPath: ws })
    console.log(`[store] 初始化完成，连接=${inJcef ? 'JCEF' : 'mock'}，workspace=${ws || '(空)'}`)

    get().loadSessions()
    get().loadModels()
  },

  loadSessions: () => {
    sendToJava({ op: 'listSessions', workspacePath: get().projectPath })
  },

  selectSession: (session) => {
    const workspacePath = session.workspacePath || get().projectPath
    set({
      currentSessionId: session.sessionId,
      currentWorkspacePath: workspacePath,
      messages: [],
      loadingMessages: true,
      streaming: false,
      streamingMessageId: null,
      waitingSince: null,
      queuedMessages: [], // 队列绑定会话上下文，切会话丢弃
      contextUsage: null, // 清空旧会话数据，等 getUsage 回来更新
      contextBreakdown: null,
      thoughtLevel: null, // 清空旧会话设置，等 getSettings 回来更新（currentMode 由 messages 推断兜底）
      todos: [], // 派生状态同步清零，消除 messages 响应回来前的底部栏串扰空窗
      agents: [],
      fileChanges: [],
      subagentActivities: [], // 子代理数据绑定会话，切会话清空重拉
      subagents: [],
      subagentDetail: null,
      childMessages: {},
      childMessagesError: null,
      childSessionKeys: {}, // 子会话注册与实时归约数据同样绑定会话
      childLiveMessages: {},
      childStreamingIds: {},
    })
    // 切换会话时订阅事件流（带 workspacePath，Java 端 subscribe 前要先 resume 激活会话）
    sendToJava({ op: 'subscribe', sessionId: session.sessionId, workspacePath })
    sendToJava({ op: 'messages', sessionId: session.sessionId, workspacePath })
    // 拉取该会话的子代理列表（历史会话也能在底部栏查看已完成子代理）
    get().loadSubagents()
    // 切会话后拉取上下文用量（圆环显示）
    get().loadUsage()
    // 拉取运行时设置（mode + 思考级别，级别列表随模型变化）
    get().loadSettings()
    // 会话切换后，把 localStorage 记忆的模型真正下发 setModel（见 models 响应里的 applyModelIfReady）
    get().applyModelIfReady(session.sessionId)
  },

  sendMessage: (text) => {
    const sid = get().currentSessionId
    if (!sid || !text.trim()) return
    // 对话进行中：不丢弃，入队等待（回合结束自动发队头，对齐 cc-gui useMessageQueue）
    if (get().streaming) {
      set((s) => ({
        queuedMessages: [
          ...s.queuedMessages,
          {
            id: `queue_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
            text,
            queuedAt: Date.now(),
          },
        ],
      }))
      return
    }

    set({
      streaming: true,
      streamingMessageId: null,
      waitingSince: Date.now(),
      lastError: null,
    })

    // 确保已订阅（规格书 §4：先 subscribe 再 send，否则丢事件）
    sendToJava({ op: 'subscribe', sessionId: sid, workspacePath: get().currentWorkspacePath })
    // 发送
    sendToJava({
      op: 'send',
      sessionId: sid,
      text,
      workspacePath: get().currentWorkspacePath,
    })

    // 本地把用户消息立即加入列表（不等 reload，体验更快）
    const userMsg: ZCodeMessage = {
      info: {
        role: 'user',
        time: { created: Date.now() },
        id: `local_u_${Date.now()}`,
        sessionID: sid,
      },
      parts: [{ type: 'text', text }],
    }
    set((s) => ({ messages: [...s.messages, userMsg] }))
  },

  createSession: () => {
    sendToJava({ op: 'createSession', workspacePath: get().projectPath })
  },

  deleteSession: (sessionId) => {
    sendToJava({ op: 'deleteSession', sessionId })
  },

  stopStreaming: () => {
    const sid = get().currentSessionId
    if (!sid) return
    sendToJava({ op: 'stop', sessionId: sid })
  },

  removeQueuedMessage: (id) => {
    set((s) => ({ queuedMessages: s.queuedMessages.filter((m) => m.id !== id) }))
  },

  sendQueuedNow: (id) => {
    const q = get().queuedMessages
    const target = q.find((m) => m.id === id)
    if (!target) return
    if (get().streaming) {
      // 移到队头 + 中断当前回合；turn 结束事件到达后 flushQueue 自动发送它
      // （send 在 stop 之后立即发出，早于回合结束重拉的 messages 请求，重拉会包含该消息）
      set({ queuedMessages: [target, ...q.filter((m) => m.id !== id)] })
      get().stopStreaming()
    } else {
      set({ queuedMessages: q.filter((m) => m.id !== id) })
      get().sendMessage(target.text)
    }
  },

  flushQueue: () => {
    if (get().streaming || get().queuedMessages.length === 0) return
    const [next, ...rest] = get().queuedMessages
    set({ queuedMessages: rest })
    get().sendMessage(next.text)
  },

  renameSession: (sessionId, title) => {
    // 持久化到 localStorage（listSessions 响应时合并回来）
    try {
      localStorage.setItem(`zcode.sessionTitle.${sessionId}`, title)
    } catch { /* localStorage 不可用时仅内存生效 */ }
    set((s) => ({
      sessions: s.sessions.map((x) => (x.sessionId === sessionId ? { ...x, title } : x)),
    }))
  },

  loadModels: () => {
    sendToJava({ op: 'listModels' })
  },

  setModel: (modelId, providerId) => {
    const sid = get().currentSessionId
    if (!sid) return
    // 记忆当前选择（localStorage），切换会话后仍显示
    try {
      localStorage.setItem('zcode.currentModel', JSON.stringify({ modelId, providerId }))
    } catch { /* ignore */ }
    set({ currentModel: { modelId, providerId } })
    sendToJava({ op: 'setModel', sessionId: sid, modelId, providerId })
  },

  applyModelIfReady: (sessionId) => {
    // 同一会话只下发一次（避免 messages 刷新重复触发）
    if (get().modelAppliedForSession === sessionId) return
    let saved: { modelId: string; providerId: string } | null = null
    try {
      const raw = localStorage.getItem('zcode.currentModel')
      if (raw) saved = JSON.parse(raw)
    } catch { /* ignore */ }
    if (!saved) return
    // 等待 models 列表就绪，且记忆的模型仍在列表里（避免下发无效模型）
    const models = get().models
    if (models.length === 0) return
    const exists = models.some((m) => m.modelId === saved!.modelId && m.providerId === saved!.providerId)
    if (!exists) return
    set({ currentModel: saved, modelAppliedForSession: sessionId })
    sendToJava({ op: 'setModel', sessionId, modelId: saved.modelId, providerId: saved.providerId })
  },

  loadSettings: () => {
    const sid = get().currentSessionId
    if (!sid) return
    sendToJava({ op: 'getSettings', sessionId: sid })
  },

  setThoughtLevel: (level) => {
    const sid = get().currentSessionId
    if (!sid) return
    // 记忆选择（localStorage），新会话/切模型后仍尝试恢复
    try {
      localStorage.setItem('zcode.thoughtLevel', level)
    } catch { /* ignore */ }
    // 乐观更新 current（thoughtLevelSet 响应 / settings 重拉时服务端校准）
    const info = get().thoughtLevel
    if (info) set({ thoughtLevel: { ...info, current: level } })
    set({ thoughtLevelAppliedForSession: sid })
    sendToJava({ op: 'setThoughtLevel', sessionId: sid, thoughtLevel: level })
  },

  setMode: (mode) => {
    const sid = get().currentSessionId
    if (!sid) return
    // 不做 localStorage 记忆：模式是"现在想怎么干活"的即时选择（新会话默认 yolo，避免 plan 粘性）
    // 手动切到 plan 记住前一模式（缺陷E：ExitPlanMode 批准后即时恢复）；切离 plan 清除
    const prev = get().currentMode
    set({
      currentMode: mode,
      prePlanMode: mode === 'plan'
        ? (prev && prev !== 'plan' ? prev : get().prePlanMode)
        : null,
    })
    sendToJava({ op: 'setMode', sessionId: sid, mode })
  },

  applyThoughtLevelIfReady: (sessionId) => {
    // 同一会话只下发一次（settings 可能在切会话/切模型后多次到达）
    if (get().thoughtLevelAppliedForSession === sessionId) return
    let saved: string | null = null
    try {
      saved = localStorage.getItem('zcode.thoughtLevel')
    } catch { /* ignore */ }
    if (!saved) return
    // 等级别列表就绪，且记忆值仍有效（切模型后级别集会变，如 off/high/max ↔ enabled/off）
    const info = get().thoughtLevel
    if (!info || info.available.length === 0) return
    if (!info.available.some((a) => a.value === saved)) return
    set({ thoughtLevelAppliedForSession: sessionId })
    // 与当前一致则只标记不下发（服务端已生效）
    if (info.current === saved) return
    sendToJava({ op: 'setThoughtLevel', sessionId, thoughtLevel: saved })
  },

  loadUsage: () => {
    const sid = get().currentSessionId
    if (!sid) return
    sendToJava({ op: 'getUsage', sessionId: sid })
  },

  loadQuota: () => {
    set({ quotaLoading: true })
    sendToJava({ op: 'getQuota' })
  },

  setUsageRange: (range) => {
    set({ usageRange: range })
    get().loadUsageData()
  },

  setUsageDates: (start, end) => {
    set({ usageRange: 'custom', customStart: start, customEnd: end })
    get().loadUsageData()
  },

  loadUsageData: () => {
    const { usageRange, customStart, customEnd } = get()
    const { start, end } = rangeToTimes(usageRange, customStart, customEnd)
    set({ modelUsage: null, toolUsage: null, usageError: null })
    sendToJava({ op: 'getModelUsage', startTime: start, endTime: end })
    sendToJava({ op: 'getToolUsage', startTime: start, endTime: end })
  },

  clearError: () => set({ lastError: null }),

  loadSubagents: () => {
    const sid = get().currentSessionId
    if (!sid) return
    sendToJava({ op: 'subagents', sessionId: sid })
  },

  openSubagentDetail: (key) => {
    set({ subagentDetail: key })
    // 已结束且有 childSessionId 且未缓存 → 自动拉完整过程
    // （运行中不拉：对运行中的子会话做 resume 行为未验证，运行期用实时聚合数据）
    const st = get()
    const item = st.agents.find((a) => a.callID === key)
    const info = st.subagents.find((s) => s.toolCallId === key)
    const csid = item?.childSessionId ?? info?.childSessionId
    const running = st.subagentActivities.find((a) => a.key === key)?.status === 'running'
      || info?.status === 'running'
    if (csid && !st.childMessages[csid] && !running) {
      get().loadChildMessages(csid)
    }
  },

  closeSubagentDetail: () => set({ subagentDetail: null, childMessagesError: null }),

  loadChildMessages: (childSessionId) => {
    set({ childMessagesLoading: true, childMessagesError: null })
    sendToJava({
      op: 'subagentMessages',
      sessionId: childSessionId,
      workspacePath: get().currentWorkspacePath,
    })
  },

  stopSubagent: (childSessionId, agentId) => {
    const sid = get().currentSessionId
    if (!sid) return
    sendToJava({ op: 'stopSubagent', childSessionId, parentSessionId: sid, ...(agentId ? { agentId } : {}) })
  },
}))

/** 用量查询时间窗计算：start=当天 00:00:00，end=当天 23:59:59 */
function pad2(n: number): string {
  return n < 10 ? '0' + n : String(n)
}
function dateStr(d: Date): string {
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
}
function rangeToTimes(
  range: UsageRange,
  customStart?: string | null,
  customEnd?: string | null,
): { start: string; end: string } {
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const end = dateStr(today) + ' 23:59:59'
  if (range === 'custom' && customStart && customEnd) {
    return { start: customStart + ' 00:00:00', end: customEnd + ' 23:59:59' }
  }
  const days = range === 'today' ? 0 : range === '7d' ? 7 : 30
  const startDay = new Date(today)
  startDay.setDate(startDay.getDate() - days)
  return { start: dateStr(startDay) + ' 00:00:00', end }
}

// ============ 普通响应处理 ============

/**
 * 从消息流推断当前会话使用的模型（currentModel 为 null 时用）。
 * 兼容历史会话 / CLI 默认模型场景：取最后一条带 modelID 的消息。
 * assistant 用扁平 modelID/providerID；user 用嵌套 model.{modelID,providerID}。
 * providerID 缺失时从 models 列表反查（需 models 已加载）。
 */
function inferCurrentModel(messages: ZCodeMessage[], models: ModelOption[]): { modelId: string; providerId: string } | null {
  for (let i = messages.length - 1; i >= 0; i--) {
    const info = messages[i].info
    const modelId = info.modelID ?? info.model?.modelID
    if (modelId) {
      const providerId = info.providerID ?? info.model?.providerID ?? models.find((m) => m.modelId === modelId)?.providerId
      return providerId ? { modelId, providerId } : null
    }
  }
  return null
}

/** 从消息流推断当前权限模式（currentMode 为 null 时用，settings 拉取前的兜底显示）*/
function inferCurrentMode(messages: ZCodeMessage[]): string | null {
  for (let i = messages.length - 1; i >= 0; i--) {
    const mode = messages[i].info.mode
    if (mode) return mode
  }
  return null
}

/**
 * 从 messages 重新解析状态面板数据（todos/agents/fileChanges），返回 store patch。
 * agents 三源合并：parseAgents（兜底）+ 实时聚合活动 + session/subagents RPC（权威）。
 */
function refreshStatus(
  messages: ZCodeMessage[],
  activities: SubagentActivity[] = [],
  rpc: SubagentInfo[] = [],
): Partial<StoreState> {
  return {
    todos: parseTodos(messages),
    agents: mergeAgentItems(parseAgents(messages), activities, rpc),
    fileChanges: parseFileChanges(messages),
  }
}

function handleResponse(
  msg: JavaResponse,
  set: (partial: Partial<StoreState>) => void,
  get: () => StoreState,
) {
  switch (msg.op) {
    case 'listSessions': {
      // 合并 localStorage 里的自定义标题（renameSession 持久化）
      const merged = msg.sessions.map((s) => {
        try {
          const stored = localStorage.getItem(`zcode.sessionTitle.${s.sessionId}`)
          return stored ? { ...s, title: stored } : s
        } catch {
          return s
        }
      })
      set({ sessions: merged })

      // 会话自动恢复（仅多标签体系）：
      //   - 有标签注入的初始会话（重启恢复）且会话仍存在 → 选中它
      //   - 注入的会话已被删 / 新标签（无注入）→ 自动新建会话
      // 注意：不走 localStorage（多标签 webview 同 origin 共享存储，lastSessionId
      // 会互相覆盖导致新标签串到别的标签的会话）；恢复职责由 Java 侧 TabState 承担
      if (get().currentSessionId === null) {
        const initialId = getInitialSessionId()
        if (initialId) {
          const initial = merged.find((s) => s.sessionId === initialId)
          if (initial) {
            console.log(`[store] 恢复标签绑定的会话: ${initialId}`)
            get().selectSession(initial)
          } else if (!autoCreateInFlight) {
            // 绑定的会话已被删除 → 新建会话补位（TabState 的 sessionId 由后续 subscribe 更新）
            console.log('[store] 标签绑定的会话已不存在，自动新建')
            autoCreateInFlight = true
            get().createSession()
          }
        } else if (!autoCreateInFlight) {
          // 新标签（或浏览器 mock）→ 自动新建会话
          console.log('[store] 新标签，自动新建会话')
          autoCreateInFlight = true
          get().createSession()
        }
      }
      break
    }

    case 'createSession': {
      // 清除自动新建防重入标志（无论成功失败）
      autoCreateInFlight = false
      // 点 + 新建后直接切换到新会话（Java 返回 sessionId）
      const sid = msg.sessionId
      if (sid) {
        const ws = get().projectPath
        set({
          currentSessionId: sid,
          currentWorkspacePath: ws,
          messages: [],
          loadingMessages: false,
          streaming: false,
          streamingMessageId: null,
          waitingSince: null,
          queuedMessages: [], // 队列绑定旧会话上下文，新建会话丢弃
          contextUsage: null, // 清空旧会话数据，等 getUsage 回来更新
          contextBreakdown: null,
          thoughtLevel: null,
          currentMode: null,
          todos: [], // 派生状态同步清零：新会话不发 messages 请求，不重算会一直残留旧会话底部栏数据
          agents: [],
          fileChanges: [],
          subagentActivities: [], // 新会话无子代理
          subagents: [],
          subagentDetail: null,
          childMessages: {},
          childMessagesError: null,
          childSessionKeys: {},
          childLiveMessages: {},
          childStreamingIds: {},
        })
        // 订阅新会话（Java 端 handleSubscribe 内部会先 resume 激活）
        sendToJava({ op: 'subscribe', sessionId: sid, workspacePath: ws })
        // 新会话也按记忆模型下发 setModel（等 models 就绪，由 applyModelIfReady 内部判断）
        get().applyModelIfReady(sid)
        // 拉取上下文用量（圆环显示）
        get().loadUsage()
        // 拉取运行时设置（新会话默认模式 + 级别集）
        get().loadSettings()
      }
      get().loadSessions()
      break
    }

    case 'sessionDeleted': {
      // 从列表移除，如果删的是当前会话则清空
      const cur = get()
      const deletedCurrent = cur.currentSessionId === msg.sessionId
      set({
        sessions: cur.sessions.filter((x) => x.sessionId !== msg.sessionId),
        ...(deletedCurrent
          ? {
            currentSessionId: null, messages: [], streaming: false, streamingMessageId: null, waitingSince: null,
            queuedMessages: [],
            contextUsage: null, contextBreakdown: null, thoughtLevel: null, currentMode: null,
            todos: [], agents: [], fileChanges: [], // 底部栏派生状态随会话删除清零
            subagentActivities: [], subagents: [], subagentDetail: null, childMessages: {},
            childMessagesError: null,
            childSessionKeys: {}, childLiveMessages: {}, childStreamingIds: {},
          }
          : {}),
      })
      break
    }

    case 'messages':
      if (msg.sessionId === get().currentSessionId) {
        const st = get()
        const patch: Partial<StoreState> = {
          messages: msg.messages,
          loadingMessages: false,
          ...refreshStatus(msg.messages, st.subagentActivities, st.subagents),
        }
        // currentModel 为 null 时从消息推断（兼容历史会话 / CLI 默认模型，解除空会话发送限制）
        if (!get().currentModel) {
          const inferred = inferCurrentModel(msg.messages, get().models)
          if (inferred) patch.currentModel = inferred
        }
        // currentMode 为 null 时从消息推断（settings 拉取前的兜底显示）
        if (!get().currentMode) {
          const mode = inferCurrentMode(msg.messages)
          if (mode) patch.currentMode = mode
        }
        set(patch)
      }
      break

    case 'subagents': {
      // session/subagents RPC 权威列表：刷新 agents 合并结果；
      // 详情弹窗若开着且此前没有 childSessionId，现在补拉完整过程。
      // 失败不弹全局错误（底部栏还有解析兜底数据），静默保留旧值
      const st = get()
      if (msg.sessionId !== st.currentSessionId) break
      if (msg.error) break
      const items = [...msg.data.running, ...msg.data.ended.items]
      set({
        subagents: items,
        agents: mergeAgentItems(parseAgents(st.messages), st.subagentActivities, items),
      })
      const detail = st.subagentDetail
      if (detail) {
        const info = items.find((s) => s.toolCallId === detail)
        if (info && !st.childMessages[info.childSessionId] && info.status !== 'running') {
          get().loadChildMessages(info.childSessionId)
        }
      }
      break
    }

    case 'subagentMessages': {
      // 子会话完整消息（详情弹窗"原始过程"）：失败就地提示，不污染全局错误栏
      if (msg.error) {
        set({ childMessagesLoading: false, childMessagesError: msg.error })
        break
      }
      const st = get()
      set({
        childMessages: { ...st.childMessages, [msg.sessionId]: msg.messages },
        childMessagesLoading: false,
        childMessagesError: null,
      })
      break
    }

    case 'subagentStopped': {
      // 手动停止子代理的 ack：失败就地提示；成功则等事件流自然收尾
      // （子会话 turn 终止 → 父会话 Agent 工具中断结果 → stopped 通知 → 权威转录）
      if (msg.error) {
        set({ childMessagesError: `停止失败：${msg.error}` })
      } else {
        console.log(`[store] 子会话停止请求已受理: ${msg.sessionId}`)
      }
      break
    }

    case 'sendAccepted':
      // 发送被接受，等待流式事件（turn.started 即将到来）
      // CLI fallback 模式（带 cliResponse）：直接重新拉消息，并串行发送队列下一条
      if ('cliResponse' in msg && msg.cliResponse) {
        set({ streaming: false, waitingSince: null })
        const sid = get().currentSessionId
        if (sid) {
          setTimeout(() => {
            sendToJava({ op: 'messages', sessionId: sid, workspacePath: get().currentWorkspacePath })
          }, 1500)
        }
        get().flushQueue()
      }
      break

    case 'subscribed':
    case 'stopped':
      break

    case 'newSession':
      // Java 端自动新建会话（老会话模型不可用），切换到新会话
      console.log(`[store] 切换到新会话: ${msg.sessionId}`)
      set({
        currentSessionId: msg.sessionId,
        messages: [], // 新会话无历史消息
        streaming: false,
        streamingMessageId: null,
        waitingSince: null,
        thoughtLevel: null,
        currentMode: null,
        queuedMessages: [], // 队列绑定旧会话上下文，丢弃
        todos: [], // 底部栏派生状态随会话切换清零
        agents: [],
        fileChanges: [],
        subagentActivities: [], // 子代理数据绑定旧会话，丢弃
        subagents: [],
        subagentDetail: null,
        childMessages: {},
        childMessagesError: null,
        childSessionKeys: {},
        childLiveMessages: {},
        childStreamingIds: {},
      })
      // 刷新会话列表（新会话会出现在列表里）
      get().loadSessions()
      // 拉取新会话运行时设置
      get().loadSettings()
      break

    case 'error':
      autoCreateInFlight = false
      set({ lastError: msg.message, loadingMessages: false, streaming: false, waitingSince: null })
      console.error('[store] Java 错误:', msg.message)
      // 错误清 streaming 后继续发队列下一条（排队意图明确；持续失败时用户可删队列项）
      get().flushQueue()
      break

    case 'askUser':
      // AskUserQuestion 弹窗（服务器反向请求 interaction/requestUserInput）
      console.log('[store] 收到 askUser:', msg.toolName, msg.questions)
      set({ askUser: { requestId: msg.requestId, toolName: msg.toolName, questions: msg.questions } })
      break

    case 'exitPlanApproval':
      // ExitPlanMode 计划审批弹窗：渲染 plan markdown，用户批准/拒绝
      console.log('[store] 收到 exitPlanApproval，plan 长度:', msg.plan?.length ?? 0)
      set({ exitPlanApproval: { requestId: msg.requestId, plan: msg.plan || '' } })
      break

    case 'askUserAck':
      // Java 确认已收到用户选择，关闭弹窗
      set({ askUser: null, exitPlanApproval: null })
      break

    case 'ideTheme':
    case 'files':
      break

    case 'models':
      set({ models: msg.models })
      // 恢复 localStorage 记忆的模型选择（如仍在列表里）
      try {
        const saved = localStorage.getItem('zcode.currentModel')
        if (saved && get().currentModel === null) {
          const parsed = JSON.parse(saved) as { modelId: string; providerId: string }
          if (msg.models.some((m) => m.modelId === parsed.modelId && m.providerId === parsed.providerId)) {
            set({ currentModel: parsed })
          }
        }
      } catch { /* ignore */ }
      // localStorage 无记忆时，从已有消息推断（models 刚加载，messages 推断可能因缺 providerId 失败）
      if (!get().currentModel) {
        const inferred = inferCurrentModel(get().messages, msg.models)
        if (inferred) set({ currentModel: inferred })
      }
      // models 就绪后，若当前会话还没下发过 setModel → 真正下发（修复"选 deepseek 实际 GLM5"）
      {
        const sid = get().currentSessionId
        if (sid) get().applyModelIfReady(sid)
      }
      break

    case 'modelSet':
      set({ currentModel: { modelId: msg.modelId, providerId: msg.providerId } })
      // 切换模型后立即刷新用量，圆环 size 随新模型窗口更新（不用等下次对话结束）
      setTimeout(() => get().loadUsage(), 500)
      // 级别集随模型变化（off/high/max ↔ enabled/off），重拉 settings（current 由服务端校准）
      setTimeout(() => get().loadSettings(), 500)
      break

    case 'settings': {
      // 过期的 settings 响应（切会话竞态）直接丢弃
      if (msg.sessionId !== get().currentSessionId) break
      set({
        currentMode: msg.mode?.current ?? null,
        thoughtLevel: msg.thoughtLevel,
      })
      // 设置就绪：把记忆的思考级别下发给该会话（available 已知，仿 applyModelIfReady 门控）
      get().applyThoughtLevelIfReady(msg.sessionId)
      break
    }

    case 'thoughtLevelSet': {
      // 服务端校准 current（与本地乐观更新可能一致）
      const info = get().thoughtLevel
      if (info) set({ thoughtLevel: { ...info, current: msg.thoughtLevel } })
      break
    }

    case 'modeSet':
      set({ currentMode: msg.mode })
      break

    case 'usage':
      // 流式轮询期间切会话：旧会话的迟到响应直接丢弃，避免污染新会话圆环
      if (msg.sessionId && msg.sessionId !== get().currentSessionId) break
      set({
        contextUsage: { used: msg.used, size: msg.size, hitRate: msg.hitRate },
        // 构成明细来自 session/read 的 runtime.breakdown（turn 后 CLI 构建）
        ...(msg.breakdown ? { contextBreakdown: msg.breakdown } : {}),
      })
      break

    case 'quota':
      if (msg.error) {
        // 失败也记录刷新时间：否则 quotaFetchedAt 永远为 0，悬浮框/设置页的「上次刷新」永不显示，
        // 用户只看到错误文案，无法判断是否刚尝试过拉取
        set({ quota: null, quotaLoading: false, usageError: msg.error, quotaFetchedAt: Date.now() })
      } else {
        set({ quota: msg.data ?? null, quotaLoading: false, usageError: null, quotaFetchedAt: Date.now() })
      }
      break

    case 'modelUsage':
      if (msg.error) {
        set({ modelUsage: null, usageError: msg.error })
      } else {
        set({ modelUsage: msg.data ?? null })
      }
      break

    case 'toolUsage':
      if (msg.error) {
        set({ toolUsage: null, usageError: msg.error })
      } else {
        set({ toolUsage: msg.data ?? null })
      }
      break
  }
}

// ============ 流式事件处理 ============

/**
 * state.updated 通知：模式/思考级别/模型的服务端权威变化（含 ZCode 自动进出计划模式、
 * 外部客户端修改）。payload = {reason:"mode_changed"|..., patch:{mode, thoughtLevel, ...}}。
 * 自己 setMode/setThoughtLevel 后也会收到（幂等校准）。
 */
function applyStateUpdated(
  event: StreamEvent,
  set: (partial: Partial<StoreState>) => void,
) {
  const payload = event.payload as {
    reason?: string
    patch?: { mode?: { current?: string }; thoughtLevel?: ThoughtLevelInfo }
  }
  const patch = payload.patch
  if (patch) {
    const p: Partial<StoreState> = {}
    if (patch.mode?.current) {
      p.currentMode = patch.mode.current
      // 权威值切离 plan：清除 prePlanMode 记忆（避免下次 ExitPlanMode 恢复到过期值）
      if (patch.mode.current !== 'plan') p.prePlanMode = null
    }
    if (patch.thoughtLevel) p.thoughtLevel = patch.thoughtLevel
    if (Object.keys(p).length > 0) set(p)
  }
  console.log(`[store] state.updated(${payload.reason ?? '?'}): 模式/级别已按服务端同步`)
}

/**
 * 缺陷E修复：回合中的模式推断。
 * 服务端只在回合边界（prompt_completed）推带 mode 的 state.updated，回合中途
 * EnterPlanMode 成功 / ExitPlanMode 批准时刻均无推送——由 reducer 从工具事件推断
 * modeEvent，这里即时应用到指示器，不等回合结束：
 *   enter_plan：记住进 plan 前的模式，立即显示 plan
 *   exit_plan ：恢复记忆的模式（无记忆则 yolo），随后 loadSettings 拉权威值校正
 */
function applyModeEventToPatch(
  modeEvent: 'enter_plan' | 'exit_plan',
  patch: Partial<StoreState>,
  get: () => StoreState,
) {
  if (modeEvent === 'enter_plan') {
    const cur = get().currentMode
    if (cur && cur !== 'plan') patch.prePlanMode = cur
    patch.currentMode = 'plan'
  } else {
    patch.currentMode = get().prePlanMode ?? 'yolo'
    patch.prePlanMode = null
  }
}

/**
 * 批量处理流式事件（Java 端 16ms 节流合并的一批）。
 * 逐个归约但只 set 一次，避免每个 delta 都触发 React 重渲染。
 */
/**
 * 处理 subagent.lifecycle 通知（父会话流里的 session.updated）：
 * - spawned/stopped 都注册 childSessionId → 聚合键（parentToolCallId 优先），
 *   注册后子会话原生事件流即可实时归约（见 handleChildStreamBatch）
 * - stopped：若详情弹窗正开着且属于该子会话 → 拉权威全量替换实时流
 */
function applySubagentLifecycle(
  lc: SubagentLifecyclePayload,
  set: (partial: Partial<StoreState>) => void,
  get: () => StoreState,
) {
  const key = lc.parentToolCallId || lc.agentId
  if (key && get().childSessionKeys[lc.childSessionId] !== key) {
    const st = get()
    set({ childSessionKeys: { ...st.childSessionKeys, [lc.childSessionId]: key } })
    console.log(`[store] 子会话已注册: ${lc.childSessionId} → ${key} (${lc.phase})`)
  }
  if (lc.phase === 'stopped') {
    const st = get()
    if (st.subagentDetail && st.childSessionKeys[lc.childSessionId] === st.subagentDetail) {
      st.loadChildMessages(lc.childSessionId)
    }
  }
}

/**
 * 子会话原生事件流归约（批）：turn.started/text_delta/tool.updated 等 → 完整对话。
 * 复用 applyStreamEvent（纯函数，与会话无关）；跳过 state.updated（子会话的
 * 模式/级别变化不套用到主界面）。turn 结束不重拉——由 stopped → 权威全量替换。
 */
function handleChildStreamBatch(
  sessionId: string,
  events: StreamEvent[],
  set: (partial: Partial<StoreState>) => void,
  get: () => StoreState,
) {
  if (events.length === 0) return
  let messages = get().childLiveMessages[sessionId] ?? []
  let streamingId = get().childStreamingIds[sessionId] ?? null
  for (const event of events) {
    if (event.type === 'state.updated') continue
    // 防御：子会话原生流不应出现转发标记，出现则跳过（转发事件走父会话流）
    if (event.type === 'tool.updated' && isSubagentToolEvent(event.payload)) continue
    const r = applyStreamEvent(messages, event, streamingId)
    messages = r.messages
    streamingId = r.streamingMessageId
  }
  const st = get()
  set({
    childLiveMessages: { ...st.childLiveMessages, [sessionId]: messages },
    childStreamingIds: { ...st.childStreamingIds, [sessionId]: streamingId },
  })
}

function handleStreamBatch(
  sessionId: string,
  events: StreamEvent[],
  set: (partial: Partial<StoreState>) => void,
  get: () => StoreState,
) {
  if (sessionId !== get().currentSessionId) {
    // 已注册子会话的原生事件流 → 实时归约成完整对话（运行中详情弹窗数据源，
    // 含 AI 文本增量；Java 全局监听器本就把所有会话事件推到了前端）
    if (sessionId in get().childSessionKeys) {
      handleChildStreamBatch(sessionId, events, set, get)
    }
    return
  }
  if (events.length === 0) return

  let messages = get().messages
  let streamingMessageId = get().streamingMessageId
  let activities = get().subagentActivities
  let turnStarted = false
  let turnEnded = false
  let modeEvent: 'enter_plan' | 'exit_plan' | undefined
  const childKeyPatch: Record<string, string> = {}

  for (const event of events) {
    // 状态变化通知（不走消息归约，直接同步 settings）
    if (event.type === 'state.updated') {
      applyStateUpdated(event, set)
      continue
    }
    // 子代理生命周期通知（session.updated / kind=subagent.lifecycle）：
    // spawned 携带 childSessionId → 注册子会话；stopped → 详情弹窗开着则拉权威全量
    if (event.type === 'session.updated') {
      const lc = asSubagentLifecycle(event.payload)
      if (lc) {
        applySubagentLifecycle(lc, set, get)
        continue
      }
    }
    // 子代理转发工具事件（source=subagent）：不进主聊天 parts（防刷屏），
    // 聚合到 subagentActivities 供底部子代理栏与详情弹窗使用
    if (event.type === 'tool.updated' && isSubagentToolEvent(event.payload)) {
      activities = applySubagentToolEvent(activities, event.payload, event.timestamp)
      // 兜底注册子会话（spawned 通知缺失时，转发事件自带的归属字段也能建立映射）
      const fp = event.payload as ToolUpdatedPayload
      if (fp.childSessionId && fp.parentToolCallId
        && !(fp.childSessionId in get().childSessionKeys) && !(fp.childSessionId in childKeyPatch)) {
        childKeyPatch[fp.childSessionId] = fp.parentToolCallId
      }
      continue
    }
    // 父会话 Agent 工具本身收尾 → 对应子代理活动即时标记完成/失败
    if (event.type === 'tool.updated') {
      const p = event.payload as ToolUpdatedPayload
      if (p.kind === 'result' && p.toolCallId && activities.some((a) => a.key === p.toolCallId)) {
        activities = markActivityOutcome(activities, p.toolCallId, p.result?.success === false, event.timestamp)
      }
    }
    if (event.type === 'turn.started') turnStarted = true
    const result = applyStreamEvent(messages, event, streamingMessageId)
    messages = result.messages
    streamingMessageId = result.streamingMessageId
    if (result.turnEnded) turnEnded = true
    if (result.modeEvent) modeEvent = result.modeEvent
  }

  // 一次性 set（整批只触发一次重渲染）
  const patch: Partial<StoreState> = {
    messages,
    streamingMessageId,
    subagentActivities: activities,
    ...refreshStatus(messages, activities, get().subagents),
  }
  if (Object.keys(childKeyPatch).length > 0) {
    patch.childSessionKeys = { ...get().childSessionKeys, ...childKeyPatch }
  }
  if (turnStarted) patch.streaming = true, patch.waitingSince = null
  if (turnEnded) patch.streaming = false, patch.streamingMessageId = null, patch.waitingSince = null
  if (modeEvent) applyModeEventToPatch(modeEvent, patch, get)
  set(patch)
  // 退出 plan 的推断值可能与服务端有偏差（记忆缺失时兜底 yolo），立即拉权威值校正
  if (modeEvent === 'exit_plan') get().loadSettings()

  if (turnEnded) {
    console.log(`[store] turn 结束（批量），重新拉取消息确保一致`)
    // 本批未同时开启新 turn 时自动发送队列下一条（同批 completed+started 说明服务端已自动续轮）
    if (!turnStarted) get().flushQueue()
    setTimeout(() => {
      sendToJava({ op: 'messages', sessionId, workspacePath: get().currentWorkspacePath })
      // 刷新会话列表：CLI 会根据对话内容更新标题（sess_xxx → 用户问题）
      get().loadSessions()
      // 刷新子代理权威列表（running/ended + summary，底部栏与详情弹窗用）
      get().loadSubagents()
      // 刷新上下文用量（圆环更新）
      get().loadUsage()
      // 兜底重拉设置（ZCode 自动进出计划模式若伴随 turn 结束也能对齐）
      get().loadSettings()
    }, 300)
  }
}

function handleStreamEvent(
  sessionId: string,
  event: StreamEvent,
  set: (partial: Partial<StoreState>) => void,
  get: () => StoreState,
) {
  // 非当前会话：已注册子会话的原生事件流 → 实时归约（同批量路径）
  if (sessionId !== get().currentSessionId) {
    if (sessionId in get().childSessionKeys) {
      handleChildStreamBatch(sessionId, [event], set, get)
    }
    return
  }

  // 状态变化通知（panel 单推，低频即时）：模式/级别跟随服务端
  if (event.type === 'state.updated') {
    applyStateUpdated(event, set)
    return
  }

  // 子代理生命周期通知（spawned/stopped）：注册子会话，stop 时按需拉权威
  if (event.type === 'session.updated') {
    const lc = asSubagentLifecycle(event.payload)
    if (lc) {
      applySubagentLifecycle(lc, set, get)
      return
    }
  }

  // 子代理转发工具事件分流（同批量路径）：聚合不进主聊天
  if (event.type === 'tool.updated' && isSubagentToolEvent(event.payload)) {
    const st = get()
    const activities = applySubagentToolEvent(st.subagentActivities, event.payload, event.timestamp)
    // 兜底注册子会话（同批量路径）
    const fp = event.payload as ToolUpdatedPayload
    const keyPatch = (fp.childSessionId && fp.parentToolCallId && !(fp.childSessionId in st.childSessionKeys))
      ? { [fp.childSessionId]: fp.parentToolCallId }
      : {}
    set({
      subagentActivities: activities,
      ...refreshStatus(st.messages, activities, st.subagents),
      ...(Object.keys(keyPatch).length > 0
        ? { childSessionKeys: { ...st.childSessionKeys, ...keyPatch } }
        : {}),
    })
    return
  }

  // 父会话 Agent 工具本身收尾 → 对应子代理活动即时标记
  if (event.type === 'tool.updated') {
    const p = event.payload as ToolUpdatedPayload
    if (p.kind === 'result' && p.toolCallId
      && get().subagentActivities.some((a) => a.key === p.toolCallId)) {
      const st = get()
      const activities = markActivityOutcome(st.subagentActivities, p.toolCallId, p.result?.success === false, event.timestamp)
      set({ subagentActivities: activities, ...refreshStatus(st.messages, activities, st.subagents) })
    }
  }

  const { messages, streamingMessageId, turnEnded, modeEvent } = applyStreamEvent(
    get().messages,
    event,
    get().streamingMessageId,
  )

  // turn.started：进入流式，清除 waiting（开始有内容了）
  if (event.type === 'turn.started') {
    set({ streaming: true, waitingSince: null })
  }

  const st = get()
  const patch: Partial<StoreState> = {
    messages,
    streamingMessageId,
    ...refreshStatus(messages, st.subagentActivities, st.subagents),
  }
  if (modeEvent) applyModeEventToPatch(modeEvent, patch, get)
  set(patch)
  if (modeEvent === 'exit_plan') get().loadSettings()

  // turn 结束：重新拉完整消息确保数据一致，清除流式状态，自动发送队列下一条
  if (turnEnded) {
    set({ streaming: false, streamingMessageId: null, waitingSince: null })
    console.log(`[store] turn ${event.type}，重新拉取消息确保一致`)
    get().flushQueue()
    setTimeout(() => {
      sendToJava({
        op: 'messages',
        sessionId,
        workspacePath: get().currentWorkspacePath,
      })
      // 刷新会话列表：CLI 会根据对话内容更新标题
      get().loadSessions()
      // 刷新子代理权威列表
      get().loadSubagents()
      // 兜底重拉设置（模式/思考级别对齐服务端）
      get().loadSettings()
      // 刷新上下文用量（圆环更新，对齐批量路径）
      get().loadUsage()
    }, 300)
  }
}

// ===== 流式期间轮询上下文用量（圆环实时刷新）=====
// 机制：contextUsage 唯一来源是 session/read RPC，服务端在读时从最新 assistant 消息的
// tokens 实时计算（zcode.cjs ida/LRe/dda），流式期间没有推送事件——不轮询的话圆环
// 只在回合结束后才更新一次。streaming 翻转时启停：true → 每 5s loadUsage（幂等读，
// Kotlin 端走线程池，不阻塞 EDT/reader；响应带 sessionId 防切会话竞态）。
let usagePollTimer: ReturnType<typeof setInterval> | null = null

useStore.subscribe((s, prev) => {
  if (s.streaming === prev.streaming) return
  if (s.streaming) {
    if (!usagePollTimer) {
      // 立即采样一次（短回合 <5s 也能刷一次圆环），此后每 5s
      useStore.getState().loadUsage()
      usagePollTimer = setInterval(() => {
        const st = useStore.getState()
        if (!st.streaming) {
          if (usagePollTimer) clearInterval(usagePollTimer)
          usagePollTimer = null
        } else {
          st.loadUsage()
        }
      }, 5000)
    }
  } else if (usagePollTimer) {
    clearInterval(usagePollTimer)
    usagePollTimer = null
  }
})
