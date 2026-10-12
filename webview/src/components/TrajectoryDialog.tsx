/**
 * 模型调用轨迹弹窗（会话顶栏「调用轨迹」按钮）
 *
 * 数据：getModelTrajectory（Kotlin 端 ModelIoTrajectoryReader 读 CLI 落盘的
 *       ~/.zcode/cli/{debug,rollout}/model-io-<sessionId>.jsonl——每次模型调用
 *       一条记录，含完整请求上下文/响应/耗时/token；直读文件非协议 RPC，与官方
 *       桌面端同路）。
 * 注意：CLI 只保留最近 3 个会话的 model-io（新会话首写时轮转删最旧），
 *       更早会话查不到文件——空态给出原因与探测路径，不猜"没调用过"。
 * 交互：刷新、点击行进二级详情弹窗；详情弹窗内置搜索（多关键词 AND，
 *       命中处 mark 高亮 + 计数 + 上/下导航滚动定位），详情内容自然撑开
 *       仅 body 一条滚动。
 * 壳复用全局 .modal-overlay/.modal-content（history-view.less）。
 */

import { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useStore } from '@/store/useStore'
import type { TrajectoryPart, TrajectoryRecord } from '@/types/messages'
import { TrajectoryDetailRail, type TrajectoryAnchor } from './TrajectoryDetailRail'
import '../styles/trajectory-dialog.less'

const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(' ')

