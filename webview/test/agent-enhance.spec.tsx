/**
 * 提示词润色 + 子智能体功能测试
 *
 * 覆盖：
 * 1. store 润色状态机：enhancePrompt 请求发起到 enhancePromptResult 落地（成功/失败/超时兜底）
 * 2. PromptEnhancerDialog：loading/错误/结果三态渲染、Enter=使用、Esc=关闭
 * 3. 子智能体：agents 响应落地、agentDeleted 清选中、AgentSelect 下拉选择/取消
 * 4. InputBox 发送拼装：选中子智能体时消息前置 @<name>（协议实测格式）
 */
// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react'

let messageHandler: ((msg: unknown) => void) | null = null
const sentRequests: Array<Record<string, unknown>> = []

vi.mock('@/ipc/bridge', () => ({
  initBridge: () => {},
  isInJcef: () => false,
  getWorkspacePath: () => 'G:\\mock',
  getInitialSessionId: () => '',
  onMessage: (fn: (msg: unknown) => void) => { messageHandler = fn },
  onStreamEvent: () => {},
  onStreamBatch: () => {},
  sendToJava: (req: Record<string, unknown>) => { sentRequests.push(req) },
}))

import '@/i18n/config'
import { useStore } from '@/store/useStore'
import { PromptEnhancerDialog } from '@/components/PromptEnhancerDialog'
import { AgentSelect } from '@/components/AgentSelect'
import { InputBox } from '@/components/InputBox'
import type { AgentDef } from '@/types/messages'

const agentDef = (over: Partial<AgentDef> = {}): AgentDef => ({
  name: 'test-agent',
  description: '测试子智能体',
  color: 'yellow',
  tools: [],
  disallowedTools: [],
  injectAgentsMd: true,
  mcpServers: [],
  systemPrompt: '你好，我是测试的子智能体',
  path: 'C:/users/.zcode/agents/test-agent.md',
  scope: 'user',
  ...over,
})

beforeEach(() => {
  sentRequests.length = 0
  // jsdom 不实现 innerText（InputBox 幽灵补全读取），polyfill 成 textContent
  if (!('innerText' in HTMLDivElement.prototype)) {
    Object.defineProperty(HTMLDivElement.prototype, 'innerText', {
      configurable: true,
      get(this: HTMLDivElement) {
        return this.textContent ?? ''
      },
      set(this: HTMLDivElement, v: string) {
        this.textContent = v
      },
    })
  }
  useStore.getState().init()
  sentRequests.length = 0
  useStore.setState({
    subagentDefs: null,
    selectedAgent: null,
    enhancing: false,
    enhanceResult: null,
    currentSessionId: 'sess_t1',
    currentModel: { modelId: 'GLM-5.2', providerId: 'builtin:bigmodel-coding-plan' },
    currentWorkspacePath: 'G:\\mock',
    streaming: false,
    messages: [],
  })
})
afterEach(cleanup)

describe('润色状态机（store）', () => {
  it('enhancePrompt 发请求带当前模型，置 enhancing + 弹窗占位', () => {
    useStore.getState().enhancePrompt('帮我写个函数')
    const req = sentRequests.find((r) => r.op === 'enhancePrompt')
    expect(req).toBeTruthy()
    expect(req).toMatchObject({
      text: '帮我写个函数',
      providerId: 'builtin:bigmodel-coding-plan',
      modelId: 'GLM-5.2',
    })
    expect(useStore.getState().enhancing).toBe(true)
    expect(useStore.getState().enhanceResult).toEqual({ original: '帮我写个函数' })
  })

  it('空文本不触发请求；enhancing 中防重入', () => {
    useStore.getState().enhancePrompt('   ')
    expect(sentRequests.filter((r) => r.op === 'enhancePrompt')).toHaveLength(0)
    useStore.getState().enhancePrompt('第一条')
    useStore.getState().enhancePrompt('第二条')
    expect(sentRequests.filter((r) => r.op === 'enhancePrompt')).toHaveLength(1)
  })

  it('enhancePromptResult 成功落地（关闭 loading 带文本）', () => {
    useStore.getState().enhancePrompt('原文')
    messageHandler!({ op: 'enhancePromptResult', original: '原文', text: '润色后' })
    const s = useStore.getState()
    expect(s.enhancing).toBe(false)
    expect(s.enhanceResult).toEqual({ original: '原文', text: '润色后' })
  })

  it('enhancePromptResult 失败落地（错误态）', () => {
    useStore.getState().enhancePrompt('原文')
    messageHandler!({ op: 'enhancePromptResult', error: 'CLI 超时' })
    const s = useStore.getState()
    expect(s.enhancing).toBe(false)
    expect(s.enhanceResult?.error).toBe('CLI 超时')
  })

  it('clearEnhanceResult 关弹窗', () => {
    useStore.getState().enhancePrompt('原文')
    messageHandler!({ op: 'enhancePromptResult', original: '原文', text: '润色后' })
    useStore.getState().clearEnhanceResult()
    expect(useStore.getState().enhanceResult).toBeNull()
    expect(useStore.getState().enhancing).toBe(false)
  })
})

