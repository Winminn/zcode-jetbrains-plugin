import { describe, expect, it } from 'vitest'
import type { MessagePart, ToolPart } from '../src/types/messages'
import { groupParts, toolGroupKind } from '../src/utils/groupParts'
import {
  cuaActionSummary, cuaIconKeyFromOutput, isCuaTool, cuaToolShortName,
  sessionReferencesCua, CUA_SKILL_NAME, parseCuaAppList, parseCuaWindows,
} from '../src/utils/cuaTools'

const P = 'mcp__zcgui-computer-use__'

let seq = 0
function cuaTool(tool: string, opts: Partial<Pick<ToolPart['state'], 'output' | 'input' | 'media'>> = {}): ToolPart {
  return {
    type: 'tool',
    callID: `call_cua_${++seq}`,
    tool,
    state: {
      status: 'completed',
      ...(opts.input ? { input: opts.input } : {}),
      ...(opts.output !== undefined ? { output: opts.output } : {}),
      ...(opts.media ? { media: opts.media } : {}),
    },
  }
}

function stepStart(): MessagePart {
  return { type: 'step-start' } as MessagePart
}

function text(t: string): MessagePart {
  return { type: 'text', text: t } as MessagePart
}

describe('toolGroupKind（CUA 族）', () => {
  it('CUA 前缀工具归 cua 组，其余 mcp__* 不聚组', () => {
    expect(toolGroupKind(`${P}left_click`)).toBe('cua')
    expect(toolGroupKind(`${P}get_app_state`)).toBe('cua')
    expect(toolGroupKind('mcp__other-server__do')).toBeNull()
    expect(toolGroupKind('Bash')).toBe('bash')
    expect(toolGroupKind('TodoWrite')).toBeNull()
  })

  it('isCuaTool / cuaToolShortName 前缀判定与剪短', () => {
    expect(isCuaTool(`${P}key`)).toBe(true)
    expect(isCuaTool('mcp__other__key')).toBe(false)
    expect(cuaToolShortName(`${P}left_click_drag`)).toBe('left_click_drag')
  })
})

describe('groupParts（cua 同名聚组）', () => {
  it('同名相邻 CUA 工具聚成一组（step 标记不打断）', () => {
    const parts: MessagePart[] = [
      stepStart(),
      cuaTool(`${P}left_click`, { input: { target: [1, 2] } }),
      stepStart(),
      cuaTool(`${P}left_click`, { input: { target: [3, 4] } }),
      cuaTool(`${P}left_click`, { input: { target: [5, 6] } }),
    ]
    const units = groupParts(parts)
    expect(units).toHaveLength(1)
    expect(units[0].kind).toBe('toolGroup')
    if (units[0].kind === 'toolGroup') {
      expect(units[0].group).toBe('cua')
      expect(units[0].parts).toHaveLength(3)
    }
  })

  it('不同名 CUA 工具不并组（click→type 各自成组/单卡）', () => {
    const parts: MessagePart[] = [
      cuaTool(`${P}left_click`, { input: { target: [1, 2] } }),
      cuaTool(`${P}left_click`, { input: { target: [3, 4] } }),
      cuaTool(`${P}type`, { input: { text: 'hi' } }),
      cuaTool(`${P}type`, { input: { text: 'yo' } }),
    ]
    const units = groupParts(parts)
    expect(units).toHaveLength(2)
    expect(units.every((u) => u.kind === 'toolGroup' && u.group === 'cua')).toBe(true)
    if (units[0].kind === 'toolGroup' && units[1].kind === 'toolGroup') {
      expect(units[0].parts).toHaveLength(2)
      expect(units[1].parts).toHaveLength(2)
    }
  })

  it('文本打断聚组；单个 CUA 工具保持单卡', () => {
    const parts: MessagePart[] = [
      cuaTool(`${P}key`, { input: { text: 'Return' } }),
      text('中间的正文'),
      cuaTool(`${P}key`, { input: { text: 'Escape' } }),
    ]
    const units = groupParts(parts)
    expect(units).toHaveLength(3)
    expect(units.every((u) => u.kind === 'single')).toBe(true)
  })

  it('cua 组与既有 bash 组互不打断', () => {
    const bash = (cmd: string): ToolPart => ({
      type: 'tool',
      callID: `call_bash_${++seq}`,
      tool: 'Bash',
      state: { status: 'completed', input: { command: cmd } },
    })
    const parts: MessagePart[] = [
      bash('echo a'),
      bash('echo b'),
      cuaTool(`${P}left_click`, { input: { target: [1, 1] } }),
      cuaTool(`${P}left_click`, { input: { target: [2, 2] } }),
    ]
    const units = groupParts(parts)
    expect(units).toHaveLength(2)
    if (units[0].kind === 'toolGroup' && units[1].kind === 'toolGroup') {
      expect(units[0].group).toBe('bash')
      expect(units[1].group).toBe('cua')
    }
  })
})

