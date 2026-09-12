/**
 * 粘贴文本 chip（长文本粘贴折叠，类附件）
 *
 * 粘贴超过阈值（≥10 行或 ≥500 字符，见 InputBox PASTE_* 常量）的文本时，
 * 不进输入框正文（撑爆编辑区影响阅读），而是折叠为顶部 chips 区的一个块：
 *   [📝 粘贴文本 · 1234 字]  ✕
 * 点击 chip 弹预览 modal（全文 + 字符数），✕ 移除（内容不再发送）。
 * 发送时由 InputBox 把各段原文拼到正文末尾（CLI 收到完整文本）。
 *
 * 中性灰色调，区别于文件引用（蓝）/技能（紫）。
 */

import { memo, useRef } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { ScrollJumpButton } from './ScrollJumpButton'
import '../styles/pasted-text-ref.less'

export interface PastedTextItem {
  id: string
  text: string
  chars: number
}

interface Props {
  item: PastedTextItem
  onPreview: () => void
  onRemove: () => void
}

function PastedTextRefInner({ item, onPreview, onRemove }: Props) {
  const { t } = useTranslation()
  return (
    <span
      className="pasted-text-ref"
      data-tip={t('input.pasted.clickToPreview')}
      onClick={(e) => {
        e.stopPropagation()
        onPreview()
      }}
    >
      <span className="codicon codicon-note pasted-text-ref__icon" />
      <span className="pasted-text-ref__name">{t('input.pasted.label', { count: item.chars })}</span>
      <button
        className="pasted-text-ref__remove"
        onClick={(e) => {
          e.stopPropagation()
          onRemove()
        }}
        title={t('input.pasted.remove')}
        type="button"
      >
        ✕
      </button>
    </span>
  )
}

export const PastedTextRef = memo(PastedTextRefInner)

/** 预览弹窗（骨架复用压缩摘要弹窗 subagent-detail 系，与用户消息全文弹窗同款，2026-09-12 统一；
 *  Escape 在 InputBox 的 window 级监听关闭，点遮罩/✕ 同样关闭）*/
export function PastedTextPreview({ item, onClose }: { item: PastedTextItem; onClose: () => void }) {
  const { t } = useTranslation()
  const bodyRef = useRef<HTMLPreElement>(null)
  return createPortal(
    <div className="subagent-detail-overlay" role="presentation" onClick={onClose}>
      <div
        className="subagent-detail-dialog pasted-text-preview"
        role="dialog"
        aria-label={t('input.pasted.previewAriaLabel')}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="subagent-detail-header">
          <span className="codicon codicon-note subagent-detail-header__icon" />
          <div className="subagent-detail-header__main">
            <span className="subagent-detail-header__title">
              {t('input.pasted.previewTitle', { count: item.chars })}
            </span>
          </div>
          <button
            className="subagent-detail-icon-btn"
            onClick={onClose}
            title={t('input.pasted.close')}
            aria-label={t('input.pasted.close')}
            type="button"
          >
            <span className="codicon codicon-chrome-close" />
          </button>
        </div>
        <pre ref={bodyRef} className="subagent-detail-body pasted-text-preview__body">{item.text}</pre>
        <ScrollJumpButton containerRef={bodyRef} />
      </div>
    </div>,
    document.body,
  )
}
