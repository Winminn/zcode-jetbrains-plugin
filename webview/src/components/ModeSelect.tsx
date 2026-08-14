/**
 * 权限模式选择下拉（仿 ModelSelect，复用 selector-* 样式）
 *
 * - 固定 4 项 build/edit/plan/yolo，与 ZCode 客户端 UI 选择器一致
 *   （协议还有 auto，但不可经切换路径设置，不暴露）
 * - 当前值：store currentMode（settings 权威 / setMode 乐观更新）→ 消息流 info.mode 兜底
 * - 切换 = session/setMode；不做 localStorage 记忆（模式是即时意图，新会话默认 yolo）
 * - 外部点击 / Escape 关闭；当前选中项高亮；仅「完全控制」按钮着警示色，其余模式原版
 */

import { useEffect, useMemo, useRef, useState } from 'react'
import { useStore } from '@/store/useStore'

/** 协议 4 模式（value 与服务端一致；label 中文，title 为官方英文描述）*/
const MODES = [
  { value: 'build', label: '变更前询问', title: 'Ask before changes — 每次文件变更前询问' },
  { value: 'edit', label: '自动编辑', title: 'Edit automatically — 自动编辑选中/相关文件' },
  { value: 'plan', label: '计划模式', title: 'Plan mode — 只读检查代码、先出计划再编辑' },
  { value: 'yolo', label: '完全控制', title: 'Full access — 更少确认、直接编辑并运行命令' },
] as const

const MODE_LABELS: Record<string, string> = Object.fromEntries(MODES.map((m) => [m.value, m.label]))

/** 模式图标（build=对话气泡 / edit=盾牌 / plan=任务清单 / yolo=闪电）*/const MODE_ICONS: Record<string, string> = {
  build: 'codicon-comment-discussion',
  edit: 'codicon-shield',
  plan: 'codicon-tasklist',
  yolo: 'codicon-zap',
}
const modeIcon = (v: string) => MODE_ICONS[v] ?? 'codicon-shield'

export function ModeSelect() {
  const currentMode = useStore((s) => s.currentMode)
  const messages = useStore((s) => s.messages)
  const sessionId = useStore((s) => s.currentSessionId)
  const setMode = useStore((s) => s.setMode)
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)

  // 显示值：currentMode → 消息流推断（settings 拉取前）→ 兜底「完全控制」（新会话默认 yolo）
  const displayValue = useMemo(() => {
    if (currentMode) return currentMode
    for (let i = messages.length - 1; i >= 0; i--) {
      const mode = messages[i].info.mode
      if (mode) return mode
    }
    return 'yolo'
  }, [currentMode, messages])

  const displayLabel = MODE_LABELS[displayValue] ?? displayValue
  const activeMode = MODES.find((m) => m.value === displayValue)
  // 激活色 class：仅完全控制（yolo）着橙金警示色，其余模式用原版按钮色
  const activeClass = displayValue === 'yolo' ? 'mode-active-yolo' : ''

  // 外部点击 / Escape 关闭
  useEffect(() => {
    if (!open) return
    const onDocClick = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDocClick)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDocClick)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  return (
    <div className="selector-button-wrap" ref={rootRef}>
      <button
        className={`selector-button ${activeClass}`}
        onClick={() => setOpen((v) => !v)}
        disabled={!sessionId}
        title={activeMode?.title ?? '权限模式'}
      >
        <span className={`codicon ${modeIcon(displayValue)}`} />
        <span className="selector-button-text">{displayLabel}</span>
        <span className="codicon codicon-chevron-down selector-button-chevron" />
      </button>

      {open && (
        <div className="selector-dropdown">
          {MODES.map((m) => (
            <div
              key={m.value}
              className={`selector-dropdown-item ${displayValue === m.value ? 'is-selected' : ''}`}
              title={m.title}
              onClick={() => {
                setMode(m.value)
                setOpen(false)
              }}
            >
              <span className={`codicon ${modeIcon(m.value)}`} />
              <div className="selector-dropdown-item-main">
                <span className="selector-dropdown-item-name">{m.label}</span>
                {displayValue === m.value && <span className="codicon codicon-check selector-dropdown-item-check" />}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
