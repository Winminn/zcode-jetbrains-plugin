/**
 * 对话结束提醒配置（utils/notifyConfig.ts）读写回归：
 * - 无配置 → 默认关闭（不弹）
 * - 损坏 JSON / 部分字段 → 逐字段回默认（与 Kotlin ZCodeNotifyService.parseConfig 同语义）
 * - 写入 → localStorage 落盘且可回读
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'

// ---- mock localStorage（node 环境无实现；persist.ts 经 window.localStorage 访问）----
const store = new Map<string, string>()
const lsMock = {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => void store.set(k, v),
  removeItem: (k: string) => void store.delete(k),
  key: (i: number) => [...store.keys()][i] ?? null,
  get length() {
    return store.size
  },
  clear: () => store.clear(),
}
vi.stubGlobal('localStorage', lsMock)
vi.stubGlobal('window', { localStorage: lsMock, dispatchEvent: () => {} })

import {
  readNotifyConfig,
  writeNotifyConfig,
  DEFAULT_NOTIFY_CONFIG,
} from '@/utils/notifyConfig'

describe('对话结束提醒配置', () => {
  beforeEach(() => {
    store.clear()
  })

  it('无配置时默认关闭（不弹）', () => {
    expect(readNotifyConfig()).toEqual(DEFAULT_NOTIFY_CONFIG)
    expect(DEFAULT_NOTIFY_CONFIG).toEqual({ notifyEnabled: false })
  })

  it('损坏 JSON 回默认值', () => {
    store.set('zcode.notify.config', '{not json')
    expect(readNotifyConfig()).toEqual(DEFAULT_NOTIFY_CONFIG)
  })

  it('类型不对的字段回默认（字符串 "true" 不生效）', () => {
    store.set('zcode.notify.config', JSON.stringify({ notifyEnabled: 'true' }))
    expect(readNotifyConfig().notifyEnabled).toBe(false)
  })

  it('旧版遗留的 notifyOnlyUnfocused 字段被忽略（废弃不迁移）', () => {
    store.set('zcode.notify.config', JSON.stringify({ notifyEnabled: true, notifyOnlyUnfocused: true }))
    expect(readNotifyConfig()).toEqual({ notifyEnabled: true })
  })

  it('写入后回读一致', () => {
    writeNotifyConfig({ notifyEnabled: true })
    expect(readNotifyConfig()).toEqual({ notifyEnabled: true })
    expect(store.get('zcode.notify.config')).toBeTruthy()
  })
})
