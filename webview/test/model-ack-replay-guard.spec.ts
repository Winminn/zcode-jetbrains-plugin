/**
 * 缺陷BO 回归测试（2026-09-14 修复）：回执已到的会话不再被 applyModelIfReady 重放
 *
 * 修复前（复现档案）：a8a4776 给 modelAppliedSessions 登记 60s TTL 用于「回执丢失防
 * 锁死」，但 TTL 过期无法区分「回执丢失（该重发）」与「回执已到（重发多余）」——
 * 会话创建分钟级后任何 models 刷新（设置页模型管理挂载 → case 'modelManage' 连带
 * loadModels、provider 启停、重连初始化）都会重放 setModel；回合中被 Java 挂起成
 * 幽灵「本轮结束后生效」横幅（目标=当前已在用的模型，idea.log 实锤：modelManageList
 * returned 后 3~6ms 跟 Model switch deferred，与刚落定的模型相同）。
 *
 * 修复后行为（本文件断言）：
 *   1. modelSet / modelSetPending 到达即登记 modelAckSessions，applyModelIfReady 见之
 *      跳过（挂起也是回执——Java 已接管回合结束补发）。
 *   2. modelSetFailed 清除回执标记（与登记解锁同语义，失败后仍可重试）。
 *   3. 用户 setModel 动作清除回执标记（新切换回执若丢失，TTL 过期后追发链路保持可用）。
 *   4. 真回执丢失场景保留：无 ack 且登记超 TTL → 下次触发仍重发（TTL 语义不回退）。
 *
 * 独立文件原因：不与 selectSession 用例混跑——selectSession 会给会话挂
 * pendingModelApplyAfterSubscribe（12s 真表计时器，条目常驻本测试进程），case 'models'
 * 的触发口被它拦住，混文件会让本文件的「应发送」用例空转失败、「应跳过」用例空转
 * 通过（均为伪验证）。
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'

// ---- mock localStorage（node 环境无实现）----
const storage = new Map<string, string>()
const lsMock = {
  getItem: (k: string) => storage.get(k) ?? null,
  setItem: (k: string, v: string) => { storage.set(k, v) },
  removeItem: (k: string) => { storage.delete(k) },
  key: (i: number) => Array.from(storage.keys())[i] ?? null,
  get length() { return storage.size },
  clear: () => { storage.clear() },
}
vi.stubGlobal('localStorage', lsMock)
vi.stubGlobal('window', { localStorage: lsMock, dispatchEvent: () => {}, __ZCODE_KVSTORE__: null })

// ---- mock 桥接层：捕获 sendToJava，手动注入事件/响应 ----
let messageHandler: ((msg: unknown) => void) | null = null
const sentRequests: Array<Record<string, unknown>> = []

vi.mock('@/ipc/bridge', () => ({
  initBridge: () => {},
  isInJcef: () => false,
  getWorkspacePath: () => 'G:\\mock',
  getInitialSessionId: () => '',
  onMessage: (fn: (msg: unknown) => void) => { messageHandler = fn },
  onStreamEvent: () => {},
  onStreamBatch: () => {},
  sendToJava: (req: Record<string, unknown>) => { sentRequests.push(req) },
}))

import { useStore } from '@/store/useStore'

const SID1 = 'sess_bo_1'
const GLM = { modelId: 'GLM-5.3', providerId: 'builtin:bigmodel-coding-plan' }
const KIMI = { modelId: 'kimi-k3', providerId: 'kimi' }

function pushResponse(msg: Record<string, unknown>): void {
  messageHandler!(msg)
}

function setModelReqs(sid?: string): Array<Record<string, unknown>> {
  return sentRequests.filter((r) => r.op === 'setModel' && (!sid || r.sessionId === sid))
}

function lastSetModelReq(): Record<string, unknown> | undefined {
  return [...sentRequests].reverse().find((r) => r.op === 'setModel')
}

beforeEach(() => {
  storage.clear()
  sentRequests.length = 0
  useStore.getState().init()
  // 初始：会话 1 在用 GLM（会话级记忆 + 登记=很旧，模拟创建已超 60s TTL）
  storage.set('zcode.currentModel', JSON.stringify(GLM))
  storage.set('zcode.modelMemory', JSON.stringify({ [SID1]: GLM }))
  useStore.setState({
    currentSessionId: SID1,
    currentModel: { ...GLM },
    modelInvalidated: false,
    models: [
      { ...GLM, label: 'GLM-5.3' },
      { ...KIMI, label: 'Kimi K3' },
    ],
    modelAppliedSessions: new Map<string, number>(),
    modelAckSessions: new Set<string>(),
    createdSessionIds: new Set(),
    modelSwitchInFlightAt: null,
    modelPendingSwitch: null,
    modelSwitchPrevModel: null,
    lastNotice: null,
    lastError: null,
  })
})

describe('缺陷BO：回执已到的会话不再被 applyModelIfReady 重放（幽灵挂起横幅）', () => {
  /** 复现链路尾部：设置页模型管理 → case 'modelManage' 连带 loadModels → models 到达
   *  触发 applyModelIfReady */
  function pushManageAndModels(): void {
    pushResponse({ op: 'modelManage', providers: [], configPath: 'G:\\mock\\config.json' })
    pushResponse({
      op: 'models',
      models: [
        { ...GLM, label: 'GLM-5.3' },
        { ...KIMI, label: 'Kimi K3' },
      ],
    })
  }

  /** 登记做旧越过 60s TTL（TTL 守卫放行场景）*/
  function expireRegistration(sid: string): void {
    useStore.setState({ modelAppliedSessions: new Map([[sid, Date.now() - 120_000]]) })
  }

  it('主场景：modelSet 落定后 TTL 过期，设置页链路不再重发 setModel、无幽灵横幅', () => {
    pushResponse({ op: 'modelSet', sessionId: SID1, ...GLM }) // 切换落定（回执）
    expireRegistration(SID1)
    sentRequests.length = 0
    pushManageAndModels()
    expect(setModelReqs()).toEqual([]) // 修复前：TTL 过期放行重发，回合中被挂起成幽灵横幅
    expect(useStore.getState().lastNotice).toBeNull()
  })

  it('modelSetPending（挂起）也是回执：Java 已接管补发，models 刷新不重放', () => {
    pushResponse({ op: 'modelSetPending', sessionId: SID1, ...KIMI })
    expireRegistration(SID1)
    sentRequests.length = 0
    pushManageAndModels()
    expect(setModelReqs()).toEqual([])
  })

  it('modelSetFailed 清回执：失败后 models 刷新仍可重试（与登记解锁同语义）', () => {
    pushResponse({ op: 'modelSet', sessionId: SID1, ...GLM }) // 先落定
    pushResponse({ op: 'modelSetFailed', sessionId: SID1, ...KIMI, message: 'Model switch failed: [-32603] Unsupported model' })
    useStore.setState({ modelAppliedSessions: new Map() }) // 失败解锁已清登记
    sentRequests.length = 0
    pushManageAndModels()
    // 会话 1 记忆=GLM（beforeEach），重试按记忆目标放行
    expect(setModelReqs(SID1).length).toBe(1)
  })

  it('用户 setModel 清回执：新切换回执丢失时 TTL 追发链路仍可用', () => {
    pushResponse({ op: 'modelSet', sessionId: SID1, ...GLM }) // 旧切换的回执
    useStore.getState().setModel(KIMI.modelId, KIMI.providerId) // 新切换在途（清 ack、写记忆 kimi）
    // 回执丢失：不推 modelSet/Pending/Failed —— 登记做旧越过 TTL
    expireRegistration(SID1)
    sentRequests.length = 0
    pushManageAndModels()
    expect(lastSetModelReq()).toMatchObject({ sessionId: SID1, ...KIMI }) // 按记忆目标追发
  })

  it('真回执丢失场景保留（TTL 语义不回退）：无 ack 且登记超 TTL → models 刷新重发', () => {
    expireRegistration(SID1)
    sentRequests.length = 0
    pushManageAndModels()
    expect(setModelReqs(SID1).length).toBe(1)
    expect(lastSetModelReq()).toMatchObject({ sessionId: SID1, ...GLM })
  })
})
