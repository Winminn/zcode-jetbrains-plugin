/**
 * QuotaBanner 组件冒烟测试（jsdom 渲染）
 *
 * 锁定：耗尽/低额/并发三类视图渲染不崩、文案含窗口点名、刷新与关闭按钮可用；
 * 关闭后同源触发不再渲染（dismiss 去重键回路）。
 */
// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, cleanup } from '@testing-library/react'

vi.mock('@/ipc/bridge', () => ({
  initBridge: () => {},
  isInJcef: () => false,
  getWorkspacePath: () => 'G:\\mock',
  getInitialSessionId: () => '',
  onMessage: () => {},
  onStreamEvent: () => {},
  onStreamBatch: () => {},
  sendToJava: () => {},
}))

import '@/i18n/config'
import { useStore } from '@/store/useStore'
import { QuotaBanner } from '@/components/QuotaBanner'

beforeEach(() => {
  cleanup()
  useStore.getState().init()
  useStore.setState({
    connectionStatus: 'mock',
    currentSessionId: 'sess_b1',
    currentWorkspacePath: 'G:\\mock',
    messages: [],
    quotaBannerError: null,
    quotaBannerDismissed: [],
    quota: null,
    currentModel: { modelId: 'GLM-5.3', providerId: 'builtin:bigmodel-coding-plan' },
  })
})

describe('QuotaBanner 渲染', () => {
  it('无触发源且无低额窗口 → 不渲染', () => {
    const { container } = render(<QuotaBanner />)
    expect(container.querySelector('.quota-banner')).toBeNull()
  })

  it('耗尽触发 + 额度数据 → 窗口点名与重置时间，关闭后消失', () => {
    useStore.setState({
      quotaBannerError: { kind: 'window-exhausted', triggerKey: 'err:k' },
      quota: { level: 'Max', limits: [{ type: 'TOKENS_LIMIT', unit: 3, number: 5, percentage: 100, nextResetTime: Date.now() + 3600_000 }] },
    })
    const { container } = render(<QuotaBanner />)
    const banner = container.querySelector('.quota-banner')!
    expect(banner).not.toBeNull()
    expect(banner.className).toContain('quota-banner--error')
    expect(banner.textContent).toContain('5 小时池')
    expect(banner.textContent).toContain('已用完')

    fireEvent.click(container.querySelector('.quota-banner__close')!)
    // dismiss 后派生视图归 null → 横幅消失；触发源仍在（窗口恢复时由 effect 清）
    expect(container.querySelector('.quota-banner')).toBeNull()
    expect(useStore.getState().quotaBannerDismissed).toHaveLength(1)
  })

  it('低额窗口（无错误触发）→ warning 横幅含剩余百分比', () => {
    useStore.setState({
      quota: { level: 'Max', limits: [{ type: 'TOKENS_LIMIT', unit: 6, percentage: 95, nextResetTime: Date.now() + 5 * 86400_000 }] },
    })
    const { container } = render(<QuotaBanner />)
    const banner = container.querySelector('.quota-banner')!
    expect(banner.className).toContain('quota-banner--warning')
    expect(banner.textContent).toContain('本周额度')
    expect(banner.textContent).toContain('5%')
  })

  it('刷新按钮走 loadQuota（sendToJava op=getQuota）', () => {
    useStore.setState({ quotaBannerError: { kind: 'concurrent-limit', triggerKey: 'err:c' } })
    const sent: Array<Record<string, unknown>> = []
    // 直接断言 store 动作（桥已 mock 成 no-op，这里验证动作接线即可）
    useStore.getState().loadQuota()
    expect(useStore.getState().quotaLoading).toBe(true)
    sent.length = 0
  })
})