/** 该会话的 model-io 落盘路径（与 Kotlin sanitizeSessionSegment 同规则；空态自诊断展示用） */
function expectedModelIoPaths(sessionId: string): string[] {
  const seg =
    sessionId.replace(/[^a-zA-Z0-9_-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 80) || 'no-session'
  return [`~/.zcode/cli/debug/model-io-${seg}.jsonl`, `~/.zcode/cli/rollout/model-io-${seg}.jsonl`]
}

// ---------- 详情内搜索：关键词经 context 下发，所有文本出口过 Hl 高亮 ----------

const SearchKeywordsContext = createContext<string[]>([])

/** 文本高亮：按关键词切出全部命中区间（重叠合并）包 mark，无关键词原样返回 */
function Hl({ text }: { text: string }) {
  const keywords = useContext(SearchKeywordsContext)
  const nodes = useMemo(() => {
    if (!text || keywords.length === 0) return text
    const lower = text.toLowerCase()
    const ranges: [number, number][] = []
    for (const k of keywords) {
      let i = lower.indexOf(k)
      while (i !== -1) {
        ranges.push([i, i + k.length])
        i = lower.indexOf(k, i + k.length)
      }
    }
    if (ranges.length === 0) return text
    ranges.sort((a, b) => a[0] - b[0])
    const merged: [number, number][] = []
    for (const r of ranges) {
      const last = merged[merged.length - 1]
      if (last && r[0] <= last[1]) last[1] = Math.max(last[1], r[1])
      else merged.push([r[0], r[1]])
    }
    const parts: ReactNode[] = []
    let pos = 0
    merged.forEach(([s, e], i) => {
      if (s > pos) parts.push(text.slice(pos, s))
      parts.push(mark(text.slice(s, e), i))
      pos = e
    })
    if (pos < text.length) parts.push(text.slice(pos))
    return parts
  }, [text, keywords])
  return <>{nodes}</>
}

function mark(text: string, i: number) {
  return <mark key={i} className="traj-hit">{text}</mark>
}

/** UTC ISO → 本地 HH:mm:ss */
function fmtTime(ts: string): string {
  const d = new Date(ts)
  return isNaN(d.getTime()) ? ts.slice(11, 19) : d.toLocaleTimeString('zh-CN', { hour12: false })
}

/** 耗时人读：<1s 显示 ms，否则 s（一位小数） */
function fmtDuration(ms?: number): string | null {
  if (ms == null || ms < 0) return null
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`
}

/** 来源徽标：querySource 细分（标题生成/提示优化/目标验证）优先于 kind 粗分 */
function useSourceLabel() {
  const { t } = useTranslation()
  return (record: TrajectoryRecord): { text: string; kind: string } => {
    const qs = record.callSource?.querySource
    const kind = record.callSource?.kind ?? 'main'
    const text =
      qs === 'session_title'
        ? t('trajectory.source.sessionTitle')
        : qs === 'promptEnhance'
          ? t('trajectory.source.promptEnhance')
          : qs === 'targetCompletion'
            ? t('trajectory.source.targetCompletion')
            : kind === 'main'
              ? t('trajectory.source.main')
              : kind === 'subagent'
                ? t('trajectory.source.subagent')
                : kind === 'compact'
                  ? t('trajectory.source.compact')
                  : kind === 'sidecar'
                    ? t('trajectory.source.sidecar')
                    : t('trajectory.source.unknown')
    return { text, kind }
  }
}

function finishLabel(fr: string | undefined, t: (k: string) => string): string | null {
  if (!fr) return null
  const key = fr.toLowerCase()
  if (key === 'stop' || key === 'end') return t('trajectory.finish.stop')
  if (key.includes('tool')) return t('trajectory.finish.toolCalls')
  if (key.includes('length')) return t('trajectory.finish.length')
  if (key.includes('filter')) return t('trajectory.finish.contentFilter')
  return fr
}

/** 消息 role → 显示标签与色调类 */
const ROLE_LABEL: Record<string, { key: string; cls: string }> = {
  system: { key: 'trajectory.role.system', cls: 'system' },
  user: { key: 'trajectory.role.user', cls: 'user' },
  assistant: { key: 'trajectory.role.assistant', cls: 'assistant' },
  tool: { key: 'trajectory.role.tool', cls: 'tool' },
}

function PartView({ part }: { part: TrajectoryPart }) {
  if (part.kind === 'text') {
    return part.text ? <pre className="traj-part traj-part--text"><Hl text={part.text} /></pre> : null
  }
  if (part.kind === 'reasoning') {
    return part.text ? <pre className="traj-part traj-part--reasoning"><Hl text={part.text} /></pre> : null
  }
  if (part.kind === 'tool-call') {
    return (
      <div className="traj-part traj-part--tool">
        <span className="traj-part__tool-name">
          <span className="codicon codicon-tools" /> {part.toolName}
        </span>
        <pre>
          <Hl text={part.input != null ? JSON.stringify(part.input, null, 2) : ''} />
        </pre>
      </div>
    )
  }
  if (part.kind === 'tool-result') {
    const isError = isErrorOutput(part.output)
    return (
      <div className={cx('traj-part traj-part--tool', isError && 'traj-part--tool-error')}>
        <span className="traj-part__tool-name">
          <span className="codicon codicon-arrow-left" /> {part.toolName}
        </span>
        <pre>
          <Hl text={part.output != null ? stringifyOutput(part.output) : ''} />
        </pre>
      </div>
    )
  }
  if (part.kind === 'image') {
    return (
      <div className="traj-part traj-part--image">
        <span className="codicon codicon-file-media" /> {part.mediaType}
      </div>
    )
  }
  return (
    <pre className="traj-part traj-part--raw">
      <Hl text={JSON.stringify(part.raw ?? part, null, 2)} />
    </pre>
  )
}

/** tool-result output 的 error-text 信封（Reader 对 tool 消息 isError 的包装） */
function isErrorOutput(output: unknown): boolean {
  return (
    !!output &&
    typeof output === 'object' &&
    (output as { type?: string }).type === 'error-text'
  )
}

function stringifyOutput(output: unknown): string {
  if (typeof output === 'string') return output
  try {
    return JSON.stringify(output, null, 2)
  } catch {
    return String(output)
  }
}

/** 列表行：只展示摘要（时间/来源/模型/attempt/finish/耗时/token），点击进详情弹窗 */
function TrajectoryRow({ record, onOpen }: {
  record: TrajectoryRecord
  onOpen: () => void
}) {
  const { t } = useTranslation()
  const sourceLabel = useSourceLabel()
  const source = sourceLabel(record)
  const duration = fmtDuration(record.durationMs)
  const finish = finishLabel(record.response?.finishReason, t)
  const usage = record.response?.usage

  return (
    <div className={cx('traj-row', record.error && 'traj-row--error')}>
      <button className="traj-row__head" onClick={onOpen}>
        <span className="codicon codicon-chevron-right" />
        <span className="traj-row__time" title={record.startedAt}>
          {fmtTime(record.startedAt)}
        </span>
        <span className={cx('traj-row__source', `traj-row__source--${source.kind}`)}>{source.text}</span>
        {record.model?.modelId && <span className="traj-row__model">{record.model.modelId}</span>}
        {(record.attempt ?? 1) > 1 && (
          <span className="traj-row__attempt">{t('trajectory.attempt', { attempt: record.attempt })}</span>
        )}
        {record.error ? (
          <span className="traj-row__badge traj-row__badge--error">
            <span className="codicon codicon-error" /> {record.error.name}
          </span>
        ) : finish ? (
          <span className="traj-row__badge">{finish}</span>
        ) : null}
        <span className="traj-row__spacer" />
        {duration && <span className="traj-row__duration">{duration}</span>}
        {usage?.totalTokens != null && (
          <span className="traj-row__tokens" title={t('trajectory.summaryTokens')}>
            {usage.totalTokens.toLocaleString()} tok
          </span>
        )}
      </button>
    </div>
  )
}

/** 详情体：错误信封 + 完整输入消息 + 输出（详情弹窗专用，列表行不再内联展开） */
function TrajectoryDetailBody({ record }: { record: TrajectoryRecord }) {
  const { t } = useTranslation()
  const usage = record.response?.usage
  const messages = record.request?.messages ?? []
  return (
    <>
      {record.error && (
        <div className="traj-error" data-anchor-msg="error">
          <div className="traj-error__name">
            <Hl text={record.error.name} />
          </div>
          <pre>
            <Hl text={record.error.message} />
          </pre>
        </div>
      )}

      <div className="traj-section">
        <div className="traj-section__title">
          {t('trajectory.inputSection')}
          <span className="traj-section__hint">· {messages.length}</span>
        </div>
        {messages.length === 0 ? (
          <div className="traj-empty-hint">—</div>
        ) : (
          messages.map((m, i) => {
            const role = ROLE_LABEL[m.role] ?? { key: m.role, cls: 'other' }
            return (
              <div key={i} className="traj-msg" data-anchor-msg={`m${i}`}>
                <span className={cx('traj-msg__role', `traj-msg__role--${role.cls}`)}>{t(role.key)}</span>
                <div className="traj-msg__parts">
                  {(m.parts ?? []).length === 0 ? (
                    <div className="traj-empty-hint">—</div>
                  ) : (
                    (m.parts ?? []).map((p, j) => <PartView key={j} part={p} />)
                  )}
                </div>
              </div>
            )
          })
        )}
      </div>

      {record.response && (
        <div className="traj-section" data-anchor-msg="output">
          <div className="traj-section__title">{t('trajectory.outputSection')}</div>
          {record.response.reasoningText && (
            <div className="traj-msg">
              <span className="traj-msg__role traj-msg__role--reasoning">{t('trajectory.reasoning')}</span>
              <div className="traj-msg__parts">
                <pre className="traj-part traj-part--reasoning"><Hl text={record.response.reasoningText} /></pre>
              </div>
            </div>
          )}
          {record.response.text && (
            <div className="traj-msg">
              <span className="traj-msg__role traj-msg__role--assistant">{t('trajectory.message')}</span>
              <div className="traj-msg__parts">
                <pre className="traj-part traj-part--text"><Hl text={record.response.text} /></pre>
              </div>
            </div>
          )}
          {(record.response.toolCalls ?? []).map((p, i) => (
            <PartView key={i} part={p} />
          ))}
          {usage && (
            <div className="traj-usage">
              {usage.inputTokens != null && <span>{t('trajectory.usage.input', { count: usage.inputTokens })}</span>}
              {usage.outputTokens != null && (
                <span>{t('trajectory.usage.output', { count: usage.outputTokens })}</span>
              )}
              {usage.cacheReadTokens != null && (
                <span>{t('trajectory.usage.cache', { count: usage.cacheReadTokens })}</span>
              )}
              {usage.reasoningTokens != null && (
                <span>{t('trajectory.usage.reasoning', { count: usage.reasoningTokens })}</span>
              )}
            </div>
          )}
        </div>
      )}
    </>
  )
}

/** 单条调用详情弹窗（叠加在列表弹窗之上）：sticky 头部 + 内置搜索 + 内滚内容 */
function TrajectoryDetailDialog({ record, onClose }: { record: TrajectoryRecord; onClose: () => void }) {
  const { t } = useTranslation()
  const sourceLabel = useSourceLabel()
  const source = sourceLabel(record)
  const duration = fmtDuration(record.durationMs)
  const finish = finishLabel(record.response?.finishReason, t)
  const usage = record.response?.usage

  // 详情内搜索：多关键词 AND（空格分隔），命中处高亮 + 计数 + 上/下导航
  const [query, setQuery] = useState('')
  const [activeHit, setActiveHit] = useState(0)
  const [hitCount, setHitCount] = useState(0)
  const bodyRef = useRef<HTMLDivElement>(null)
  const keywords = useMemo(
    () => query.trim().toLowerCase().split(/\s+/).filter(Boolean),
    [query],
  )

  // 关键词/定位/内容变化后重数命中、标当前、滚动定位（mark 由 React 渲染，此处只做计数与导航态）
  useEffect(() => {
    const marks = bodyRef.current?.querySelectorAll('mark.traj-hit') ?? []
    setHitCount(marks.length)
    if (marks.length === 0) return
    const idx = Math.min(activeHit, marks.length - 1)
    marks.forEach((m, i) => m.classList.toggle('traj-hit--active', i === idx))
    marks[idx]?.scrollIntoView({ block: 'center' })
  }, [keywords, activeHit, record])

  const step = (delta: number) => {
    if (hitCount > 0) setActiveHit((i) => (i + delta + hitCount) % hitCount)
  }

  // 锚点导航：错误块 + 每条输入消息 + 输出 section（id 与 DetailBody 的 data-anchor-msg 对应）
  const anchors = useMemo<TrajectoryAnchor[]>(() => {
    const list: TrajectoryAnchor[] = []
    if (record.error) {
      list.push({ id: 'error', preview: `${record.error.name}: ${record.error.message}`.slice(0, 300) })
    }
    const roleText = (role: string) => {
      const entry = ROLE_LABEL[role]
      return entry ? t(entry.key) : role
    }
    ;(record.request?.messages ?? []).forEach((m, i) => {
      const text = (m.parts ?? [])
        .map((p) => p.text || (p.toolName ? `[${p.toolName}]` : ''))
        .filter(Boolean)
        .join(' ')
        .trim()
      list.push({ id: `m${i}`, preview: `${roleText(m.role)} · ${text || '—'}`.slice(0, 300) })
    })
    if (record.response) {
      const toolNames = (record.response.toolCalls ?? [])
        .map((p) => p.toolName)
        .filter(Boolean)
        .slice(0, 5)
      const summary = [
        record.response.text?.slice(0, 120),
        toolNames.length > 0 ? toolNames.join(', ') : '',
      ]
        .filter(Boolean)
        .join(' · ')
      list.push({ id: 'output', preview: `${t('trajectory.outputSection')} · ${summary || '—'}`.slice(0, 300) })
    }
    return list
  }, [record, t])

  return (
    <div className="modal-overlay traj-detail-overlay" onClick={onClose} role="presentation">
      <div className="modal-content traj-detail" onClick={(e) => e.stopPropagation()}>
        <TrajectoryDetailRail anchors={anchors} containerRef={bodyRef} />
        <div className="traj-detail__header">
          <span className="traj-detail__time" title={record.startedAt}>
            {fmtTime(record.startedAt)}
          </span>
          <span className={cx('traj-row__source', `traj-row__source--${source.kind}`)}>{source.text}</span>
          {record.model?.modelId && <span className="traj-row__model">{record.model.modelId}</span>}
          {(record.attempt ?? 1) > 1 && (
            <span className="traj-row__attempt">{t('trajectory.attempt', { attempt: record.attempt })}</span>
          )}
          {record.error ? (
            <span className="traj-row__badge traj-row__badge--error">
              <span className="codicon codicon-error" /> {record.error.name}
            </span>
          ) : finish ? (
            <span className="traj-row__badge">{finish}</span>
          ) : null}
          <span className="traj-row__spacer" />
          {duration && <span className="traj-row__duration">{duration}</span>}
          {usage?.totalTokens != null && (
            <span className="traj-row__tokens" title={t('trajectory.summaryTokens')}>
              {usage.totalTokens.toLocaleString()} tok
            </span>
          )}
          <button className="traj-dialog__icon-btn" onClick={onClose} title={t('trajectory.close')}>
            <span className="codicon codicon-close" />
          </button>
        </div>

        <div className="traj-detail__search">
          <span className="codicon codicon-search" />
          <input
            value={query}
            onChange={(e) => {
              setQuery(e.target.value)
              setActiveHit(0)
            }}
            onKeyDown={(e) => e.key === 'Escape' && setQuery('')}
            placeholder={t('trajectory.searchPlaceholder')}
            autoFocus
          />
          {query.trim() && (
            <>
              <span className="traj-detail__search-count">
                {hitCount > 0 ? `${Math.min(activeHit, hitCount - 1) + 1} / ${hitCount}` : '0 / 0'}
              </span>
              <button
                className="traj-dialog__icon-btn"
                onClick={() => step(-1)}
                disabled={hitCount === 0}
                title={t('trajectory.searchPrev')}
              >
                <span className="codicon codicon-chevron-up" />
              </button>
              <button
                className="traj-dialog__icon-btn"
                onClick={() => step(1)}
                disabled={hitCount === 0}
                title={t('trajectory.searchNext')}
              >
                <span className="codicon codicon-chevron-down" />
              </button>
              <button
                className="traj-dialog__icon-btn"
                onClick={() => {
                  setQuery('')
                  setActiveHit(0)
                }}
                title={t('trajectory.close')}
              >
                <span className="codicon codicon-close" />
              </button>
            </>
          )}
        </div>

        <div className="traj-detail__body" ref={bodyRef}>
          <SearchKeywordsContext.Provider value={keywords}>
            <TrajectoryDetailBody record={record} />
          </SearchKeywordsContext.Provider>
        </div>
      </div>
    </div>
  )
}

export function TrajectoryDialog({ sessionId, onClose }: { sessionId: string | null; onClose: () => void }) {
  const { t } = useTranslation()
  const trajectory = useStore((s) => s.trajectory)
  const trajectoryLoading = useStore((s) => s.trajectoryLoading)
  const trajectoryError = useStore((s) => s.trajectoryError)
  const loadTrajectory = useStore((s) => s.loadTrajectory)

  // 当前查看详情的调用（二级弹窗，叠加在列表之上）
  const [detail, setDetail] = useState<TrajectoryRecord | null>(null)

  // 打开/切会话即加载（trajectory 属于别的会话视为未加载）
  useEffect(() => {
    if (sessionId && trajectory?.sessionId !== sessionId) loadTrajectory(sessionId)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId])

  const records = useMemo(() => trajectory?.records ?? [], [trajectory])

  const summary = useMemo(() => {
    let totalTokens = 0
    const models = new Set<string>()
    for (const r of records) {
      totalTokens += r.response?.usage?.totalTokens ?? 0
      if (r.model?.modelId) models.add(r.model.modelId)
    }
    return { totalTokens, models: Array.from(models) }
  }, [records])

  return (
    <div className="modal-overlay" onClick={onClose} role="presentation">
      <div className="modal-content traj-dialog" onClick={(e) => e.stopPropagation()}>
        <div className="traj-dialog__header">
          <span className="codicon codicon-circuit-board" />
          <h3 className="traj-dialog__title">{t('trajectory.title')}</h3>
          {records.length > 0 && (
            <span className="traj-dialog__hint" title={t('trajectory.summaryTokens')}>
              {trajectory && trajectory.totalCount > records.length
                ? t('trajectory.summaryTruncated', { count: trajectory.totalCount, shown: records.length })
                : t('trajectory.summaryCalls', { count: records.length })}
              {summary.totalTokens > 0 && <> · {summary.totalTokens.toLocaleString()} tok</>}
              {summary.models.length > 0 && <> · {summary.models.join(', ')}</>}
            </span>
          )}
          <button
            className="traj-dialog__icon-btn"
            onClick={() => sessionId && loadTrajectory(sessionId)}
            disabled={trajectoryLoading || !sessionId}
            title={t('trajectory.refresh')}
          >
            <span className={cx('codicon', trajectoryLoading ? 'codicon-loading spin' : 'codicon-refresh')} />
          </button>
          <button className="traj-dialog__icon-btn" onClick={onClose} title={t('trajectory.close')}>
            <span className="codicon codicon-close" />
          </button>
        </div>

        <div className="traj-dialog__body">
          {trajectoryLoading && records.length === 0 ? (
            <div className="traj-dialog__empty">
              <span className="codicon codicon-loading spin" /> {t('trajectory.loading')}
            </div>
          ) : trajectoryError ? (
            <div className="traj-dialog__empty traj-dialog__empty--error">
              <span className="codicon codicon-error" /> {t('trajectory.error')}
              <pre>{trajectoryError}</pre>
            </div>
          ) : records.length === 0 ? (
            <div className="traj-dialog__empty">
              <span className="codicon codicon-circuit-board" />
              {t('trajectory.empty')}
              {sessionId && (
                <>
                  <div className="traj-dialog__empty-reason">
                    {(trajectory?.sourceFiles?.length ?? 0) > 0
                      ? t('trajectory.emptyNoRecord')
                      : t('trajectory.emptyNoFile')}
                  </div>
                  <div className="traj-dialog__empty-paths">
                    {((trajectory?.sourceFiles?.length ?? 0) > 0
                      ? trajectory?.sourceFiles
                      : expectedModelIoPaths(sessionId)
                    )?.join('\n')}
                  </div>
                </>
              )}
            </div>
          ) : (
            <div className="traj-dialog__list">
              {records.map((r) => (
                <TrajectoryRow
                  key={r.requestId + r.startedAt}
                  record={r}
                  onOpen={() => setDetail(r)}
                />
              ))}
            </div>
          )}
          {trajectory?.truncated && records.length > 0 && (
            <div className="traj-dialog__truncated">
              {t('trajectory.truncatedNotice', {
                shown: records.length,
                total: trajectory.totalCount,
                hidden: Math.max(0, trajectory.totalCount - records.length),
              })}
            </div>
          )}
        </div>
      </div>
      {detail && <TrajectoryDetailDialog record={detail} onClose={() => setDetail(null)} />}
    </div>
  )
}
