/**
 * 子代理详情弹窗（原始过程查看）
 *
 * 入口：底部状态面板"子代理"条目点击 / 主聊天 Agent 工具卡点击。
 *
 * 数据分三层（按运行状态取最优）：
 * - 运行中：childLiveMessages——子会话原生事件流（含 AI 文本增量）的实时归约，
 *   完整对话实时滚动；即使手动拉过快照（childMessages）也优先实时流；
 * - 已结束：childMessages——subagentMessages op（resume + session/messages）的
 *   权威全量转录；由 stopped 通知 / turn 结束自动拉取；
 * - 兜底：subagentActivities 的实时工具列表（父会话转发的工具事件聚合，
 *   子会话事件流缺失时——如历史会话——至少展示工具过程）。
 */

import { useEffect, useRef, useState } from 'react'
import { useStore } from '@/store/useStore'
import { MarkdownBlock } from './MarkdownBlock'
import { ToolCallCard } from './ToolCallCard'
import type { ZCodeMessage } from '@/types/messages'
import '../styles/subagent-detail.less'

/** 秒级耗时格式化（子代理 startedAt/endedAt 是 ms 时间戳）*/
function formatDuration(startedAt?: number, endedAt?: number): string {
  if (!startedAt) return ''
  const end = endedAt ?? Date.now()
  const sec = Math.max(0, Math.round((end - startedAt) / 1000))
  if (sec < 60) return `${sec}s`
  const min = Math.floor(sec / 60)
  return min < 60 ? `${min}m${sec % 60}s` : `${Math.floor(min / 60)}h${min % 60}m`
}

/** 状态徽标文案 */
function statusText(status: string | undefined): { text: string; cls: string } {
  switch (status) {
    case 'running': return { text: '运行中', cls: 'running' }
    case 'completed': return { text: '已完成', cls: 'completed' }
    case 'error': return { text: '失败', cls: 'error' }
    default: return { text: '等待中', cls: 'pending' }
  }
}

/** 子会话完整消息 → 转录（user prompt + assistant 文本/工具，复用主聊天渲染组件）*/
function Transcript({ messages }: { messages: ZCodeMessage[] }) {
  return (
    <div className="subagent-detail-transcript">
      {messages.map((msg, i) => (
        <div key={msg.info.id || i} className={`subagent-detail-msg role-${msg.info.role}`}>
          <div className="subagent-detail-msg-role">
            {msg.info.role === 'user' ? '任务' : 'AI'}
          </div>
          <div className="subagent-detail-msg-body">
            {msg.parts.map((part, j) => {
              if (part.type === 'text' && part.text.trim()) {
                return <MarkdownBlock key={j} markdown={part.text} />
              }
              if (part.type === 'tool') {
                return <ToolCallCard key={j} part={part} />
              }
              return null
            })}
          </div>
        </div>
      ))}
    </div>
  )
}

