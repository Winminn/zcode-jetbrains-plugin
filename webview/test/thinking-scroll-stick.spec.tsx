/**
 * 思考块内部滚动的跟滚语义（用户上滚不被流式置底强制拉回）：
 * 思考过程实时刷新（16ms 一轮流式置底）期间，用户上滚回看历史会被无条件
 * 置底反复拽回——修复后距底 <80px（NEAR_BOTTOM_PX）才自动跟滚，滚轮上滑
 * 或滚动离开底部即断跟，滚回底部附近自动恢复（语义同 ChatView 主容器）。
 *
 * jsdom 无布局（scrollTop/scrollHeight 恒 0），按元素实例覆盖为可控属性，
 * 断言"程序置底是否发生"（scrollTop 是否被 effect 赋值为 scrollHeight）。
 */
// @vitest-environment jsdom
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup, fireEvent } from '@testing-library/react'

import '@/i18n/config'
import { ThinkingBlock } from '@/components/ThinkingBlock'
import type { ReasoningPart } from '@/types/messages'

const LONG = 'a'.repeat(400)

function makePart(text: string): ReasoningPart {
  return { type: 'reasoning', text }
}

function mockScrollMetrics(el: HTMLElement) {
  const state = { scrollTop: 0, scrollHeight: 600, clientHeight: 300 }
  Object.defineProperty(el, 'scrollTop', {
    get: () => state.scrollTop,
    set: (v: number) => { state.scrollTop = v },
    configurable: true,
  })
  Object.defineProperty(el, 'scrollHeight', { get: () => state.scrollHeight, configurable: true })
  Object.defineProperty(el, 'clientHeight', { get: () => state.clientHeight, configurable: true })
  return state
}

afterEach(() => cleanup())

describe('ThinkingBlock 流式跟滚', () => {
  it('用户在底部 → 文本增长继续跟滚（置底到 scrollHeight）', () => {
    const { rerender } = render(<ThinkingBlock part={makePart(LONG)} autoExpand />)
    const el = document.querySelector('.thinking-block__body') as HTMLElement
    const state = mockScrollMetrics(el)
    // 程序已置底的状态：scrollTop=scrollHeight（距底 0）
    state.scrollTop = 600
    rerender(<ThinkingBlock part={makePart(LONG + 'b')} autoExpand />)
    expect(state.scrollTop).toBe(600)
  })

  it('用户上滚回看（距底 ≥80px）→ 流式增长不打扰，scrollTop 保持', () => {
    const { rerender } = render(<ThinkingBlock part={makePart(LONG)} autoExpand />)
    const el = document.querySelector('.thinking-block__body') as HTMLElement
    const state = mockScrollMetrics(el)
    state.scrollTop = 600
    // 上滚到距底 200px（600-100-300=200 ≥ NEAR_BOTTOM_PX=80）
    state.scrollTop = 100
    fireEvent.scroll(el)
    rerender(<ThinkingBlock part={makePart(LONG + 'b')} autoExpand />)
    expect(state.scrollTop).toBe(100)
  })

  it('滚回底部附近 → 自动恢复跟滚', () => {
    const { rerender } = render(<ThinkingBlock part={makePart(LONG)} autoExpand />)
    const el = document.querySelector('.thinking-block__body') as HTMLElement
    const state = mockScrollMetrics(el)
    state.scrollTop = 600
    state.scrollTop = 100
    fireEvent.scroll(el) // 断跟
    // 滚回底部（距底 0 < 80）
    state.scrollTop = 600 - 300
    fireEvent.scroll(el)
    rerender(<ThinkingBlock part={makePart(LONG + 'b')} autoExpand />)
    expect(state.scrollTop).toBe(600)
  })

  it('滚轮上滑先行断跟：起步第一格 scroll 仍在 80px 窗口内也不恢复跟滚（弹跳防御）', () => {
    const { rerender } = render(<ThinkingBlock part={makePart(LONG)} autoExpand />)
    const el = document.querySelector('.thinking-block__body') as HTMLElement
    const state = mockScrollMetrics(el)
    state.scrollTop = 600
    // 滚轮上滑（wheel 先于 scroll 生效）→ 立即断跟
    fireEvent.wheel(el, { deltaY: -100 })
    // 起步第一格 scroll：scrollTop=550 距底 -250（<80，nearBottom 误判窗口）
    state.scrollTop = 550
    fireEvent.scroll(el)
    rerender(<ThinkingBlock part={makePart(LONG + 'b')} autoExpand />)
    // wheel 断跟覆盖了 nearBottom 误判，不被流式置底拽回
    expect(state.scrollTop).toBe(550)
  })
})
