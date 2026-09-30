/**
 * 通知桥接轮组分组（缺陷EG）单元测试：buildTurnChains
 *
 * 后台任务通知（合成 user 消息）会唤醒一条新 turn 的续跑回复——协议上一轮执行
 * 被切成两段 assistant 消息。buildTurnChains 把「仅被通知桥接」的 assistant 序列
 * 重组为一条渲染链（一个折叠栏一个结论），真实用户消息切断，无通知桥的相邻
 * assistant（model_change 分隔卡等）维持分栏。
 */
import { describe, it, expect } from 'vitest'
import { buildTurnChains, isNotificationBridgedChain } from '@/utils/turnChains'
import type { ZCodeMessage } from '@/types/messages'

let seq = 0

function assistant(created: number, text = '回复'): ZCodeMessage {
  seq += 1
  return {
    info: { id: `a_${seq}`, role: 'assistant', sessionID: 's1', time: { created } },
    parts: [{ type: 'text', text }],
  }
}

function realUser(created: number): ZCodeMessage {
  seq += 1
  return {
    info: { id: `u_${seq}`, role: 'user', sessionID: 's1', time: { created } },
    parts: [{ type: 'text', text: '用户消息' }],
  }
}

function notification(created: number): ZCodeMessage {
  seq += 1
  return {
    info: {
      id: `n_${seq}`,
      role: 'user',
      sessionID: 's1',
      synthetic: true,
      source: 'background_task',
      time: { created },
    },
    parts: [{ type: 'text', text: '<task-notification><status>completed</status></task-notification>' }],
  }
}

const ids = (chains: ReturnType<typeof buildTurnChains>) => chains.map((c) => c.map((m) => m.info.id))

describe('缺陷EG：通知桥接的 assistant 消息链合并为一个视觉轮组', () => {
  it('主场景：assistant → 通知 → assistant（wake 续条）合并为一条链', () => {
    const a1 = assistant(1)
    const n = notification(2)
    const a2 = assistant(3)
    const chains = buildTurnChains([a1, n, a2])
    expect(chains.length).toBe(1)
    expect(isNotificationBridgedChain(chains[0])).toBe(true)
    expect(ids(chains)[0]).toEqual([a1.info.id, n.info.id, a2.info.id])
  })

  it('多条通知连续桥接同样入链；通知后无续条也挂在链尾（时序位渲染）', () => {
    const a1 = assistant(1)
    const n1 = notification(2)
    const n2 = notification(3)
    const a2 = assistant(4)
    const chains = buildTurnChains([a1, n1, n2, a2])
    expect(chains.length).toBe(1)
    expect(chains[0].length).toBe(4)

    // 回合中途到达的通知（组内时序位）：链尾挂通知
    const a3 = assistant(10)
    const n3 = notification(11)
    const chains2 = buildTurnChains([a3, n3])
    expect(chains2.length).toBe(1)
    expect(isNotificationBridgedChain(chains2[0])).toBe(true)
  })

  it('真实用户消息切断链：跨轮次绝不合并', () => {
    const a1 = assistant(1)
    const n = notification(2)
    const u = realUser(3)
    const a2 = assistant(4)
    const chains = buildTurnChains([a1, n, u, a2])
    expect(ids(chains)).toEqual([[a1.info.id, n.info.id], [u.info.id], [a2.info.id]])
  })

  it('无通知桥的相邻 assistant 不合并（model_change 分隔卡等维持分栏）', () => {
    const a1 = assistant(1)
    const a2 = assistant(2)
    const chains = buildTurnChains([a1, a2])
    expect(chains.length).toBe(2)
    expect(chains.every((c) => !isNotificationBridgedChain(c))).toBe(true)
  })

  it('无链可挂的通知独立成链（会话开头场景，渲染路径与旧版一致）', () => {
    const n = notification(1)
    const u = realUser(2)
    const chains = buildTurnChains([n, u])
    expect(ids(chains)).toEqual([[n.info.id], [u.info.id]])
  })
})
