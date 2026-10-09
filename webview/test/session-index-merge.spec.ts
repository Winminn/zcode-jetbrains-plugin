/**
 * 会话列表活性合并（sessions-index → store.sessions）回归测试
 *
 * 锁定：相位映射、标题防回退（手动改名 > 服务端非占位 > 本地保留）、
 * updatedAt 只前进、跨进程新会话追加、removed 出列（当前会话豁免）、倒序收口。
 */

import { describe, it, expect, vi } from 'vitest'

vi.mock('@/utils/persist', () => ({
  getPersisted: (key: string) => (key.endsWith('sess_persisted') ? '手动改名标题' : null),
  setPersisted: () => {},
  removePersisted: () => {},
  entriesWithPrefix: () => [],
}))

import { mergeSessionsIndex, indexPhaseToStatus } from '@/utils/sessionIndexMerge'
import type { SessionInfo, SessionIndexEntry } from '@/types/messages'

const row = (id: string, over: Partial<SessionInfo> = {}): SessionInfo => ({
  sessionId: id,
  title: `会话 ${id}`,
  status: 'idle',
  mode: '',
  workspacePath: 'G:\\proj',
  createdAt: 1000,
  updatedAt: 1000,
  ...over,
})

const entry = (id: string, over: Partial<SessionIndexEntry> = {}): SessionIndexEntry => ({
  sessionId: id,
  title: `索引标题 ${id}`,
  phase: 'completedSuccess',
  lastActivityAt: 5000,
  createdAt: 1000,
  ...over,
})

describe('indexPhaseToStatus', () => {
  it('running/prewarming → running，其余（含 error/completedSuccess/draft）→ idle', () => {
    expect(indexPhaseToStatus('running')).toBe('running')
    expect(indexPhaseToStatus('prewarming')).toBe('running')
    expect(indexPhaseToStatus('completedSuccess')).toBe('idle')
    expect(indexPhaseToStatus('error')).toBe('idle')
    expect(indexPhaseToStatus('draft')).toBe('idle')
    expect(indexPhaseToStatus(undefined)).toBe('idle')
  })
})

describe('mergeSessionsIndex', () => {
  it('已有行：相位翻转 status、updatedAt 取 max 只前进', () => {
    const prev = [row('sess_a', { updatedAt: 3000 })]
    const merged = mergeSessionsIndex({
      prev,
      entries: [entry('sess_a', { phase: 'running', lastActivityAt: 9000 })],
      removed: [],
      currentId: null,
    })
    expect(merged[0].status).toBe('running')
    expect(merged[0].updatedAt).toBe(9000)
  })

  it('completedSuccess 复位为 idle；lastActivityAt 早于本地时不回退', () => {
    const prev = [row('sess_a', { status: 'running', updatedAt: 9000 })]
    const merged = mergeSessionsIndex({
      prev,
      entries: [entry('sess_a', { phase: 'completedSuccess', lastActivityAt: 4000 })],
      removed: [],
      currentId: null,
    })
    expect(merged[0].status).toBe('idle')
    expect(merged[0].updatedAt).toBe(9000)
  })

  it('标题防回退：persist 手动改名 > 服务端非占位标题；占位/空标题保留本地', () => {
    const prev = [row('sess_persisted', { title: '旧标题' }), row('sess_x', { title: '本地标题' })]
    const merged = mergeSessionsIndex({
      prev,
      entries: [
        entry('sess_persisted', { title: '服务端新标题' }),
        entry('sess_x', { title: '' }),
      ],
      removed: [],
      currentId: null,
    })
    expect(merged.find((s) => s.sessionId === 'sess_persisted')!.title).toBe('手动改名标题')
    expect(merged.find((s) => s.sessionId === 'sess_x')!.title).toBe('本地标题')
  })

  it('新行（跨进程新建会话）追加：空标题回退 id 前缀', () => {
    const merged = mergeSessionsIndex({
      prev: [],
      entries: [entry('sess_new1', { title: '', workspaceId: 'G:\\proj', phase: 'draft' })],
      removed: [],
      currentId: null,
    })
    expect(merged).toHaveLength(1)
    expect(merged[0].title).toBe('sess_new1'.slice(0, 12))
    expect(merged[0].workspacePath).toBe('G:\\proj')
    expect(merged[0].status).toBe('idle')
  })

  it('removed 出列；当前打开的会话豁免', () => {
    const prev = [row('sess_a'), row('sess_b'), row('sess_cur')]
    const merged = mergeSessionsIndex({
      prev,
      entries: [],
      removed: ['sess_a', 'sess_cur'],
      currentId: 'sess_cur',
    })
    expect(merged.map((s) => s.sessionId).sort()).toEqual(['sess_b', 'sess_cur'])
  })

  it('子代理行拒绝（Java 已过滤，前端兜底）；结果按 updatedAt 倒序', () => {
    const merged = mergeSessionsIndex({
      prev: [row('sess_old', { updatedAt: 100 })],
      entries: [
        entry('sess_subagent_x'),
        entry('sess_fresh', { lastActivityAt: 9999 }),
      ],
      removed: [],
      currentId: null,
    })
    expect(merged.map((s) => s.sessionId)).toEqual(['sess_fresh', 'sess_old'])
  })

  it('空帧原引用返回（不触发多余重渲染）', () => {
    const prev = [row('sess_a')]
    expect(mergeSessionsIndex({ prev, entries: [], removed: [], currentId: null })).toBe(prev)
  })
})
