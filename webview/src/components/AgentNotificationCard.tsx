/**
 * 子 agent / 任务回调通知卡片
 *
 * 渲染 app-server 注入的合成通知消息（详见 utils/parseNotification.ts）。
 * 与普通用户消息区分：左对齐独立卡片，正文走 MarkdownBlock，默认按长度决定折叠。
 *
 * 两种形态：
 *   - kind:'task'    后台子代理（run_in_background）完成，含 status/usage/result
 *   - kind:'message' 同步子代理中途回消息，含 agentType/message
 */

import { useMemo, useState } from 'react'
import type { ZCodeMessage, TextPart } from '@/types/messages'
import { MarkdownBlock } from './MarkdownBlock'
import {
  parseNotificationText,
  notificationTitle,
  type ParsedNotification,
} from '@/utils/parseNotification'
import '../styles/notification-card.less'

interface Props {
  message: ZCodeMessage
  time: string
}

export function AgentNotificationCard({ message, time }: Props) {
  const { info, parts } = message
  // 合成通知只有一个 text part，内容是 XML
  const textPart = parts.find((p): p is TextPart => p.type === 'text')
  const text = textPart?.text ?? ''

  const parsed = useMemo<ParsedNotification>(() => parseNotificationText(text), [text])
  const title = notificationTitle(info, parsed)

  const body = parsed.kind === 'task' ? parsed.result : parsed.kind === 'message' ? parsed.message : ''
  const hasBody = !!body && body.trim().length > 0
  // 短正文默认展开，长正文（>1500 字符，如完整架构文档）默认折叠避免刷屏
  const [expanded, setExpanded] = useState(!hasBody || body.length <= 1500)

  const usage = parsed.kind === 'task' ? parsed.usage : undefined
  const status = parsed.kind === 'task' ? parsed.status ?? 'completed' : 'completed'
  const badgeCls = status === 'completed' ? 'ok' : status === 'error' ? 'err' : 'info'
  const badgeText = status === 'completed' ? '✓ 完成' : status === 'error' ? '✗ 失败' : status

  return (
    <div className={`notif-card notif-card--${badgeCls}`}>
      <div
        className="notif-card__header"
        onClick={() => hasBody && setExpanded((e) => !e)}
        role={hasBody ? 'button' : undefined}
      >
        <span className="notif-card__icon">
          <span className="codicon codicon-hubot" />
        </span>
        <span className="notif-card__label">子代理</span>
        <span className="notif-card__title" title={title}>{title}</span>
        <span className={`notif-card__badge notif-card__badge--${badgeCls}`}>{badgeText}</span>
        {usage?.durationMs != null && (
          <span className="notif-card__meta">⏱ {formatDuration(usage.durationMs)}</span>
        )}
        {usage?.tokens != null && (
          <span className="notif-card__meta">💡 {usage.tokens.toLocaleString()}</span>
        )}
        {parsed.kind === 'message' && parsed.agentType && (
          <span className="notif-card__meta">{parsed.agentType}</span>
        )}
        <span className="notif-card__time">{time}</span>
        {hasBody && <span className="notif-card__toggle">{expanded ? '▼' : '▶'}</span>}
      </div>
      {expanded && hasBody && (
        <div className="notif-card__body">
          <MarkdownBlock markdown={body} />
        </div>
      )}
    </div>
  )
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  const m = Math.floor(ms / 60000)
  const s = Math.round((ms % 60000) / 1000)
  return `${m}m${s}s`
}
