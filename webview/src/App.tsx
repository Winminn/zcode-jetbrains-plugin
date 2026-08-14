/**
 * 主布局（cc-gui 风格：无侧边栏，Header 视图切换）
 *
 * currentView: 'chat' | 'history' | 'settings'
 *   - chat：常驻挂载（切走时 display:none，保留滚动位置）
 *   - history：历史会话列表（条件渲染）
 *   - settings：设置视图（左侧导航 + 用量查询）
 *
 * Header 按钮：新会话/历史/设置已实现；新Tab→createTab op；搜索→会话内搜索面板
 */

import { useEffect, useRef, useState } from 'react'
import { useStore } from '@/store/useStore'
import { useTheme } from '@/hooks/useTheme'
import { ChatHeader } from '@/components/ChatHeader'
import { ChatView } from '@/components/ChatView'
import { StatusPanel } from '@/components/StatusPanel'
import { HistoryView } from '@/components/HistoryView'
import { SettingsView } from '@/components/SettingsView'
import { InputBox } from '@/components/InputBox'
import { AskUserDialog } from '@/components/AskUserDialog'
import { PlanApprovalDialog } from '@/components/PlanApprovalDialog'
import { SubagentDetailDialog } from '@/components/SubagentDetailDialog'
import { sendToJava } from '@/ipc/bridge'
import './styles/global.less'
import './styles/buttons.less'

type View = 'chat' | 'history' | 'settings'

export default function App() {
  const {
    sessions, currentSessionId,
    messages, loadingMessages, streaming, streamingMessageId, waitingSince, lastError,
    askUser, exitPlanApproval, currentModel,
    init, loadSessions, selectSession, createSession, deleteSession, stopStreaming, sendMessage,
    clearError,
    renameSession, setModel,
  } = useStore()

  // IDE 主题同步
  useTheme()

  // 视图切换
  const [currentView, setCurrentView] = useState<View>('chat')

  // 会话内搜索面板（仅 chat 视图）
  const [searchOpen, setSearchOpen] = useState(false)

  // 离开 chat 视图自动关闭搜索（清理由面板卸载 effect 兜底）
  useEffect(() => {
    if (currentView !== 'chat') setSearchOpen(false)
  }, [currentView])

  // Ctrl+F / Cmd+F 打开会话内搜索（capture 阶段拦截；跳过 IME 组合态）
  useEffect(() => {
    if (currentView !== 'chat') return
    const handler = (e: KeyboardEvent) => {
      if (e.isComposing) return
      const isMac = navigator.platform.toLowerCase().includes('mac')
      const key = e.key.toLowerCase()
      if ((isMac ? e.metaKey : e.ctrlKey) && key === 'f') {
        e.preventDefault()
        setSearchOpen(true)
      }
    }
    document.addEventListener('keydown', handler, true)
    return () => document.removeEventListener('keydown', handler, true)
  }, [currentView])

  // 轻量 toast（暂未支持提示）
  const [toast, setToast] = useState<string | null>(null)
  useEffect(() => {
    if (!toast) return
    const timer = setTimeout(() => setToast(null), 2000)
    return () => clearTimeout(timer)
  }, [toast])

  useEffect(() => {
    init()
  }, [init])

  const currentSession = sessions.find((s) => s.sessionId === currentSessionId)
  // 标题：列表里的标题（CLI 会随对话更新）→ 会话 id 前缀（列表尚未刷新时）→ 空
  const sessionTitle =
    currentSession?.title ?? (currentSessionId ? currentSessionId.slice(0, 12) : '')

  // 会话标题推给 Java 作标签 tooltip（悬停显示会话名；标签本身保持「会话N」编号）。
  // 变化时防抖 500ms；只在本 webview 连接 Java 时发送（mock 模式静默丢弃）
  const lastPushedTitleRef = useRef('')
  useEffect(() => {
    const effective = sessionTitle || (currentSessionId ? currentSessionId.slice(0, 12) : '')
    if (!effective || effective === lastPushedTitleRef.current) return
    const timer = setTimeout(() => {
      lastPushedTitleRef.current = effective
      sendToJava({ op: 'setTabTitle', title: effective.slice(0, 50), sessionId: currentSessionId ?? undefined })
    }, 500)
    return () => clearTimeout(timer)
  }, [sessionTitle, currentSessionId])

  return (
    <div className="app">
      <ChatHeader
        currentView={currentView}
        sessionTitle={sessionTitle}
        onBack={() => setCurrentView('chat')}
        onNewSession={createSession}
        onNewTab={() => sendToJava({ op: 'createTab' })}
        onSearch={() => setSearchOpen(true)}
        onHistory={() => setCurrentView('history')}
        onSettings={() => setCurrentView('settings')}
        onTitleChange={(t) => {
          if (currentSessionId) renameSession(currentSessionId, t)
        }}
      />

      <div className="app__body">
        {currentView === 'settings' ? (
          <SettingsView onBack={() => setCurrentView('chat')} />
        ) : (
          <>
            {/* chat 视图常驻挂载（切历史时 display:none，保留消息滚动位置）*/
            /* 无会话时也渲染（WelcomeScreen + 禁用输入框）*/}
            <div
              style={
                currentView === 'chat'
                  ? { display: 'flex', flex: 1, minHeight: 0, flexDirection: 'column', overflow: 'hidden' }
                  : { display: 'none' }
              }
            >
              <ChatView
                messages={messages}
                loading={loadingMessages}
                waiting={streaming}
                waitingSince={waitingSince ?? undefined}
                streamingMessageId={streamingMessageId}
                noSession={!currentSessionId}
                searchOpen={searchOpen}
                onSearchClose={() => setSearchOpen(false)}
              />
              <StatusPanel />
              <InputBox
                onSend={(text) => sendMessage(text)}
                isStreaming={streaming}
                onStop={stopStreaming}
                disabled={!currentSessionId}
                currentModel={currentModel}
                onModelSelect={(modelId, providerId) => setModel(modelId, providerId)}
              />
            </div>

            {currentView === 'history' && (
              <HistoryView
                sessions={sessions}
                currentSessionId={currentSessionId}
                onSelect={selectSession}
                onBack={() => setCurrentView('chat')}
                onDelete={deleteSession}
                onRefresh={loadSessions}
              />
            )}
          </>
        )}
      </div>

      {lastError && (
        <div className="app__error-bar">
          <span>⚠️ {lastError}</span>
          <button className="app__error-close" onClick={clearError} title="关闭" aria-label="关闭错误">
            <span className="codicon codicon-close" />
          </button>
        </div>
      )}

      {/* 轻量 toast */}
      {toast && <div className="app__toast">{toast}</div>}

      {/* AskUserQuestion 弹窗 */}
      {askUser && (
        <AskUserDialog
          requestId={askUser.requestId}
          toolName={askUser.toolName}
          questions={askUser.questions}
          onClose={() => useStore.setState({ askUser: null })}
        />
      )}

      {/* ExitPlanMode 计划审批弹窗 */}
      {exitPlanApproval && (
        <PlanApprovalDialog
          requestId={exitPlanApproval.requestId}
          plan={exitPlanApproval.plan}
          onClose={() => useStore.setState({ exitPlanApproval: null })}
        />
      )}

      {/* 子代理详情弹窗（底部子代理栏 / Agent 工具卡点击打开，store 自管理开关）*/}
      <SubagentDetailDialog />
    </div>
  )
}
