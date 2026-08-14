/**
 * 聊天视图：消息列表容器（cc-gui ChatScreen 简化版）
 *
 * - 滚动展示所有消息（自动滚底，用户上滚不强制）
 * - 无消息时显示 WelcomeScreen
 * - 左侧 MessageAnchorRail 锚点导航
 * - 右上角 ConversationSearch 会话内搜索浮层
 * - 停止生成按钮已移到输入框（发送/停止互斥）
 */

import { useEffect, useRef } from 'react'
import type { ZCodeMessage } from '@/types/messages'
import { MessageBubble } from './MessageBubble'
import { WaitingIndicator } from './WaitingIndicator'
import { WelcomeScreen } from './WelcomeScreen'
import { MessageAnchorRail } from './MessageAnchorRail'
import { ConversationSearch } from './ConversationSearch'
import { isAgentNotification } from '@/utils/parseNotification'
import '../styles/chat-view.less'

interface Props {
  messages: ZCodeMessage[]
  loading: boolean
  waiting: boolean
  waitingSince?: number
  streamingMessageId?: string | null
  /** 无会话状态（提示新建，区别于空会话欢迎页）*/
  noSession?: boolean
  /** 会话内搜索面板开关（App 级状态，Ctrl+F / Header 搜索按钮触发）*/
  searchOpen?: boolean
  /** 关闭搜索面板 */
  onSearchClose?: () => void
}

/** 计算最后一条消息的内容指纹（流式增长时变化 → 触发滚动）*/
function lastMessageFingerprint(messages: ZCodeMessage[]): string {
  const last = messages[messages.length - 1]
  if (!last) return ''
  // 拼接所有 part 的长度（text/reasoning 的内容增长会改变指纹）
  return last.parts.map((p) => {
    if (p.type === 'text') return `t${p.text.length}`
    if (p.type === 'reasoning') return `r${p.text.length}`
    if (p.type === 'tool') return `o${p.callID}${p.state.status}`
    return p.type
  }).join(',') + '#' + messages.length
}

export function ChatView({ messages, loading, waiting, waitingSince, streamingMessageId, noSession, searchOpen, onSearchClose }: Props) {
  const bottomRef = useRef<HTMLDivElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const userScrolledUp = useRef(false)
  const prevLastId = useRef<string | undefined>(undefined)
  const fingerprint = lastMessageFingerprint(messages)

  const handleScroll = () => {
    const el = containerRef.current
    if (!el) return
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80
    userScrolledUp.current = !nearBottom
  }

  // 消息变化（含流式内容增长）+ 新消息 + waiting 变化时滚动
  useEffect(() => {
    const el = containerRef.current
    if (!el) return
    const last = messages[messages.length - 1]
    // 用户刚发送消息（末尾新增真实 user 消息）→ 强制滚到底，并重置上滑标志，
    // 让后续流式回复自动跟滚（即使发送前用户上滑阅读过历史）
    const userJustSent =
      !!last &&
      last.info.role === 'user' &&
      !isAgentNotification(last.info) &&
      last.info.id !== prevLastId.current
    if (last) prevLastId.current = last.info.id
    if (userJustSent) userScrolledUp.current = false
    if (userJustSent || !userScrolledUp.current) {
      // 直接设 scrollTop（比 scrollIntoView 更可靠，不依赖布局完成）
      el.scrollTop = el.scrollHeight
    }
  }, [fingerprint, waiting])

  if (loading) {
    return (
      <div className="messages-shell">
        <div className="chat-view__loading">
          <span className="chat-view__loading-dots"><span /><span /><span /></span>
          加载消息中…
        </div>
      </div>
    )
  }

  if (messages.length === 0 && !waiting) {
    return (
      <div className="messages-shell">
        <WelcomeScreen noSession={noSession} />
      </div>
    )
  }

  return (
    <div className="messages-shell">
      <MessageAnchorRail messages={messages} containerRef={containerRef} />
      {/* 会话内搜索浮层（消息变化即重扫：fingerprint 覆盖流式追加与切会话重拉）*/}
      <ConversationSearch
        open={!!searchOpen}
        onClose={() => onSearchClose?.()}
        containerRef={containerRef}
        messagesSignal={`${fingerprint}|${streamingMessageId ?? ''}`}
      />
      <div className="messages-container" ref={containerRef} onScroll={handleScroll}>
        <div className="chat-view__inner">
          {messages.map((m) => (
            <MessageBubble
              key={m.info.id}
              message={m}
              streaming={m.info.id === streamingMessageId}
              anchorAttr={m.info.role === 'user' && !isAgentNotification(m.info) ? m.info.id : undefined}
            />
          ))}
          {waiting && <WaitingIndicator since={waitingSince} />}
          <div ref={bottomRef} />
        </div>
      </div>
    </div>
  )
}
