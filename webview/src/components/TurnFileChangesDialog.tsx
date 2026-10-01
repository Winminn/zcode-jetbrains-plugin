/**
 * 逐轮文件更改弹窗（B2 回合产物）
 *
 * 数据：v4/conversation/fileChanges（服务端权威账本——+/−行数与 unified diff hunks
 * 都由服务端算好直出，与底部状态栏的客户端 LCS 聚合口径互补：这里带 shell 之外
 * 全部写入类工具的逐轮视角，状态栏是全会话 Edit/Write/MultiEdit 视角）。
 * 撤销：fileRewindPreview（hash 预检，safe/unsafe/ignored 三桶、全有或全无）→
 * applyFileRewind（恢复/删除文件，不截断聊天历史，不依赖 git）。
 * 受理后的 reverted 态由服务端 turn.fileChanges 事件权威回推（标题订阅增量），
 * store 在 turnFileRewindApplied 应答时乐观置位做即时反馈。
 * 壳复用全局 .modal-overlay/.modal-content（history-view.less）。
 */

import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { createPortal } from 'react-dom'
import { useStore } from '@/store/useStore'
import { onMessage, sendToJava } from '@/ipc/bridge'
import { FileIcon } from './FileIcon'
import type {
  JavaResponse,
  TurnFileChangesDetail,
  TurnFileRewindPreview,
} from '@/types/messages'
import '../styles/turn-file-changes.less'

const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(' ')

/** 错误码 → i18n key（reason=Java 侧机器码；未知码回退 err.internal） */
function errText(t: (k: string) => string, reason?: string, message?: string): string {
  const known = ['unsupported', 'targetGone', 'snapshotTimeout', 'revisionUnknown', 'stale', 'commandFailed', 'missingParams', 'internal']
  if (reason && known.includes(reason)) return t(`chat.fileChanges.err.${reason}`)
  return message || t('chat.fileChanges.err.internal')
}

/** unsafe reason → i18n key */
function reasonText(t: (k: string) => string, reason: string): string {
  return t(`chat.fileChanges.reason.${reason}`)
}

/** 写入类工具名 → 本地化文案；不在映射内的工具名原样保留 */
function toolNameText(t: (k: string, opts?: { defaultValue: string }) => string, name: string): string {
  return t(`chat.fileChanges.tool.${name}`, { defaultValue: name })
}

function hunkHeader(h: { oldStart: number; oldLines: number; newStart: number; newLines: number }): string {
  return `@@ -${h.oldStart},${h.oldLines} +${h.newStart},${h.newLines} @@`
}

