/**
 * 额度窗口识别与额度横幅派生（纯函数，对齐官方 v3.14.3 客户端语义）
 *
 * 数据源：HTTP quota/limit（store.quota，60s 常驻轮询）。官方语义要点：
 *  - percentage 是「已使用占比」，剩余 = 100 - percentage（官方 codingPlanQuotaPresentation）
 *  - 窗口挑选：5h 池 = TOKENS_LIMIT(unit=3,number=5)、周配额 = unit=6、
 *    工具调用 = TIME_LIMIT(unit=5,number=1)；zai 团队后端用 CREDIT_LIMIT 代替
 *    TOKENS_LIMIT，两者视为等价（官方 isSameLimitCategory）
 *  - 会话额度横幅错误分类：并发 3008/3009/3010、GLM 额度边界 1308-1321、
 *    耗尽 1005/exceed quota/quota_exceeded、限频 429/3002（官方 providerBusinessError +
 *    sessionQuotaBannerState 的插件化简化版，去掉 Start Plan 专属桶与 MCP 面）
 */

import type { QuotaLimit } from '@/types/messages'

/** 标准额度窗口：5 小时池 / 周配额 / 月度工具调用 */
export type QuotaWindowKey = '5h' | 'weekly' | 'tool'

/** 窗口 → i18n label key（QuotaBanner 横幅与 ContextRing 窗口卡共用文案） */
export const WINDOW_LABEL_I18N: Record<QuotaWindowKey, string> = {
  '5h': 'usage.windows.fiveHours',
  weekly: 'usage.windows.weekly',
  tool: 'usage.windows.toolCalls',
}

/** 额度横幅种类（优先级从高到低：concurrent > exhausted > provider > rate > low） */
export type QuotaBannerKind =
  | 'window-exhausted'
  | 'concurrent-limit'
  | 'provider-limited'
  | 'rate-limited'
  | 'window-low'

/** 错误触发源（store.quotaBannerError），由 turn.failed / backendError 分类命中时写入 */
export interface QuotaBannerTrigger {
  kind: Exclude<QuotaBannerKind, 'window-low'>
  /** provider-limited 原始/提取信息 */
  message?: string
  triggerKey: string
}

/** 派生后的横幅视图（组件渲染的唯一输入） */
export interface QuotaBannerView {
  kind: QuotaBannerKind
  priority: number
  triggerKey: string
  /** exhausted/low 命中的窗口（用于文案点名与重置时间） */
  windowKeys: QuotaWindowKey[]
  /** 最早重置时间（毫秒），窗口数据不可用时缺省 */
  resetTime?: number
  /** low 档剩余百分比 */
  remainPct?: number
  /** provider-limited 的信息文本 */
  rawMessage?: string
}

/** 官方 TOKEN_LIMIT_TYPES：zai 团队后端用 CREDIT_LIMIT，与 TOKENS_LIMIT 等价 */
const TOKEN_LIMIT_TYPES = new Set(['TOKENS_LIMIT', 'CREDIT_LIMIT'])

/** 剩余 ≤ 该占比视为耗尽（percentage 已用 ≥ 99.5） */
const EXHAUSTED_EPSILON_PCT = 0.5
/** 剩余 ≤ 10% 触发低额提醒（官方 bucketRemainingRatio 阈值） */
const LOW_REMAIN_THRESHOLD_PCT = 10

export function isTokenLimitType(type?: string): boolean {
  return type !== undefined && TOKEN_LIMIT_TYPES.has(type)
}

export function isToolLimitType(type?: string): boolean {
  return type === 'TIME_LIMIT'
}

/**
 * 官方 findCodingPlanQuotaLimit 插件化：先按 (unit, number) 精确匹配，
 * 未命中时降级为仅按 unit 匹配——部分套餐/旧响应不回传 number 字段，
 * 严格按官方全条件匹配会让窗口行整块消失（防御性 diverge，官方无此兜底）。
 */
export function findWindowLimit(limits: QuotaLimit[] | undefined, window: QuotaWindowKey): QuotaLimit | null {
  if (!limits?.length) return null
  const wantToken = window !== 'tool'
  const unit = window === '5h' ? 3 : window === 'weekly' ? 6 : 5
  const num = window === '5h' ? 5 : window === 'tool' ? 1 : undefined
  const byType = limits.filter((l) =>
    wantToken ? isTokenLimitType(l.type) : isToolLimitType(l.type),
  )
  if (num !== undefined) {
    const exact = byType.find((l) => l.unit === unit && l.number === num)
    if (exact) return exact
  }
  return byType.find((l) => l.unit === unit) ?? null
}

