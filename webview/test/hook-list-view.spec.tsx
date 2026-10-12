// @vitest-environment jsdom
/**
 * 钩子管理页（HookListView）测试
 *
 * 覆盖：
 *   1. 挂载即拉列表（workspaceHooksList），响应前 loading；响应到达渲染事件/匹配器/命令/徽标
 *   2. 搜索前端过滤
 *   3. 安全提示横幅：存在未信任钩子时显示，全部信任后消失（与官方同一判定）
 *   4. 未信任行：行内「信任」按钮（单 digest grant），开关强制关闭且禁用
 *   5. 乐观更新：点信任/开关瞬间翻转，权威响应到达后对账
 *   6. error 响应：丢弃乐观覆盖回退到上一份权威状态并报错
 *   7. 点命令块 → openFile 拼工作区根；无工作区；无「全部信任」批量按钮
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, cleanup, act } from '@testing-library/react'

const sendToJavaMock = vi.fn()
let messageHandler: ((msg: Record<string, unknown>) => void) | null = null

vi.mock('@/ipc/bridge', () => ({
  initBridge: () => {},
  isInJcef: () => false,
  getWorkspacePath: () => 'G:\\mock',
  getInitialSessionId: () => '',
  onMessage: (fn: (msg: Record<string, unknown>) => void) => { messageHandler = fn },
  onStreamEvent: () => {},
  onStreamBatch: () => {},
  sendToJava: (...args: unknown[]) => sendToJavaMock(...args),
}))

import * as React from 'react'
import { useStore } from '@/store/useStore'
import { HookListView } from '@/components/HookListView'
import type { WorkspaceHooksStatus, WorkspaceHooksStatusItem } from '@/types/messages'

function makeItem(over: Partial<WorkspaceHooksStatusItem> = {}): WorkspaceHooksStatusItem {
  return {
    reviewItemId: 'workspace-hook-0-UserPromptSubmit-0-0',
    event: 'UserPromptSubmit',
    matcher: null,
    displayCommand: 'bash hook.sh',
    sourcePath: '.zcode/config.json',
    configuredEnabled: true,
    hookDeclarationDigest: 'digest-h1',
    trustState: 'pending_trust',
    ...over,
  }
}

function makeStatus(over: Partial<WorkspaceHooksStatus> = {}): WorkspaceHooksStatus {
  return {
    workspacePath: 'G:/mock',
    workspaceIdentity: 'g:/mock',
    bundleDigest: 'digest-b1',
    items: [
      makeItem(),
      makeItem({
        reviewItemId: 'workspace-hook-0-PreToolUse-0-0',
        event: 'PreToolUse',
        matcher: 'Bash',
        displayCommand: 'node block.js',
        hookDeclarationDigest: 'digest-h2',
        trustState: 'trusted_persistent',
      }),
    ],
    ...over,
  }
}

/** 模拟 Kotlin 推列表响应（act 包裹，zustand 外的状态变更需 flush） */
function pushStatus(status: WorkspaceHooksStatus) {
  act(() => {
    messageHandler!({ op: 'workspaceHooksList', status })
  })
}

function pushError(message: string) {
  act(() => {
    messageHandler!({ op: 'error', message })
  })
}

function rows() {
  return Array.from(document.querySelectorAll('.hook-row'))
}