export function TurnFileChangesDialog() {
  const { t } = useTranslation()
  const messageId = useStore((s) => s.turnFileChangesDialogFor)
  const sessionId = useStore((s) => s.currentSessionId)
  const initialPath = useStore((s) => s.turnFileChangesDialogPath)
  const initialRewind = useStore((s) => s.turnFileChangesDialogRewind)
  const close = useStore((s) => s.closeTurnFileChanges)
  // 摘要与撤销资格来自订阅直出的逐轮账本（canRewind=服务端 actions.canRewindFiles）
  const summary = useStore((s) => (s.turnFileChangesDialogFor ? s.turnFileChanges[s.turnFileChangesDialogFor] : undefined))

  const [detail, setDetail] = useState<TurnFileChangesDetail | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailError, setDetailError] = useState<string | null>(null)
  const [selectedPath, setSelectedPath] = useState<string | null>(null)

  const [mode, setMode] = useState<'files' | 'rewind'>('files')
  const [preview, setPreview] = useState<TurnFileRewindPreview | null>(null)
  const [previewLoading, setPreviewLoading] = useState(false)
  const [applying, setApplying] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  const items = detail?.items ?? []
  const selected = useMemo(
    () => items.find((i) => i.path === selectedPath) ?? items[0] ?? null,
    [items, selectedPath],
  )
  const canRewind = !!summary?.canRewind && summary.state !== 'reverted' && !detail?.state

  // 打开即拉明细；会话/轮切换（弹窗换目标）时整体复位。initialPath=文件行「审查」
  // 直达定位；initialRewind=头部「撤销」直达预览模式（明细仍后台拉，返回列表可见）
  useEffect(() => {
    setDetail(null)
    setDetailError(null)
    setSelectedPath(initialPath ?? null)
    setMode(initialRewind ? 'rewind' : 'files')
    setPreview(null)
    setPreviewLoading(false)
    setApplying(false)
    setActionError(null)
    if (!messageId || !sessionId) return
    setDetailLoading(true)
    sendToJava({ op: 'turnFileChanges', sessionId, messageId })
    if (initialRewind) {
      setPreviewLoading(true)
      sendToJava({ op: 'turnFileRewindPreview', sessionId, messageId })
    }
  }, [messageId, sessionId, initialPath, initialRewind])

  // 响应监听（按 sessionId+messageId 匹配本弹窗目标，防切轮串台）
  useEffect(() => {
    if (!messageId) return
    const off = onMessage((msg: JavaResponse) => {
      if (msg.op === 'turnFileChangesResult' && msg.messageId === messageId) {
        setDetail(msg.data)
        setDetailLoading(false)
      } else if (msg.op === 'turnFileChangesError') {
        setDetailError(errText(t, msg.reason, msg.message))
        setDetailLoading(false)
      } else if (msg.op === 'turnFileRewindPreviewResult' && msg.messageId === messageId) {
        setPreview(msg.data)
        setPreviewLoading(false)
      } else if (msg.op === 'turnFileRewindPreviewError') {
        setActionError(errText(t, msg.reason, msg.message))
        setPreviewLoading(false)
      } else if (msg.op === 'turnFileRewindApplyError') {
        setApplying(false)
        setActionError(errText(t, msg.reason, msg.message))
      }
      // turnFileRewindApplied 由 store 全局处理（乐观置 reverted + 关弹窗）
    })
    return off
  }, [messageId, t])

  // Escape 关闭（独立阅读弹窗，无让位对象）
  useEffect(() => {
    if (!messageId) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [messageId, close])

  // 路径悬浮提示：JCEF 不渲染原生 title tooltip，列表容器 overflow:auto 又会裁剪
  // CSS ::after 气泡——复用 chip 方案：fixed 定位挂 body（.inline-chip-tip 全局样式）
  const [tip, setTip] = useState<{ text: string; left: number; top: number; bottom: number } | null>(null)
  const tipRef = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => {
    const node = tipRef.current
    if (!tip || !node) return
    const w = node.offsetWidth
    const h = node.offsetHeight
    let x = Math.max(8, tip.left)
    if (x + w > window.innerWidth - 8) x = Math.max(8, window.innerWidth - 8 - w)
    node.style.left = `${x}px`
    // 优先弹行上方，顶部空间不足弹行下方
    if (tip.top - h - 6 >= 8) {
      node.style.top = `${tip.top - 6}px`
      node.style.transform = 'translateY(-100%)'
    } else {
      node.style.top = `${tip.bottom + 6}px`
      node.style.transform = 'none'
    }
  }, [tip])
  const showTip = (e: React.MouseEvent<HTMLElement>, text: string) => {
    if (!text) return
    const rect = e.currentTarget.getBoundingClientRect()
    setTip({ text, left: rect.left, top: rect.top, bottom: rect.bottom })
  }
  const hideTip = () => setTip(null)

  const openRewind = () => {
    if (!messageId || !sessionId) return
    setMode('rewind')
    setActionError(null)
    if (preview) return
    setPreviewLoading(true)
    sendToJava({ op: 'turnFileRewindPreview', sessionId, messageId })
  }

  const confirmApply = () => {
    if (!messageId || !sessionId || !preview?.canApply || applying) return
    setApplying(true)
    setActionError(null)
    sendToJava({ op: 'turnFileRewindApply', sessionId, messageId })
  }

  if (!messageId) return null

  const reverted = summary?.state === 'reverted'

  return (
    <>
      <div className="modal-overlay" onClick={close} role="presentation">
      <div className="modal-content tfc-dialog" onClick={(e) => e.stopPropagation()}>
        <div className="tfc-dialog__header">
          <span className="codicon codicon-diff-multiple" />
          <h3 className="tfc-dialog__title">{t('chat.fileChanges.dialogTitle')}</h3>
          {summary && (
            <span className="tfc-dialog__summary">
              {t('chat.fileChanges.turnSummary', {
                files: summary.files,
                additions: summary.additions,
                deletions: summary.deletions,
              })}
            </span>
          )}
          {reverted && <span className="tfc-dialog__reverted">{t('chat.fileChanges.reverted')}</span>}
          <button className="tfc-dialog__icon-btn" onClick={close} title={String(t('common.confirm.cancel'))}>
            <span className="codicon codicon-close" />
          </button>
        </div>

        {mode === 'files' ? (
          <>
            <div className="tfc-dialog__body">
              {detailLoading && !detail ? (
                <div className="tfc-dialog__empty">
                  <span className="codicon codicon-loading spin" /> {t('common.actions.loading')}
                </div>
              ) : detailError ? (
                <div className="tfc-dialog__empty tfc-dialog__empty--error">
                  <span className="codicon codicon-error" /> {detailError}
                </div>
              ) : items.length === 0 ? (
                <div className="tfc-dialog__empty">
                  <span className="codicon codicon-file" /> {t('chat.fileChanges.empty')}
                </div>
              ) : (
                <>
                  <div className="tfc-dialog__files">
                    {items.map((it) => {
                      const name = it.path.replace(/\\/g, '/').split('/').pop() || it.path
                      return (
                        <button
                          key={it.path}
                          type="button"
                          className={cx('tfc-dialog__file', selected?.path === it.path && 'tfc-dialog__file--active')}
                          onClick={() => setSelectedPath(it.path)}
                          onMouseEnter={(e) => showTip(e, it.path)}
                          onMouseLeave={hideTip}
                        >
                          <FileIcon path={it.path} className="file-type-icon tfc-dialog__file-icon" />
                          <span className="tfc-dialog__file-name">{name}</span>
                          <span className="tfc-dialog__file-stat">
                            <span className="tfc-add">+{it.additions}</span>
                            <span className="tfc-del">−{it.deletions}</span>
                          </span>
                        </button>
                      )
                    })}
                  </div>
                  <div className="tfc-dialog__patch">
                    {selected && selected.patches.length > 0 ? (
                      selected.patches.map((h, hi) => (
                        <div key={hi} className="tfc-dialog__hunk">
                          <div className="tfc-dialog__hunk-header">{hunkHeader(h)}</div>
                          <pre className="tfc-dialog__hunk-lines">
                            {h.lines.map((line, li) => {
                              const prefix = line.charAt(0)
                              const tone =
                                prefix === '+' ? 'add' : prefix === '-' ? 'del' : 'ctx'
                              return (
                                <div key={li} className={cx('tfc-dialog__line', `tfc-dialog__line--${tone}`)}>
                                  {line.length > 0 ? line : ' '}
                                </div>
                              )
                            })}
                          </pre>
                        </div>
                      ))
                    ) : (
                      <div className="tfc-dialog__empty">
                        <span className="codicon codicon-file" /> {t('chat.fileChanges.noPatches')}
                      </div>
                    )}
                  </div>
                </>
              )}
            </div>
            {canRewind && (
              <div className="tfc-dialog__footer">
                <button type="button" className="tfc-dialog__rewind-btn" onClick={openRewind}>
                  <span className="codicon codicon-discard" />
                  {t('chat.fileChanges.rewindBtn')}
                </button>
              </div>
            )}
          </>
        ) : (
          <>
            <div className="tfc-dialog__body tfc-dialog__body--rewind">
              {previewLoading && !preview ? (
                <div className="tfc-dialog__empty">
                  <span className="codicon codicon-loading spin" /> {t('common.actions.loading')}
                </div>
              ) : preview ? (
                <>
                  <div className="tfc-dialog__rewind-intro">{t('chat.fileChanges.rewindIntro')}</div>
                  {preview.safeFiles.length > 0 && (
                    <div className="tfc-dialog__section">
                      <div className="tfc-dialog__section-title">
                        {t('chat.fileChanges.safeSection', { count: preview.safeFiles.length })}
                      </div>
                      {preview.safeFiles.map((f) => (
                        <div key={f.path} className="tfc-dialog__row"
                          onMouseEnter={(e) => showTip(e, f.path)}
                          onMouseLeave={hideTip}
                        >
                          <span className={cx('codicon', f.action === 'delete' ? 'codicon-trash' : 'codicon-discard')} />
                          <span className="tfc-dialog__row-action">
                            {f.action === 'delete' ? t('chat.fileChanges.actionDelete') : t('chat.fileChanges.actionRestore')}
                          </span>
                          <span className="tfc-dialog__row-path">{f.path}</span>
                          {f.toolNames.length > 0 && (
                            <span className="tfc-dialog__row-tools">
                              {f.toolNames.map((n) => toolNameText(t, n)).join(', ')}
                            </span>
                          )}
                        </div>
                      ))}
                    </div>
                  )}
                  {preview.unsafeFiles.length > 0 && (
                    <div className="tfc-dialog__section tfc-dialog__section--unsafe">
                      <div className="tfc-dialog__section-title">
                        {t('chat.fileChanges.unsafeSection', { count: preview.unsafeFiles.length })}
                      </div>
                      {preview.unsafeFiles.map((f) => (
                        <div key={f.path} className="tfc-dialog__row"
                          onMouseEnter={(e) => showTip(e, f.message || f.path)}
                          onMouseLeave={hideTip}
                        >
                          <span className="codicon codicon-warning" />
                          <span className="tfc-dialog__row-action">{reasonText(t, f.reason)}</span>
                          <span className="tfc-dialog__row-path">{f.path}</span>
                        </div>
                      ))}
                      <div className="tfc-dialog__unsafe-hint">{t('chat.fileChanges.unsafeHint')}</div>
                    </div>
                  )}
                  {preview.ignoredFiles.length > 0 && (
                    <div className="tfc-dialog__section tfc-dialog__section--muted">
                      <div className="tfc-dialog__section-title">
                        {t('chat.fileChanges.ignoredSection', { count: preview.ignoredFiles.length })}
                      </div>
                      {preview.ignoredFiles.map((f) => (
                        <div key={f.path} className="tfc-dialog__row"
                          onMouseEnter={(e) => showTip(e, f.path)}
                          onMouseLeave={hideTip}
                        >
                          <span className="codicon codicon-terminal" />
                          <span className="tfc-dialog__row-path">{f.path}</span>
                        </div>
                      ))}
                    </div>
                  )}
                  {actionError && (
                    <div className="tfc-dialog__error">
                      <span className="codicon codicon-error" /> {actionError}
                    </div>
                  )}
                </>
              ) : (
                <div className="tfc-dialog__empty tfc-dialog__empty--error">
                  <span className="codicon codicon-error" /> {actionError || t('chat.fileChanges.err.internal')}
                </div>
              )}
            </div>
            <div className="tfc-dialog__footer">
              <button type="button" className="tfc-dialog__back-btn" onClick={() => setMode('files')} disabled={applying}>
                {t('chat.fileChanges.backToList')}
              </button>
              <button
                type="button"
                className="tfc-dialog__rewind-btn"
                onClick={confirmApply}
                disabled={!preview?.canApply || applying}
              >
                {applying ? (
                  <>
                    <span className="codicon codicon-loading spin" />
                    {t('chat.fileChanges.applying')}
                  </>
                ) : (
                  <>
                    <span className="codicon codicon-discard" />
                    {t('chat.fileChanges.confirmRewind')}
                  </>
                )}
              </button>
            </div>
          </>
        )}
      </div>
      </div>
      {tip && createPortal(
        <div ref={tipRef} className="inline-chip-tip" style={{ left: tip.left, top: tip.top }}>
          {tip.text}
        </div>,
        document.body,
      )}
    </>
  )
}
