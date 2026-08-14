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

import { memo } from 'react'
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
  return (
    <span
      className="pasted-text-ref"
      data-tip="点击预览完整内容"
      onClick={(e) => {
        e.stopPropagation()
        onPreview()
      }}
    >
      <span className="codicon codicon-note pasted-text-ref__icon" />
      <span className="pasted-text-ref__name">粘贴文本 · {item.chars} 字</span>
      <button
        className="pasted-text-ref__remove"
        onClick={(e) => {
          e.stopPropagation()
          onRemove()
        }}
        title="移除粘贴内容"
        type="button"
      >
        ✕
      </button>
    </span>
  )
}

export const PastedTextRef = memo(PastedTextRefInner)

/** 预览弹窗（复用全局 .modal-overlay/.modal-content；Escape 在 InputBox 全局监听关闭）*/
export function PastedTextPreview({ item, onClose }: { item: PastedTextItem; onClose: () => void }) {
  return (
    <div className="modal-overlay" role="presentation" onClick={onClose}>
      <div
        className="modal-content pasted-text-preview"
        role="dialog"
        aria-label="粘贴内容预览"
        onClick={(e) => e.stopPropagation()}
      >
        <h3>粘贴内容 · {item.chars} 字</h3>
        <pre className="pasted-text-preview__body">{item.text}</pre>
        <div className="modal-actions">
          <button className="modal-btn modal-btn-primary" onClick={onClose} type="button">
            关闭
          </button>
        </div>
      </div>
    </div>
  )
}
