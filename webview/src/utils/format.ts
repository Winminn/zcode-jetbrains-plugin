/**
 * 用量/额度相关的数字与文案格式化（ContextRing / QuotaView / UsageView / LineChart 共用）
 *
 * 规则对齐 glm-plan-usage-idea（智谱官方页源码同款）：
 *   - fmtTokens：上下文/额度数值（万/k）
 *   - fmtBig：汇总表数值（亿/万/原数）
 *   - compact：图表 Y 轴刻度缩写
 */

import type { QuotaLimit } from '@/types/messages'

/** 格式化为万/k（上下文用量、额度已用/总量）*/
export function fmtTokens(n?: number | null): string {
  if (n == null) return '-'
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万`
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`
  return String(n)
}

/** 格式化为亿/万/原数（汇总表「总 Token M」用，对齐 glm fmt）*/
export function fmtBig(n?: number | null): string {
  if (n == null) return '-'
  if (n >= 1e8) return `${(n / 1e8).toFixed(2)}亿`
  if (n >= 1e4) return `${(n / 1e4).toFixed(1)}万`
  return n.toFixed(0)
}

/** Y 轴刻度缩写（对齐 glm LineChart compact）*/
export function compact(n: number): string {
  if (n >= 1e8) return `${(n / 1e8).toFixed(1)}亿`
  if (n >= 1e4) return `${Math.round(n / 1e4)}万`
  if (n >= 1e3) return `${Math.round(n / 1e3)}k`
  if (n >= 1) return String(Math.round(n))
  return '0'
}

/** unit/type → 标题（挖自智谱官方页源码，glm-plan-usage-idea 同款）*/
export function limitTitle(limit: QuotaLimit): string {
  if (limit.type === 'TIME_LIMIT') {
    return limit.unit === 5 ? 'MCP 每月额度' : '周期额度'
  }
  if (limit.unit === 3) return '每 5 小时使用额度'
  if (limit.unit === 6) return '每周使用额度'
  return '使用额度'
}

/** 单位文案（TIME_LIMIT → 次，TOKENS_LIMIT → Tokens）*/
export function limitUnitText(limit: QuotaLimit): string {
  return limit.type === 'TIME_LIMIT' ? '次' : 'Tokens'
}

/** 格式化重置时间（毫秒时间戳 → MM-dd HH:mm）*/
export function fmtResetTime(ts?: number): string {
  if (!ts) return ''
  const d = new Date(ts)
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  return `${mm}-${dd} ${hh}:${mi}`
}

/** 格式化刷新时间（毫秒时间戳 → HH:mm:ss）*/
export function fmtTime(ts?: number): string {
  if (!ts) return ''
  const d = new Date(ts)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  const ss = String(d.getSeconds()).padStart(2, '0')
  return `${hh}:${mm}:${ss}`
}

/** X 轴标签格式化（对齐 glm parseXTime）：daily → MM-DD，hourly → HH:mm */
export function formatXLabel(t: string, granularity?: string): string {
  if (granularity === 'daily') return t.slice(5) // MM-DD
  const sp = t.lastIndexOf(' ')
  return sp >= 0 ? t.slice(sp + 1) : t // HH:mm
}