/** 剩余百分比（0-100）；percentage 缺失时返回 null（不猜） */
export function remainingPercent(limit: QuotaLimit | null | undefined): number | null {
  if (!limit || typeof limit.percentage !== 'number' || !Number.isFinite(limit.percentage)) return null
  return Math.max(0, Math.min(100, 100 - limit.percentage))
}

/** 官方 formatQuotaRemainingPercentage 口径：≥10% 取整、<10% 一位小数 */
export function formatRemainPct(pct: number): string {
  const rounded = pct >= 10 ? Math.round(pct) : Math.round(pct * 10) / 10
  return `${rounded}%`
}

/** 三个标准窗口行（按 5h → weekly → tool 顺序，缺窗口不占位） */
export function standardWindowLimits(limits: QuotaLimit[] | undefined): Array<{ key: QuotaWindowKey; limit: QuotaLimit }> {
  const rows: Array<{ key: QuotaWindowKey; limit: QuotaLimit }> = []
  for (const key of ['5h', 'weekly', 'tool'] as QuotaWindowKey[]) {
    const limit = findWindowLimit(limits, key)
    if (limit) rows.push({ key, limit })
  }
  return rows
}

/**
 * 悬浮窗窗口卡行：三标准窗 + 未命中标准窗的其余额度（通用额度兜底行，
 * 对齐官方「标准窗挑选 + MCP 独立源」结构；本插件 MCP 额度不在 quota/limit 返回内，
 * 其余形状一律落 other 行，避免形状漂移时数据凭空消失）。
 */
export function quotaWindowRows(
  limits: QuotaLimit[] | undefined,
): Array<{ key: QuotaWindowKey | 'other'; limit: QuotaLimit }> {
  const rows: Array<{ key: QuotaWindowKey | 'other'; limit: QuotaLimit }> = standardWindowLimits(limits)
  if (rows.length === 0 && limits?.length) {
    return limits.map((limit) => ({ key: 'other' as const, limit }))
  }
  const consumed = new Set(rows.map((r) => r.limit))
  for (const limit of limits ?? []) {
    if (!consumed.has(limit)) rows.push({ key: 'other', limit })
  }
  return rows
}

/** 当前已耗尽的标准窗口（percentage 已用 ≥ 99.5） */
export function pickExhaustedWindows(limits: QuotaLimit[] | undefined): QuotaWindowKey[] {
  return standardWindowLimits(limits)
    .filter(({ limit }) => {
      const remain = remainingPercent(limit)
      return remain !== null && remain <= EXHAUSTED_EPSILON_PCT
    })
    .map(({ key }) => key)
}

/** 低额窗口（0 < 剩余 ≤ 10%），按剩余升序（最紧张在前） */
export function pickLowWindows(limits: QuotaLimit[] | undefined): Array<{ key: QuotaWindowKey; remainPct: number; resetTime?: number }> {
  return standardWindowLimits(limits)
    .map(({ key, limit }) => ({
      key,
      remainPct: remainingPercent(limit) ?? 100,
      resetTime: limit.nextResetTime,
    }))
    .filter((w) => w.remainPct > 0 && w.remainPct <= LOW_REMAIN_THRESHOLD_PCT)
    .sort((a, b) => a.remainPct - b.remainPct)
}

/** quota/limit 是否至少命中过一个标准窗口（恢复判定需要"有窗口数据"前提） */
export function hasStandardWindowData(limits: QuotaLimit[] | undefined): boolean {
  return standardWindowLimits(limits).length > 0
}

// ===== 错误分类（官方 providerBusinessError 插件化简化版）=====

const CONCURRENT_CODES = new Set(['3008', '3009', '3010'])
/** GLM 额度/套餐边界业务码（官方 GLM_QUOTA_BANNER_BUSINESS_CODES） */
const PROVIDER_LIMITED_CODES = new Set([
  '1308', '1309', '1310', '1311', '1313', '1314', '1315', '1316', '1317', '1318', '1319', '1320', '1321',
])
const EXHAUSTED_WRAPPER_CODES = new Set(['PROVIDER_BUSINESS_ERROR', 'SEND_FAILED', 'unknown_error'])
const CONCURRENT_WRAPPER_CODES = new Set(['PROVIDER_BUSINESS_ERROR', 'SEND_FAILED', 'unknown_error', 'MODEL_RATE_LIMITED'])

/** 官方 normalizeProviderLimitedBannerMessage：≥3 段方括号取第 2 段，否则原文 */
export function providerLimitedMessage(message: string | null | undefined): string | null {
  const trimmed = message?.trim()
  if (!trimmed) return null
  const parts = [...trimmed.matchAll(/\[([^\]]*)\]/gu)].map((m) => m[1]?.trim() ?? '')
  return parts.length >= 3 && parts[1] ? parts[1] : trimmed
}

