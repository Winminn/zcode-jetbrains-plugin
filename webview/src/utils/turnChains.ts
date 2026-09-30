/**
 * 通知桥接的 assistant 消息链分组（缺陷EG）
 *
 * 后台任务通知由 CLI 以合成 user 消息注入转录，并唤醒一条**新 turn** 的续跑回复
 * （wake 轮有独立 turn.started/assistant 消息）。协议上一通知 legitimately 把一轮
 * 执行切成两段 assistant 消息，但用户视角这是一次执行——折叠栏不应拆成两个。
 *
 * 本工具把消息序列重组为渲染链（chains）：
 *   - assistant 消息开头一条链；其后仅被**通知**桥接的 assistant 消息继续吸收进
 *     同一条链（通知卡也随之入链，渲染在组内时序位置）；
 *   - 真实用户消息 / 压缩摘要等非通知消息切断链（跨真实轮次绝不合并）；
 *   - assistant 直接跟 assistant（无通知桥，如 model_change 分隔卡）不合并——
 *     维持既有分栏行为；
 *   - 无链可挂的通知（会话开头/真实用户消息后）独立成链，渲染路径与旧版一致。
 */

import { isAgentNotification } from './parseNotification'
import type { ZCodeMessage } from '@/types/messages'

/** 单条渲染链：长度 1 = 普通单消息；长度 >1 = 通知桥接的合并轮组（首元素必为 assistant）*/
export type TurnChain = ZCodeMessage[]

export function isNotificationBridgedChain(chain: TurnChain): boolean {
  return chain.length > 1
}

export function buildTurnChains(messages: ZCodeMessage[]): TurnChain[] {
  const chains: TurnChain[] = []
  let current: TurnChain | null = null

  const flush = () => {
    if (current) chains.push(current)
    current = null
  }

  for (const m of messages) {
    if (isAgentNotification(m.info)) {
      if (current) {
        current.push(m) // 桥接：先挂起，等后续 assistant 决定入链还是断链
      } else {
        chains.push([m]) // 无链可挂：独立渲染（旧路径）
      }
      continue
    }
    if (m.info.role === 'assistant') {
      if (current && current[current.length - 1].info.role === 'assistant') {
        // 尾部已是 assistant（中间无通知桥）：不合并（model_change 分隔卡等维持分栏）
        flush()
        current = [m]
      } else if (current) {
        current.push(m) // 通知桥接的续条：入链
      } else {
        current = [m]
      }
      continue
    }
    // 真实用户消息 / 压缩摘要等：切断链，独立渲染
    flush()
    chains.push([m])
  }
  flush()
  return chains
}
