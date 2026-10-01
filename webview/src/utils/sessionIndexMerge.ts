/**
 * 会话列表活性合并（sessions-index v4 topic → store.sessions 纯函数）
 *
 * 语义（叠加而非替换，session/list 快照仍是权威底座）：
 *  - upsert 已有行：相位映射 status（running 族 → running，其余 → idle 与 legacy
 *    二值口径对齐）、标题（手动重命名 persist 权威 > 服务端非占位标题 > 本地保留）、
 *    updatedAt = max(本地, lastActivityAt)（列表倒序键，服务端活性只前进不回退）
 *  - 新行：官方桌面端/手机 H5 新建的会话跨进程出现（此前完全无感知，须重启才可见）
 *  - removed：服务端移除（客户端删除）直接出列
 */

import type { SessionInfo, SessionIndexEntry } from '@/types/messages'
import { getPersisted } from '@/utils/persist'
import { isDefaultSessionTitle } from '@/utils/format'
/** 服务端 phase → 列表行 status（idle/running 二值，legacy 口径；错误态与完成态同形） */
export function indexPhaseToStatus(phase?: string): string {
  return phase === 'running' || phase === 'prewarming' ? 'running' : 'idle'
}

/**
 * 活性合并入口。
 *
 * @param params.prev          当前列表（session/list 快照 + 既有合并结果）
 * @param params.entries       本帧 upsert 的索引摘要（Java 侧已过滤软删/子会话）
 * @param params.removed       本帧移除的会话 id
 * @param params.currentId     本标签当前打开的会话（removed 时行可出列，标签内容不动）
 * @returns 新列表（updatedAt 倒序重排，与 listSessions 收口同法；无变更时原引用返回）
 */
export function mergeSessionsIndex(params: {
  prev: SessionInfo[]
  entries: SessionIndexEntry[]
  removed: string[]
  currentId: string | null
}): SessionInfo[] {
  const { prev, entries, removed, currentId } = params
  if (entries.length === 0 && removed.length === 0) return prev

  const byId = new Map(prev.map((s) => [s.sessionId, s]))

  for (const id of removed) {
    // 当前打开的会话行保留（服务端快照时序/删除广播早于本地关标签的竞态窗口，
    // 行消失会让 header 与列表失配；内容与后续刷新收口）
    if (id === currentId) continue
    byId.delete(id)
  }

  for (const e of entries) {
    if (!e?.sessionId || e.sessionId.startsWith('sess_subagent')) continue
    const status = indexPhaseToStatus(e.phase)
    const existing = byId.get(e.sessionId)
    if (existing) {
      // 标题防回退：手动重命名（persist）权威；服务端占位/空标题不覆盖本地非占位标题
      const stored = getPersisted(`zcode.sessionTitle.${e.sessionId}`)
      const incomingUsable = !!e.title && !isDefaultSessionTitle(e.title, e.sessionId)
      byId.set(e.sessionId, {
        ...existing,
        title: stored || (incomingUsable ? e.title! : existing.title),
        status: status || existing.status,
        updatedAt: Math.max(existing.updatedAt ?? 0, e.lastActivityAt ?? 0),
      })
    } else {
      byId.set(e.sessionId, {
        sessionId: e.sessionId,
        // 空标题回退 id 前缀（运行中会话内存序列化缺陷的既有口径，listSessions 同款兜底）
        title: e.title && !isDefaultSessionTitle(e.title, e.sessionId) ? e.title : e.sessionId.slice(0, 12),
        status,
        mode: '',
        workspacePath: e.workspaceId ?? '',
        createdAt: e.createdAt ?? Date.now(),
        updatedAt: e.lastActivityAt ?? Date.now(),
      })
    }
  }

  const merged = [...byId.values()]
  merged.sort((a, b) => (b.updatedAt ?? 0) - (a.updatedAt ?? 0))
  return merged
}
