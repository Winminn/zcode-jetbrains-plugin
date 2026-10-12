/**
 * store kv 偏好迟水合校正测试（2026-10-10，子开关重启丢失缺陷）
 *
 * 根因：store 模块创建先于 main.tsx 的 initPersist()，初值读到水合前的空
 * localStorage；而桥注入早于前端脚本时 initPersist 在 render 前就同步完成水合并
 * 派发 KV_HYDRATED——init() 里补挂的事件监听永远等不到，偏好停在默认值
 * （表现：重启后「在输入框显示电脑操作按钮」从选中变回未选中）。
 *
 * 锁定行为：
 * 1. 同步水合竞态：init() 时已水合（isKvHydrated()=true）→ 直接重读恢复，不依赖事件
 * 2. 异步水合：init() 后 KV_HYDRATED 事件到达 → 重读恢复
 * 两个分支都必须落到 store（cuaInputButton/statusPanelCollapsed 同函数校正）。
 * init() 有 bridgeInitialized 守卫（二次调用直接 return），故每用例 resetModules
 * 取全新模块图再动态 import。
 */
// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'

const hydrationState = { hydrated: false }

vi.mock(import('@/utils/persist'), async (importOriginal) => {
  const actual = await importOriginal()
  return {
    ...actual,
    isKvHydrated: () => hydrationState.hydrated,
  }
})

// jsdom 的 localStorage 是无 clear 的普通对象（inputbox-goal.spec 同款 mock）
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

const CUA_CFG = 'zcode.cua.inputButton.config'
const loadStore = async () => (await import('@/store/useStore')).useStore

beforeEach(async () => {
  vi.resetModules()
  storage.clear()
  hydrationState.hydrated = false
})

describe('store kv 偏好迟水合校正', () => {
  it('同步水合竞态：init 时已水合 → 直接重读恢复 cuaInputButton（不依赖错过的事件）', async () => {
    storage.set(CUA_CFG, JSON.stringify({ showInInputBox: true }))
    hydrationState.hydrated = true // 模拟水合在 render 前同步完成
    const useStore = await loadStore()
    useStore.getState().init()
    expect(useStore.getState().cuaInputButton).toBe(true)
  })

  it('同步水合竞态（默认值）：已水合但 kv 无该 key → 保持默认 true（2026-10-11 默认开）', async () => {
    hydrationState.hydrated = true
    const useStore = await loadStore()
    useStore.getState().init()
    expect(useStore.getState().cuaInputButton).toBe(true)
  })

  it('异步水合：init 后 KV_HYDRATED 事件到达 → 重读恢复', async () => {
    const { KV_HYDRATED_EVENT } = await import('@/utils/persist')
    const useStore = await loadStore()
    useStore.getState().init()
    storage.set(CUA_CFG, JSON.stringify({ showInInputBox: true }))
    window.dispatchEvent(new Event(KV_HYDRATED_EVENT))
    expect(useStore.getState().cuaInputButton).toBe(true)
  })

  it('同一校正同时落 statusPanelCollapsed（同函数三键）', async () => {
    storage.set('zcode.statusPanel.config', JSON.stringify({ collapsed: true }))
    hydrationState.hydrated = true
    const useStore = await loadStore()
    useStore.getState().init()
    expect(useStore.getState().statusPanelCollapsed).toBe(true)
  })
})
