/**
 * CUA 列表类工具卡友好渲染（list_apps/list_windows）：
 *   - 头部：工具短名（list_apps，非 MCP·server__tool 长名）+ 数量摘要
 *   - 展开区：行式应用列表（图标兜底 codicon + 名称 + pid + 窗口标题 + 前台徽标）
 *   - 解析失败（流式半截/形状变更）回退裸 JSON 原文
 */
// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, fireEvent } from '@testing-library/react'

vi.mock('@/ipc/bridge', () => ({
  sendToJava: () => {},
  openExternalUrl: () => {},
}))

import '@/i18n/config'
import { useStore } from '@/store/useStore'
import { ToolCallCard } from '@/components/ToolCallCard'
import type { ToolPart } from '@/types/messages'

const APPS_OUT = JSON.stringify({
  apps: [
    { pid: 35462, name: 'idea64', bundle_id: null, active: true, title: 'zcode-idea-plugin – handoff-cua.md', icon: '123812b2f895' },
    { pid: 44148, name: '360se', bundle_id: null, active: false, title: '创作中心 - 哔哩哔哩弹幕视频网', icon: '72180469345d' },
  ],
})

const WINS_OUT = '{"windows":[{"window_id":35462,"title":"zcode-idea-plugin – README.md","rect":null}]}\napp: pid=35462 name=idea64 active=true icon=123812b2f895'

function cuaPart(tool: string, output: string): ToolPart {
  return {
    type: 'tool',
    callID: 'call_cua_list_test',
    tool: `mcp__zcgui-computer-use__${tool}`,
    state: { status: 'completed', input: {}, output, time: { start: 1787283860000, end: 1787283861000 } },
  }
}

function expandCard(container: HTMLElement) {
  fireEvent.click(container.querySelector('.tool-card__header')!)
}

beforeEach(() => {
  useStore.setState({ markdownPreview: null, subagentDetail: null, subagentReport: null })
})

afterEach(cleanup)

describe('list_apps 友好渲染', () => {
  it('头部：短名 + 数量摘要（非 MCP· 长名、非空摘要）', () => {
    const { container } = render(<ToolCallCard part={cuaPart('list_apps', APPS_OUT)} />)
    expect(container.querySelector('.tool-card__name')!.textContent).toBe('list_apps')
    expect(container.querySelector('.tool-card__summary')!.textContent).toBe('2 个窗口应用')
  })

  it('展开区：行式列表（名称/pid/标题/前台徽标），无裸 JSON 输出块', () => {
    const { container } = render(<ToolCallCard part={cuaPart('list_apps', APPS_OUT)} />)
    expandCard(container)
    const items = container.querySelectorAll('.cua-app-item')
    expect(items).toHaveLength(2)
    expect(items[0].textContent).toContain('idea64')
    expect(items[0].textContent).toContain('pid 35462')
    expect(items[0].textContent).toContain('zcode-idea-plugin – handoff-cua.md')
    expect(items[0].querySelector('.cua-app-item__active')!.textContent).toBe('前台')
    expect(items[1].querySelector('.cua-app-item__active')).toBeNull()
    // 裸 JSON 输出块不再渲染（友好分支接管）
    expect(container.querySelectorAll('.tool-card__code')).toHaveLength(0)
  })

  it('解析失败（流式半截）回退裸 JSON 原文', () => {
    const { container } = render(<ToolCallCard part={cuaPart('list_apps', '{"apps":[{"pid":1')} />)
    expandCard(container)
    expect(container.querySelectorAll('.cua-app-item')).toHaveLength(0)
    expect(container.querySelector('.tool-card__code')).not.toBeNull()
  })
})

describe('list_windows 友好渲染', () => {
  it('头部窗口数 + 展开区行式窗口列表', () => {
    const { container } = render(<ToolCallCard part={cuaPart('list_windows', WINS_OUT)} />)
    expect(container.querySelector('.tool-card__summary')!.textContent).toBe('1 个窗口')
    expandCard(container)
    const items = container.querySelectorAll('.cua-app-item')
    expect(items).toHaveLength(1)
    expect(items[0].textContent).toContain('35462')
    expect(items[0].textContent).toContain('zcode-idea-plugin – README.md')
  })
})
