/**
 * /compact 压缩摘要卡片
 *
 * 压缩摘要消息（role=user + info.summary）的专门渲染（2026-08-21 RPC 实测）：
 * 不当用户气泡（8k+ 字符摘要塞右对齐蓝气泡的视觉灾难），改为左对齐折叠卡——
 * 头部一行元信息（标题 + 被摘要消息数 + token 收缩），正文默认折叠，
 * 点开渲染 summary.body 全文 markdown。
 *
 * token 元信息优先取 compaction part 的 compactBoundary（摘要消息自带），
 * 缺失时退化为纯标题行（boundary 是压缩点固有产物，正常都在）。
 */

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ZCodeMessage, CompactionPart } from '@/types/messages'
import { MarkdownBlock } from './MarkdownBlock'
import '../styles/compaction.less'

/** token 数缩写：287247 → 287k（分隔卡与摘要卡共用）*/
export function shortTokens(n: number): string {
  return n >= 1000 ? `${Math.round(n / 1000)}k` : String(n)
}

interface Props {
  message: ZCodeMessage
  time: string
}

export function CompactionSummaryCard({ message, time }: Props) {
  const { t } = useTranslation()
  const { info, parts } = message
  const body = info.summary?.body ?? ''
  // 默认折叠：摘要是给需要回溯细节的场合看的，收起保聊天区干净
  const [expanded, setExpanded] = useState(false)

  const boundary = parts.find(
    (p): p is CompactionPart => p.type === 'compaction',
  )?.compactBoundary
  const count = boundary?.summarizedMessageCount

  return (
    <div className="compact-card">
      <div
        className="compact-card__header"
        onClick={() => setExpanded((e) => !e)}
        role="button"
      >
        <span className="compact-card__icon">
          <span className="codicon codicon-compress" />
        </span>
        <span className="compact-card__label">{t('chat.compaction.title')}</span>
        {count != null && (
          <span className="compact-card__meta">
            {t('chat.compaction.messagesSummarized', { count })}
          </span>
        )}
        <span className="compact-card__time">{time}</span>
        <span className="compact-card__toggle">{expanded ? '▼' : '▶'}</span>
      </div>
      {expanded && body && (
        <div className="compact-card__body">
          <MarkdownBlock markdown={body} />
        </div>
      )}
    </div>
  )
}
