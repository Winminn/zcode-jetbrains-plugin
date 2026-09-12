/**
 * 图片大图预览 overlay（cc-gui image-preview-overlay 同款）
 *
 * 输入框附件缩略图与消息区图片点击共用：portal 挂 body（脱离
 * messages-container / input-area 的 overflow 裁剪），Esc 或点遮罩关闭。
 *
 * 多图切换：传 images 列表 + initialIndex 即启用左右切换（两侧按钮 + ←/→ 键 +
 * 序号指示），到头禁用不循环；单图仍走 src/title，无切换 UI。
 */

import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import '../styles/input-box.less'

export interface PreviewImage {
  src: string
  title?: string
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

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose()
        return
      }
      if (!multi) return
      if (e.key === 'ArrowLeft') setIdx((v) => Math.max(0, v - 1))
      if (e.key === 'ArrowRight') setIdx((v) => Math.min(list.length - 1, v + 1))
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [multi, list.length, onClose])

  // 防御：images 半路清空（如附件被移除）时不渲染空壳
  if (!cur) return null

  return createPortal(
    <div className="image-preview-overlay" role="presentation" onClick={onClose}>
      <div
        className="image-preview-content"
        role="dialog"
        aria-label={cur.title ?? t('chat.message.imagePreview')}
        onClick={(e) => e.stopPropagation()}
      >
        <img src={cur.src} alt={cur.title ?? ''} />
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
    </div>,
    document.body,
  )
}
