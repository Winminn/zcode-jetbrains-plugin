/**
 * 定时任务工具（CronCreate/CronUpdate/CronList/CronDelete）卡片的解析与描述。
 *
 * 数据形状（rollout + diag-automation-host.py 实测，2026-09-16）：
 *  - CronCreate input = { title, prompt, cron?, delayMinutes?, recurring?, maxRuns?,
 *    intervalUnit?, interval? }（注意：模型侧字段名是 cron/delayMinutes，转发到宿主
 *    才变成 cronExpr/relativeDelayMinutes——卡片看到的是模型侧）；
 *  - CronUpdate input = { id, cron?/delayMinutes?, title?, prompt?, recurring?... }；
 *  - CronDelete input = { id }；CronList input = {}；
 *  - output 一律为 JSON 文本：create/update={automation,message}、list={automations}、
 *    delete={deleted,id,message}。第一期宿主仅支持一次性任务，周期形态在 create/update
 *    即被拒（error 文本非 JSON，解析 null 回退原文展示）。
 */
import type { TFunction } from 'i18next'

export const CRON_TOOLS = ['CronCreate', 'CronUpdate', 'CronList', 'CronDelete'] as const

export function isCronTool(tool: string): boolean {
  return (CRON_TOOLS as readonly string[]).includes(tool)
}

/** 宿主侧一次性 cron 形态（官方 zSe）：四个纯数字字段 + 星期 *，如 "30 9 17 9 *" */
const PINNED_ONE_SHOT = /^(\d{1,2})\s+(\d{1,2})\s+(\d{1,2})\s+(\d{1,2})\s+\*$/

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

function localDateTimeParts(epochMs: number): { date: Date; time: string } {
  const d = new Date(epochMs)
  return { time: `${pad2(d.getHours())}:${pad2(d.getMinutes())}`, date: d }
}

/** 按当前界面语言格式化日期部分（"9月17日" / "September 17"）；失败回退数字形态 */
function formatDate(d: Date, language: string): string {
  try {
    return new Intl.DateTimeFormat(language, { month: 'long', day: 'numeric' }).format(d)
  } catch {
    return `${d.getMonth() + 1}/${d.getDate()}`
  }
}

/**
 * input 里的触发时刻描述（卡片时间行）：
 *  - delayMinutes → 「N 分钟后」；
 *  - cron 为占位 "* * * * *"（延时形态的占位符）→ null（用 delayMinutes 行）；
 *  - cron 为一次性四字段 → 解析成本地时刻（今年已过取明年——创建回放/改期时语义稳定）；
 *  - 其他 cron（周期/区间等第一期不支持的形态）→ 原样返回（错误卡里保持原貌）。
 */
export function describeCronSchedule(input: Record<string, unknown> | undefined, t: TFunction, language: string): string | null {
  if (!input) return null
  const delay = typeof input.delayMinutes === 'number' ? input.delayMinutes : Number(input.delayMinutes)
  if (Number.isFinite(delay) && delay > 0) {
    return t('tool.cron.afterMinutes', { count: delay })
  }
  const cron = typeof input.cron === 'string' ? input.cron.trim() : ''
  if (!cron || cron === '* * * * *') return null
  const m = PINNED_ONE_SHOT.exec(cron)
  if (m) {
    const minute = Number(m[1])
    const hour = Number(m[2])
    const day = Number(m[3])
    const month = Number(m[4])
    if (minute > 59 || hour > 23 || day < 1 || day > 31 || month < 1 || month > 12) return cron
    const now = new Date()
    let target = new Date(now.getFullYear(), month - 1, day, hour, minute)
    if (target.getTime() <= now.getTime()) target = new Date(now.getFullYear() + 1, month - 1, day, hour, minute)
    const { date, time } = localDateTimeParts(target.getTime())
    return t('tool.cron.at', { date: formatDate(date, language), time })
  }
  return cron
}

/** automations 列表条目（automation/list 的 automation 对象，宿主侧字段 cronExpr/nextRunAt） */
export interface AutomationEntry {
  automationId?: string
  title?: string
  prompt?: string
  cronExpr?: string
  nextRunAt?: number
  lastRunAt?: number
  runCount?: number
  enabled?: boolean
  lifecycleStatus?: string
  targetTaskId?: string
  recurring?: boolean
}

export interface ParsedCronOutput {
  message?: string
  automations?: AutomationEntry[]
  automation?: AutomationEntry
}

/** output JSON 解析（非 JSON 的错误文本/流式半截 → null，调用方回退原文展示） */
export function parseCronToolOutput(output: string | null | undefined): ParsedCronOutput | null {
  if (!output) return null
  try {
    const o = JSON.parse(output) as ParsedCronOutput
    if (typeof o === 'object' && o !== null && (typeof o.message === 'string' || Array.isArray(o.automations))) {
      return o
    }
    return null
  } catch {
    return null
  }
}

/** 列表条目的时间列：优先 nextRunAt（epoch ms），缺失回退 cronExpr 的解析/原样 */
export function describeAutomationTime(a: AutomationEntry, t: TFunction, language: string): string {
  if (typeof a.nextRunAt === 'number' && a.nextRunAt > 0) {
    const { date, time } = localDateTimeParts(a.nextRunAt)
    return t('tool.cron.at', { date: formatDate(date, language), time })
  }
  if (typeof a.cronExpr === 'string') {
    return describeCronSchedule({ cron: a.cronExpr }, t, language) ?? a.cronExpr
  }
  return ''
}
