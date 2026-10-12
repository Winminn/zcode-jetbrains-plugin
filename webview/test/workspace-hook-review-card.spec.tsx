// @vitest-environment jsdom
/**
 * 工作区钩子信任评审卡测试
 *
 * 覆盖：
 *   1. v4 workspaceHookReview 事件（经 onStreamBatch）→ store 落账 → 卡片渲染
 *   2. 条目勾选/全选：已信任条目禁选；信任按钮按勾选数启用并计数
 *   3. 信任点击：identity 原样回传 + reviewItemIds；settled 空集合事件收敛卡片
 *   4. 来源文件点击：相对路径拼工作区根后走 openFile
 *   5. 折叠/关闭/命令展开
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, cleanup, act } from '@testing-library/react'

const sendToJavaMock = vi.fn()
let streamBatchHandler: ((sid: string, events: unknown[]) => void) | null = null
let messageHandler: ((msg: Record<string, unknown>) => void) | null = null

vi.mock('@/ipc/bridge', () => ({
  initBridge: () => {},
  isInJcef: () => false,
  getWorkspacePath: () => 'G:\\mock',
  getInitialSessionId: () => '',
  onMessage: (fn: (msg: Record<string, unknown>) => void) => { messageHandler = fn },
  onStreamEvent: () => {},
  onStreamBatch: (fn: (sid: string, events: unknown[]) => void) => { streamBatchHandler = fn },
  sendToJava: (...args: unknown[]) => sendToJavaMock(...args),
}))

import * as React from 'react'
import { useStore } from '@/store/useStore'
import { WorkspaceHookReviewCard } from '@/components/WorkspaceHookReviewCard'
import type { WorkspaceHookReview } from '@/types/messages'

function makeReview(over: Partial<WorkspaceHookReview> = {}): WorkspaceHookReview {
  return {
    interactionId: 'workspace-hook-interaction:u1',
    sessionId: 'sess_1',
    taskId: 'task_1',
    runId: 'run_1',
    workspaceIdentity: 'g:/mock',
    workspaceLabel: 'mock-project',
    bundleDigest: 'sha256:b1',
    reviewFlowId: 'flow_1',
    generation: 1,
    createdAt: 1000,
    deadlineAt: 9_999_999,
    sourceFiles: [{ path: 'zcode.json', displayPath: 'zcode.json', editable: true }],
    summary: { eventCount: 1, hookCount: 2, pendingCount: 2 },
    items: [
      {
        reviewItemId: 'item_1', event: 'PreToolUse', type: 'command',
        displayName: 'PreToolUse · Bash', displayCommand: 'echo hi', sourcePath: 'zcode.json',
        resolvedTimeoutMs: 60000, resolvedMaxOutputBytes: 32768,
        executionMode: 'foreground', configuredEnabled: true, editable: true,
        trustState: 'blocked_untrusted',
      },
      {
        reviewItemId: 'item_2', event: 'Stop', type: 'command',
        displayName: 'Stop', displayCommand: 'echo done', sourcePath: 'zcode.json',
        resolvedTimeoutMs: 60000, resolvedMaxOutputBytes: 32768,
        executionMode: 'background', configuredEnabled: true, editable: true,
        trustState: 'trusted_persistent',
      },
    ],
    ...over,
  }
}

function pushReviewEvent(reviews: WorkspaceHookReview[]) {
  // act 包裹：zustand 更新发生在 React 事件外，否则重渲染被调度延迟、断言时未 flush
  act(() => {
    streamBatchHandler!('sess_1', [{
      type: 'workspaceHookReview', seq: 0, sessionId: 'sess_1', timestamp: 1,
      payload: { reviews },
    }])
  })
}

beforeEach(() => {
  sendToJavaMock.mockClear()
  useStore.getState().init()
  useStore.setState({
    connectionStatus: 'mock',
    currentSessionId: 'sess_1',
    currentWorkspacePath: 'G:\\mock',
  })
})

afterEach(cleanup)

describe('事件落账与渲染', () => {
  it('workspaceHookReview 事件到达后渲染标题/汇总/来源/条目', () => {
    pushReviewEvent([makeReview()])
    const { container } = render(<WorkspaceHookReviewCard />)
    expect(container.querySelector('.hook-review-overlay--dock')).toBeTruthy()
    expect(container.querySelector('.hook-review-dialog__title')!.textContent).toBe('mock-project')
    expect(container.querySelector('.hook-review-dialog__summary')!.textContent)
      .toContain('1 个事件')
    expect(screen.getByTitle('zcode.json')).toBeTruthy()
    expect(container.querySelector('.hook-review-item__name')!.textContent)
      .toBe('PreToolUse · Bash')
    // 后台执行态角标
    expect(container.textContent).toContain('后台')
  })

  it('空集合事件（settled）不渲染卡片', () => {
    pushReviewEvent([])
    const { container } = render(<WorkspaceHookReviewCard />)
    expect(container.querySelector('.hook-review-dialog')).toBeNull()
  })

  it('待命态（currentSessionId=null）渲染不触发无限重渲染（React #185 回归）', () => {
    useStore.setState({ currentSessionId: null })
    // selector 若每次返回新 []，useSyncExternalStore 会 forceStoreRerender 死循环
    expect(() => render(<WorkspaceHookReviewCard />)).not.toThrow()
    expect(document.querySelector('.hook-review-dialog')).toBeNull()
  })
})

describe('勾选与全选', () => {
  it('已信任条目禁选；单条勾选启用按钮并计数', () => {
    pushReviewEvent([makeReview()])
    const { container } = render(<WorkspaceHookReviewCard />)
    const boxes = () => Array.from(container.querySelectorAll('.hook-review-item__checkbox')) as HTMLInputElement[]
    expect(boxes()[1].disabled).toBe(true)

    const trustBtn = () => screen.getByRole('button', { name: /信任所选|提交中/ }) as HTMLButtonElement
    expect(trustBtn().disabled).toBe(true)

    fireEvent.click(boxes()[0])
    expect(trustBtn().disabled).toBe(false)
    expect(trustBtn().textContent).toContain('信任所选（1）')

    // 再点取消
    fireEvent.click(boxes()[0])
    expect(trustBtn().disabled).toBe(true)
  })

  it('全选只含可选条目（已信任不选），再点清空', () => {
    pushReviewEvent([makeReview()])
    const { container } = render(<WorkspaceHookReviewCard />)
    const boxes = () => Array.from(container.querySelectorAll('.hook-review-item__checkbox')) as HTMLInputElement[]
    const selectAll = container.querySelector('.hook-review-dialog__select-all input') as HTMLInputElement

    fireEvent.click(selectAll)
    expect(boxes()[0].checked).toBe(true)
    expect(boxes()[1].checked).toBe(false)
    expect((screen.getByRole('button', { name: /信任所选/ }) as HTMLButtonElement).disabled).toBe(false)

    fireEvent.click(selectAll)
    expect(boxes()[0].checked).toBe(false)
    expect((screen.getByRole('button', { name: /信任所选/ }) as HTMLButtonElement).disabled).toBe(true)
  })
})

describe('信任应答与收敛', () => {
  it('点击信任：identity 字段与 reviewItemIds 原样下发', () => {
    pushReviewEvent([makeReview()])
    render(<WorkspaceHookReviewCard />)
    const boxes = document.querySelectorAll('.hook-review-item__checkbox')
    fireEvent.click(boxes[0])
    fireEvent.click(screen.getByRole('button', { name: /信任所选/ }))

    expect(sendToJavaMock).toHaveBeenCalledWith(expect.objectContaining({
      op: 'workspaceHookReviewRespond',
      sessionId: 'sess_1',
      reviewItemIds: ['item_1'],
      identity: expect.objectContaining({
        interactionId: 'workspace-hook-interaction:u1',
        bundleDigest: 'sha256:b1',
        reviewFlowId: 'flow_1',
        generation: 1,
        taskId: 'task_1',
      }),
    }))
    // 提交后按钮进入 sending 态
    expect(screen.getByRole('button', { name: /提交中/ })).toBeTruthy()
  })

  it('settled 空集合事件到达后卡片消失', () => {
    pushReviewEvent([makeReview()])
    const { container } = render(<WorkspaceHookReviewCard />)
    expect(container.querySelector('.hook-review-dialog')).toBeTruthy()

    pushReviewEvent([])
    expect(container.querySelector('.hook-review-dialog')).toBeNull()
  })
})

describe('来源文件', () => {
  it('点击来源文件：相对路径拼工作区根后走 openFile', () => {
    pushReviewEvent([makeReview()])
    render(<WorkspaceHookReviewCard />)
    fireEvent.click(screen.getByTitle('zcode.json'))
    expect(sendToJavaMock).toHaveBeenCalledWith(expect.objectContaining({
      op: 'openFile',
      filePath: 'G:/mock/zcode.json',
    }))
  })
})

describe('折叠/关闭/命令展开', () => {
  it('折叠只留 header；关闭后不渲染', () => {
    pushReviewEvent([makeReview()])
    const { container } = render(<WorkspaceHookReviewCard />)
    fireEvent.click(screen.getByTitle('收起'))
    expect(container.querySelector('.hook-review-dialog__body')).toBeNull()

    // 收起态点 header 展开
    fireEvent.click(container.querySelector('.hook-review-dialog__header')!)
    expect(container.querySelector('.hook-review-dialog__body')).toBeTruthy()

    fireEvent.click(screen.getByTitle('关闭（保持阻止）'))
    expect(container.querySelector('.hook-review-dialog')).toBeNull()
  })

  it('点命令块展开全文', () => {
    pushReviewEvent([makeReview()])
    const { container } = render(<WorkspaceHookReviewCard />)
    const cmd = container.querySelector('.hook-review-item__command')!
    expect(cmd.classList.contains('hook-review-item__command--expanded')).toBe(false)
    fireEvent.click(cmd)
    expect(container.querySelector('.hook-review-item__command')!.classList
      .contains('hook-review-item__command--expanded')).toBe(true)
  })
})
