/**
 * 当前文件 chip 发送链路测试（《当前文件chip-发送链路实现.md》5 条验收标准）
 *
 * 首要原则：任何时刻"chip 显示的" == "下一条消息会携带的"。两条硬约束：
 *   单一数据源——发送取值表达式 = chip 显示表达式（enabled && currentFileRef）；
 *   唯一拼点——拼接只发生在 InputBox doSend（本文件全部断言都落在 onSend 第 1 参）。
 *
 * doSend 层不区分新会话/继续对话：取值与会话 id 无关（跟随 IDE 选区），懒创建
 * 路径由 store.sendMessage 原样透传 text（useStore.ts pendingFirstMessage），
 * 所以验收 1（新会话首条）与验收 2（继续对话）在 InputBox 层是同一条断言。
 */
// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, fireEvent, cleanup, act } from '@testing-library/react'

const sentRequests: Array<Record<string, unknown>> = []

vi.mock('@/ipc/bridge', () => ({
  initBridge: () => {},
  isInJcef: () => false,
  getWorkspacePath: () => 'G:\\mock',
  getInitialSessionId: () => '',
  onMessage: () => {},
  onStreamEvent: () => {},
  onStreamBatch: () => {},
  sendToJava: (req: Record<string, unknown>) => { sentRequests.push(req) },
}))

import '@/i18n/config'
import { useStore } from '@/store/useStore'
import { InputBox } from '@/components/InputBox'

// jsdom 的 localStorage 是无 clear 的普通对象（inputbox-goal.spec 同款 mock），
// chip 勾选态（zcode.currentFile.enabled）与输入历史 persist 通道都落在上面
const storage = new Map<string, string>()
Object.defineProperty(window, 'localStorage', {
  configurable: true,
  writable: true,
  value: {
    getItem: (k: string) => storage.get(k) ?? null,
    setItem: (k: string, v: string) => { storage.set(k, v) },
    removeItem: (k: string) => { storage.delete(k) },
    key: (i: number) => Array.from(storage.keys())[i] ?? null,
    get length() { return storage.size },
    clear: () => storage.clear(),
  },
})

/** 带行号的 ref（与 EditorContextTracker 推送同形态：`@path#L10-20`，自带 @ 前缀）*/
const REF = '@E:/proj/src/App.ts#L10-20'

beforeEach(() => {
  sentRequests.length = 0
  storage.clear()
  // jsdom 不实现 innerText（InputBox 幽灵补全/历史回填读写），polyfill 成 textContent
  if (!('innerText' in HTMLDivElement.prototype)) {
    Object.defineProperty(HTMLDivElement.prototype, 'innerText', {
      configurable: true,
      get(this: HTMLDivElement) { return this.textContent ?? '' },
      set(this: HTMLDivElement, v: string) { this.textContent = v },
    })
  }
  document.execCommand = (() => false) as typeof document.execCommand
  useStore.getState().init()
  sentRequests.length = 0
  useStore.setState({
    currentSessionId: 'sess_a',
    currentModel: { modelId: 'GLM-5.2', providerId: 'builtin:bigmodel-coding-plan' },
    currentWorkspacePath: 'G:\\mock',
    streaming: false,
    messages: [],
    goal: null,
    selectedAgent: null,
    queuedMessages: [],
  })
})
afterEach(cleanup)

function setup(opts?: { ref?: string | null; enabled?: boolean }) {
  // 勾选态在 useState 初始化时读 localStorage，必须先于 render 写入
  if (opts?.enabled) storage.set('zcode.currentFile.enabled', '1')
  const onSend = vi.fn()
  const { container } = render(
    <InputBox
      onSend={onSend}
      currentModel={{ modelId: 'GLM-5.2', providerId: 'p1' }}
      onModelSelect={() => {}}
      currentFileRef={opts?.ref ?? null}
    />,
  )
  const editor = container.querySelector('.input-editable') as HTMLElement
  const sendBtn = (Array.from(container.querySelectorAll('button')).find(
    (b) => b.className.includes('submit-button') && !b.className.includes('stop-button'),
  ) ?? container.querySelector('.submit-button')) as HTMLButtonElement
  const chip = container.querySelector('[data-testid="current-file-chip"]') as HTMLElement
  return { container, editor, sendBtn, chip, onSend }
}

/** 模拟键入正文（handleInput 读 textContent；打字后光标落在编辑器末尾，模拟真实输入）*/
function type(editor: HTMLElement, text: string) {
  editor.textContent = text
  fireEvent.input(editor)
  const sel = window.getSelection()
  const range = document.createRange()
  range.selectNodeContents(editor)
  range.collapse(false)
  sel?.removeAllRanges()
  sel?.addRange(range)
}

/** onSend 第 n 次调用的第 1 参（发送文本）*/
const sentText = (onSend: ReturnType<typeof vi.fn>, n = 0) =>
  onSend.mock.calls[n][0] as string

