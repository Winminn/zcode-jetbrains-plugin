/**
 * 子 agent / 任务回调通知的识别与解析
 *
 * 背景（2026-08-13 实测 sess_ad3de3e3 sqlite 数据）：
 * 后台子代理（run_in_background）完成、或同步子代理中途回消息时，app-server 会向主会话
 * 注入一条 role:"user" 的「合成消息」：
 *   - message.synthetic === true
 *   - message.source === "background_task" | "subagent_message"
 *   - message.semantics.origin === "agent_runtime"
 *   - message.visibility === "model-only"
 *   - 唯一 part 是 type:"text"，text 是原始 XML：
 *       <task-notification>...</task-notification>   （后台任务完成）
 *       <subagent-message>...</subagent-message>     （子代理中途消息）
 *
 * 之前前端只判 role==='user'，把它当用户消息明文渲染。这里负责识别并解析成结构化对象，
 * 供 AgentNotificationCard 友好展示。
 */

import type { MessageInfo } from '@/types/messages'

// ============ 类型 ============

export interface TaskNotification {
  kind: 'task'
  taskId?: string
  toolUseId?: string
  outputFile?: string
  status?: string
  summary?: string
  /** 子代理成果正文（markdown，已反转义 HTML 实体）*/
  result?: string
  usage?: { tokens?: number; toolUses?: number; durationMs?: number }
}

export interface SubagentMessageNotification {
  kind: 'message'
  agentId?: string
  agentType?: string
  summary?: string
  /** 子代理消息正文（markdown，已反转义 HTML 实体）*/
  message?: string
}

export type ParsedNotification = TaskNotification | SubagentMessageNotification | { kind: 'unknown' }

// ============ 识别 ============

/** 判断一条消息是否是子 agent / 任务回调的合成通知 */
export function isAgentNotification(info: MessageInfo): boolean {
  if (info.synthetic !== true) return false
  const sem = info.semantics as { origin?: string; kind?: string } | undefined
  if (sem?.origin === 'agent_runtime') return true
  if (info.source === 'background_task' || info.source === 'subagent_message') return true
  return false
}

// ============ 解析 ============

/** 提取顶层标签内容（result/message 内的 < 都是实体编码，不会出现裸 </tag>，非贪婪安全）*/
function extractTag(s: string, tag: string): string | undefined {
  const re = new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`)
  const m = s.match(re)
  return m ? m[1].trim() : undefined
}

/**
 * 反转义 HTML 实体。
 * 注意顺序：&amp; 必须最后转，否则会破坏其他实体（如把 &amp;quot; 错误转成 "）。
 * result 正文里的 markdown 被大量实体编码（&quot; &lt; &gt; &#39;），必须还原才能正确渲染。
 */
function decodeEntities(s: string): string {
  return s
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&#x27;/g, "'")
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
}

/** 同 decodeEntities，但接受 undefined（缺失时原样返回 undefined）*/
export function decodeOptional(s: string | undefined): string | undefined {
  return s != null ? decodeEntities(s) : undefined
}

/** 解析通知 XML 文本，返回结构化对象 */
export function parseNotificationText(text: string): ParsedNotification {
  if (text.includes('<task-notification>')) {
    const usageBlock = extractTag(text, 'usage')
    const usage = usageBlock
      ? {
          tokens: num(extractTag(usageBlock, 'subagent_tokens')),
          toolUses: num(extractTag(usageBlock, 'tool_uses')),
          durationMs: num(extractTag(usageBlock, 'duration_ms')),
        }
      : undefined
    const result = extractTag(text, 'result')
    return {
      kind: 'task',
      taskId: extractTag(text, 'task-id'),
      toolUseId: extractTag(text, 'tool-use-id'),
      outputFile: extractTag(text, 'output-file'),
      status: extractTag(text, 'status'),
      summary: decodeOptional(extractTag(text, 'summary')),
      result: result != null ? decodeEntities(result) : undefined,
      usage,
    }
  }

  if (text.includes('<subagent-message>')) {
    const message = extractTag(text, 'message')
    return {
      kind: 'message',
      agentId: extractTag(text, 'agent-id'),
      agentType: extractTag(text, 'agent-type'),
      summary: decodeOptional(extractTag(text, 'summary')),
      message: message != null ? decodeEntities(message) : undefined,
    }
  }

  return { kind: 'unknown' }
}

function num(s: string | undefined): number | undefined {
  if (s == null || s === '') return undefined
  const n = Number(s)
  return Number.isFinite(n) ? n : undefined
}

// ============ 辅助：从 info.metadata 提取展示用元信息 ============

/** 通知卡片的展示标题：优先用 originMeta.title（后台任务）或 summary，兜底 agentType */
export function notificationTitle(info: MessageInfo, parsed: ParsedNotification): string {
  const meta = info.metadata as
    | { originMeta?: { title?: string }; subagentMessage?: { agentType?: string } }
    | undefined
  if (meta?.originMeta?.title) return meta.originMeta.title
  if (parsed.kind === 'task' && parsed.summary) {
    // summary 形如 `Agent general-purpose task "xxx" completed.` → 取引号内的标题
    const m = parsed.summary.match(/"([^"]+)"/)
    if (m) return m[1]
    return parsed.summary
  }
  if (parsed.kind === 'message' && parsed.summary) return parsed.summary
  if (meta?.subagentMessage?.agentType) return `子代理 · ${meta.subagentMessage.agentType}`
  return '子代理通知'
}
