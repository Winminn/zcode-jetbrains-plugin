/**
 * 设置视图（对齐 cc-gui SettingsView 结构）
 *
 * 布局：
 *   ┌─────────────────────────────────────┐
 *   │ [←] 设置            （顶部标题栏）    │  ← 返回图标在标题栏左侧
 *   ├──────┬──────────────────────────────┤
 *   │  📊  │                              │  ← 窄边栏（纯图标，hover tooltip 文字）
 *   │      │      内容区（UsageView）      │
 *   └──────┴──────────────────────────────┘
 *
 * 左侧 nav 目前只有「用量查询」一个条目（预留扩展：常规/模型/MCP…）。
 */

import { useState } from 'react'
import { UsageView } from './UsageView'
import '../styles/settings.less'

type SettingsTab = 'usage'

const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(' ')

interface Props {
  onBack: () => void
}

export function SettingsView({ onBack }: Props) {
  const [tab, setTab] = useState<SettingsTab>('usage')

  const navItems: { key: SettingsTab; icon: string; label: string }[] = [
    { key: 'usage', icon: 'codicon-graph', label: '用量查询' },
  ]

  return (
    <div className="settings-view">
      {/* 顶部标题栏（对齐 cc-gui SettingsHeader）*/}
      <header className="settings-view__header">
        <div className="settings-view__header-left">
          <button className="settings-view__back" onClick={onBack} title="返回聊天">
            <span className="codicon codicon-arrow-left" />
          </button>
          <h2 className="settings-view__title">设置</h2>
        </div>
      </header>

      <div className="settings-view__main">
        {/* 左侧窄边栏：纯图标，hover title 显示文字 */}
        <aside className="settings-view__sidebar">
          {navItems.map((it) => (
            <button
              key={it.key}
              className={cx('settings-view__nav-item', tab === it.key && 'active')}
              onClick={() => setTab(it.key)}
              title={it.label}
            >
              <span className={cx('codicon', it.icon)} />
            </button>
          ))}
        </aside>

        {/* 右侧内容区 */}
        <main className="settings-view__content">
          {tab === 'usage' && <UsageView />}
        </main>
      </div>
    </div>
  )
}
