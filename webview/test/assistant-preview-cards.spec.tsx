/**
 * 产物预览卡组件测试（B2 二期）：
 * 1. md/html 门控链路：先发 turnFileChanges 查询拿账本路径 → 命中后出卡
 * 2. checkFilesExist 批量校验：exists=false 滤掉已删文件（防闪卡）
 * 3. 点击路由：md/html→openFile（编辑器）；pdf→openFileSystem（系统程序）；网站卡→openExternal
 * 4. reverted 权威抑制：不查询直接抑制 md/html
 */
// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react'

const messageHandlers = new Set<(msg: unknown) => void>()
const sentRequests: Array<Record<string, unknown>> = []

vi.mock('@/ipc/bridge', () => ({
  initBridge: () => {},
  isInJcef: () => false,
  getWorkspacePath: () => 'G:\\demo',
  getInitialSessionId: () => '',
  // 组件会注册多个监听（门控查询 + stat 配对），必须多播（真实桥为事件发射器）
  onMessage: (fn: (msg: unknown) => void) => {
    messageHandlers.add(fn)
    return () => { messageHandlers.delete(fn) }
  },
  onStreamEvent: () => {},
  onStreamBatch: () => {},
  sendToJava: (req: Record<string, unknown>) => { sentRequests.push(req) },
}))

import '@/i18n/config'
import { useStore } from '@/store/useStore'
import { AssistantPreviewCards } from '@/components/AssistantPreviewCards'

function respond(msg: Record<string, unknown>) {
  messageHandlers.forEach((fn) => fn(msg))
}

function statResultFor(req: Record<string, unknown>, existsByPath: (p: string) => boolean) {
  const paths = req.paths as string[]
  respond({
    op: 'checkFilesExistResult',
    requestId: req.requestId,
    results: paths.map((p) => ({ path: p, exists: existsByPath(p) })),
  })
}

