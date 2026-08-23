/**
 * 提示词润色对比确认弹窗（cc-gui PromptEnhancerDialog 风格）
 *
 * 点击输入框润色按钮后弹出：上栏原始提示词、下栏润色结果（loading 转圈 / 错误态），
 * 「保留原始」仅关闭、「使用润色」回填输入框（由 InputBox 回调操作 editorRef）。
 * 键盘：Enter = 使用润色（有结果时）、Escape = 关闭（window 级监听，焦点不在弹窗也能关）。
 */

import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import '../styles/prompt-enhancer.less'

export interface EnhanceResult {
  original: string
  text?: string
  error?: string
}

interface Props {
  /** loading 态（CLI 调用在途）*/
  enhancing: boolean
  result: EnhanceResult
  /** 使用润色结果（回填输入框）*/
  onUse: (text: string) => void
  /** 关闭弹窗（保留原始）*/
  onClose: () => void
}

export function PromptEnhancerDialog({ enhancing, result, onUse, onClose }: Props) {
  const { t } = useTranslation()

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        onClose()
      } else if (e.key === 'Enter' && !e.shiftKey && !enhancing && result.text) {
        e.preventDefault()
        onUse(result.text)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [enhancing, result.text, onClose, onUse])

  return (
    <div className="modal-overlay" role="presentation" onClick={onClose}>
      <div
        className="modal-content prompt-enhancer"
        role="dialog"
        aria-label={t('enhance.dialogAriaLabel')}
        onClick={(e) => e.stopPropagation()}
      >
        <h3>{t('enhance.title')}</h3>

        <div className="prompt-enhancer__section">
          <div className="prompt-enhancer__section-title">{t('enhance.original')}</div>
          <pre className="prompt-enhancer__body prompt-enhancer__body--original">{result.original}</pre>
        </div>

        <div className="prompt-enhancer__section">
          <div className="prompt-enhancer__section-title">{t('enhance.enhanced')}</div>
          {enhancing ? (
            <div className="prompt-enhancer__loading">
              <span className="codicon codicon-loading codicon-modifier-spin" />
              <span>{t('enhance.loading')}</span>
            </div>
          ) : result.error ? (
            <div className="prompt-enhancer__error">
              <span className="codicon codicon-error" />
              <span>{result.error}</span>
            </div>
          ) : (
            <pre className="prompt-enhancer__body prompt-enhancer__body--enhanced">{result.text}</pre>
          )}
        </div>

        <div className="modal-actions">
          <button
            className="modal-btn"
            onClick={onClose}
            disabled={enhancing}
            type="button"
            title="Escape"
          >
            {t('enhance.keepOriginal')}
          </button>
          <button
            className="modal-btn modal-btn-primary"
            onClick={() => result.text && onUse(result.text)}
            disabled={enhancing || !result.text}
            type="button"
            title="Enter"
          >
            {t('enhance.useEnhanced')}
          </button>
        </div>
      </div>
    </div>
  )
}
