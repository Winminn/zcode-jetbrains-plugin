/**
 * 调用详情弹窗锚点导航（MessageAnchorRail 移植简化版，交互对齐主界面）
 *
 * - 锚点由调用方构建（详情体错误块/每条输入消息/输出 section，id 对应
 *   DOM 上的 data-anchor-msg），按顺序均匀分布在 4%~92% 轨道
 * - >30 条均匀抽样（保留首尾），与主界面同款
 * - IntersectionObserver 高亮当前（视口上 32% 区域第一条）+ 贴底接管
 *   点亮最后一个；点击平滑滚动（目标停视口 28%）+ 跳转锁定期抑制 observer
 * - hover 500ms 显示锚点预览（复用全局 .anchor-tooltip，方向由详情域覆盖）
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

export interface TrajectoryAnchor {
  id: string
  preview: string
}

interface Props {
  anchors: TrajectoryAnchor[]
  containerRef: React.RefObject<HTMLDivElement | null>
}

/** 均匀抽样（cc-gui sampleItems，与 MessageAnchorRail 同款）*/
function sampleItems<T>(items: T[], max: number): T[] {
  if (items.length <= max) return items
  return items.filter((_, index) => {
    const sampleIndex = Math.round((index / (items.length - 1)) * (max - 1))
    return index === 0 || sampleIndex !== Math.round(((index - 1) / (items.length - 1)) * (max - 1))
  })
}

/** 贴底判定容差（px） */
const BOTTOM_EPSILON = 2
const MAX_ANCHORS = 30

export function TrajectoryDetailRail({ anchors, containerRef }: Props) {
  const [activeId, setActiveId] = useState<string | null>(null)
  const [hoverId, setHoverId] = useState<string | null>(null)
  const hoverTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const suppressHighlightRef = useRef(false)
  const suppressGenRef = useRef(0)

  const sampled = useMemo(() => sampleItems(anchors, MAX_ANCHORS), [anchors])
  const positioned = useMemo(
    () =>
      sampled.map((a, idx) => ({
        ...a,
        position: sampled.length === 1 ? 0.04 : 0.04 + (idx / (sampled.length - 1)) * 0.88,
      })),
    [sampled],
  )

  // 贴底接管：末尾锚点内容短可能进不了判定区，贴底时直接点亮最后一个
  const highlightLastAnchorAtBottom = useCallback(() => {
    const last = sampled[sampled.length - 1]
    const container = containerRef.current
    if (!last || !container) return false
    if (container.scrollHeight - container.scrollTop - container.clientHeight > BOTTOM_EPSILON) return false
    setActiveId(last.id)
    return true
  }, [sampled, containerRef])

  useEffect(() => {
    const container = containerRef.current
    if (!container || sampled.length === 0) return

    const observer = new IntersectionObserver(
      (entries) => {
        if (suppressHighlightRef.current) return
        if (highlightLastAnchorAtBottom()) return
        const visible = entries
          .filter((e) => e.isIntersecting)
          .sort((a, b) => (a.target.compareDocumentPosition(b.target) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1))
        if (visible.length > 0) {
          setActiveId(visible[0].target.getAttribute('data-anchor-msg'))
        }
      },
      { root: container, rootMargin: '0px 0px -68% 0px', threshold: 0 },
    )

    container.querySelectorAll('[data-anchor-msg]').forEach((node) => observer.observe(node))
    return () => observer.disconnect()
  }, [sampled, containerRef, highlightLastAnchorAtBottom])

  useEffect(() => {
    const container = containerRef.current
    if (!container || sampled.length === 0) return
    const onScroll = () => {
      if (!suppressHighlightRef.current) highlightLastAnchorAtBottom()
    }
    container.addEventListener('scroll', onScroll, { passive: true })
    return () => container.removeEventListener('scroll', onScroll)
  }, [sampled, containerRef, highlightLastAnchorAtBottom])

  // 点击滚动：目标停在视口上 28%；点击即点亮目标并锁定高亮到滚动静止
  const scrollToAnchor = (id: string) => {
    const container = containerRef.current
    if (!container) return
    const node = container.querySelector(`[data-anchor-msg="${id}"]`) as HTMLElement | null
    if (!node) return
    const delta = node.getBoundingClientRect().top - container.getBoundingClientRect().top
    setActiveId(id)
    suppressHighlightRef.current = true
    const gen = ++suppressGenRef.current
    container.scrollTo({
      top: container.scrollTop + delta - container.clientHeight * 0.28,
      behavior: 'smooth',
    })
    const release = () => {
      if (gen !== suppressGenRef.current) return
      setTimeout(() => {
        if (gen === suppressGenRef.current) suppressHighlightRef.current = false
      }, 150)
    }
    if ('onscrollend' in container) {
      container.addEventListener('scrollend', release, { once: true })
      setTimeout(() => container.removeEventListener('scrollend', release), 2500)
    } else {
      setTimeout(release, 1200)
    }
  }

  if (positioned.length === 0) return null

  return (
    <div className="messages-anchor-rail">
      <div className="messages-anchor-track" />
      {positioned.map((a) => (
        <div
          key={a.id}
          className={`messages-anchor-dot ${activeId === a.id ? 'is-active' : ''}`}
          style={{ top: `${a.position * 100}%` }}
          onMouseEnter={() => {
            hoverTimer.current = setTimeout(() => setHoverId(a.id), 500)
          }}
          onMouseLeave={() => {
            if (hoverTimer.current) clearTimeout(hoverTimer.current)
            setHoverId(null)
          }}
          onClick={() => scrollToAnchor(a.id)}
        >
          {hoverId === a.id && <div className="anchor-tooltip">{a.preview}</div>}
        </div>
      ))}
    </div>
  )
}
