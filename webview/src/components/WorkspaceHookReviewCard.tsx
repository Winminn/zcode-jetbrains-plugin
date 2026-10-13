/**
 * 工作区钩子信任评审卡（底部停靠，v4 pendingInteractions 投影）
 *
 * 项目级钩子（zcode.json）未信任前一律 blocked 不生效；服务端把待审条目经
 * v4 帧的 pendingInteractions 投影下发，本卡片承载评审操作。信任写入磁盘
 * 共享存储（官方客户端点的信任本插件同样继承），活动会话即时生效。
 *
 * 形态对齐 AskUserQuestion/PlanApproval dock：底部停靠非模态、可最小化、
 * 跨回合存活；不做显式拒绝按钮——关闭/最小化即保持阻止（协议无 decline
 * action，deadlineAt 到期服务端自收敛）。
 *
 * 应答：op=workspaceHookReviewRespond，identity 字段原样回传 + 勾选的
 * reviewItemIds；卡片由 settled 投影帧（空集合）收敛，不靠应答本地移除。
 */

import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { onMessage, sendToJava } from '@/ipc/bridge'
import { useStore } from '@/store/useStore'
import type { JavaResponse, WorkspaceHookReview, WorkspaceHookReviewItem } from '@/types/messages'
import '../styles/workspace-hook-review.less'

/** 稳定空数组：selector 每次返回新 [] 会被 useSyncExternalStore 判为快照变化，
 *  触发 forceStoreRerender 无限循环（React #185），必须共享同一引用 */
const EMPTY_REVIEWS: WorkspaceHookReview[] = []

export function WorkspaceHookReviewCard() {
  const currentSessionId = useStore((s) => s.currentSessionId)
  const reviews = useStore((s) =>
    currentSessionId
      ? s.workspaceHookReviewsBySession[currentSessionId] ?? EMPTY_REVIEWS
      : EMPTY_REVIEWS,
  )
  if (reviews.length === 0) return null
  return (
    <>
      {reviews.map((review) => <ReviewDock key={review.interactionId} review={review} />)}
    </>
  )
}

