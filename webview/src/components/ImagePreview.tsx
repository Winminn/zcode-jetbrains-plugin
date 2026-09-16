/**
 * 图片大图预览 overlay（cc-gui image-preview-overlay 同款）
 *
 * 输入框附件缩略图与消息区图片点击共用：portal 挂 body（脱离
 * messages-container / input-area 的 overflow 裁剪），Esc 或点遮罩关闭。
 *
 * 多图切换：传 images 列表 + initialIndex 即启用左右切换（两侧按钮 + ←/→ 键 +
 * 序号指示），到头禁用不循环；单图仍走 src/title，无切换 UI。
 *
 * 缩放：滚轮以光标为锚缩放（1x=fit 适配尺寸 ~ 8x），放大后图片可拖拽平移
 * （无空隙夹取：图片边缘不会拖过初始占位）；双击在 fit / 2.5x 间切换；底部
 * 工具条（−/百分比/＋，点百分比复位）与 +/-/0 键等效；切图自动复位。拖拽
 * 结束后短窗口内抑制遮罩点击关闭，避免平移松手误关。
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import '../styles/input-box.less'

export interface PreviewImage {
  src: string
  title?: string
}

const MIN_SCALE = 1
const MAX_SCALE = 8
/** 滚轮/按钮单档缩放系数 */
const STEP_FACTOR = 1.2
const DBLCLICK_SCALE = 2.5

interface Offset {
  x: number
  y: number
}