describe('cuaIconKeyFromOutput', () => {
  it('从 app 行提取末尾 icon key（get_app_state 形态）', () => {
    const out = '{\n  "state_id": "s1"\n}\napp: pid=1001 name=idea64 title=IDEA - demo active=true icon=a1b2c3d4e5f6'
    expect(cuaIconKeyFromOutput(out)).toBe('a1b2c3d4e5f6')
  })

  it('无 key/空输出返回 null；标题含 icon= 时取最后合法匹配', () => {
    expect(cuaIconKeyFromOutput('{"apps":[]}')).toBeNull()
    expect(cuaIconKeyFromOutput('')).toBeNull()
    expect(cuaIconKeyFromOutput(undefined)).toBeNull()
    // 非 12 位 hex 的 icon= 不匹配
    expect(cuaIconKeyFromOutput('app: name=x icon=NOTVALID12345')).toBeNull()
  })
})

describe('cuaActionSummary', () => {
  it('各类动作的一行摘要', () => {
    expect(cuaActionSummary(`${P}left_click`, { target: [512, 384] })).toBe('[512, 384]')
    expect(cuaActionSummary(`${P}left_click`, { target: [1, 2], click_count: 2 })).toBe('[1, 2] ×2')
    expect(cuaActionSummary(`${P}left_click_drag`, { from_target: [1, 2], to: [3, 4] })).toBe('[1, 2] → [3, 4]')
    expect(cuaActionSummary(`${P}scroll`, { target: [0, 0], scroll_direction: 'down', scroll_amount: 3 })).toBe('↓ 3')
    expect(cuaActionSummary(`${P}key`, { text: 'Control_L+s' })).toBe('Control_L+s')
    expect(cuaActionSummary(`${P}type`, { text: 'hello\nworld' })).toBe('hello world')
    expect(cuaActionSummary(`${P}get_app_state`, { app_ref: { name: 'idea64' }, display: 0 })).toBe('idea64 · display 0')
    expect(cuaActionSummary(`${P}get_app_state`, { app_ref: { pid: 1001 }, display: 0 })).toBe('')
  })

  it('input 缺失/无参字段回退空串（行渲染回退工具名）', () => {
    expect(cuaActionSummary(`${P}left_click`)).toBe('')
    expect(cuaActionSummary(`${P}left_click`, {})).toBe('')
    expect(cuaActionSummary(`${P}stop_computer_control`, { reason: 'x' })).toBe('')
  })
})

type ScanMsg = Parameters<typeof sessionReferencesCua>[0][number]

function scanMsg(parts: Array<Record<string, unknown>>): ScanMsg {
  return { parts } as unknown as ScanMsg
}