function rowSwitch(i: number): HTMLButtonElement {
  return rows()[i].querySelector('.hook-switch') as HTMLButtonElement
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

describe('拉取与渲染', () => {
  it('挂载即发 workspaceHooksList，响应前显示加载中', () => {
    const { container } = render(<HookListView />)
    expect(sendToJavaMock).toHaveBeenCalledWith({ op: 'workspaceHooksList', workspace: 'G:\\mock' })
    expect(container.textContent).toContain('加载中')
  })

  it('响应到达后渲染事件/匹配器/命令/信任徽标与已安装计数', () => {
    const { container } = render(<HookListView />)
    pushStatus(makeStatus())

    expect(rows()).toHaveLength(2)
    expect(container.textContent).toContain('UserPromptSubmit')
    expect(container.textContent).toContain('PreToolUse')
    expect(container.textContent).toContain('Bash')
    expect(container.textContent).toContain('bash hook.sh')
    expect(container.textContent).toContain('node block.js')
    expect(container.textContent).toContain('已安装 2')
    expect(container.querySelector('.hook-badge--stateTrusted')!.textContent).toContain('已信任')
    expect(container.querySelector('.hook-badge--stateUntrusted')!.textContent).toContain('未信任')
  })

  it('空 items 显示空态', () => {
    const { container } = render(<HookListView />)
    pushStatus(makeStatus({ items: [] }))
    expect(container.textContent).toContain('当前工作区没有钩子')
  })

  it('不存在「全部信任」批量按钮', () => {
    render(<HookListView />)
    pushStatus(makeStatus())
    expect(screen.queryByRole('button', { name: '全部信任' })).toBeNull()
  })
})

describe('搜索过滤', () => {
  it('按命令关键字过滤，清空后恢复', () => {
    render(<HookListView />)
    pushStatus(makeStatus())

    fireEvent.change(screen.getByPlaceholderText('搜索事件或命令'), { target: { value: 'block' } })
    expect(rows()).toHaveLength(1)
    expect(document.querySelector('.hook-row')!.textContent).toContain('PreToolUse')

    fireEvent.change(screen.getByPlaceholderText('搜索事件或命令'), { target: { value: '' } })
    expect(rows()).toHaveLength(2)
  })

  it('按事件名匹配', () => {
    render(<HookListView />)
    pushStatus(makeStatus())
    fireEvent.change(screen.getByPlaceholderText('搜索事件或命令'), { target: { value: 'userprompt' } })
    expect(rows()).toHaveLength(1)
  })
})

describe('安全提示横幅', () => {
  it('存在未信任钩子时显示；全部信任后消失', () => {
    const { container } = render(<HookListView />)
    pushStatus(makeStatus())
    const notice = () => container.querySelector('.hook-list__notice')
    expect(notice()!.textContent).toContain('钩子可在沙盒外运行')

    const trusted = makeStatus()
    trusted.items = trusted.items.map((it) => ({ ...it, trustState: 'trusted_persistent' as const }))
    pushStatus(trusted)
    expect(notice()).toBeNull()
  })
})

describe('未信任行：信任按钮与开关禁用', () => {
  it('未信任行显示信任按钮；开关强制关闭且禁用（点击不发 toggle）', () => {
    render(<HookListView />)
    pushStatus(makeStatus())

    const firstRow = rows()[0] as HTMLElement
    const trustBtn = firstRow.querySelector('.hook-row__trust') as HTMLButtonElement
    expect(trustBtn.textContent).toContain('信任')

    const sw = rowSwitch(0)
    expect(sw.disabled).toBe(true)
    expect(sw.getAttribute('aria-checked')).toBe('false')
    expect(sw.title).toBe('请先信任该钩子再启用')
    expect(sw.classList.contains('hook-switch--on')).toBe(false)
    fireEvent.click(sw)
    expect(sendToJavaMock).not.toHaveBeenCalledWith(expect.objectContaining({ op: 'workspaceHooksToggle' }))
  })
})

describe('乐观更新', () => {
  it('点信任：瞬间按已信任渲染（徽标变、按钮消失、开关可用呈开启态），响应到达后保持', () => {
    render(<HookListView />)
    pushStatus(makeStatus())

    fireEvent.click(rows()[0].querySelector('.hook-row__trust') as HTMLButtonElement)
    expect(sendToJavaMock).toHaveBeenCalledWith({
      op: 'workspaceHooksGrant',
      workspace: 'G:\\mock',
      hookDigests: ['digest-h1'],
    })

    // 权威响应未到：乐观态已翻转
    let sw = rowSwitch(0)
    expect(rows()[0].querySelector('.hook-row__trust')).toBeNull()
    expect(rows()[0].querySelector('.hook-badge')!.textContent).toContain('已信任')
    expect(sw.disabled).toBe(false)
    expect(sw.getAttribute('aria-checked')).toBe('true')

    // 权威对账
    const updated = makeStatus()
    updated.items[0] = { ...updated.items[0], trustState: 'trusted_persistent' }
    pushStatus(updated)
    sw = rowSwitch(0)
    expect(rows()[0].querySelector('.hook-row__trust')).toBeNull()
    expect(sw.disabled).toBe(false)
    expect(sw.getAttribute('aria-checked')).toBe('true')
  })

  it('点开关：瞬间翻转且无需等响应；响应到达后按权威值保持', () => {
    render(<HookListView />)
    pushStatus(makeStatus())

    // 第二条（PreToolUse）已信任可切
    expect(rowSwitch(1).getAttribute('aria-checked')).toBe('true')
    fireEvent.click(rowSwitch(1))

    // 瞬间翻转（此时 pushStatus 尚未发生）
    expect(rowSwitch(1).getAttribute('aria-checked')).toBe('false')
    expect(sendToJavaMock).toHaveBeenCalledWith({
      op: 'workspaceHooksToggle',
      workspace: 'G:\\mock',
      reviewItemId: 'workspace-hook-0-PreToolUse-0-0',
      sourcePath: '.zcode/config.json',
      enabled: false,
    })

    const updated = makeStatus()
    updated.items[1] = { ...updated.items[1], configuredEnabled: false }
    pushStatus(updated)
    expect(rowSwitch(1).getAttribute('aria-checked')).toBe('false')
  })

  it('error 响应：丢弃乐观覆盖回退到上一份权威状态并显示错误', () => {
    const { container } = render(<HookListView />)
    pushStatus(makeStatus())

    // 乐观关闭第二条
    fireEvent.click(rowSwitch(1))
    expect(rowSwitch(1).getAttribute('aria-checked')).toBe('false')

    // 失败：回退为权威的开启态
    pushError('写入失败')
    expect(rowSwitch(1).getAttribute('aria-checked')).toBe('true')
    expect(container.textContent).toContain('写入失败')
  })
})

describe('钩子脚本入口与无工作区', () => {
  it('点命令块：带 displayCommand/sourcePath 走 workspaceHooksOpenScript（Kotlin 解析脚本路径）', () => {
    render(<HookListView />)
    pushStatus(makeStatus())
    fireEvent.click(document.querySelector('.hook-row__command')!)
    expect(sendToJavaMock).toHaveBeenCalledWith({
      op: 'workspaceHooksOpenScript',
      workspace: 'G:\\mock',
      displayCommand: 'bash hook.sh',
      sourcePath: '.zcode/config.json',
    })
  })

  it('error 响应后可再次刷新', () => {
    const { container } = render(<HookListView />)
    pushError('CLI 执行失败')
    expect(container.textContent).toContain('CLI 执行失败')
    fireEvent.click(container.querySelector('.hook-list__refresh')!)
    expect(sendToJavaMock).toHaveBeenCalledWith({ op: 'workspaceHooksList', workspace: 'G:\\mock' })
  })

  it('无工作区：显示提示且不发请求', () => {
    useStore.setState({ currentWorkspacePath: '' })
    const { container } = render(<HookListView />)
    expect(container.textContent).toContain('未打开工作区')
    expect(sendToJavaMock).not.toHaveBeenCalled()
  })
})
