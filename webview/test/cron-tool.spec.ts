import { describe, expect, it } from 'vitest'
import { isCronTool, parseCronToolOutput, describeCronSchedule, describeAutomationTime } from '../src/utils/cronTool'

// i18n 直取模板渲染（与运行时 t 函数同形：键值模板 + 插值）
const zhRaw = {
  'tool.cron.afterMinutes': '{{count}} 分钟后',
  'tool.cron.at': '{{date}} {{time}}',
}
const t = ((key: string, opts?: Record<string, unknown>) => {
  let s = zhRaw[key as keyof typeof zhRaw] ?? key
  for (const [k, v] of Object.entries(opts ?? {})) s = s.replace(new RegExp(`{{${k}}}`, 'g'), String(v))
  return s
}) as unknown as (key: string, opts?: Record<string, unknown>) => string

describe('isCronTool', () => {
  it('只识别四个 Cron 工具', () => {
    expect(isCronTool('CronCreate')).toBe(true)
    expect(isCronTool('CronUpdate')).toBe(true)
    expect(isCronTool('CronList')).toBe(true)
    expect(isCronTool('CronDelete')).toBe(true)
    expect(isCronTool('Bash')).toBe(false)
    expect(isCronTool('Task')).toBe(false)
  })
})

describe('describeCronSchedule', () => {
  it('delayMinutes 优先渲染「N 分钟后」', () => {
    // 官方延时形态：占位 cron 与 delayMinutes 并存，cron 不参与展示
    expect(
      describeCronSchedule({ cron: '* * * * *', delayMinutes: 20, recurring: false }, t, 'zh-CN'),
    ).toBe('20 分钟后')
  })

  it('一次性绝对 cron 解析为本地时刻（未来）', () => {
    const now = new Date()
    const targetMonth = now.getMonth() + 1
    const targetDay = now.getDate()
    // 今天时刻 +1h，确保未来（同日同时分边界用 +2h 更稳）
    const h = (now.getHours() + 2) % 24
    const cron = `30 ${h} ${targetDay} ${targetMonth} *`
    const text = describeCronSchedule({ cron }, t, 'zh-CN') as string
    expect(text).toMatch(/30$/)
    expect(text).not.toBe(cron)
  })

  it('占位/缺失/周期形态回退', () => {
    expect(describeCronSchedule(undefined, t, 'zh-CN')).toBeNull()
    expect(describeCronSchedule({}, t, 'zh-CN')).toBeNull()
    // 周期 cron（第一期不支持）：原样返回保持原貌
    expect(describeCronSchedule({ cron: '*/20 * * * *' }, t, 'zh-CN')).toBe('*/20 * * * *')
    expect(describeCronSchedule({ cron: '0 9 * * 1-5' }, t, 'zh-CN')).toBe('0 9 * * 1-5')
  })
})

describe('parseCronToolOutput', () => {
  it('create/update 的 {automation,message} 提取 message', () => {
    const parsed = parseCronToolOutput(
      JSON.stringify({ automation: { automationId: 'sched_1' }, message: 'Created automation sched_1.' }),
    )
    expect(parsed?.message).toBe('Created automation sched_1.')
    expect(parsed?.automation?.automationId).toBe('sched_1')
  })

  it('list 的 automations 数组透传', () => {
    const parsed = parseCronToolOutput(
      JSON.stringify({ automations: [{ automationId: 'a', title: '喝水提醒', nextRunAt: 1789526362883 }] }),
    )
    expect(parsed?.automations).toHaveLength(1)
    expect(parsed?.automations?.[0].title).toBe('喝水提醒')
  })

  it('非 JSON（错误文本/流式半截）返回 null', () => {
    expect(parseCronToolOutput(null)).toBeNull()
    expect(parseCronToolOutput('')).toBeNull()
    expect(parseCronToolOutput('插件当前版本仅支持一次性定时任务')).toBeNull()
    expect(parseCronToolOutput('{"automation":{"id')).toBeNull()
    expect(parseCronToolOutput('123')).toBeNull()
  })
})

describe('describeAutomationTime', () => {
  it('优先 nextRunAt（epoch ms → 本地时刻）', () => {
    const ms = new Date(2026, 8, 17, 9, 30).getTime()
    expect(describeAutomationTime({ nextRunAt: ms }, t, 'zh-CN')).toMatch(/9:30|09:30/)
  })

  it('无 nextRunAt 回退 cronExpr 解析，再回退原样', () => {
    expect(describeAutomationTime({ cronExpr: '*/20 * * * *' }, t, 'zh-CN')).toBe('*/20 * * * *')
    expect(describeAutomationTime({}, t, 'zh-CN')).toBe('')
  })
})
