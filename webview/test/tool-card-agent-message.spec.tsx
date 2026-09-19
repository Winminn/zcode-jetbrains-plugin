/**
 * 代理间消息族工具卡（SendMessage/RespondToCoordinator）友好渲染：
 *   - 头部摘要 = 收件人短 id · summary（SendMessage）/ summary（RespondToCoordinator）
 *   - 展开区收件人行（短 id，title=完整 id）+ 正文原文换行，替代裸 JSON
 *   - 回执 JSON 解析为送达形态一行；失败显 error；非 JSON 回退原文
 */
// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, fireEvent } from '@testing-library/react'

vi.mock('@/ipc/bridge', () => ({
  sendToJava: () => {},
  openExternalUrl: () => {},
}))

import '@/i18n/config'
import { ToolCallCard } from '@/components/ToolCallCard'
import type { ToolPart } from '@/types/messages'

function msgPart(tool: 'SendMessage' | 'RespondToCoordinator', input: Record<string, unknown>, output: string | null): ToolPart {
  return {
    type: 'tool',
    callID: 'call_msg_test',
    tool,
    state: { status: 'completed', input, output, time: { start: 1787283860000, end: 1787283861000 } },
  }
}

function expandCard(container: HTMLElement) {
  fireEvent.click(container.querySelector('.tool-card__header')!)
}

const FULL_ID = 'agent_d8b9704-ec27-4fc0-bf29-35401ad12add'

beforeEach(() => { vi.clearAllMocks() })
afterEach(() => cleanup())

describe('SendMessage 卡片', () => {
  it('头部摘要 = 收件人短 id · summary', () => {
    render(
      <ToolCallCard
        part={msgPart('SendMessage', { to: FULL_ID, summary: '0027 修复后请复核', message: '正文' }, null)}
      />,
    )
    expect(screen.getByText(`agent_d8b9704 · 0027 修复后请复核`)).toBeTruthy()
  })

  it('展开区：收件人行 + 正文原文换行，无裸 JSON 键转储；回执解析为送达形态', () => {
    const { container } = render(
      <ToolCallCard
        part={msgPart(
          'SendMessage',
          { to: FULL_ID, summary: '0027 修复后请复核', message: '第一行\n第二行 17 页' },
          JSON.stringify({ status: 'success', messageId: 'msg_abc', delivery: 'queued' }),
        )}
      />,
    )
    expandCard(container)
    const recipient = container.querySelector('.tool-card__msg-recipient')!
    expect(recipient.textContent).toContain('agent_d8b9704')
    expect(recipient.getAttribute('title')).toBe(FULL_ID)
    expect(screen.getByText('已入队，待子代理读取')).toBeTruthy()
    // 正文原文渲染（换行保留），不再是 "message": "..." 的 JSON 转储
    const prompt = container.querySelector('.tool-card__code.tool-card__prompt')!
    expect(prompt.textContent).toContain('第一行\n第二行 17 页')
    expect(container.textContent).not.toContain('"summary"')
  })

  it('失败回执显示 error 文本', () => {
    const { container } = render(
      <ToolCallCard
        part={msgPart(
          'SendMessage',
          { to: FULL_ID, summary: 's', message: 'm' },
          JSON.stringify({ status: 'failed', messageId: 'msg_x', error: 'agent not found' }),
        )}
      />,
    )
    expandCard(container)
    expect(screen.getByText('agent not found')).toBeTruthy()
    expect(container.querySelector('.tool-card__msg-receipt--err')).toBeTruthy()
  })

  it('非 JSON 输出回退原文展示', () => {
    const { container } = render(
      <ToolCallCard part={msgPart('SendMessage', { to: FULL_ID, summary: 's', message: 'm' }, '发送超时，请稍后重试')} />,
    )
    expandCard(container)
    expect(container.querySelector('.tool-card__msg-receipt')).toBeNull()
    expect(container.textContent).toContain('发送超时，请稍后重试')
  })
})

describe('RespondToCoordinator 卡片', () => {
  it('头部摘要 = summary；展开区无收件人行，回执取 responseId', () => {
    const { container } = render(
      <ToolCallCard
        part={msgPart(
          'RespondToCoordinator',
          { summary: '三处复核全过', message: '结论：全部通过' },
          JSON.stringify({ status: 'success', responseId: 'resp_1', message: 'ok' }),
        )}
      />,
    )
    expect(screen.getByText('三处复核全过')).toBeTruthy()
    expandCard(container)
    expect(container.querySelector('.tool-card__msg-recipient')).toBeNull()
    expect(screen.getByText('已送达')).toBeTruthy()
    const receipt = container.querySelector('.tool-card__msg-receipt')!
    expect(receipt.getAttribute('title')).toBe('resp_1')
  })
})