describe('当前文件 chip 发送链路', () => {
  it('验收1/2：勾选 + chip 显示 ref → 发送文本头部携带同一 ref（行号完整）', () => {
    const { editor, sendBtn, chip, onSend } = setup({ ref: REF, enabled: true })
    // chip 显示值 = basename + 行号（对照基准：发送值必须与它同源）
    expect(chip.textContent).toContain('App.ts')
    expect(chip.textContent).toContain('L10-20')
    type(editor, '你好')
    fireEvent.click(sendBtn)
    expect(sentText(onSend)).toBe(`${REF}\n\n你好`)
  })

  it('验收5：未勾选 → 发送文本原样，不带 ref', () => {
    const { editor, sendBtn, onSend } = setup({ ref: REF, enabled: false })
    // 未勾选时 chip 只显示文字标签，不显示文件名（显示表达式 enabled && ref）
    expect(useStore.getState().currentSessionId).toBe('sess_a')
    type(editor, '你好')
    fireEvent.click(sendBtn)
    expect(sentText(onSend)).toBe('你好')
  })

  it('勾选但 ref=null（IDE 无打开文件）→ 发送文本原样', () => {
    const { editor, sendBtn, onSend } = setup({ ref: null, enabled: true })
    type(editor, '你好')
    fireEvent.click(sendBtn)
    expect(sentText(onSend)).toBe('你好')
  })

  it('验收3：发送后不漂移——chip 仍显示文件，再发仍携带同值', () => {
    const { editor, sendBtn, chip, onSend } = setup({ ref: REF, enabled: true })
    type(editor, '第一条')
    fireEvent.click(sendBtn)
    // 发送后 chip 显示不变（生命周期跟随 IDE 选区，不随发送清空/冻结）
    expect(chip.textContent).toContain('App.ts')
    type(editor, '第二条')
    fireEvent.click(sendBtn)
    expect(sentText(onSend, 1)).toBe(`${REF}\n\n第二条`)
  })

  it('验收4：切会话后发送仍携带 chip 显示值（跟随 IDE，不随会话清零）', () => {
    const { editor, sendBtn, onSend } = setup({ ref: REF, enabled: true })
    type(editor, '会话A')
    fireEvent.click(sendBtn)
    act(() => useStore.setState({ currentSessionId: 'sess_b' }))
    type(editor, '会话B')
    fireEvent.click(sendBtn)
    expect(sentText(onSend, 1)).toBe(`${REF}\n\n会话B`)
  })

  it('/goal 拦截不受上下文前缀影响（拼点在 goal 拦截之后）', () => {
    const { editor, sendBtn, onSend } = setup({ ref: REF, enabled: true })
    type(editor, '/goal 重构登录页')
    fireEvent.click(sendBtn)
    // 勾选态下 /goal 仍命中控制意图：转 goalManage，不带上下文进消息流
    expect(sentRequests.find((r) => r.op === 'goalManage')).toMatchObject({
      action: 'set',
      objective: '重构登录页',
    })
    expect(onSend).not.toHaveBeenCalled()
  })

  it('chip 点击切换即时生效：勾选带、取消不带，勾选态落 localStorage', () => {
    const { editor, sendBtn, chip, onSend } = setup({ ref: REF })
    // 初始未勾选：不带
    type(editor, '一')
    fireEvent.click(sendBtn)
    expect(sentText(onSend)).toBe('一')
    // 点击勾选：下一条立即携带（发送取值与 chip 显示同一表达式，无中间态）
    fireEvent.click(chip)
    expect(storage.get('zcode.currentFile.enabled')).toBe('1')
    type(editor, '二')
    fireEvent.click(sendBtn)
    expect(sentText(onSend, 1)).toBe(`${REF}\n\n二`)
    // 再点取消：立即恢复不带
    fireEvent.click(chip)
    expect(storage.get('zcode.currentFile.enabled')).toBe('0')
    type(editor, '三')
    fireEvent.click(sendBtn)
    expect(sentText(onSend, 2)).toBe('三')
  })

  it('输入历史只记用户内容：发送后 ArrowUp 回填不含 @ref 前缀', () => {
    const { editor, sendBtn, onSend } = setup({ ref: REF, enabled: true })
    type(editor, '你好')
    fireEvent.click(sendBtn)
    // 前置确认：本条确实携带着上下文发出（否则回填断言无意义）
    expect(sentText(onSend)).toBe(`${REF}\n\n你好`)
    // 发送后输入框已清空，ArrowUp 回溯最近一条历史
    expect(editor.textContent ?? '').toBe('')
    fireEvent.keyDown(editor, { key: 'ArrowUp' })
    // 回填的是用户内容而非发送载荷：上下文前缀不固化进历史，
    // 重发时按当时 chip 显示值重新派生
    expect(editor.textContent).toBe('你好')
  })
})