function messageMatches(message: string, patterns: string[]): boolean {
  const m = message.toLowerCase()
  return patterns.some((p) => m.includes(p))
}

/**
 * 错误 → 横幅种类（按优先级求值；未命中返回 null）。
 * 仅在当前模型属于 bigmodel 系渠道时调用（调用方门控），第三方渠道错误不进横幅。
 */
export function classifyQuotaError(
  code: string | number | undefined,
  message: string | undefined,
  statusCode?: number,
): Exclude<QuotaBannerKind, 'window-low'> | null {
  const c = code !== undefined ? String(code).trim() : ''
  const m = (message || '').trim().toLowerCase()

  if (CONCURRENT_CODES.has(c) || (messageMatches(m, ['concurrent', 'concurrency', '并发']) && (!c || CONCURRENT_WRAPPER_CODES.has(c)))) {
    return 'concurrent-limit'
  }
  if (
    c.includes('quota') ||
    messageMatches(m, ['quota_exceeded', 'token_quota', 'exceed quota limit', 'exceed limit', 'quota exceeded']) ||
    (messageMatches(m, ['exceeded']) && m.includes('quota') && (!c || EXHAUSTED_WRAPPER_CODES.has(c)))
  ) {
    return 'window-exhausted'
  }
  if (PROVIDER_LIMITED_CODES.has(c)) {
    return 'provider-limited'
  }
  if (statusCode === 429 || c === '3002' || c === '429' || messageMatches(m, ['rate limit', 'too many requests', '请求过频'])) {
    return 'rate-limited'
  }
  return null
}

/** 错误触发源去重键（kind + 错误指纹；窗口期签名在 derive 时追加） */
export function bannerTriggerKey(kind: string, code: string | number | undefined, message: string | undefined): string {
  return `err:${kind}:${String(code ?? '')}:${(message || '').slice(0, 80)}`
}

const KIND_PRIORITY: Record<QuotaBannerKind, number> = {
  'concurrent-limit': 60,
  'window-exhausted': 50,
  'provider-limited': 45,
  'rate-limited': 40,
  'window-low': 30,
}

/**
 * 派生横幅视图：错误触发源优先（并发 > 耗尽 > 服务商 > 限频），无错误时从
 * 额度数据派生低额提醒（≤10%，每窗口期提醒一次）。已关闭的 triggerKey 不再显示。
 *
 * @param params.limits        quota/limit 数据（60s 轮询刷新，可为 null = 无数据）
 * @param params.error         错误触发源（store），null = 无
 * @param params.dismissed     已关闭的 triggerKey 集合
 */
export function deriveQuotaBanner(params: {
  limits?: QuotaLimit[] | null
  error: QuotaBannerTrigger | null
  dismissed: readonly string[]
}): QuotaBannerView | null {
  const { limits, error, dismissed } = params

  if (error) {
    const limitsOrNull = limits ?? undefined
    const windowKeys = error.kind === 'window-exhausted' ? pickExhaustedWindows(limitsOrNull) : []
    const windowLimits = windowKeys
      .map((key) => findWindowLimit(limitsOrNull, key))
      .filter((l): l is QuotaLimit => !!l)
    const resetTime = windowLimits
      .map((l) => l.nextResetTime)
      .filter((t): t is number => typeof t === 'number' && t > 0)
      .sort((a, b) => a - b)[0]
    // 窗口期签名：同一错误指纹在下一个重置周期复发时应重新提醒
    const periodSig = windowKeys.length ? `|w:${[...windowKeys].sort().join(',')}:${resetTime ?? 'na'}` : ''
    const triggerKey = `${error.triggerKey}${periodSig}`
    if (dismissed.includes(triggerKey)) return null
    return {
      kind: error.kind,
      priority: KIND_PRIORITY[error.kind],
      triggerKey,
      windowKeys,
      ...(resetTime !== undefined ? { resetTime } : {}),
      ...(error.message ? { rawMessage: error.message } : {}),
    }
  }

  for (const low of pickLowWindows(limits ?? undefined)) {
    const triggerKey = `low:${low.key}:${low.resetTime ?? 'na'}`
    // 首个窗口已被关闭时顺延看下一个（多个窗口同时低额不互相顶掉提醒）
    if (dismissed.includes(triggerKey)) continue
    return {
      kind: 'window-low',
      priority: KIND_PRIORITY['window-low'],
      triggerKey,
      windowKeys: [low.key],
      ...(low.resetTime !== undefined ? { resetTime: low.resetTime } : {}),
      remainPct: low.remainPct,
    }
  }
  return null
}