export function ImagePreview({
  src,
  title,
  images,
  initialIndex = 0,
  onClose,
}: {
  /** 单图形态（向后兼容）：不传 images 时用 */
  src?: string
  title?: string
  /** 多图形态：列表长度 > 1 启用左右切换 */
  images?: PreviewImage[]
  initialIndex?: number
  onClose: () => void
}) {
  const { t } = useTranslation()
  const list: PreviewImage[] = images && images.length > 0 ? images : src ? [{ src, title }] : []
  const multi = list.length > 1
  const [idx, setIdx] = useState(() =>
    Math.min(Math.max(initialIndex, 0), Math.max(list.length - 1, 0)),
  )
  const cur = list[idx]

  // 缩放/平移：state 供渲染，ref 供 wheel/pointer 等原生事件同步读最新值
  const [scale, setScale] = useState(1)
  const [offset, setOffset] = useState<Offset>({ x: 0, y: 0 })
  const [panning, setPanning] = useState(false)
  const scaleRef = useRef(1)
  const offsetRef = useRef<Offset>({ x: 0, y: 0 })
  const overlayRef = useRef<HTMLDivElement | null>(null)
  const imgRef = useRef<HTMLImageElement | null>(null)
  const dragRef = useRef<{ px: number; py: number; ox: number; oy: number; moved: boolean } | null>(
    null,
  )
  const lastDragEndRef = useRef(0)

  const applyTransform = useCallback((s: number, x: number, y: number) => {
    scaleRef.current = s
    offsetRef.current = { x, y }
    setScale(s)
    setOffset({ x, y })
  }, [])

  /** 以视口点 (vx, vy) 为锚缩放到 next（越界夹取），锚点在缩放前后保持不动 */
  const zoomAt = useCallback(
    (vx: number, vy: number, next: number) => {
      const img = imgRef.current
      if (!img) return
      const s = scaleRef.current
      const ns = Math.min(MAX_SCALE, Math.max(MIN_SCALE, next))
      if (ns === s) return
      // rect 是 transform 后的盒子：布局中心 = 盒中心 - 当前平移；基准尺寸 = 盒尺寸 / 当前倍率
      const rect = img.getBoundingClientRect()
      if (rect.width === 0 || rect.height === 0) return
      const cx = rect.left + rect.width / 2 - offsetRef.current.x
      const cy = rect.top + rect.height / 2 - offsetRef.current.y
      const k = ns / s
      const limX = ((ns - 1) * rect.width) / 2 / s
      const limY = ((ns - 1) * rect.height) / 2 / s
      const nx = Math.max(-limX, Math.min(limX, (vx - cx) * (1 - k) + offsetRef.current.x * k))
      const ny = Math.max(-limY, Math.min(limY, (vy - cy) * (1 - k) + offsetRef.current.y * k))
      applyTransform(ns, nx, ny)
    },
    [applyTransform],
  )

  /** 以图片当前视口中心为锚缩放（工具条/键盘路径），平移量不变 */
  const zoomBy = useCallback(
    (factor: number) => {
      const rect = imgRef.current?.getBoundingClientRect()
      if (!rect) return
      zoomAt(rect.left + rect.width / 2, rect.top + rect.height / 2, scaleRef.current * factor)
    },
    [zoomAt],
  )

  const resetZoom = useCallback(() => applyTransform(1, 0, 0), [applyTransform])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose()
        return
      }
      if (e.key === '+' || e.key === '=') zoomBy(STEP_FACTOR)
      else if (e.key === '-') zoomBy(1 / STEP_FACTOR)
      else if (e.key === '0') resetZoom()
      else if (!multi) return
      else if (e.key === 'ArrowLeft') setIdx((v) => Math.max(0, v - 1))
      else if (e.key === 'ArrowRight') setIdx((v) => Math.min(list.length - 1, v + 1))
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [multi, list.length, onClose, zoomBy, resetZoom])

  // 滚轮缩放：非被动监听，preventDefault 拦截避免缩放时滚动背后页面；
  // 落在工具条/切换按钮上的滚轮只拦截不缩放
  useEffect(() => {
    const el = overlayRef.current
    if (!el) return
    const onWheel = (e: WheelEvent) => {
      e.preventDefault()
      const target = e.target as Element | null
      if (target?.closest('.image-preview-toolbar, .image-preview-nav')) return
      zoomAt(e.clientX, e.clientY, scaleRef.current * (e.deltaY < 0 ? STEP_FACTOR : 1 / STEP_FACTOR))
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [zoomAt])

  // 切图复位缩放
  useEffect(() => {
    applyTransform(1, 0, 0)
  }, [idx, applyTransform])

  const panLimit = () => {
    const rect = imgRef.current?.getBoundingClientRect()
    const s = scaleRef.current
    if (!rect || s <= MIN_SCALE) return { x: 0, y: 0 }
    return { x: ((s - 1) * rect.width) / 2 / s, y: ((s - 1) * rect.height) / 2 / s }
  }

  const onImgPointerDown = (e: React.PointerEvent<HTMLImageElement>) => {
    if (e.button !== 0 || scaleRef.current <= MIN_SCALE) return
    e.preventDefault()
    imgRef.current?.setPointerCapture(e.pointerId)
    dragRef.current = {
      px: e.clientX,
      py: e.clientY,
      ox: offsetRef.current.x,
      oy: offsetRef.current.y,
      moved: false,
    }
    setPanning(true)
  }

  const onImgPointerMove = (e: React.PointerEvent<HTMLImageElement>) => {
    const d = dragRef.current
    if (!d) return
    const dx = e.clientX - d.px
    const dy = e.clientY - d.py
    if (!d.moved && Math.hypot(dx, dy) > 3) d.moved = true
    if (!d.moved) return
    const lim = panLimit()
    applyTransform(
      scaleRef.current,
      Math.max(-lim.x, Math.min(lim.x, d.ox + dx)),
      Math.max(-lim.y, Math.min(lim.y, d.oy + dy)),
    )
  }

  const onImgPointerEnd = () => {
    if (!dragRef.current) return
    if (dragRef.current.moved) lastDragEndRef.current = Date.now()
    dragRef.current = null
    setPanning(false)
  }

  const onImgDoubleClick = (e: React.MouseEvent) => {
    if (Date.now() - lastDragEndRef.current < 200) return
    if (scaleRef.current > MIN_SCALE) resetZoom()
    else zoomAt(e.clientX, e.clientY, DBLCLICK_SCALE)
  }

  const onOverlayClick = () => {
    // 平移松手后的 click 不当关闭（阈值窗口滤掉拖尾点击）
    if (Date.now() - lastDragEndRef.current < 200) return
    onClose()
  }

  // 防御：images 半路清空（如附件被移除）时不渲染空壳
  if (!cur) return null

  return createPortal(
    <div className="image-preview-overlay" role="presentation" ref={overlayRef} onClick={onOverlayClick}>
      <div
        className="image-preview-content"
        role="dialog"
        aria-label={cur.title ?? t('chat.message.imagePreview')}
        onClick={(e) => e.stopPropagation()}
      >
        <img
          ref={imgRef}
          src={cur.src}
          alt={cur.title ?? ''}
          draggable={false}
          className={panning ? 'is-panning' : scale > MIN_SCALE ? 'is-zoomable' : undefined}
          style={{ transform: `translate(${offset.x}px, ${offset.y}px) scale(${scale})` }}
          onPointerDown={onImgPointerDown}
          onPointerMove={onImgPointerMove}
          onPointerUp={onImgPointerEnd}
          onPointerCancel={onImgPointerEnd}
          onDoubleClick={onImgDoubleClick}
        />
        <div className="image-preview-meta">
          {multi && (
            <span className="image-preview-counter">
              {idx + 1} / {list.length}
            </span>
          )}
          {cur.title && <span className="image-preview-title">{cur.title}</span>}
        </div>
      </div>
      {multi && (
        <>
          <button
            type="button"
            className="image-preview-nav image-preview-nav--prev"
            disabled={idx === 0}
            onClick={(e) => {
              e.stopPropagation()
              setIdx((v) => Math.max(0, v - 1))
            }}
            title={t('chat.message.imagePrev')}
            aria-label={t('chat.message.imagePrev')}
          >
            <span className="codicon codicon-chevron-left" />
          </button>
          <button
            type="button"
            className="image-preview-nav image-preview-nav--next"
            disabled={idx === list.length - 1}
            onClick={(e) => {
              e.stopPropagation()
              setIdx((v) => Math.min(list.length - 1, v + 1))
            }}
            title={t('chat.message.imageNext')}
            aria-label={t('chat.message.imageNext')}
          >
            <span className="codicon codicon-chevron-right" />
          </button>
        </>
      )}
      <div
        className="image-preview-toolbar"
        role="toolbar"
        aria-label={t('chat.message.imagePreview')}
        onClick={(e) => e.stopPropagation()}
        onDoubleClick={(e) => e.stopPropagation()}
      >
        <button
          type="button"
          className="image-preview-zoom-btn"
          disabled={scale <= MIN_SCALE}
          onClick={() => zoomBy(1 / STEP_FACTOR)}
          title={t('chat.message.imageZoomOut')}
          aria-label={t('chat.message.imageZoomOut')}
        >
          <span className="codicon codicon-zoom-out" />
        </button>
        <button
          type="button"
          className="image-preview-zoom-level"
          onClick={resetZoom}
          title={t('chat.message.imageZoomReset')}
          aria-label={t('chat.message.imageZoomReset')}
        >
          {Math.round(scale * 100)}%
        </button>
        <button
          type="button"
          className="image-preview-zoom-btn"
          disabled={scale >= MAX_SCALE}
          onClick={() => zoomBy(STEP_FACTOR)}
          title={t('chat.message.imageZoomIn')}
          aria-label={t('chat.message.imageZoomIn')}
        >
          <span className="codicon codicon-zoom-in" />
        </button>
      </div>
    </div>,
    document.body,
  )
}
