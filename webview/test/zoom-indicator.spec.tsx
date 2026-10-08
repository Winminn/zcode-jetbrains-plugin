/**
 * 浏览器缩放指示器回归测试（Ctrl+滚轮缩放百分比胶囊）
 *
 * 信号链（Chromium 原生缩放无事件可拦）：devicePixelRatio 变化 + resize →
 * 节流 zoomQuery → Java zoomLevel 权威回包 → 胶囊展示。
 *
 * 覆盖：
 *   - 启动首查：恒发 zoomQuery；回包 100% 不出胶囊（正常启动不打扰）
 *   - 启动恢复非 100%：胶囊直接展示（持久 origin 重载场景）+ 3s 自动消失
 *   - 悬停暂停倒计时（移开重新计时）
 *   - dPR 变化 + resize：节流（150ms 合并）后查询，回包驱动胶囊刷新
 *   - 普通 resize（dPR 不变）：不触发查询
 *   - Ctrl+0 / 点击胶囊：发 zoomReset + 乐观展示 100%
 */
// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, cleanup, act } from '@testing-library/react'

type SendOp = { op: string }
let sent: SendOp[] = []
let messageHandler: ((msg: unknown) => void) | null = null

vi.mock('@/ipc/bridge', () => ({
  onMessage: (fn: (msg: unknown) => void) => {
    messageHandler = fn
    return () => {
      messageHandler = null
    }
  },
  sendToJava: (req: SendOp) => {
    sent.push(req)
  },
}))

import '@/i18n/config'
import { ZoomIndicator } from '@/components/ZoomIndicator'

/** jsdom 的 dPR 只读，测试用 defineProperty 覆写模拟浏览器缩放 */
function setDpr(v: number) {
  Object.defineProperty(window, 'devicePixelRatio', { value: v, configurable: true })
}

function respond(percent: number) {
  act(() => {
    messageHandler!({ op: 'zoomLevel', percent })
  })
}

beforeEach(() => {
  vi.useFakeTimers()
  sent = []
  messageHandler = null
  setDpr(1)
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  setDpr(1)
})

describe('浏览器缩放指示器', () => {
  it('挂载即发 zoomQuery；回包 100% 不出胶囊（正常启动不打扰）', () => {
    render(<ZoomIndicator />)
    expect(sent).toEqual([{ op: 'zoomQuery' }])
    respond(100)
    expect(screen.queryByText('100%')).toBeNull()
  })

  it('启动恢复非 100% 直接展示，3s 后自动消失', () => {
    render(<ZoomIndicator />)
    respond(110)
    expect(screen.getByText('110%')).toBeTruthy()
    act(() => {
      vi.advanceTimersByTime(3000)
    })
    expect(screen.queryByText('110%')).toBeNull()
  })

  it('悬停暂停倒计时，移开重新计时后消失（真机反馈 1s 来不及点）', () => {
    render(<ZoomIndicator />)
    respond(110)
    const pill = screen.getByText('110%')
    fireEvent.mouseEnter(pill)
    act(() => {
      vi.advanceTimersByTime(5000) // 远超驻留时长，悬停中不消失
    })
    expect(screen.getByText('110%')).toBeTruthy()
    fireEvent.mouseLeave(pill)
    act(() => {
      vi.advanceTimersByTime(2999)
    })
    expect(screen.getByText('110%')).toBeTruthy()
    act(() => {
      vi.advanceTimersByTime(1)
    })
    expect(screen.queryByText('110%')).toBeNull()
  })

  it('dPR 变化 + resize 触发节流查询，回包驱动胶囊刷新', () => {
    render(<ZoomIndicator />)
    respond(100) // 消耗启动首查
    expect(sent).toHaveLength(1)
    setDpr(1.25)
    act(() => {
      window.dispatchEvent(new Event('resize'))
    })
    // 节流：未到 150ms 不发查询
    expect(sent).toHaveLength(1)
    act(() => {
      vi.advanceTimersByTime(150)
    })
    expect(sent).toHaveLength(2)
    respond(125)
    expect(screen.getByText('125%')).toBeTruthy()
  })

  it('普通 resize（dPR 不变）不触发查询', () => {
    render(<ZoomIndicator />)
    respond(100)
    act(() => {
      window.dispatchEvent(new Event('resize'))
      vi.advanceTimersByTime(500)
    })
    expect(sent).toHaveLength(1)
  })

  it('Ctrl+0 重置：发 zoomReset 并乐观展示 100%', () => {
    render(<ZoomIndicator />)
    respond(100)
    fireEvent.keyDown(document, { key: '0', ctrlKey: true })
    expect(sent.map((r) => r.op)).toContain('zoomReset')
    expect(screen.getByText('100%')).toBeTruthy()
  })

  it('普通按键（无修饰键的 0）不触发重置', () => {
    render(<ZoomIndicator />)
    respond(100)
    fireEvent.keyDown(document, { key: '0' })
    expect(sent.map((r) => r.op)).not.toContain('zoomReset')
  })

  it('点击胶囊重置：发 zoomReset 并回 100%', () => {
    render(<ZoomIndicator />)
    respond(100)
    setDpr(1.5)
    act(() => {
      window.dispatchEvent(new Event('resize'))
      vi.advanceTimersByTime(150)
    })
    respond(150)
    const pill = screen.getByText('150%')
    fireEvent.click(pill)
    expect(sent.map((r) => r.op)).toContain('zoomReset')
    expect(screen.getByText('100%')).toBeTruthy()
  })
})
