/**
 * 思考级别选择下拉（仿 ModelSelect，复用 selector-* 样式）
 *
 * - 数据源：session/read → settings.thoughtLevel.available（服务端权威，因模型而异：
 *   GLM-5.2/deepseek=off/high/max，GLM-4.x/qwen=enabled/off，kimi=low/high/max）
 * - 按钮显示当前级别中文；current 未设置时显示 defaultLevel（后缀「默认」）
 * - 模型不支持思考（enabled=false）或无会话时隐藏
 * - 外部点击 / Escape 关闭；当前选中项高亮
 */

import { useEffect, useRef, useState } from 'react'
import { useStore } from '@/store/useStore'

/** 级别 value → 中文标签（ZCode 客户端 UI 本地化同款，未知值原样显示）
 *  实测补充：部分模型用 disabled / nothink 表示关闭思考（与 off 同义）*/
const LEVEL_LABELS: Record<string, string> = {
  off: '不思考',
  disabled: '不思考',
  nothink: '不思考',
  low: '低',
  medium: '中',
  high: '高',
  max: '最高',
  enabled: '思考',
}

/** 级别图标（off/disabled/nothink=禁止圈 / low·enabled=灯泡 / medium·high=灯泡火花 / max=火箭）*/
const LEVEL_ICONS: Record<string, string> = {
  off: 'codicon-circle-slash',
  disabled: 'codicon-circle-slash',
  nothink: 'codicon-circle-slash',
  low: 'codicon-lightbulb',
  medium: 'codicon-lightbulb-sparkle',
  high: 'codicon-lightbulb-sparkle',
  max: 'codicon-rocket',
  enabled: 'codicon-lightbulb',
}
const levelIcon = (v: string) => LEVEL_ICONS[v] ?? 'codicon-lightbulb'

function levelLabel(value: string): string {
  return LEVEL_LABELS[value] ?? value
}

export function ThoughtLevelSelect() {
  const thoughtLevel = useStore((s) => s.thoughtLevel)
  const sessionId = useStore((s) => s.currentSessionId)
  const setThoughtLevel = useStore((s) => s.setThoughtLevel)
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)

  // 外部点击 / Escape 关闭（须在条件 return 之前，保证 hooks 调用顺序恒定）
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

  // 模型不支持思考级别 / 无数据 / 无会话时整个隐藏
  const levels = thoughtLevel?.available ?? []
  if (!thoughtLevel?.enabled || levels.length === 0 || !sessionId) return null

  // 显示值：current → defaultLevel（标注默认）→ 兜底「思考」
  const current = thoughtLevel.current ?? thoughtLevel.defaultLevel
  const displayText = current ? levelLabel(current) : '思考'
  const isDefault = !thoughtLevel.current && !!thoughtLevel.defaultLevel

  return (
    <div className="selector-button-wrap" ref={rootRef}>
      <button
        className="selector-button"
        onClick={() => setOpen((v) => !v)}
        title={isDefault ? `思考级别：${displayText}（模型默认）` : '思考级别'}
      >
        <span className={`codicon ${current ? levelIcon(current) : 'codicon-lightbulb'}`} />
        <span className="selector-button-text">{displayText}</span>
        <span className="codicon codicon-chevron-down selector-button-chevron" />
      </button>

      {open && (
        <div className="selector-dropdown">
          {levels.map((l) => (
            <div
              key={l.value}
              className={`selector-dropdown-item ${current === l.value ? 'is-selected' : ''}`}
              onClick={() => {
                setThoughtLevel(l.value)
                setOpen(false)
              }}
            >
              <span className={`codicon ${levelIcon(l.value)}`} />
              <div className="selector-dropdown-item-main">
                <span className="selector-dropdown-item-name">{levelLabel(l.value)}</span>
                {l.value === thoughtLevel.defaultLevel && (
                  <span className="selector-dropdown-item-sub">默认</span>
                )}
                {current === l.value && <span className="codicon codicon-check selector-dropdown-item-check" />}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
