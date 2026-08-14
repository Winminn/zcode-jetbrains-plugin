/**
 * 活动计时 tick
 *
 * "工作中 X 秒"这类秒级跳动的驱动源：active 时每 intervalMs 返回一次新的
 * 当前时间戳，只重渲染挂载它的组件（不整树轮询，JCEF 流式性能要求）。
 * active 翻 false 后停表，返回值保持在最后时刻。
 */

import { useEffect, useState } from 'react'

export function useTick(active: boolean, intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!active) return
    setNow(Date.now())
    const timer = setInterval(() => setNow(Date.now()), intervalMs)
    return () => clearInterval(timer)
  }, [active, intervalMs])
  return now
}