/** sourceFiles 路径相对工作区根，openFile 前拼绝对路径 */
function resolveSourcePath(p: string, root: string): string {
  const norm = p.replace(/\\/g, '/')
  if (/^[A-Za-z]:\//.test(norm) || norm.startsWith('/')) return norm
  const r = root.replace(/\\/g, '/').replace(/\/+$/, '')
  if (!r) return norm
  return norm.startsWith('./') ? `${r}/${norm.slice(2)}` : `${r}/${norm}`
}

function ReviewDock({ review }: { review: WorkspaceHookReview }) {
  const { t } = useTranslation()
  const workspacePath = useStore((s) => s.currentWorkspacePath)
  const [collapsed, setCollapsed] = useState(false)
  const [closed, setClosed] = useState(false)
  const [sending, setSending] = useState(false)
  const [sendError, setSendError] = useState('')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [expanded, setExpanded] = useState<Set<string>>(new Set())

  // 已信任条目免选；其余状态（待审/已撤销/配置已变更）均可勾选授予
  const selectable = useMemo(
    () => review.items.filter((it) => it.trustState !== 'trusted_persistent'),
    [review.items],
  )

  // 信任失败反馈：respond 错误带 sourceOp+interactionId 归属（面板专用错误形状），
  // 复位发送态并就地提示——卡片不监听无归属的全局 error，其他操作的失败不波及本卡。
  // 挂在 early return 之前保 hooks 顺序稳定
  useEffect(() => {
    const off = onMessage((msg: JavaResponse) => {
      if (msg.op !== 'error' || msg.sourceOp !== 'workspaceHookReviewRespond') return
      if (msg.interactionId && msg.interactionId !== review.interactionId) return
      setSending(false)
      setSendError(msg.message)
    })
    return off
  }, [review.interactionId])

  if (closed) return null

  const title = review.workspaceLabel || review.workspaceIdentity || t('app.hookReview.titleFallback')

  const toggleItem = (it: WorkspaceHookReviewItem) => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(it.reviewItemId)) next.delete(it.reviewItemId)
      else next.add(it.reviewItemId)
      return next
    })
  }

  const allSelected = selectable.length > 0 && selectable.every((it) => selected.has(it.reviewItemId))
  const toggleAll = () => {
    setSelected(allSelected ? new Set() : new Set(selectable.map((it) => it.reviewItemId)))
  }

  const toggleCommand = (id: string) => {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const handleTrust = () => {
    if (selected.size === 0 || sending) return
    sendToJava({
      op: 'workspaceHookReviewRespond',
      sessionId: review.sessionId,
      reviewItemIds: Array.from(selected),
      identity: {
        interactionId: review.interactionId,
        taskId: review.taskId,
        runId: review.runId,
        remoteSessionId: review.remoteSessionId,
        workspaceIdentity: review.workspaceIdentity,
        bundleDigest: review.bundleDigest,
        reviewFlowId: review.reviewFlowId,
        generation: review.generation,
      },
    })
    setSendError('')
    setSending(true)
  }

  return (
    <div className="hook-review-overlay hook-review-overlay--dock">
      <div className={`hook-review-dialog ${collapsed ? 'hook-review-dialog--collapsed' : ''}`}>
        <div
          className="hook-review-dialog__header"
          onClick={collapsed ? () => setCollapsed(false) : undefined}
          role={collapsed ? 'button' : undefined}
        >
          <span className="hook-review-dialog__badge">
            <span className="codicon codicon-shield" />
            {t('app.hookReview.badge')}
          </span>
          <span className="hook-review-dialog__title">{title}</span>
          <button
            type="button"
            className="hook-review-dialog__icon-btn"
            aria-expanded={!collapsed}
            title={collapsed ? t('app.askUser.expand') : t('app.askUser.collapse')}
            onClick={(e) => {
              e.stopPropagation()
              setCollapsed((v) => !v)
            }}
          >
            <span className={`codicon codicon-chevron-${collapsed ? 'up' : 'down'}`} />
          </button>
          <button
            type="button"
            className="hook-review-dialog__icon-btn"
            title={t('app.hookReview.close')}
            onClick={(e) => {
              e.stopPropagation()
              setClosed(true)
            }}
          >
            <span className="codicon codicon-close" />
          </button>
        </div>

        {!collapsed && (
          <>
            <div className="hook-review-dialog__body">
              <div className="hook-review-dialog__summary">
                {t('app.hookReview.summary', {
                  eventCount: review.summary.eventCount,
                  hookCount: review.summary.hookCount,
                  pendingCount: review.summary.pendingCount,
                })}
              </div>

              <div className="hook-review-dialog__sources">
                <span className="hook-review-dialog__sources-label">
                  {t('app.hookReview.sources')}
                </span>
                {review.sourceFiles.map((f) => (
                  <button
                    key={f.path}
                    type="button"
                    className="hook-review-dialog__source-file"
                    onClick={() => sendToJava({
                      op: 'openFile',
                      filePath: resolveSourcePath(f.path, workspacePath),
                    })}
                    title={f.displayPath}
                  >
                    <span className="codicon codicon-file-code" />
                    {f.displayPath}
                  </button>
                ))}
              </div>

              <label className="hook-review-dialog__select-all">
                <input type="checkbox" checked={allSelected} onChange={toggleAll} />
                {t('app.hookReview.selectAll')}
              </label>

              <div className="hook-review-dialog__items">
                {review.items.map((it) => {
                  const isTrusted = it.trustState === 'trusted_persistent'
                  const checked = selected.has(it.reviewItemId)
                  const cmdExpanded = expanded.has(it.reviewItemId)
                  return (
                    <div key={it.reviewItemId} className="hook-review-item">
                      <div className="hook-review-item__top">
                        <input
                          type="checkbox"
                          className="hook-review-item__checkbox"
                          checked={checked}
                          disabled={isTrusted}
                          onChange={() => toggleItem(it)}
                        />
                        <span className="hook-review-item__name">{it.displayName}</span>
                        {it.trustState === 'trusted_persistent' && (
                          <span className="hook-review-item__badge hook-review-item__badge--muted">
                            {t('app.hookReview.stateTrusted')}
                          </span>
                        )}
                        {it.trustState === 'revoked' && (
                          <span className="hook-review-item__badge hook-review-item__badge--danger">
                            {t('app.hookReview.stateRevoked')}
                          </span>
                        )}
                        {it.trustState === 'stale_digest' && (
                          <span className="hook-review-item__badge hook-review-item__badge--warning">
                            {t('app.hookReview.stateStale')}
                          </span>
                        )}
                        <span className="hook-review-item__meta">
                          {it.executionMode === 'background'
                            ? t('app.hookReview.modeBackground')
                            : t('app.hookReview.modeForeground')}
                          {' · '}{Math.round(it.resolvedTimeoutMs / 1000)}s
                        </span>
                      </div>
                      <button
                        type="button"
                        className={`hook-review-item__command ${cmdExpanded ? 'hook-review-item__command--expanded' : ''}`}
                        onClick={() => toggleCommand(it.reviewItemId)}
                        title={t('app.hookReview.commandToggle')}
                      >
                        <code>{it.displayCommand}</code>
                      </button>
                    </div>
                  )
                })}
              </div>
            </div>

            <div className="hook-review-dialog__footer">
              {sendError && (
                <div className="hook-review-dialog__send-error" role="alert">
                  <span className="codicon codicon-error" />
                  <span>{sendError}</span>
                </div>
              )}
              <button
                type="button"
                className="hook-review-dialog__trust-btn"
                disabled={selected.size === 0 || sending}
                onClick={handleTrust}
              >
                <span className="codicon codicon-shield" />
                {sending
                  ? t('app.hookReview.sending')
                  : t('app.hookReview.trustSelected', { count: selected.size })}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
