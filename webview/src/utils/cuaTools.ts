/**
 * 电脑控制（CUA）工具族共享工具函数。
 *
 * 图标链：server 在 list_apps / get_app_state / list_windows 的结果文本里附
 * 「icon=<12位hex>」内容寻址 key（PNG 落盘 ~/.zcode/cli/zcgui-cua-icons/），
 * 前端经 op=getCuaIcon 按需取图渲染工具卡 header；动作类工具（click/type/...）
 * 无 app 信号，用同会话内最近一次解析到的 key 兜底（renderPartUnits 顺扫）。
 */

/** 自带 CUA server 的 MCP 工具前缀（与 Kotlin CuaFeature.SERVER_NAME 对齐）*/
export const CUA_TOOL_PREFIX = 'mcp__zcgui-computer-use__'

/** 电脑操作技能名（与 Kotlin CuaFeature 安装的技能同名；InputBox 转发导出供 spec 断言）*/
export const CUA_SKILL_NAME = 'zcgui-computer-use'

/** 图标 key 形态：CuaIcons 内容寻址 MD5 前 12 位 hex */
const ICON_KEY_RE = /(?:^|[\s])icon=([0-9a-f]{12})(?=\s|$)/g

export function isCuaTool(tool: string): boolean {
  return tool.startsWith(CUA_TOOL_PREFIX)
}

/** CUA 工具短名（mcp__zcgui-computer-use__left_click → left_click）*/
export function cuaToolShortName(tool: string): string {
  return tool.slice(CUA_TOOL_PREFIX.length)
}

/** 从工具结果文本提取图标 key（app 行末字段；取最后匹配防标题误含「icon=」）*/
export function cuaIconKeyFromOutput(output?: string | null): string | null {
  if (!output) return null
  let m: RegExpExecArray | null
  let last: string | null = null
  ICON_KEY_RE.lastIndex = 0
  while ((m = ICON_KEY_RE.exec(output)) !== null) last = m[1]
  return last
}

const SCROLL_ARROWS: Record<string, string> = { up: '↑', down: '↓', left: '←', right: '→' }

/** list_apps 结果行（解析失败返回 null，卡片回退裸 JSON）*/
export interface CuaAppRow {
  pid: number
  name: string
  title: string
  active: boolean
  /** 内容寻址图标 key（server 落盘，getCuaIcon 懒取）*/
  icon?: string
}

/** 解析 list_apps 输出 {"apps":[...]}；形状不符/非 JSON → null（调用方回退原文）*/
export function parseCuaAppList(output?: string | null): CuaAppRow[] | null {
  if (!output) return null
  try {
    const apps = (JSON.parse(output) as { apps?: unknown })?.apps
    if (!Array.isArray(apps) || apps.length === 0) return null
    const rows: CuaAppRow[] = []
    for (const a of apps) {
      const r = a as Record<string, unknown>
      if (typeof r.pid !== 'number') return null
      rows.push({
        pid: r.pid,
        name: String(r.name ?? ''),
        title: String(r.title ?? ''),
        active: r.active === true,
        icon: typeof r.icon === 'string' ? r.icon : undefined,
      })
    }
    return rows
  } catch {
    return null
  }
}

export interface CuaWindowRow {
  windowId: number
  title: string
}

/** 解析 list_windows 输出（首行 {"windows":[...]}，其后可能跟 app 行）*/
export function parseCuaWindows(output?: string | null): CuaWindowRow[] | null {
  if (!output) return null
  try {
    const nl = output.indexOf('\n')
    const head = nl === -1 ? output : output.slice(0, nl)
    const wins = (JSON.parse(head) as { windows?: unknown })?.windows
    if (!Array.isArray(wins) || wins.length === 0) return null
    const rows: CuaWindowRow[] = []
    for (const w of wins) {
      const r = w as Record<string, unknown>
      if (typeof r.window_id !== 'number') return null
      rows.push({ windowId: r.window_id, title: String(r.title ?? '') })
    }
    return rows
  } catch {
    return null
  }
}

function coord(v: unknown): string {
  return Array.isArray(v) && v.length >= 2 ? `[${v[0]}, ${v[1]}]` : ''
}

/**
 * CUA 动作一行摘要（组卡成员行用；坐标/按键/文本本身语言中立，不走 i18n）。
 * input 可能未解析（流式），返回空串由行渲染回退工具名。
 */
export function cuaActionSummary(tool: string, input?: Record<string, unknown>): string {
  const short = cuaToolShortName(tool)
  if (!input) return ''
  switch (short) {
    case 'left_click': {
      const c = coord(input.target)
      if (!c) return ''
      const n = Number(input.click_count ?? 1) || 1
      return n > 1 ? `${c} ×${n}` : c
    }
    case 'left_click_drag': {
      const from = coord(input.from_target)
      const to = coord(input.to)
      return from && to ? `${from} → ${to}` : from || to
    }
    case 'scroll': {
      const arrow = SCROLL_ARROWS[String(input.scroll_direction ?? '')] ?? ''
      const amount = Number(input.scroll_amount ?? 1) || 1
      return `${arrow} ${amount}`.trim()
    }
    case 'type':
    case 'paste':
      return String(input.text ?? '').replace(/\s+/g, ' ').trim().slice(0, 60)
    case 'key':
      return String(input.text ?? '').trim()
    case 'get_app_state': {
      const ref = (input.app_ref ?? input.app) as Record<string, unknown> | undefined
      const display = Number(input.display ?? 0) || 0
      if (ref && typeof ref.name === 'string' && ref.name) return `${ref.name} · display ${display}`
      return display ? `display ${display}` : ''
    }
    case 'list_windows': {
      const ref = (input.app_ref ?? input.app) as Record<string, unknown> | undefined
      return ref && typeof ref.name === 'string' ? ref.name : ''
    }
    default:
      return ''
  }
}

/**
 * 会话是否已进入电脑控制上下文（输入框「电脑操作」入口常亮判据之一）——只认
 * AI 的真实使用事实：
 *   - Skill 工具调用目标为 zcgui-computer-use（AI 加载技能）
 *   - 出现过 mcp__zcgui-computer-use__* 工具调用（CUA 工具真实执行）
 * 用户消息文本提及技能名不算（bug 报告/引用会话标题都会带技能名，宽松子串
 * 实测误点亮——sess_e7373f8d 真机反馈），文本 signals 全部不参与判定。
 *
 * ref 缓存：作 useStore selector 消费（返回原语布尔，不触发流式重渲染），
 * messages 引用未变时直接返回上次结果，避免每次 store 更新全量重扫。
 */
let cuaScanCache: { ref: unknown; result: boolean } | null = null

/** part 的扫描视野（结构化收窄，不依赖 MessagePart 具体类型避免耦合）*/
type CuaScanPart = { type?: string; tool?: string; text?: string; state?: { input?: { skill?: unknown } } }

export function sessionReferencesCua(
  messages: ReadonlyArray<{ parts: ReadonlyArray<unknown> }>,
): boolean {
  if (cuaScanCache && cuaScanCache.ref === messages) return cuaScanCache.result
  let result = false
  scan: for (const m of messages) {
    for (const raw of m.parts) {
      const p = raw as CuaScanPart
      if (p.type !== 'tool') continue
      if (isCuaTool(String(p.tool))) {
        result = true
        break scan
      }
      if (p.tool === 'Skill' && p.state?.input?.skill === CUA_SKILL_NAME) {
        result = true
        break scan
      }
    }
  }
  cuaScanCache = { ref: messages, result }
  return result
}