describe('sessionReferencesCua（入口常亮判据）', () => {
  it('Skill 工具调用目标为 CUA 技能 → true', () => {
    const msgs = [scanMsg([
      { type: 'tool', tool: 'Skill', state: { status: 'completed', input: { skill: CUA_SKILL_NAME } } },
    ])]
    expect(sessionReferencesCua(msgs)).toBe(true)
  })

  it('出现 CUA MCP 工具调用 → true', () => {
    const msgs = [scanMsg([
      { type: 'tool', tool: `${P}get_app_state`, state: { status: 'completed', output: 'x' } },
    ])]
    expect(sessionReferencesCua(msgs)).toBe(true)
  })

  it('用户消息文本提及技能名不算（bug 报告/会话标题引用都会误点，宽松子串已撤）', () => {
    const msgs = [scanMsg([{ type: 'text', text: `[$${CUA_SKILL_NAME}](C:\\s\\SKILL.md) 操作桌面` }])]
    expect(sessionReferencesCua(msgs)).toBe(false)
  })

  it('AI 正文顺嘴提到技能名不算', () => {
    const msgs = [scanMsg([{ type: 'text', text: `我可以通过 ${CUA_SKILL_NAME} 技能帮你` }])]
    expect(sessionReferencesCua(msgs)).toBe(false)
  })

  it('其他技能的 Skill 调用不算', () => {
    const msgs = [scanMsg([
      { type: 'tool', tool: 'Skill', state: { status: 'completed', input: { skill: 'code-review' } } },
    ])]
    expect(sessionReferencesCua(msgs)).toBe(false)
  })

  it('空会话 → false；AI 真实调用后翻转', () => {
    // ref 缓存契约：store 的 messages 恒为不可变替换（新引用），原地 mutate 不感知——
    // 这里同样以新数组引用模拟生产形态
    let msgs: ScanMsg[] = []
    expect(sessionReferencesCua(msgs)).toBe(false)
    msgs = [...msgs, scanMsg([
      { type: 'tool', tool: `${P}list_apps`, state: { status: 'completed', output: '{"apps":[]}' } },
    ])]
    expect(sessionReferencesCua(msgs)).toBe(true)
  })
})

describe('parseCuaAppList / parseCuaWindows（list_apps/list_windows 友好渲染解析）', () => {
  const APPS_JSON = JSON.stringify({
    apps: [
      { pid: 35462, name: 'idea64', bundle_id: null, active: true, title: 'IDEA – 项目', icon: '123812b2f895' },
      { pid: 23152, name: 'WorkBuddy', bundle_id: null, active: false, title: 'WorkBuddy' },
    ],
  })

  it('list_apps：解析出行（icon 缺省 undefined、active 布尔）', () => {
    const rows = parseCuaAppList(APPS_JSON)
    expect(rows).toHaveLength(2)
    expect(rows![0]).toEqual({ pid: 35462, name: 'idea64', title: 'IDEA – 项目', active: true, icon: '123812b2f895' })
    expect(rows![1].icon).toBeUndefined()
    expect(rows![1].active).toBe(false)
  })

  it('list_apps：空数组/缺字段/非 JSON/空输入 → null（回退裸 JSON）', () => {
    expect(parseCuaAppList('{"apps":[]}')).toBeNull()
    expect(parseCuaAppList('{"apps":[{"name":"x"}]}')).toBeNull() // 缺 pid
    expect(parseCuaAppList('不是 JSON')).toBeNull()
    expect(parseCuaAppList('')).toBeNull()
    expect(parseCuaAppList(undefined)).toBeNull()
    expect(parseCuaAppList('{"other":1}')).toBeNull()
  })

  it('list_windows：取首行 JSON（其后 app 行不参与解析）', () => {
    const out = '{"windows":[{"window_id":52876,"title":"zcode-idea-plugin – README.md","rect":null}]}\napp: pid=52876 name=idea64 icon=123812b2f895'
    const rows = parseCuaWindows(out)
    expect(rows).toEqual([{ windowId: 52876, title: 'zcode-idea-plugin – README.md' }])
  })

  it('list_windows：非 JSON/空数组 → null', () => {
    expect(parseCuaWindows('{"windows":[]}')).toBeNull()
    expect(parseCuaWindows('garbage')).toBeNull()
    expect(parseCuaWindows(null)).toBeNull()
  })
})
