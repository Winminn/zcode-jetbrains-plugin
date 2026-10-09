/**
 * 会话列表置顶分层排序（稳定）：置顶组在前、组内保持原序（上游已按 updatedAt 倒序
 * 收口，见 store listSessions 归约——此处不再按时间重排，避免两处排序语义漂移）。
 * 与 Kotlin 库内 pinned 位解耦：pin 状态是渲染叠加层，任何数据源（拉取/活性订阅/
 * staleLocal 补插）进来的数组顺序都不影响置顶优先。
 */
export function sortSessionsByPin<T>(sessions: T[], pinnedIds: string[], getId: (s: T) => string): T[] {
  if (pinnedIds.length === 0) return sessions
  const pinned = new Set(pinnedIds)
  const top: T[] = []
  const rest: T[] = []
  for (const s of sessions) (pinned.has(getId(s)) ? top : rest).push(s)
  return [...top, ...rest]
}