export function SubagentDetailDialog() {
  const detailKey = useStore((s) => s.subagentDetail)
  const agents = useStore((s) => s.agents)
  const activities = useStore((s) => s.subagentActivities)
  const subagents = useStore((s) => s.subagents)
  const childMessages = useStore((s) => s.childMessages)
  const childLiveMessages = useStore((s) => s.childLiveMessages)
  const loading = useStore((s) => s.childMessagesLoading)
  const error = useStore((s) => s.childMessagesError)
  const messages = useStore((s) => s.messages)
  const closeDetail = useStore((s) => s.closeSubagentDetail)
  const loadChildMessages = useStore((s) => s.loadChildMessages)
  const stopSubagent = useStore((s) => s.stopSubagent)
  // 停止按钮防重复点击（受理后等事件流收尾，状态变非 running 时按钮消失）
  const [stopping, setStopping] = useState(false)

  const key = detailKey
  const item = key ? agents.find((a) => a.callID === key) : undefined
  const activity = key ? activities.find((a) => a.key === key) : undefined
  const info = key ? subagents.find((s) => s.toolCallId === key) : undefined

  const childSessionId = item?.childSessionId ?? activity?.childSessionId ?? info?.childSessionId
  const transcript = childSessionId ? childMessages[childSessionId] : undefined
  const liveMessages = childSessionId ? childLiveMessages[childSessionId] : undefined
  const running = item?.status === 'running' || item?.status === 'pending'
  // 显示源：运行中实时流优先（手动拉的快照不覆盖实时）；结束后权威快照优先
  const display = running ? (liveMessages ?? transcript) : (transcript ?? liveMessages)

  // 自动滚底（与 ChatView 同策略）：距底 80px 内才跟随内容滚动，用户上滑即停
  const bodyRef = useRef<HTMLDivElement>(null)
  const userScrolledUp = useRef(false)
  const handleScroll = () => {
    const el = bodyRef.current
    if (!el) return
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80
    userScrolledUp.current = !nearBottom
  }
  // 内容指纹：实时工具追加/状态变化、实时流文本增长、转录加载完成、loading 切换都会变
  const activityFingerprint = activity?.tools
    .map((t) => `${t.callID}:${t.state.status}:${t.state.output?.length ?? 0}`)
    .join(',') ?? ''
  const displayFingerprint = (display ?? [])
    .map((m) => m.parts.map((p) =>
      p.type === 'text' ? `t${p.text.length}`
      : p.type === 'reasoning' ? `r${p.text.length}`
      : p.type === 'tool' ? `o${p.callID}${p.state.status}${p.state.output?.length ?? 0}`
      : p.type,
    ).join(','))
    .join('|')
  const contentFingerprint = `${activityFingerprint}#${displayFingerprint}#${loading}`
  // 打开/切换子代理详情 → 重置上滑标志并定位到底部（最新进展）
  useEffect(() => {
    userScrolledUp.current = false
    setStopping(false)
    const el = bodyRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [key])
  // 内容增长时跟随滚底（用户上滑阅读历史时不打扰）
  useEffect(() => {
    const el = bodyRef.current
    if (!el || userScrolledUp.current) return
    el.scrollTop = el.scrollHeight
  }, [contentFingerprint])

  // 已结束且有 childSessionId 但未加载 → 自动拉完整过程（运行中不拉，见文件头）
  useEffect(() => {
    if (!key || !childSessionId || transcript || running || loading || error) return
    loadChildMessages(childSessionId)
  }, [key, childSessionId, transcript, running, loading, error, loadChildMessages])

  // Escape 关闭
  useEffect(() => {
    if (!key) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') closeDetail()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [key, closeDetail])

  if (!key) return null

  const duration = formatDuration(item?.startedAt ?? info?.startedAt, item?.endedAt ?? info?.endedAt)
  const badge = statusText(item?.status)
  const toolCount = activity?.tools.length ?? 0

  const handleRefresh = () => {
    if (childSessionId) loadChildMessages(childSessionId)
  }

  // 主聊天里 Agent 工具 part 的 output（最终报告，childSessionId 缺失时的兜底内容）
  let agentOutput = ''
  for (const m of messages) {
    for (const p of m.parts) {
      if (p.type === 'tool' && p.callID === key && p.state.output) agentOutput = p.state.output
    }
  }

  return (
    <div className="subagent-detail-overlay" onClick={closeDetail}>
      <div className="subagent-detail-dialog" onClick={(e) => e.stopPropagation()}>
        <div className="subagent-detail-header">
          <span className="codicon codicon-hubot subagent-detail-header__icon" />
          <div className="subagent-detail-header__main">
            <span className="subagent-detail-header__title" title={item?.description}>
              {item?.description || activity?.description || '子代理任务'}
            </span>
            <div className="subagent-detail-header__meta">
              {(item?.subagentType || activity?.agentType || info?.subagentType) && (
                <span className="subagent-detail-badge type">
                  {item?.subagentType || activity?.agentType || info?.subagentType}
                </span>
              )}
              <span className={`subagent-detail-badge ${badge.cls}`}>{badge.text}</span>
              {duration && <span className="subagent-detail-meta-item">{duration}</span>}
              {toolCount > 0 && <span className="subagent-detail-meta-item">{toolCount} 个工具</span>}
            </div>
          </div>
          {/* 运行中：手动停止子代理（cancelBackgroundTask 优先、停主 turn 兜底；
              停止后事件流自然收尾：子会话终止 → Agent 中断结果 → stopped → 权威转录）*/}
          {running && childSessionId && (
            <button
              className="subagent-detail-icon-btn subagent-detail-stop-btn"
              title={stopping ? '停止请求已发出…' : '停止子代理（若无法单独停止，将中断主代理当前回合）'}
              disabled={stopping}
              onClick={() => {
                setStopping(true)
                stopSubagent(childSessionId, info?.agentId ?? activity?.agentId)
              }}
            >
              <span className={`codicon ${stopping ? 'codicon-loading spin' : 'codicon-debug-stop'}`} />
            </button>
          )}
          {childSessionId && (
            <button className="subagent-detail-icon-btn" title="重新加载完整记录" onClick={handleRefresh}>
              <span className={`codicon codicon-refresh ${loading ? 'spin' : ''}`} />
            </button>
          )}
          <button className="subagent-detail-icon-btn" title="关闭" onClick={closeDetail}>
            <span className="codicon codicon-chrome-close" />
          </button>
        </div>

        <div className="subagent-detail-body" ref={bodyRef} onScroll={handleScroll}>
          {error && (
            <div className="subagent-detail-error">
              <span className="codicon codicon-error" />
              <span>加载失败：{error}</span>
              {childSessionId && (
                <button className="subagent-detail-retry" onClick={handleRefresh}>重试</button>
              )}
            </div>
          )}

          {/* 层1：完整对话（运行中=实时流；已结束=权威转录）*/}
          {display && display.length > 0 && <Transcript messages={display} />}

          {/* 层2 回退：无对话流（事件流缺失，如历史会话）→ 父会话转发的实时工具列表 */}
          {!display && toolCount > 0 && (
            <>
              {running && (
                <div className="subagent-detail-hint">
                  <span className="codicon codicon-loading spin" /> 子代理运行中（子会话事件流未就绪，显示转发的工具事件）。
                </div>
              )}
              <div className="subagent-detail-tools">
                {activity!.tools.map((t) => <ToolCallCard key={t.callID} part={t} />)}
              </div>
            </>
          )}

          {/* 层3：无实时数据 → 兜底显示 Agent 工具的最终报告 */}
          {!display && toolCount === 0 && agentOutput && (
            <div className="subagent-detail-fallback">
              <div className="subagent-detail-hint">无子会话记录，以下为子代理最终报告：</div>
              <MarkdownBlock markdown={agentOutput} />
            </div>
          )}

          {/* 层4：什么都没有 */}
          {!display && toolCount === 0 && !agentOutput && (
            <div className="subagent-detail-empty">
              {loading ? '正在加载子代理记录…' : '暂无子代理过程数据'}
              {childSessionId && !loading && (
                <button className="subagent-detail-retry" onClick={handleRefresh}>加载完整记录</button>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
