/**
 * 额度横幅「关闭后静默」的持久化（仅低额提醒路）
 *
 * 背景：quotaBannerDismissed 原为纯内存数组，而多标签页各自独立 webview 独立 store，
 * 低额提醒（≤10% 派生路，见 utils/quotaWindows.ts）在额度低位期间每个新开的会话
 * 都会重新点亮——标签 A 关掉，新会话照弹。落 persist kv 通道（IDE 侧 PropertiesComponent
 * 权威、跨标签共享、跨重启保留）后语义变为：任一标签关闭一次 → 全部标签（含新会话）
 * 静默，直到窗口期轮转——triggerKey 内嵌重置时间（low:<window>:<resetTime>），轮转后
 * 生成新 key 自然再武装；7 天 TTL 兜底 resetTime 缺失（key 形如 low:weekly:na）的
 * 条目不会永久静音。
 *
 * 只持久化 low: 前缀键：错误触发源（err:）是会话内瞬态，不跨标签重现，无需落盘。
 */
import { getPersisted, setPersisted } from './persist'

export const QUOTA_BANNER_DISMISSALS_KEY = 'zcode.quota.bannerDismissals'

/** 单条静默上限：resetTime 缺失时防永久静音（正常条目由窗口轮转剪除，远早于 TTL） */
const DISMISS_TTL_MS = 7 * 86400_000

/** 窗口期已轮转的条目（key 内嵌重置时间已过）永不再匹配，读取/写入时顺手剪除 */
function isRotatedKey(key: string, now: number): boolean {
  const m = /^low:[^:]+:(\d+)$/.exec(key)
  return m !== null && Number(m[1]) < now
}

/** 存量 map 解析 + 剪除（损坏按空处理；TTL 过期与窗口已轮转的条目剔除） */
function parseLiveMap(now: number): Record<string, number> {
  const raw = getPersisted(QUOTA_BANNER_DISMISSALS_KEY)
  if (!raw) return {}
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>
    const live: Record<string, number> = {}
    for (const [k, at] of Object.entries(parsed)) {
      if (typeof at === 'number' && now - at < DISMISS_TTL_MS && !isRotatedKey(k, now)) live[k] = at
    }
    return live
  } catch {
    return {}
  }
}

/** 仍处静默期内的去重键 */
export function liveQuotaBannerDismissals(now = Date.now()): string[] {
  return Object.keys(parseLiveMap(now))
}

/** 并入当前内存关闭集合（去重；store 初值/水合校正/storage 跨标签同步共用） */
export function mergeLiveQuotaBannerDismissals(current: readonly string[]): string[] {
  return Array.from(new Set([...current, ...liveQuotaBannerDismissals()]))
}

/** 记录一次关闭（upsert 时间戳；顺手剪除过期条目，map 恒小于窗口数 + TTL 界内残量） */
export function recordQuotaBannerDismissal(key: string, now = Date.now()): void {
  const live = parseLiveMap(now)
  live[key] = now
  setPersisted(QUOTA_BANNER_DISMISSALS_KEY, JSON.stringify(live))
}