beforeEach(() => {
  sentRequests.length = 0
  useStore.setState({
    projectPath: 'G:/demo',
    currentSessionId: 'sess1',
    turnFileChanges: {},
  })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('AssistantPreviewCards', () => {
  it('pdf 提及 + stat 存在 → 出卡；stat 不存在 → 滤掉', async () => {
    const { container } = render(
      <AssistantPreviewCards messageId="msg1" text="看 report.pdf 与 ghost.pdf" />,
    )
    // 先发批量 stat
    await waitFor(() => {
      expect(sentRequests.some((r) => r.op === 'checkFilesExist')).toBe(true)
    })
    const statReq = sentRequests.find((r) => r.op === 'checkFilesExist')!
    statResultFor(statReq, (p) => !p.includes('ghost'))
    expect(await screen.findByText('report.pdf')).toBeTruthy()
    expect(screen.queryByText('ghost.pdf')).toBeNull()
    expect(container.textContent).toContain('文档 · PDF')
  })

  it('md 需命中本轮 fileChanges：先查询账本，命中后出卡', async () => {
    render(<AssistantPreviewCards messageId="msg2" text="报告见 docs/report.md" />)
    // md 引用 → 先发 turnFileChanges 查询（不发 stat：无其他文件卡）
    await waitFor(() => {
      expect(sentRequests.some((r) => r.op === 'turnFileChanges')).toBe(true)
    })
    expect(sentRequests.some((r) => r.op === 'checkFilesExist')).toBe(false)
    respond({
      op: 'turnFileChangesResult',
      sessionId: 'sess1',
      messageId: 'msg2',
      data: {
        files: 1,
        additions: 3,
        deletions: 0,
        state: 'active',
        items: [{ path: 'docs/report.md', additions: 3, deletions: 0, writeCount: 1, toolNames: ['Write'], patches: [] }],
      },
    })
    // 二段：md 卡进候选 → 批量 stat → 存在 → 出卡
    await waitFor(() => {
      expect(sentRequests.some((r) => r.op === 'checkFilesExist')).toBe(true)
    })
    statResultFor(sentRequests.find((r) => r.op === 'checkFilesExist')!, () => true)
    expect(await screen.findByText('report.md')).toBeTruthy()
  })

  it('账本未命中（AI 光提路径）→ md 不出卡', async () => {
    render(<AssistantPreviewCards messageId="msg3" text="报告见 docs/other.md" />)
    await waitFor(() => {
      expect(sentRequests.some((r) => r.op === 'turnFileChanges')).toBe(true)
    })
    respond({
      op: 'turnFileChangesResult',
      sessionId: 'sess1',
      messageId: 'msg3',
      data: { files: 0, additions: 0, deletions: 0, state: 'active', items: [] },
    })
    await waitFor(() => {
      // 无 stat 查询 = 无文件卡候选
      expect(sentRequests.every((r) => r.op !== 'checkFilesExist')).toBe(true)
    })
    expect(container_result_empty())
  })

  it('reverted 权威抑制：直接抑制 md 不发查询', async () => {
    useStore.setState({
      turnFileChanges: { msg4: { rowId: 1, additions: 1, deletions: 0, files: 1, state: 'reverted', canRewind: false } },
    })
    render(<AssistantPreviewCards messageId="msg4" text="报告见 docs/report.md" />)
    await new Promise((r) => setTimeout(r, 10))
    expect(sentRequests.every((r) => r.op !== 'turnFileChanges')).toBe(true)
    expect(container_result_empty())
  })

  it('点击路由：md→openFile、html/pdf→openFileSystem（html 走浏览器+地球图标）、网站→openExternal', async () => {
    render(
      <AssistantPreviewCards
        messageId="msg5"
        text="报告 docs/report.md，页面 index.html，素材 demo.pdf，预览 http://localhost:3000"
      />,
    )
    await waitFor(() => {
      expect(sentRequests.some((r) => r.op === 'turnFileChanges')).toBe(true)
    })
    respond({
      op: 'turnFileChangesResult',
      sessionId: 'sess1',
      messageId: 'msg5',
      data: {
        files: 2,
        additions: 2,
        deletions: 0,
        state: 'active',
        items: [
          { path: 'docs/report.md', additions: 1, deletions: 0, writeCount: 1, toolNames: ['Write'], patches: [] },
          { path: 'index.html', additions: 1, deletions: 0, writeCount: 1, toolNames: ['Write'], patches: [] },
        ],
      },
    })
    // 二段 stat（pdf 第一段 + md/html 明细到达后的第二段）：全部应答
    await waitFor(() => {
      expect(sentRequests.filter((r) => r.op === 'checkFilesExist').length >= 2).toBe(true)
    })
    sentRequests
      .filter((r) => r.op === 'checkFilesExist')
      .forEach((r) => statResultFor(r, () => true))
    fireEvent.click(await screen.findByText('report.md'))
    fireEvent.click(screen.getByText('index.html'))
    fireEvent.click(screen.getByText('demo.pdf'))
    // 无路径的根 URL 标题兜底为 host
    fireEvent.click(screen.getByText('localhost:3000'))
    const ops = sentRequests.map((r) => r.op)
    expect(ops).toContain('openFile')
    expect(ops).toContain('openFileSystem')
    expect(ops).toContain('openExternal')
    const openFileReq = sentRequests.find((r) => r.op === 'openFile')!
    expect(openFileReq.filePath).toBe('G:/demo/docs/report.md')
    // html 与 pdf 同走 openFileSystem（html 由 Java 侧 BrowserUtil.browse 强制系统浏览器）
    const sysReqs = sentRequests.filter((r) => r.op === 'openFileSystem')
    expect(sysReqs.map((r) => r.filePath).sort()).toEqual(['G:/demo/demo.pdf', 'G:/demo/index.html'])
    const extReq = sentRequests.find((r) => r.op === 'openExternal')!
    expect(extReq.url).toBe('http://localhost:3000')
    // html 卡图标 = 浏览器地球（codicon-globe），不用 FileIcon 的 H5 盾牌
    const htmlCard = screen.getByText('index.html').closest('.apc__row')!
    expect(htmlCard.querySelector('.codicon-globe')).toBeTruthy()
  })
})

/** 无卡渲染 = 容器为空（settled 前与滤空后都不渲染 DOM） */
function container_result_empty(): boolean {
  expect(document.querySelector('.apc')).toBeNull()
  return true
}
