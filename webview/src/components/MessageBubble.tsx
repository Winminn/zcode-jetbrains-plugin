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
 *
 * 完成轮折叠（对齐官方客户端）：非流式的完整轮默认只渲染最终结论（最后一个 text），
 * 执行过程不进 DOM；结论上方渲染「执行过程」折叠栏（概览：思考/工具计数 + 轮次耗时），
 * 点击展开/收起。搜索面板打开时强制展开全部过程（searchActive），
 * 保会话内搜索 TreeWalker 能扫到/定位到过程文本。
 */

import { memo, useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import type { ZCodeMessage, MessagePart, TextPart, ImagePart, FilePart } from '@/types/messages'
import { useStore } from '@/store/useStore'
import { renderUserRefChips, hasUserRefChips, type CmdRefInfo } from '@/utils/userRefChips'
import { MarkdownBlock } from './MarkdownBlock'
import { AgentNotificationCard } from './AgentNotificationCard'
import { isAgentNotification, isCompactSummaryMessage, findTimelinePart } from '@/utils/parseNotification'
import { clockTime, compactTokens, formatDuration } from '@/utils/time'
import { readTurnCollapseConfig } from '@/utils/turnCollapseConfig'
import { KV_HYDRATED_EVENT } from '@/utils/persist'
import { useTick } from '@/hooks/useTick'
import { copyText, useCopyFeedback } from '@/utils/clipboard'
import { ScrollJumpButton } from './ScrollJumpButton'
import { CompactionSummaryCard } from './CompactionSummaryCard'
import { TimelineSeparator } from './TimelineSeparator'
import { ConfirmDialog } from './ConfirmDialog'
import {
  collectImageParts,
  imagePartSrc,
  imagePartTitle,
  MessageImage,
  renderPartUnits,
} from './PartUnits'
import { ImagePreview } from './ImagePreview'
import { groupParts } from '@/utils/groupParts'
import type { EditAttachmentInput } from '@/utils/editHistory'
import '../styles/message-bubble.less'

interface Props {
  message: ZCodeMessage
  /** 是否正在流式（用于打字机光标 + 思考自动展开）*/
  streaming?: boolean
  /** user 消息的锚点 id（供 MessageAnchorRail 定位，assistant 不传）*/
  anchorAttr?: string
  /** 会话内搜索面板激活（长用户消息临时展开，保 TreeWalker 高亮/定位）*/
  searchActive?: boolean
  /** 该 user 消息是最后一轮可编辑消息（编辑按钮的显示条件；官方 Edit History 语义）*/
  editable?: boolean
}

export const MessageBubble = memo(function MessageBubble({ message, streaming, anchorAttr, searchActive, editable }: Props) {
  const { info, parts } = message
  const isUser = info.role === 'user'
  const time = clockTime(info.time?.created)
  // 已发定时记录：服务端读回的消息不带定时标记，按下匹配补「定时执行」徽标
  const firedHistory = useStore((s) => s.firedHistory)
  // 引导（steer）注入的用户消息：气泡带「⚡引导」徽标（kv 持久化，跨重拉/重启）
  const isSteered = useStore((s) => s.steeredMessageIds.includes(info.id))

  // 子 agent / 任务回调的合成通知（role 是 user 但 synthetic）：独立卡片渲染，不当用户消息
  if (isAgentNotification(info)) {
    return <AgentNotificationCard message={message} time={time} />
  }
  // 压缩摘要消息（role=user + info.summary）：折叠卡片，不当用户气泡
  // （消息级无 synthetic 标记，isHiddenSyntheticMessage 拦不住，必须在此分流）
  // anchorAttr 透传挂 data-anchor-msg：锚点轨道压缩徽章/历史弹窗点击跳转落点
  if (isCompactSummaryMessage(info)) {
    return <CompactionSummaryCard message={message} time={time} anchorAttr={anchorAttr} />
  }
  // 时间线分隔符消息（assistant + timeline part）：无气泡结构的横线分隔卡，
  // 此前 timeline part 不被识别渲染成"只有耗时行的空壳气泡"
  const timelinePart = findTimelinePart(parts)
  if (timelinePart) {
    return <TimelineSeparator part={timelinePart} />
  }
  // 非流式空壳 assistant（零内容零 token）：goal 记账/校验轮 turn.started 建
  // 立的乐观消息，真身（纯 timeline 消息）被增量合并滤掉时残留，渲染成
  // "已工作 0 秒 / 0 in 0 out" 的 footer 空壳（0.3.2 真机反馈）——无可读内容
  // 直接不渲染（流式中的空消息是"思考中"占位，保留）
  if (!isUser && !streaming && (parts ?? []).length === 0) {
    return null
  }
  if (isUser) {
    const userText = collectUserText(parts)
    // 乐观消息自带 scheduledFireAt（真发时本地构造）；服务端读回（历史重拉/后台直发打开标签）
    // 不带任何定时标记，按 sessionId+text 从 Java 已发记录匹配还原
    const firedAt =
      typeof info.scheduledFireAt === 'number'
        ? (info.scheduledFireAt as number)
        : firedHistory.find((f) => f.sessionId === info.sessionID && f.text === userText)?.fireAt
    return (
      <UserBubble
        text={userText}
        imageParts={collectImageParts(parts)}
        time={time}
        anchorAttr={anchorAttr}
        searchActive={searchActive}
        scheduledFireAt={firedAt}
        steered={isSteered}
        messageId={info.id}
        editable={editable}
      />
    )
  }
  return <AssistantBubble message={message} time={time} streaming={streaming} searchActive={searchActive} />
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
  imageParts,
  time,
  anchorAttr,
  searchActive,
  scheduledFireAt,
  steered,
  messageId,
  editable,
}: {
  text: string
  imageParts: Array<ImagePart | FilePart>
  time: string
  anchorAttr?: string
  searchActive?: boolean
  /** 定时消息标记（fireAt）：发出后气泡上带「定时执行」徽标（历史重拉不带，预期）*/
  scheduledFireAt?: number
  /** 引导（steer）注入标记：气泡带「⚡引导」徽标（kv 持久化，历史重拉仍在）*/
  steered?: boolean
  /** 服务端消息 id（编辑目标锚定 + 编辑态判定）；乐观消息为 local_u_ 前缀 */
  messageId?: string
  /** 最后一轮可编辑消息（官方 Edit History：仅最后一轮用户消息可编辑）*/
  editable?: boolean
}) {
  const { t } = useTranslation()
  const [showFull, setShowFull] = useState(false)
  // 多图预览：点击任一张在整个图片组内打开（overlay 内左右切换），null=关闭
  const [previewIdx, setPreviewIdx] = useState<number | null>(null)
  // 引用 chip 化显示（@路径 / #会话引用 与输入框同视觉）：默认开，「显示原文」切回纯文本。
  // 判据与解析同源（utils/userRefChips），无引用的普通消息零差异
  const [showRaw, setShowRaw] = useState(false)
  const sessions = useStore((s) => s.sessions)
  const slashCommands = useStore((s) => s.slashCommands)
  const lines = useMemo(() => text.split('\n').length, [text])
  const collapsible = lines >= USER_COLLAPSE_LINES || text.length >= USER_COLLAPSE_CHARS
  // 搜索面板激活时强制展开：高亮 mark 与 scrollIntoView 定位需要全文可见
  const collapsed = collapsible && !searchActive
  const hasImages = imageParts.length > 0
  // 会话 chip 标题反查（裸 token 场景）；selector 返回原数组引用（zustand 纪律）
  const titleResolver = useMemo(() => {
    const map = new Map(sessions.map((s) => [s.sessionId, s.title]))
    return (id: string) => map.get(id)
  }, [sessions])
  // /命令·技能引用识别清单：slashCommands（含 kind）+ 内置 goal（扫描器不列，下拉注入同款）。
  // 内置命令以命令名作 kind（与输入框内联 chip 同规则：compact→fold 专属图标；
  // 未配的名如 init 兜底 terminal），磁盘命令/技能走通用 kind
  const cmdNames = useMemo(() => {
    const map = new Map<string, CmdRefInfo>()
    slashCommands?.forEach((c) =>
      map.set(c.name, {
        kind: c.kind === 'command' && c.source === 'builtin' ? (c.name as CmdRefInfo['kind']) : c.kind,
        icon: c.icon,
      }),
    )
    if (!map.has('goal')) map.set('goal', { kind: 'goal' })
    return map
  }, [slashCommands])
  const refChips = useMemo(
    () => (showRaw ? null : renderUserRefChips(text, titleResolver, cmdNames)),
    [text, titleResolver, cmdNames, showRaw],
  )
  const hasRefChips = useMemo(() => hasUserRefChips(text, cmdNames), [text, cmdNames])
  const images = useMemo(
    () =>
      imageParts
        .map((img, i) => ({
          src: imagePartSrc(img),
          key: img.id ?? `${img.type}-${i}`,
          title: imagePartTitle(img),
        }))
        .filter((x): x is { src: string; key: string; title: string | undefined } => !!x.src),
    [imageParts],
  )
  // 编辑态锚定本消息（store 级状态：提交/取消在 EditComposer 内完成）
  const editing = useStore((s) => !!messageId && s.editingMessageId === messageId)
  const { state: copyState, showResult } = useCopyFeedback(1200)
  const onCopy = () => { void showResult(() => copyText(text)) }

  if (editing) {
    return (
      <EditComposer
        initialText={text}
        lines={lines}
        initialImages={imageParts.filter(
          (p): p is FilePart | ImagePart =>
            (p.type === 'file' && !!p.url) || (p.type === 'image' && !!p.dataBase64),
        )}
      />
    )
  }

  return (
    <div className="msg msg--user" data-anchor-msg={anchorAttr}>
      <div className="msg__time">
        {time}
        {scheduledFireAt != null && (
          <span className="msg__schedule-badge" title={t('input.schedule.firedBadge')}>
            <span className="codicon codicon-clockface" />
            {t('input.schedule.firedBadge')}
          </span>
        )}
        {steered && (
          <span className="msg__steer-badge" title={t('input.steer.badgeTitle')}>
            <span className="codicon codicon-zap" />
            {t('input.steer.badge')}
          </span>
        )}
      </div>
      <div className={`msg__bubble${collapsed ? ' msg__bubble--collapsed' : ''}`}>
        {hasImages && (
          <div className="msg__images">
            {images.map((img, i) => (
              <MessageImage key={img.key} src={img.src} title={img.title} onOpen={() => setPreviewIdx(i)} />
            ))}
          </div>
        )}
        {text ? (refChips ?? text) : hasImages ? null : t('chat.message.emptyText')}
        {collapsed && (
          <button type="button" className="msg__expand" onClick={() => setShowFull(true)}>
            <span className="codicon codicon-unfold" />
            {t('chat.message.viewFull', { lines, count: text.length })}
          </button>
        )}
      </div>
      <div className="msg__actions">
        {/* 引用 chip 化的消息才显示「显示原文」切换（普通消息零噪音）*/}
        {hasRefChips && (
          <button
            type="button"
            className={`msg__action-btn${showRaw ? ' msg__action-btn--active' : ''}`}
            onClick={() => setShowRaw((v) => !v)}
            title={showRaw ? t('chat.message.showRefs') : t('chat.message.showRaw')}
            aria-label={showRaw ? t('chat.message.showRefs') : t('chat.message.showRaw')}
          >
            <span className="codicon codicon-code" />
          </button>
        )}
        <button
          type="button"
          className="msg__action-btn"
          onClick={onCopy}
          disabled={!text}
          title={copyState === 'ok' ? t('chat.message.copyCopied') : t('chat.message.copy')}
          aria-label={t('chat.message.copy')}
        >
          <span className={`codicon ${copyState === 'ok' ? 'codicon-check msg__action-btn--ok' : 'codicon-copy'}`} />
        </button>
        {editable && (
          <button
            type="button"
            className="msg__action-btn"
            onClick={() => useStore.getState().startEdit()}
            title={t('chat.message.edit')}
            aria-label={t('chat.message.edit')}
          >
            <span className="codicon codicon-edit" />
          </button>
        )}
      </div>
      {showFull && (
        <UserTextPreviewDialog
          text={text}
          lines={lines}
          images={images}
          onClose={() => setShowFull(false)}
        />
      )}
      {previewIdx != null && images[previewIdx] && (
        <ImagePreview
          images={images}
          initialIndex={previewIdx}
          onClose={() => setPreviewIdx(null)}
        />
      )}
    </div>
  )
}

/** 编辑器图片附件条目：cache=原消息图片（FilePart，url 即 image-cache 映射），inline=编辑时新粘贴 */
interface EditImageChip {
  key: string
  kind: 'cache' | 'inline'
  url?: string
  dataBase64?: string
  dataUrl?: string
  mime: string
  fileName: string
}

/**
 * 用户消息行内编辑器（官方 Edit History 形态：原消息展开为可编辑输入框）。
 * 图片附件以 chip 形态随编辑（可移除、可粘贴新增，对齐官方编辑器的附件保留语义）；
 * chip 双形态：cache=历史消息 file part（保留时引用 image-cache 磁盘文件），
 * inline=刚发出消息的本地 dataUrl（乐观未重拉，回合中编辑；保留时 dataBase64
 * 走临时文件 ref）。提交走 store.submitEdit（v4 editUserQuery / rewind + 重发
 * 编排）；Enter 提交、Shift+Enter 换行、Esc 取消（对齐 InputBox 键位）；IME
 * 组合中的 Enter 不当提交。
 */
function EditComposer({
  initialText,
  initialImages,
  lines,
}: {
  initialText: string
  initialImages: Array<FilePart | ImagePart>
  lines: number
}) {
  const { t } = useTranslation()
  const [value, setValue] = useState(initialText)
  const [chips, setChips] = useState<EditImageChip[]>(() =>
    initialImages.map((p, i) =>
      p.type === 'file'
        ? {
            key: p.id ?? `img-${i}`,
            kind: 'cache' as const,
            url: p.url!,
            mime: p.mime ?? 'image/png',
            fileName: p.filename ?? 'image.png',
          }
        : {
            key: p.id ?? `img-${i}`,
            kind: 'inline' as const,
            dataBase64: p.dataBase64!,
            dataUrl: p.dataUrl ?? `data:${p.mediaType ?? 'image/png'};base64,${p.dataBase64}`,
            mime: p.mediaType ?? 'image/png',
            fileName: 'image.png',
          },
    ),
  )
  const ref = useRef<HTMLTextAreaElement>(null)
  const submitEdit = useStore((s) => s.submitEdit)
  const cancelEdit = useStore((s) => s.cancelEdit)
  // chip 点击放大（复用消息态的 ImagePreview；Esc 逐层让位见 onKeyDown）
  const [previewIdx, setPreviewIdx] = useState<number | null>(null)

  // 挂载即聚焦：光标置于末尾（保留全选改写的可能——用户直接输入即整体替换的
  // 常见编辑动线由「全选」自行触发，不做预设）
  useEffect(() => {
    const el = ref.current
    if (!el) return
    el.focus()
    el.setSelectionRange(el.value.length, el.value.length)
  }, [])

  const submit = () => {
    const hasText = !!value.trim()
    if (!hasText && chips.length === 0) return
    // 全量清单语义：原消息带图而 chips 已清空 → 显式传 []（编辑时删光图片）；
    // 原消息无图也未新增 → undefined（Java 不带 attachments 字段）
    const images: EditAttachmentInput[] | undefined = chips.length
      ? chips.map((c) =>
          c.kind === 'cache'
            ? { source: 'cache' as const, url: c.url!, mime: c.mime, fileName: c.fileName }
            : { source: 'inline' as const, dataBase64: c.dataBase64!, mime: c.mime, fileName: c.fileName },
        )
      : initialImages.length > 0
        ? []
        : undefined
    submitEdit(value, images)
  }

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Escape') {
      // 预览打开时 Esc 先关预览（ImagePreview 的 document 级监听负责），不连带取消编辑
      if (previewIdx != null) return
      e.preventDefault()
      cancelEdit()
      return
    }
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      submit()
    }
  }

  // 粘贴图片 → 新增 chip（与 InputBox 粘贴图同动线；非图片粘贴放行默认行为）
  const onPaste = (e: React.ClipboardEvent<HTMLTextAreaElement>) => {
    const files = Array.from(e.clipboardData?.files ?? []).filter((f) => f.type.startsWith('image/'))
    if (files.length === 0) return
    e.preventDefault()
    void Promise.all(
      files.map(
        (f) =>
          new Promise<EditImageChip | null>((resolve) => {
            const reader = new FileReader()
            reader.onload = () => {
              const dataUrl = typeof reader.result === 'string' ? reader.result : ''
              const dataBase64 = dataUrl.split(',')[1] ?? ''
              if (!dataBase64) return resolve(null)
              resolve({
                key: `paste-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
                kind: 'inline',
                dataUrl,
                dataBase64,
                mime: f.type,
                fileName: f.name || 'image.png',
              })
            }
            reader.onerror = () => resolve(null)
            reader.readAsDataURL(f)
          }),
      ),
    ).then((added) => {
      const ok = added.filter((x): x is EditImageChip => !!x)
      if (ok.length) setChips((cur) => [...cur, ...ok])
    })
  }

  const rows = Math.min(Math.max(lines + 1, 3), 14)
  return (
    <div className="msg msg--user msg--editing">
      {chips.length > 0 && (
        <div className="msg__edit-images">
          {chips.map((c, i) => (
            <span
              key={c.key}
              className="msg__edit-image msg__edit-image--zoomable"
              title={c.fileName}
              onClick={() => setPreviewIdx(i)}
            >
              <img src={c.kind === 'cache' ? c.url : c.dataUrl} alt={c.fileName} />
              <button
                type="button"
                className="msg__edit-image-remove"
                title={t('chat.edit.removeImage')}
                aria-label={t('chat.edit.removeImage')}
                onClick={(e) => {
                  e.stopPropagation()
                  setChips((cur) => cur.filter((x) => x.key !== c.key))
                }}
              >
                <span className="codicon codicon-close" />
              </button>
            </span>
          ))}
        </div>
      )}
      <textarea
        ref={ref}
        className="msg__edit-textarea"
        value={value}
        rows={rows}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={onKeyDown}
        onPaste={onPaste}
        aria-label={t('chat.message.edit')}
      />
      <div className="msg__edit-actions">
        <button type="button" className="msg__edit-btn msg__edit-btn--ghost" onClick={cancelEdit}>
          {t('chat.message.editCancel')}
        </button>
        <button
          type="button"
          className="msg__edit-btn msg__edit-btn--primary"
          onClick={submit}
          disabled={!value.trim() && chips.length === 0}
        >
          <span className="codicon codicon-send" />
          {t('chat.message.editRegenerate')}
        </button>
      </div>
      {previewIdx != null && chips[previewIdx] && (
        <ImagePreview
          images={chips.map((c) => ({
            src: c.kind === 'cache' ? c.url! : c.dataUrl!,
            key: c.key,
            title: c.fileName,
          }))}
          initialIndex={previewIdx}
          onClose={() => setPreviewIdx(null)}
        />
      )}
    </div>
  )
}

/**
 * 用户消息全文弹窗。portal 挂 body（脱离 messages-container），
 * 避免全文 pre/文本进入会话内搜索的 TreeWalker 与代码块匹配范围。
 */
/**
 * 用户消息全文弹窗（长文折叠「查看全文」入口）。骨架复用上下文压缩摘要弹窗的
 * subagent-detail 系类名（760px 宽弹窗，2026-09-12 用户反馈替换旧 text-preview-dialog
 * 形态）：带图消息在正文顶部渲染图片网格（点击 ImagePreview 放大，Esc 逐层让位），
 * 文本保持 pre 原文形态。portal 挂 body（脱离 messages-container），避免全文进入
 * 会话内搜索的 TreeWalker 与代码块匹配范围。
 */
function UserTextPreviewDialog({
  text,
  lines,
  images,
  onClose,
}: {
  text: string
  lines: number
  images: { src: string; key: string; title: string | undefined }[]
  onClose: () => void
}) {
  const { t } = useTranslation()
  const bodyRef = useRef<HTMLDivElement>(null)
  const [previewIdx, setPreviewIdx] = useState<number | null>(null)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      // 图片预览打开时 Esc 先关预览（其 document 级监听负责），不连带关弹窗
      if (e.key === 'Escape' && previewIdx == null) onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose, previewIdx])

  return createPortal(
    <div className="subagent-detail-overlay" role="presentation" onClick={onClose}>
      <div
        className="subagent-detail-dialog msg-fulltext"
        role="dialog"
        aria-label={t('chat.message.fullTextAria')}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="subagent-detail-header">
          <span className="codicon codicon-comment-discussion subagent-detail-header__icon" />
          <div className="subagent-detail-header__main">
            <span className="subagent-detail-header__title">
              {t('chat.message.fullTextTitle', { lines, count: text.length })}
            </span>
          </div>
          <button
            className="subagent-detail-icon-btn"
            onClick={onClose}
            title={t('chat.message.closeFull')}
            aria-label={t('chat.message.closeFull')}
            type="button"
          >
            <span className="codicon codicon-chrome-close" />
          </button>
        </div>
        <div ref={bodyRef} className="subagent-detail-body msg-fulltext__body">
          {images.length > 0 && (
            <div className="msg__images msg-fulltext__images">
              {images.map((img, i) => (
                <MessageImage
                  key={img.key}
                  src={img.src}
                  title={img.title}
                  onOpen={() => setPreviewIdx(i)}
                />
              ))}
            </div>
          )}
          <pre className="msg-fulltext__text">{text}</pre>
        </div>
        <ScrollJumpButton containerRef={bodyRef} />
        {previewIdx != null && images[previewIdx] && (
          <ImagePreview
            images={images}
            initialIndex={previewIdx}
            onClose={() => setPreviewIdx(null)}
          />
        )}
      </div>
    </div>,
    document.body,
  )
}

/**
 * 完成轮折叠判定（对齐官方客户端：已完成轮默认只展示最终结论，点「已工作 X」展开过程）。
 *
 * 「最终结论」= 最后一个 text part；结论之前存在可见过程（reasoning/tool/中间 text/图）
 * 即可折叠。结论之后可能挂收尾动作（模型总结后又做的工具调用/思考，如收纳浏览器面板）——
 * 不阻断折叠，保留在结论之后渲染（折的是结论之前的过程）。
 * 折叠态只渲染结论+尾部，过程不进 DOM（性能收益 + 老会话减负）；
 * 会话内搜索靠 searchActive 强制展开兜底（与 UserBubble 长文折叠同策略）。
 */
const PROCESS_TYPES: ReadonlySet<string> = new Set(['text', 'reasoning', 'tool', 'image', 'file'])

function turnCollapseInfo(parts: MessagePart[]): { lastTextIdx: number; collapsible: boolean } {
  let lastTextIdx = -1
  for (let i = parts.length - 1; i >= 0; i--) {
    if (parts[i].type === 'text') {
      lastTextIdx = i
      break
    }
  }
  if (lastTextIdx < 0) return { lastTextIdx, collapsible: false }
  return { lastTextIdx, collapsible: parts.slice(0, lastTextIdx).some((p) => PROCESS_TYPES.has(p.type)) }
}

/**
 * 「自动折叠执行过程」配置读取。挂载即读（设置视图切换重挂 ChatView 已覆盖
 * 改设置后的回显）；KV 水合事件兜底启动竞态（水合前读到默认值），storage
 * 事件兜底多标签同步。
 */
function useAutoCollapseConfig(): boolean {
  const [auto, setAuto] = useState(() => readTurnCollapseConfig().autoCollapse)
  useEffect(() => {
    const refresh = () => setAuto(readTurnCollapseConfig().autoCollapse)
    window.addEventListener(KV_HYDRATED_EVENT, refresh)
    window.addEventListener('storage', refresh)
    return () => {
      window.removeEventListener(KV_HYDRATED_EVENT, refresh)
      window.removeEventListener('storage', refresh)
    }
  }, [])
  return auto
}

/** assistant 消息：左对齐，满宽 markdown + 工具卡片 + 思考 */
function AssistantBubble({
  message,
  time,
  streaming,
  searchActive,
}: {
  message: ZCodeMessage
  time: string
  streaming?: boolean
  searchActive?: boolean
}) {
  const { info, parts } = message

  // 分叉（B2 一期）：入口在 footer「已工作」行——fork 锚点是已完成的回复（保留到该回复含，
  // 从这条回复之后岔出去试另一方案），未获回答的用户消息没有分叉价值；
  // 本条消息流式中/本地乐观消息不显示（分叉中间态无意义；其余历史轮回合中照常可分叉，
  // diag-fork29 实测服务端受理且快照完整）；
  // 老 CLI 无 v4 面（forkSupported=false）隐藏。通道=v4 forkAssistant（官方同款，零文件操作）
  const [confirmFork, setConfirmFork] = useState(false)
  const forkBusy = useStore((s) => s.forkBusy)
  const forkSupported = useStore((s) => s.forkSupported)
  const forkable =
    forkSupported &&
    !streaming &&
    !!info.sessionID &&
    !!info.id &&
    !info.id.startsWith('stream_local_') &&
    !info.id.startsWith('local_')
  const { t } = useTranslation()

  // 连续 Bash 命令聚组（cc-gui groupBlocks 规则）：压缩批量命令的消息区长度。
  // 分组保留原始 part 下标，reasoning 自动展开/流式判定的 index 语义不变
  const units = useMemo(() => groupParts(parts), [parts])
  // 单元渲染走共享管线（与子代理弹窗共用，含组卡/单卡/reasoning 自动展开推导）；
  // 折叠态只渲染尾部单元（结论后的收尾动作），推导仍基于完整 parts
  const renderedUnits = useMemo(() => renderPartUnits(units, parts, streaming), [units, parts, streaming])

  // 完成轮折叠（流式期间全渲染，turn 结束起默认只留结论）；
  // 「自动折叠执行过程」设置控制默认态，手动点折叠栏的意图优先于设置；
  // 搜索面板激活时强制展开，保 TreeWalker 能扫到过程文本。
  // 折的是结论之前的过程；结论之后挂的收尾动作（工具/思考）不折，保留在结论后面
  const { lastTextIdx, collapsible } = useMemo(() => turnCollapseInfo(parts), [parts])
  const autoCollapse = useAutoCollapseConfig()
  const [manualExpand, setManualExpand] = useState<boolean | null>(null)
  // 设置开 → 默认收起；手动点击过的意图（非 null）优先于设置默认值
  const expanded = manualExpand ?? !autoCollapse
  const collapsed = collapsible && !streaming && !expanded && !searchActive
  // 折叠栏概览只统计结论之前的过程（尾部收尾动作不折，不计数）
  const processParts = useMemo(
    () => (collapsible ? parts.slice(0, lastTextIdx) : parts),
    [collapsible, lastTextIdx, parts],
  )
  // 折叠态下保留的尾部单元：整组/单个 part 全部落在结论之后（工具组是连续同类
  // tool 的极大游程，text 不在其中，不会出现跨越结论的组）
  const renderedTailUnits = useMemo(
    () =>
      collapsible
        ? renderPartUnits(
            units.filter((u) => (u.kind === 'toolGroup' ? u.startIndex : u.index) > lastTextIdx),
            parts,
            streaming,
          )
        : [],
    [collapsible, lastTextIdx, units, parts, streaming],
  )
  // 折叠栏概览的轮次耗时：服务端权威值（completed - created）；重拉窗口缺 completed 就不显示
  const processMs =
    collapsible && info.time?.created && info.time.completed
      ? info.time.completed - info.time.created
      : null

  return (
    <div className="msg msg--assistant">
      <div className="msg__content">
        {collapsible && !streaming && (
          <TurnProcessBar
            parts={processParts}
            collapsed={collapsed}
            durationMs={processMs}
            onToggle={() => setManualExpand(!expanded)}
          />
        )}
        {collapsed ? (
          <>
            <MarkdownBlock markdown={(parts[lastTextIdx] as TextPart).text} />
            {renderedTailUnits}
          </>
        ) : (
          renderedUnits
        )}
      </div>
      <MessageFooter
        info={info}
        time={time}
        streaming={streaming}
        copy={!streaming ? collectAssistantMarkdown(parts) : undefined}
        fork={forkable ? { busy: forkBusy, onClick: () => setConfirmFork(true) } : undefined}
      />
      {confirmFork && (
        <ConfirmDialog
          title={t('chat.fork.confirmTitle')}
          message={t('chat.fork.confirmMessage')}
          confirmText={t('chat.fork.confirmOk')}
          onConfirm={() => {
            setConfirmFork(false)
            useStore.getState().forkFromMessage(info.sessionID, info.id)
          }}
          onCancel={() => setConfirmFork(false)}
        />
      )}
    </div>
  )
}

/**
 * 完成轮折叠栏（对齐官方客户端位置：用户消息之后、最终结论之前）：
 * 「▸ 执行过程 · 思考 N 次 · N 个工具 · X 分 Y 秒」概览条，点击展开/收起执行过程。
 * 展开后条仍在过程顶部，随时可收起——不需要滚到消息底部找按钮。
 */
function TurnProcessBar({
  parts,
  collapsed,
  durationMs,
  onToggle,
}: {
  parts: MessagePart[]
  collapsed: boolean
  durationMs: number | null
  onToggle: () => void
}) {
  const { t } = useTranslation()
  const tools = parts.reduce((n, p) => (p.type === 'tool' ? n + 1 : n), 0)
  const thoughts = parts.reduce((n, p) => (p.type === 'reasoning' ? n + 1 : n), 0)
  const items: string[] = []
  if (thoughts > 0) items.push(t('chat.message.processThoughts', { count: thoughts }))
  if (tools > 0) items.push(t('chat.message.processTools', { count: tools }))
  if (durationMs != null) items.push(formatDuration(durationMs))
  return (
    <button type="button" className="msg__process-bar" onClick={onToggle}>
      <span className={`codicon codicon-chevron-${collapsed ? 'right' : 'down'}`} aria-hidden="true" />
      <span className="msg__process-bar-label">{t('chat.message.processLabel')}</span>
      {items.length > 0 && <span className="msg__process-bar-meta">{items.join(' · ')}</span>}
    </button>
  )
}

/** user 消息的文本收集：把所有 text part 合并 */
function collectUserText(parts: MessagePart[]): string {
  return parts
    .filter((p): p is TextPart => p.type === 'text')
    .map((p) => p.text)
    .join('\n')
}

/**
 * assistant 回复的 markdown 源文收集（「复制 Markdown」用）：
 * 各 text part 是独立段落，双换行连接防止相邻段落粘连；
 * 只汇总正文文本，工具调用过程不混入
 */
function collectAssistantMarkdown(parts: MessagePart[]): string {
  return parts
    .filter((p): p is TextPart => p.type === 'text')
    .map((p) => p.text)
    .join('\n\n')
}

/**
 * assistant 消息底部：时间 + 轮次耗时 + token 信息
 *
 * 轮次耗时（对齐 cc-gui）：
 *   - 流式中：⏱ 工作中 X 秒（每秒跳动，起点 = 消息 created 即 turn.started）
 *   - 已完成：⏱ 已工作 X 分 Y 秒（completed - created，服务端权威值）
 *   - turn 结束 → 重拉消息之间有短暂窗口缺 completed，用最后一次跳动值冻结过渡
 */
function MessageFooter({
  info,
  time,
  streaming,
  copy,
  fork,
}: {
  info: ZCodeMessage['info']
  time: string
  streaming?: boolean
  /** 复制 Markdown 按钮（整条回复 markdown 源文；undefined=不渲染——流式中/无文本）*/
  copy?: string
  /** 分叉按钮（footer 行右侧，hover 显示；undefined=不渲染——流式中/乐观消息）*/
  fork?: { busy: boolean; onClick: () => void }
}) {
  const { t } = useTranslation()
  const { state: copyState, showResult: showCopyResult } = useCopyFeedback(1200)
  const onCopyMarkdown = () => {
    if (copy) void showCopyResult(() => copyText(copy))
  }
  const tokens = info.tokens
  // v1 大写 D（modelID）；v2 服务端改小写驼峰（modelId，db 实测字段重命名）——双读兼容
  const model = info.modelID ?? info.modelId

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
        <span
          className="msg__footer-tokens"
          title={`${tokens.input.toLocaleString()} in / ${tokens.output.toLocaleString()} out`}
        >
          💡 {compactTokens(tokens.input)} in / {compactTokens(tokens.output)} out
          {tokens.cache?.read ? ` · ${t('chat.message.cachePercent', { percent: Math.round((tokens.cache.read / tokens.input) * 100) })}` : ''}
        </span>
      )}
      {info.cost ? <span className="msg__footer-cost">${info.cost.toFixed(4)}</span> : null}
      {copy && (
        <button
          type="button"
          className="msg__action-btn msg__footer-copy"
          onClick={onCopyMarkdown}
          title={copyState === 'ok' ? t('chat.message.copyCopied') : t('chat.message.copyMarkdown')}
          aria-label={t('chat.message.copyMarkdown')}
        >
          <span className={`codicon ${copyState === 'ok' ? 'codicon-check msg__action-btn--ok' : 'codicon-copy'}`} />
        </button>
      )}
      {fork && (
        <button
          type="button"
          className="msg__action-btn msg__footer-fork"
          onClick={fork.onClick}
          disabled={fork.busy}
          title={t('chat.message.fork')}
          aria-label={t('chat.message.fork')}
        >
          <span className="codicon codicon-git-branch" />
        </button>
      )}
    </div>
  )
}