describe('PromptEnhancerDialog 交互', () => {
  it('loading 态：spinner + 两按钮禁用', () => {
    render(
      <PromptEnhancerDialog
        enhancing={true}
        result={{ original: '原文' }}
        onUse={() => {}}
        onClose={() => {}}
      />,
    )
    expect(document.querySelector('.prompt-enhancer__loading')).toBeTruthy()
    expect((screen.getByRole('button', { name: /使用润色|Use enhanced/ }) as HTMLButtonElement).disabled).toBe(true)
    expect((screen.getByRole('button', { name: /保留原始|Keep original/ }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('错误态：显示错误 + 只能关闭', () => {
    render(
      <PromptEnhancerDialog
        enhancing={false}
        result={{ original: '原文', error: 'boom' }}
        onUse={() => {}}
        onClose={() => {}}
      />,
    )
    expect(document.querySelector('.prompt-enhancer__error')?.textContent).toContain('boom')
    expect((screen.getByRole('button', { name: /使用润色|Use enhanced/ }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('结果态：Enter=使用、Esc=关闭、点击「使用」回调带文本', () => {
    const onUse = vi.fn()
    const onClose = vi.fn()
    render(
      <PromptEnhancerDialog
        enhancing={false}
        result={{ original: '原文', text: '润色结果' }}
        onUse={onUse}
        onClose={onClose}
      />,
    )
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(onUse).toHaveBeenCalledWith('润色结果')
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: /使用润色|Use enhanced/ }))
    expect(onUse).toHaveBeenCalledTimes(2)
  })
})

describe('子智能体（store + AgentSelect）', () => {
  it('agents 响应落地 subagentDefs', () => {
    messageHandler!({ op: 'agents', agents: [agentDef(), agentDef({ name: 'b', scope: 'project' })] })
    expect(useStore.getState().subagentDefs).toHaveLength(2)
  })

  it('agentDeleted 响应清掉当前选中并重拉清单', () => {
    useStore.setState({ selectedAgent: agentDef(), subagentDefs: [agentDef()] })
    messageHandler!({ op: 'agentDeleted', name: 'test-agent', scope: 'user' })
    expect(useStore.getState().selectedAgent).toBeNull()
    expect(sentRequests.some((r) => r.op === 'listAgents')).toBe(true)
  })

  it('agentSaved 响应置保存完成信号（AgentEditDialog 监听关弹窗——保存超时假象的回归）', () => {
    messageHandler!({ op: 'agentSaved', name: 'my-agent', scope: 'user' })
    const signal = useStore.getState().agentSavedSignal
    expect(signal).toBeTruthy()
    expect(signal!.name).toBe('my-agent')
    expect(signal!.scope).toBe('user')
    expect(sentRequests.some((r) => r.op === 'listAgents')).toBe(true)
  })

  it('AgentSelect：点选中项=取消，点其他项=切换；管理入口回调', () => {
    useStore.setState({ subagentDefs: [agentDef(), agentDef({ name: 'reviewer', color: 'purple' })] })
    const onManage = vi.fn()
    const { container } = render(<AgentSelect onManage={onManage} />)
    fireEvent.click(container.querySelector('.agent-select-button')!)
    // 两项 + 管理入口
    expect(container.querySelectorAll('.selector-dropdown-item')).toHaveLength(2)
    fireEvent.click(container.querySelectorAll('.selector-dropdown-item')[0])
    expect(useStore.getState().selectedAgent?.name).toBe('test-agent')
    // 重新打开，点已选中项 = 取消
    fireEvent.click(container.querySelector('.agent-select-button')!)
    fireEvent.click(container.querySelectorAll('.selector-dropdown-item')[0])
    expect(useStore.getState().selectedAgent).toBeNull()
    // 管理入口
    fireEvent.click(container.querySelector('.agent-select-button')!)
    fireEvent.click(container.querySelector('.agent-select-manage')!)
    expect(onManage).toHaveBeenCalledTimes(1)
  })
})

describe('InputBox 发送拼装（@<name> 前缀）', () => {
  function setup(selected: AgentDef | null) {
    useStore.setState({ selectedAgent: selected })
    const onSend = vi.fn()
    render(
      <InputBox
        onSend={onSend}
        currentModel={{ modelId: 'GLM-5.2', providerId: 'p1' }}
        onModelSelect={() => {}}
      />,
    )
    const editor = document.querySelector('.input-editable') as HTMLDivElement
    editor.textContent = '帮我看看这段代码'
    fireEvent.input(editor)
    return { onSend, editor }
  }

  it('选中子智能体：发送文本前置 @test-agent', async () => {
    const { onSend } = setup(agentDef())
    fireEvent.click(screen.getByRole('button', { name: /发送/ }))
    await waitFor(() => expect(onSend).toHaveBeenCalled())
    expect(onSend.mock.calls[0][0]).toBe('@test-agent\n帮我看看这段代码')
  })

  it('未选中：不带 @ 前缀', async () => {
    const { onSend } = setup(null)
    fireEvent.click(screen.getByRole('button', { name: /发送/ }))
    await waitFor(() => expect(onSend).toHaveBeenCalled())
    expect(onSend.mock.calls[0][0]).toBe('帮我看看这段代码')
  })

  it('润色按钮：点击发 enhancePrompt 请求（带编辑器正文）', () => {
    const { editor } = setup(null)
    editor.textContent = '写一个排序函数'
    fireEvent.input(editor)
    fireEvent.click(document.querySelector('.enhance-prompt-button')!)
    const req = sentRequests.find((r) => r.op === 'enhancePrompt')
    expect(req).toMatchObject({ text: '写一个排序函数' })
  })
})
