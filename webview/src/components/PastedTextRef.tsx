/**
 * 粘贴文本预览弹窗（内联粘贴 chip 的全文查看，issue #22②）
 *
 * 超阈值粘贴（≥10 行或 ≥500 字符，见 InputBox PASTE_* 常量）的文本自 0.3.8 起
 * 折叠为输入框光标处的内联 chip（.paste-ref--inline，InputBox 生成、原文存
 * id→原文映射不进 DOM）；点击 chip 弹本预览 modal（全文 + 字符数）。
 * chip 的 ✕ 移除、Backspace 整体删除由 InputBox 的编辑器事件委托处理。
 *
 * 原顶部 chip 形态（PastedTextRef 组件）已随内联化退场；
 * 配色仍是中性灰色调，区别于文件引用（蓝）/技能（紫）。
 */

import { useRef } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { ScrollJumpButton } from './ScrollJumpButton'
import '../styles/pasted-text-ref.less'

export interface PastedTextItem {
  id: string
  text: string
  chars: number
}

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
