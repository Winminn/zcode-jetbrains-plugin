/**
 * 代理间消息工具（SendMessage / RespondToCoordinator）卡片的解析。
 *
 * 数据形状（zcode.cjs 逆向，2026-09-19）：
 *  - SendMessage input = { to: "agent_<uuid>", summary, message }——协调者向本地
 *    代理队列投消息，可续聊已完成/后台子代理；summary 是官方给 UI 的 5-10 词预览；
 *  - RespondToCoordinator input = { summary, message }（子代理→协调者回报）；
 *  - output 为 result JSON 文本：
 *    SendMessage = { status: success|failed, messageId, agentId?, delivery?, error? }，
 *    delivery 三态：queued=入队待读 / steered=插队引导 / resumed_background=唤醒后台续跑；
 *    RespondToCoordinator = { status, responseId, message, error? }。
 *    非 JSON（错误文本/流式半截）→ null，调用方回退原文展示（同 parseCronToolOutput 口径）。
 */

export const AGENT_MESSAGE_TOOLS = ['SendMessage', 'RespondToCoordinator'] as const

export function isAgentMessageTool(tool: string): boolean {
  return (AGENT_MESSAGE_TOOLS as readonly string[]).includes(tool)
}

/** agent_<uuid> → agent_ + 前 8 位（尾部连字符剪掉，完整 id 靠 title hover）*/
export function shortAgentId(id: string): string {
  return /^agent_[0-9a-f-]{8,}$/i.test(id) ? id.slice(0, 'agent_'.length + 8).replace(/-+$/, '') : id
}

export interface AgentMessageReceipt {
  status: 'success' | 'failed'
  /** SendMessage 的 messageId / RespondToCoordinator 的 responseId */
  id?: string
  /** 仅 SendMessage：投递形态 */
  delivery?: 'queued' | 'steered' | 'resumed_background'
  error?: string
  /** RespondToCoordinator 回执里的服务端附言 */
  message?: string
}

/** 回执 JSON 解析（非 JSON / status 非法的输出 → null，回退原文展示）*/
export function parseAgentMessageReceipt(output: string | null | undefined): AgentMessageReceipt | null {
  if (!output) return null
  try {
    const o = JSON.parse(output) as Record<string, unknown>
    if (typeof o !== 'object' || o === null) return null
    if (o.status !== 'success' && o.status !== 'failed') return null
    return {
      status: o.status,
      id: typeof o.messageId === 'string'
        ? o.messageId
        : typeof o.responseId === 'string' ? o.responseId : undefined,
      delivery: o.delivery === 'queued' || o.delivery === 'steered' || o.delivery === 'resumed_background'
        ? o.delivery
        : undefined,
      error: typeof o.error === 'string' ? o.error : undefined,
      message: typeof o.message === 'string' ? o.message : undefined,
    }
  } catch {
    return null
  }
}
