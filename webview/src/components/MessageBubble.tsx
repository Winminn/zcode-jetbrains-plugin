/**
 * 消息气泡
 *
 * 规划文档第四节：
 *   - user 消息：右对齐，蓝色气泡，纯文本（不走 markdown），时间戳在上方
 *   - assistant 消息：左对齐，无气泡背景，满宽 markdown 渲染
 *
 * part 渲染策略（基于抓包）：
 *   text     → MarkdownBlock（连续的 text part 合并）
 *   reasoning → ThinkingBlock（折叠）
 *   tool     → ToolCallCard（折叠）；连续同类聚组（见 utils/groupParts.ts）：
 *              Bash → BashCommandGroupCard（批量运行命令）
 *              Read/Edit/Write/Grep/Glob → FileToolGroupCard（批量读/编/搜）
 *   step-start / step-finish → 不渲染（边界标记）
 *
 * 规划文档第二节第 3 点（工具分组）：连续同类 tool part 合并成一个组（cc-gui 规则）。
 */

import { memo, useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import type { ZCodeMessage, MessagePart, TextPart } from '@/types/messages'
import { MarkdownBlock } from './MarkdownBlock'
import { ToolCallCard } from './ToolCallCard'
import { ThinkingBlock } from './ThinkingBlock'
import { AgentNotificationCard } from './AgentNotificationCard'
import { BashCommandGroupCard } from './BashCommandGroupCard'
import { FileToolGroupCard } from './FileToolGroupCard'
import { groupParts } from '@/utils/groupParts'
import { isAgentNotification, isCompactSummaryMessage, findTimelinePart } from '@/utils/parseNotification'
import { clockTime, formatDuration } from '@/utils/time'
import { useTick } from '@/hooks/useTick'
import { CompactionSummaryCard } from './CompactionSummaryCard'
import { TimelineSeparator } from './TimelineSeparator'
import '../styles/message-bubble.less'

interface Props {
  message: ZCodeMessage
  /** 是否正在流式（用于打字机光标 + 思考自动展开）*/
  streaming?: boolean
  /** user 消息的锚点 id（供 MessageAnchorRail 定位，assistant 不传）*/
  anchorAttr?: string
  /** 会话内搜索面板激活（长用户消息临时展开，保 TreeWalker 高亮/定位）*/
  searchActive?: boolean
}

export const MessageBubble = memo(function MessageBubble({ message, streaming, anchorAttr, searchActive }: Props) {
  const { info, parts } = message
  const isUser = info.role === 'user'
  const time = clockTime(info.time?.created)

  // 子 agent / 任务回调的合成通知（role 是 user 但 synthetic）：独立卡片渲染，不当用户消息
  if (isAgentNotification(info)) {
    return <AgentNotificationCard message={message} time={time} />
  }
  // 压缩摘要消息（role=user + info.summary）：折叠卡片，不当用户气泡
  // （消息级无 synthetic 标记，isHiddenSyntheticMessage 拦不住，必须在此分流）
  if (isCompactSummaryMessage(info)) {
    return <CompactionSummaryCard message={message} time={time} />
  }
  // 时间线分隔符消息（assistant + timeline part）：无气泡结构的横线分隔卡，
  // 此前 timeline part 不被识别渲染成"只有耗时行的空壳气泡"
  const timelinePart = findTimelinePart(parts)
  if (timelinePart) {
    return <TimelineSeparator part={timelinePart} />
  }
  if (isUser) {
    return <UserBubble text={collectUserText(parts)} time={time} anchorAttr={anchorAttr} searchActive={searchActive} />
  }
  return <AssistantBubble message={message} time={time} streaming={streaming} />
})

/** 用户消息长文折叠阈值（对齐 InputBox 粘贴折叠 PASTE_* 常量：≥10 行或 ≥500 字符）*/
const USER_COLLAPSE_LINES = 10
const USER_COLLAPSE_CHARS = 500

/**
 * user 消息：纯文本，右对齐蓝色气泡。
 *
 * 长文折叠：全文仍完整渲染进 DOM（搜索 TreeWalker 依赖完整文本节点），
 * 仅用 max-height+渐隐做视觉折叠；点击按钮 portal 弹窗看全文。
 */
function UserBubble({
  text,
  time,
  anchorAttr,
  searchActive,
}: {
  text: string
  time: string
  anchorAttr?: string
  searchActive?: boolean
}) {
  const { t } = useTranslation()
  const [showFull, setShowFull] = useState(false)
  const lines = useMemo(() => text.split('\n').length, [text])
  const collapsible = lines >= USER_COLLAPSE_LINES || text.length >= USER_COLLAPSE_CHARS
  // 搜索面板激活时强制展开：高亮 mark 与 scrollIntoView 定位需要全文可见
  const collapsed = collapsible && !searchActive

  return (
    <div className="msg msg--user" data-anchor-msg={anchorAttr}>
      <div className="msg__time">{time}</div>
      <div className={`msg__bubble${collapsed ? ' msg__bubble--collapsed' : ''}`}>
        {text || t('chat.message.emptyText')}
        {collapsed && (
          <button type="button" className="msg__expand" onClick={() => setShowFull(true)}>
            <span className="codicon codicon-unfold" />
            {t('chat.message.viewFull', { lines, count: text.length })}
          </button>
        )}
      </div>
      {showFull && (
        <UserTextPreviewDialog text={text} lines={lines} onClose={() => setShowFull(false)} />
      )}
    </div>
  )
}

/**
 * 用户消息全文弹窗。portal 挂 body（脱离 messages-container），
 * 避免全文 pre/文本进入会话内搜索的 TreeWalker 与代码块匹配范围。
 */
function UserTextPreviewDialog({
  text,
  lines,
  onClose,
}: {
  text: string
  lines: number
  onClose: () => void
}) {
  const { t } = useTranslation()

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  return createPortal(
    <div className="modal-overlay" role="presentation" onClick={onClose}>
      <div
        className="modal-content msg-fulltext"
        role="dialog"
        aria-label={t('chat.message.fullTextAria')}
        onClick={(e) => e.stopPropagation()}
      >
        <h3>{t('chat.message.fullTextTitle', { lines, count: text.length })}</h3>
        <pre className="msg-fulltext__body">{text}</pre>
        <div className="modal-actions">
          <button className="modal-btn modal-btn-primary" onClick={onClose} type="button">
            {t('chat.message.closeFull')}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  )
}

/** assistant 消息：左对齐，满宽 markdown + 工具卡片 + 思考 */
function AssistantBubble({ message, time, streaming }: { message: ZCodeMessage; time: string; streaming?: boolean }) {
  const { info, parts } = message
  // 找最后一个 reasoning part
  const lastReasoningIdx = (() => {
    for (let i = parts.length - 1; i >= 0; i--) {
      if (parts[i].type === 'reasoning') return i
    }
    return -1
  })()

  // 最后一个 reasoning 之后是否已有 text part（有则折叠 reasoning，没有则保持展开）
  const hasTextAfterLastReasoning = lastReasoningIdx >= 0
    ? parts.slice(lastReasoningIdx + 1).some((p) => p.type === 'text')
    : false

  // 流式时最后一个 part 是正在增长的
  const lastPartIdx = parts.length - 1

  // 连续 Bash 命令聚组（cc-gui groupBlocks 规则）：压缩批量命令的消息区长度。
  // 分组保留原始 part 下标，reasoning 自动展开/流式判定的 index 语义不变
  const units = useMemo(() => groupParts(parts), [parts])

  return (
    <div className="msg msg--assistant">
      <div className="msg__content">
        {units.map((unit) =>
          unit.kind === 'toolGroup' ? (
            unit.group === 'bash' ? (
              <BashCommandGroupCard key={`bash-${unit.startIndex}`} parts={unit.parts} />
            ) : (
              <FileToolGroupCard key={`${unit.group}-${unit.startIndex}`} kind={unit.group} parts={unit.parts} />
            )
          ) : (
            <PartRenderer
              key={unit.index}
              part={unit.part}
              // reasoning 自动展开：是最后一个 reasoning + 后面还没有正文
              autoExpandReasoning={unit.index === lastReasoningIdx && !hasTextAfterLastReasoning}
              streaming={!!streaming && unit.index === lastPartIdx}
            />
          ),
        )}
      </div>
      <MessageFooter info={info} time={time} streaming={streaming} />
    </div>
  )
}

/** 单个 part 的渲染分发 */
function PartRenderer({
  part,
  autoExpandReasoning,
  streaming,
}: {
  part: MessagePart
  autoExpandReasoning: boolean
  streaming: boolean
}) {
  switch (part.type) {
    case 'text':
      return <MarkdownBlock markdown={part.text} streaming={streaming} />

    case 'reasoning':
      // 自动展开：思考还在进行中或刚结束还没正文。正文出现后自动折叠。
      // streaming（本 part 是流式中最后一个 part）驱动思考耗时跳动计时
      return <ThinkingBlock part={part} autoExpand={autoExpandReasoning} streaming={streaming} />

    case 'tool':
      return <ToolCallCard part={part} />

    case 'step-start':
    case 'step-finish':
      return null

    default:
      return null
  }
}

/** user 消息的文本收集：把所有 text part 合并 */
function collectUserText(parts: MessagePart[]): string {
  return parts
    .filter((p): p is TextPart => p.type === 'text')
    .map((p) => p.text)
    .join('\n')
}

/**
 * assistant 消息底部：时间 + 轮次耗时 + token 信息
 *
 * 轮次耗时（对齐 cc-gui）：
 *   - 流式中：⏱ 工作中 X 秒（每秒跳动，起点 = 消息 created 即 turn.started）
 *   - 已完成：⏱ 已工作 X 分 Y 秒（completed - created，服务端权威值）
 *   - turn 结束 → 重拉消息之间有短暂窗口缺 completed，用最后一次跳动值冻结过渡
 */
function MessageFooter({ info, time, streaming }: { info: ZCodeMessage['info']; time: string; streaming?: boolean }) {
  const { t } = useTranslation()
  const tokens = info.tokens
  const model = info.modelID

  const now = useTick(!!streaming)
  const lastElapsedRef = useRef<number | null>(null)
  let durationMs: number | null = null
  let working = false
  const created = info.time?.created
  if (created) {
    if (info.time.completed) {
      durationMs = info.time.completed - created
    } else if (streaming) {
      working = true
      durationMs = now - created
      lastElapsedRef.current = durationMs
    } else {
      durationMs = lastElapsedRef.current
    }
  }

  return (
    <div className="msg__footer">
      <span className="msg__footer-time">{time}</span>
      {model && <span className="msg__footer-model">{model}</span>}
      {durationMs != null && (
        <span className={`msg__footer-duration${working ? ' msg__footer-duration--working' : ''}`}>
          ⏱ {working ? t('chat.message.working') : t('chat.message.worked')} {formatDuration(durationMs)}
        </span>
      )}
      {tokens && (
        <span className="msg__footer-tokens">
          💡 {tokens.input.toLocaleString()} in / {tokens.output.toLocaleString()} out
          {tokens.cache?.read ? ` · ${t('chat.message.cachePercent', { percent: Math.round((tokens.cache.read / tokens.input) * 100) })}` : ''}
        </span>
      )}
      {info.cost ? <span className="msg__footer-cost">${info.cost.toFixed(4)}</span> : null}
    </div>
  )
}
