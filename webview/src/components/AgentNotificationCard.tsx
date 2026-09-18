/**
 * 子 agent / 任务回调通知卡片
 *
 * 渲染 app-server 注入的合成通知消息（详见 utils/parseNotification.ts）。
 * 与普通用户消息区分：左对齐独立卡片。卡片只保留摘要行（图标/标题/状态/耗时/
 * tokens/时间），点击整行在通用 Markdown 阅读弹窗（markdownPreview）中查看
 * 成果全文——通知是完成时的离线快照，无需轮询；长文不再原地展开刷屏。
 *
 * 三种形态：
 *   - kind:'task' + subagent  后台子代理（run_in_background）完成，含 status/usage/result
 *   - kind:'task' + bash      后台 shell 命令（Bash run_in_background）完成——与子代理
 *                             共用 task-notification 通道，靠 resolveTaskSource 区分
 *   - kind:'message'          同步子代理中途回消息，含 agentType/message
 */

import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import type { ZCodeMessage, TextPart } from '@/types/messages'
import { useStore } from '@/store/useStore'
import {
  parseNotificationText,
  notificationTitle,
  resolveTaskSource,
  type ParsedNotification,
} from '@/utils/parseNotification'
import { compactTokens, formatToolDuration } from '@/utils/time'
import '../styles/notification-card.less'

interface Props {
  message: ZCodeMessage
  time: string
}

export function AgentNotificationCard({ message, time }: Props) {
  const { t } = useTranslation()
  const openMarkdownPreview = useStore((s) => s.openMarkdownPreview)
  const { info, parts } = message
  // 合成通知只有一个 text part，内容是 XML
  const textPart = parts.find((p): p is TextPart => p.type === 'text')
  const text = textPart?.text ?? ''

  const parsed = useMemo<ParsedNotification>(() => parseNotificationText(text), [text])
  const title = notificationTitle(info, parsed)
  // 后台 bash 命令与子代理共用 task-notification 通道，按执行体类型分流文案/图标
  const src = resolveTaskSource(info, parsed)
  const label = src === 'subagent' ? t('app.notification.subagent')
    : src === 'bash' ? t('app.notification.bashTask') : t('app.notification.backgroundTask')
  const icon =
    src === 'bash' ? 'codicon-terminal' : src === 'subagent' ? 'codicon-hubot' : 'codicon-bell'

  const body = parsed.kind === 'task' ? parsed.result : parsed.kind === 'message' ? parsed.message : ''
  const hasBody = !!body && body.trim().length > 0

  const usage = parsed.kind === 'task' ? parsed.usage : undefined
  // unknown（XML 解析不出）用中性徽标——兜底显示"完成"会误导读者（todo_reminder
  // 误判事故的放大器：识别层已收紧，这里再兜一层防御）
  const status = parsed.kind === 'task' ? parsed.status ?? 'completed'
    : parsed.kind === 'message' ? 'completed' : 'unknown'
  const badgeCls = status === 'completed' ? 'ok' : status === 'error' ? 'err' : 'info'
  const badgeText = status === 'completed' ? t('app.notification.completed')
    : status === 'error' ? t('app.notification.failed') : t('app.notification.notice')

  // 弹窗 meta 行：状态/耗时/tokens/agentType 与卡片头部同源信息
  const agentType = parsed.kind === 'message' ? parsed.agentType : undefined
  const previewMeta = [
    badgeText,
    usage?.durationMs != null ? formatToolDuration(usage.durationMs) : undefined,
    usage?.tokens != null ? `${compactTokens(usage.tokens)} tokens` : undefined,
    agentType,
  ].filter(Boolean).join(' · ')

  const openResult = () => {
    if (!hasBody) return
    openMarkdownPreview({ title, meta: previewMeta, markdown: body })
  }

  return (
    <div className={`notif-card notif-card--${badgeCls}`}>
      <div
        className={`notif-card__header${hasBody ? '' : ' notif-card__header--static'}`}
        onClick={openResult}
        role={hasBody ? 'button' : undefined}
        data-tip={hasBody ? t('app.notification.viewResult') : undefined}
      >
        <span className="notif-card__icon">
          <span className={`codicon ${icon}`} />
        </span>
        <span className="notif-card__label">{label}</span>
        <span className="notif-card__title" title={title}>{title}</span>
        <span className={`notif-card__badge notif-card__badge--${badgeCls}`}>{badgeText}</span>
        {usage?.durationMs != null && (
          <span className="notif-card__meta">⏱ {formatToolDuration(usage.durationMs)}</span>
        )}
        {usage?.tokens != null && (
          <span className="notif-card__meta" title={usage.tokens.toLocaleString()}>
            💡 {compactTokens(usage.tokens)}
          </span>
        )}
        {agentType && (
          <span className="notif-card__meta">{agentType}</span>
        )}
        <span className="notif-card__time">{time}</span>
        {hasBody && (
          <span className="notif-card__toggle">
            <span className="codicon codicon-chevron-right" />
          </span>
        )}
      </div>
    </div>
  )
}
