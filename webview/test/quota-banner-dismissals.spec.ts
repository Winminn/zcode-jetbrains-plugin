/**
 * 额度横幅关闭静默持久化（utils/quotaBannerDismissals.ts）单元测试
 *
 * 锁定：静默期内键存活；TTL 过期（resetTime 缺失兜底）与窗口已轮转（内嵌重置时间
 * 已过）的条目被剪除；记录 upsert 并顺手清理；损坏 JSON 按空处理；merge 去重并集。
 */
// @vitest-environment jsdom
import { describe, it, expect, beforeEach } from 'vitest'

import {
  QUOTA_BANNER_DISMISSALS_KEY,
  liveQuotaBannerDismissals,
  mergeLiveQuotaBannerDismissals,
  recordQuotaBannerDismissal,
} from '@/utils/quotaBannerDismissals'

const DAY = 86400_000

// 本环境 jsdom 的 window.localStorage 是无方法死对象（Node 实验 localStorage 抢占，
// getItem/setItem 全 undefined），注入 Map 桩替代
function installStorageStub(): Map<string, string> {
  const map = new Map<string, string>()
  Object.defineProperty(window, 'localStorage', {
    configurable: true,
    value: {
      getItem: (k: string) => (map.has(k) ? map.get(k)! : null),
      setItem: (k: string, v: string) => void map.set(k, String(v)),
      removeItem: (k: string) => void map.delete(k),
      clear: () => map.clear(),
      key: (i: number) => [...map.keys()][i] ?? null,
      get length() {
        return map.size
      },
    },
  })
  return map
}

let kvStorage: Map<string, string>

function seedMap(map: Record<string, number>): void {
  kvStorage.set(QUOTA_BANNER_DISMISSALS_KEY, JSON.stringify(map))
}

function storedRaw(): string | null {
  return kvStorage.get(QUOTA_BANNER_DISMISSALS_KEY) ?? null
}

beforeEach(() => {
  kvStorage = installStorageStub()
  kvStorage.clear()
})

describe('liveQuotaBannerDismissals', () => {
  it('静默期内键存活，TTL 过期（7 天）剪除', () => {
    const now = Date.now()
    seedMap({ 'low:weekly:na': now - 6 * DAY, 'low:5h:na': now - 8 * DAY })
    expect(liveQuotaBannerDismissals(now)).toEqual(['low:weekly:na'])
  })

  it('窗口已轮转（内嵌重置时间已过）剪除，未轮转保留', () => {
    const now = Date.now()
    seedMap({
      [`low:weekly:${now - 2 * DAY}`]: now - DAY, // 重置时间早已过：新窗口期 key 已变，永不再匹配
      [`low:weekly:${now + DAY}`]: now - DAY, // 重置时间在未来：仍处静默期
    })
    expect(liveQuotaBannerDismissals(now)).toEqual([`low:weekly:${now + DAY}`])
  })

  it('无存储 / 损坏 JSON → 空数组不抛', () => {
    expect(liveQuotaBannerDismissals()).toEqual([])
    window.localStorage.setItem(QUOTA_BANNER_DISMISSALS_KEY, '{oops')
    expect(liveQuotaBannerDismissals()).toEqual([])
  })
})

describe('recordQuotaBannerDismissal', () => {
  it('upsert 时间戳，并顺手剪除过期/轮转条目', () => {
    const now = Date.now()
    seedMap({
      [`low:weekly:${now - 2 * DAY}`]: now - DAY, // 已轮转
      'low:tool:na': now - 8 * DAY, // TTL 过期
    })
    recordQuotaBannerDismissal(`low:weekly:${now + DAY}`, now)
    const saved = JSON.parse(storedRaw()!) as Record<string, number>
    expect(Object.keys(saved)).toEqual([`low:weekly:${now + DAY}`])
    expect(saved[`low:weekly:${now + DAY}`]).toBe(now)
    // 写入的键随即存活
    expect(liveQuotaBannerDismissals(now)).toEqual([`low:weekly:${now + DAY}`])
  })

  it('重复关闭同一键覆盖时间戳（静默期顺延）', () => {
    recordQuotaBannerDismissal('low:5h:na', Date.now() - DAY)
    const now = Date.now()
    recordQuotaBannerDismissal('low:5h:na', now)
    const saved = JSON.parse(storedRaw()!) as Record<string, number>
    expect(saved['low:5h:na']).toBe(now)
  })
})

describe('mergeLiveQuotaBannerDismissals', () => {
  it('与内存集合并集去重', () => {
    seedMap({ 'low:weekly:2000': Date.now() })
    expect(mergeLiveQuotaBannerDismissals(['err:k', 'low:weekly:2000'])).toEqual([
      'err:k',
      'low:weekly:2000',
    ])
  })
})
