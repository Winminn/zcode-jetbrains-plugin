/**
 * 流式静默对账看门狗 + 冷会话 send 错误提示的回归测试
 *
 * 复现缺陷（2026-08-19，idea.log + cli jsonl 时序证据）：
 *   ZCode 桌面端自动更新 taskkill 掉插件赖以工作的 app-server → 插件自动重启
 *   新进程 → 会话 resume 恢复后的回合以 background turn 在服务端真实执行完毕
 *   （工具调用全部落地），但 session/event 零下发——前端只认终止帧收尾，
 *   无限转圈；恢复完成前的第一次 send 还会撞 -32004 Session is not active。
 *
 * 断言：
 *   1. -32004 错误信息追加人话提示（引导从历史列表重开）
 *   2. streaming 静默 60s 触发对账探测（messages + reconcile 标记）
 *   3. 快照末尾是完整 assistant 回复 → 回合判定已结束，收尾 + 落地，不报错
 *   4. 快照连续多轮无进展（尾部始终没有 assistant 内容）→ 判定流丢失，收尾 + 提示
 *   5. 事件正常流动时心跳刷新静默计时，不探测；mock 连接不探测
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'

// ---- mock 桥接层：捕获 sendToJava，手动注入事件/响应 ----
let streamEventHandler: ((sid: string, event: unknown) => void) | null = null
let messageHandler: ((msg: unknown) => void) | null = null
const sentRequests: Array<Record<string, unknown>> = []

vi.mock('@/ipc/bridge', () => ({
  initBridge: () => {},
  isInJcef: () => false,
  getWorkspacePath: () => 'G:\\mock',
  getInitialSessionId: () => '',
  onMessage: (fn: (msg: unknown) => void) => { messageHandler = fn },
  onStreamEvent: (fn: (sid: string, event: unknown) => void) => { streamEventHandler = fn },
  onStreamBatch: () => {},
  sendToJava: (req: Record<string, unknown>) => { sentRequests.push(req) },
}))

import { useStore } from '@/store/useStore'
import type { ZCodeMessage } from '@/types/messages'

const SID = 'sess_watchdog_1'

function mkMsg(role: 'user' | 'assistant', text: string, id: string): ZCodeMessage {
  return {
    info: { role, id, time: { created: 1 }, sessionID: SID },
    parts: [{ type: 'text', text }],
  } as ZCodeMessage
}

function reconcileRequests(): Array<Record<string, unknown>> {
  return sentRequests.filter((r) => r.op === 'messages' && r.reconcile === true)
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllTimers()
  sentRequests.length = 0
  useStore.getState().init()
  sentRequests.length = 0
  useStore.setState({
    connectionStatus: 'connected',
    currentSessionId: SID,
    currentWorkspacePath: 'G:\\mock',
    messages: [],
    streaming: false,
    streamingMessageId: null,
    waitingSince: null,
    queuedMessages: [],
    lastError: null,
    currentModel: { modelId: 'GLM-5.3', providerId: 'builtin' },
  })
})

describe('store：-32004 冷会话错误提示', () => {
  it('Session is not active 错误追加恢复提示', () => {
    messageHandler!({ op: 'error', message: '发送失败: [-32004] Session is not active: sess_x' })
    const lastError = useStore.getState().lastError || ''
    expect(lastError).toContain('Session is not active')
    expect(lastError).toContain('历史') // 提示里引导从历史列表重开
  })

  it('普通错误不追加提示，原样展示', () => {
    messageHandler!({ op: 'error', message: '其他错误' })
    expect(useStore.getState().lastError).toBe('其他错误')
  })
})

describe('store：流式静默对账看门狗', () => {
  it('事件正常流动时不探测（心跳刷新静默计时）', () => {
    useStore.setState({ streaming: true })
    vi.advanceTimersByTime(30_000)
    // 当前会话有任何事件到达 = 回合活着
    streamEventHandler!(SID, {
      type: 'turn.started', seq: 1, sessionId: SID, turnId: 't', timestamp: Date.now(), payload: {},
    })
    vi.advanceTimersByTime(50_000)
    expect(reconcileRequests()).toHaveLength(0)
    // 此后彻底静默 60s+ → 才开始探测。首探后响应一直不来（测试不回），
    // 30s 未回放行重探 → 61s 窗口内共 2 次（防响应丢失卡死看门狗）
    vi.advanceTimersByTime(61_000)
    expect(reconcileRequests()).toHaveLength(2)
  })

  it('mock 连接不探测（mock 数据源无对账意义）', () => {
    useStore.setState({ connectionStatus: 'mock', streaming: true })
    vi.advanceTimersByTime(120_000)
    expect(reconcileRequests()).toHaveLength(0)
  })

  it('对账快照末尾为完整 assistant 回复 → 收尾并落地，不报错', () => {
    useStore.setState({ streaming: true })
    vi.advanceTimersByTime(61_000)
    expect(reconcileRequests()).toHaveLength(1)
    messageHandler!({
      op: 'messages',
      sessionId: SID,
      reconcile: true,
      messages: [mkMsg('user', '继续', 'm1'), mkMsg('assistant', '已完成合并与推送', 'm2')],
    })
    const st = useStore.getState()
    expect(st.streaming).toBe(false)
    expect(st.waitingSince).toBeNull()
    expect(st.lastError).toBeNull() // 正常完成路径不弹错误
    const last = st.messages[st.messages.length - 1]
    expect(last?.info.role).toBe('assistant')
    expect(JSON.stringify(last?.parts)).toContain('已完成合并与推送')
  })

  it('对账快照仍在推进（尾部是工具步骤）→ 不打扰，继续等待', () => {
    useStore.setState({ streaming: true })
    vi.advanceTimersByTime(61_000)
    // 尾部是 user（本轮输入），但快照每轮都有变化（服务端在产出）→ progress
    messageHandler!({ op: 'messages', sessionId: SID, reconcile: true, messages: [mkMsg('user', '继续', 'm1')] })
    expect(useStore.getState().streaming).toBe(true)
    // 快照推进（追加了一条工具消息）→ 仍 progress
    messageHandler!({
      op: 'messages', sessionId: SID, reconcile: true,
      messages: [mkMsg('user', '继续', 'm1'), { info: { role: 'assistant', id: 'm3', time: { created: 2 }, sessionID: SID }, parts: [{ type: 'tool', name: 'Bash' }] } as ZCodeMessage],
    })
    expect(useStore.getState().streaming).toBe(true)
    expect(useStore.getState().messages).toHaveLength(0) // progress 不落地
  })

  it('快照连续多轮无进展 → 判定流丢失，收尾并提示', () => {
    useStore.setState({ streaming: true })
    const snapshot = [mkMsg('user', '继续', 'm1')]
    for (let i = 0; i < 4; i++) {
      vi.advanceTimersByTime(61_000)
      messageHandler!({ op: 'messages', sessionId: SID, reconcile: true, messages: snapshot })
      if (i < 3) expect(useStore.getState().streaming).toBe(true)
    }
    const st = useStore.getState()
    expect(st.streaming).toBe(false)
    expect(st.lastError).toBeTruthy()
  })
})
