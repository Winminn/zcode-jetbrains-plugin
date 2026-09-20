/**
 * 上下文圆环双击压缩测试（0.3.8 新功能）
 *
 * 交互链路：双击 ContextRing → ConfirmDialog 确认 → sendMessage('/compact')。
 * 发送与输入框完全同路：streaming 中由 store 自动入队、/compact 前缀触发 compacting 态。
 *
 * 断言：
 *   1. 双击圆环 → 弹确认框（标题「压缩上下文」）
 *   2. 确认 → sendMessage('/compact') 恰好一次
 *   3. 取消 → 不发送
 *   4. 无上下文数据（新会话未对话）→ 双击不响应
 *   5. hover popover 含「双击圆环可压缩上下文」常驻提示行
 */
// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, cleanup } from '@testing-library/react'

vi.mock('@/ipc/bridge', () => ({
  sendToJava: vi.fn(),
}))

import '@/i18n/config'
import { useStore } from '@/store/useStore'
import { ContextRing } from '@/components/ContextRing'

const realSendMessage = useStore.getState().sendMessage
const sendSpy = vi.fn()

beforeEach(() => {
  sendSpy.mockClear()
  useStore.setState({ sendMessage: sendSpy as typeof realSendMessage })
})

afterEach(() => {
  cleanup()
  useStore.setState({ sendMessage: realSendMessage, contextUsage: null })
})

const ring = () => document.querySelector('.context-ring') as HTMLElement

describe('上下文圆环双击压缩', () => {
  it('双击弹出确认框', () => {
    useStore.setState({ contextUsage: { used: 50000, size: 190000, hitRate: 0.8 } })
    render(<ContextRing />)
    fireEvent.doubleClick(ring())
    expect(screen.getByText('压缩上下文')).toBeTruthy()
    expect(screen.getByText(/\/compact/)).toBeTruthy()
  })

  it('确认后发 /compact（与输入框同路，回合中由 store 自动入队）', () => {
    useStore.setState({ contextUsage: { used: 50000, size: 190000, hitRate: 0.8 } })
    render(<ContextRing />)
    fireEvent.doubleClick(ring())
    fireEvent.click(screen.getByRole('button', { name: '确定' }))
    expect(sendSpy).toHaveBeenCalledTimes(1)
    expect(sendSpy).toHaveBeenCalledWith('/compact')
  })

  it('取消不发送', () => {
    useStore.setState({ contextUsage: { used: 50000, size: 190000, hitRate: 0.8 } })
    render(<ContextRing />)
    fireEvent.doubleClick(ring())
    fireEvent.click(screen.getByRole('button', { name: '取消' }))
    expect(sendSpy).not.toHaveBeenCalled()
  })

  it('无上下文数据时双击不响应', () => {
    useStore.setState({ contextUsage: null })
    render(<ContextRing />)
    fireEvent.doubleClick(ring())
    expect(screen.queryByText('压缩上下文')).toBeNull()
    expect(sendSpy).not.toHaveBeenCalled()
  })

  it('hover popover 含双击压缩提示行', () => {
    useStore.setState({ contextUsage: { used: 50000, size: 190000, hitRate: 0.8 } })
    render(<ContextRing />)
    fireEvent.mouseEnter(ring())
    expect(screen.getByText('双击圆环可压缩上下文')).toBeTruthy()
  })
})
