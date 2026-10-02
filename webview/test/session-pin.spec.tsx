/**
 * 会话置顶组件流测试（SessionItem pin 按钮 + HistoryView 置顶排序）
 *
 * 行为：
 *   1. SessionItem：pinned=true 渲染 codicon-pinned 常显按钮，点击回 onTogglePin(sid,false)
 *   2. SessionItem：pinned=false 渲染 codicon-pin，点击回 onTogglePin(sid,true)
 *   3. HistoryView：pinnedSessionIds 命中的行排在未置顶行之前（列表序断言）
 *   4. HistoryView：已归档 tab 不传 onTogglePin（归档页无置顶概念）
 */
// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, cleanup } from '@testing-library/react'

vi.mock('@/ipc/bridge', () => ({
  sendToJava: () => {},
  isInJcef: () => false,
  onMessage: () => () => {},
}))

import '@/i18n/config'
import { SessionItem } from '@/components/SessionItem'
import { HistoryView } from '@/components/HistoryView'
import type { SessionInfo } from '@/types/messages'

function session(id: string, title: string): SessionInfo {
  return {
    sessionId: id,
    title,
    status: 'idle',
    mode: 'yolo',
    workspacePath: 'G:\\mock',
    createdAt: 1,
    updatedAt: 1,
  }
}

const S_A = session('sess_a', '会话甲')
const S_B = session('sess_b', '会话乙')

function findPinButton(): HTMLElement {
  // pin 按钮带 aria title（zh：置顶/取消置顶），两态都用 codicon 图标类区分
  const el = document.querySelector('.session-item__pin')
  if (!el) throw new Error('pin 按钮未渲染')
  return el as HTMLElement
}

beforeEach(() => {
  cleanup()
})

describe('SessionItem 置顶按钮', () => {
  it('pinned=true：codicon-pinned，点击回调 unpin(false)', () => {
    const onTogglePin = vi.fn()
    render(<SessionItem session={S_A} active={false} onSelect={vi.fn()} pinned onTogglePin={onTogglePin} />)
    const btn = findPinButton()
    expect(btn.querySelector('.codicon-pinned')).toBeTruthy()
    expect(btn.getAttribute('title')).toBe('取消置顶')
    fireEvent.click(btn)
    expect(onTogglePin).toHaveBeenCalledWith('sess_a', false)
  })

  it('pinned=false：codicon-pin，点击回调 pin(true)；不触发会话选中', () => {
    const onTogglePin = vi.fn()
    const onSelect = vi.fn()
    render(<SessionItem session={S_A} active={false} onSelect={onSelect} pinned={false} onTogglePin={onTogglePin} />)
    const btn = findPinButton()
    expect(btn.querySelector('.codicon-pin')).toBeTruthy()
    expect(btn.getAttribute('title')).toBe('置顶')
    fireEvent.click(btn)
    expect(onTogglePin).toHaveBeenCalledWith('sess_a', true)
    expect(onSelect).not.toHaveBeenCalled()
  })

  it('无 onTogglePin（归档变体）不渲染 pin 按钮', () => {
    render(<SessionItem session={S_A} active={false} onSelect={vi.fn()} variant="archived" />)
    expect(document.querySelector('.session-item__pin')).toBeNull()
  })
})

describe('HistoryView 置顶排序', () => {
  function renderHistory(sessions: SessionInfo[], pinnedSessionIds: string[]) {
    render(
      <HistoryView
        sessions={sessions}
        archivedSessions={[]}
        archivedLoading={false}
        currentSessionId={null}
        onLocate={vi.fn().mockResolvedValue(false)}
        onOpenNewTab={vi.fn()}
        onSelect={vi.fn()}
        onBack={vi.fn()}
        onArchive={vi.fn()}
        onRestore={vi.fn()}
        onDeleteArchived={vi.fn()}
        onTogglePin={vi.fn()}
        pinnedSessionIds={pinnedSessionIds}
        onRefresh={vi.fn()}
        onLoadArchived={vi.fn()}
      />,
    )
  }

  it('置顶行排在未置顶行之前（输入已按 updatedAt 倒序）', () => {
    renderHistory([S_A, S_B], ['sess_b'])
    const items = document.querySelectorAll('.history-items .session-item')
    expect(items).toHaveLength(2)
    expect(items[0].textContent).toContain('会话乙')
    expect(items[1].textContent).toContain('会话甲')
  })

  it('置顶行渲染 codicon-pinned 常显态', () => {
    renderHistory([S_A, S_B], ['sess_a'])
    const pinnedBtn = document.querySelector('.session-item__pin--pinned')
    expect(pinnedBtn).toBeTruthy()
    expect(pinnedBtn!.querySelector('.codicon-pinned')).toBeTruthy()
  })

  it('空置顶集不改变列表顺序', () => {
    renderHistory([S_A, S_B], [])
    const items = document.querySelectorAll('.history-items .session-item')
    expect(items[0].textContent).toContain('会话甲')
    expect(items[1].textContent).toContain('会话乙')
  })
})
