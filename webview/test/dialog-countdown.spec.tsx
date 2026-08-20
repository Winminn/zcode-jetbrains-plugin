/**
 * DialogCountdown（反向请求弹窗超时倒计时）渲染测试
 *
 * 行为约定（Java 侧 5 分钟超时的前端呈现）：
 *   - deadlineMs 缺省（旧链路 / mock）→ 不渲染
 *   - 剩余 >60s → 显示"剩余"，普通样式
 *   - 剩余 ≤60s → 追加 warning 警示类（红字闪烁）
 *   - 已到期（remain ≤0）→ 不渲染（Java 随后推 askUserAck 关弹窗）
 */
// @vitest-environment jsdom
import { describe, it, expect, afterEach } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'

import '@/i18n/config'
import { DialogCountdown } from '@/components/DialogCountdown'

afterEach(cleanup)

const WARNING_CLASS = 'dialog-countdown--warning'

describe('DialogCountdown 倒计时', () => {
  it('deadlineMs 缺省时不渲染', () => {
    const { container } = render(<DialogCountdown />)
    expect(container.querySelector('.dialog-countdown')).toBeNull()
  })

  it('剩余 >60s：显示剩余文本，无警示类', () => {
    render(<DialogCountdown deadlineMs={Date.now() + 5 * 60_000} />)
    const el = screen.getByText(/剩余/, { exact: false }).closest('.dialog-countdown')
    expect(el).not.toBeNull()
    expect(el?.classList.contains(WARNING_CLASS)).toBe(false)
  })

  it('剩余 ≤60s：追加警示类', () => {
    render(<DialogCountdown deadlineMs={Date.now() + 30_000} />)
    const el = screen.getByText(/剩余/, { exact: false }).closest('.dialog-countdown')
    expect(el?.classList.contains(WARNING_CLASS)).toBe(true)
  })

  it('已到期不渲染', () => {
    const { container } = render(<DialogCountdown deadlineMs={Date.now() - 1_000} />)
    expect(container.querySelector('.dialog-countdown')).toBeNull()
  })
})
