/**
 * 预设供应商选择弹窗（添加渠道的第一步，对齐 ZCode 客户端「添加供应商」卡片网格）
 *
 * 分组对齐客户端：智谱族（Coding Plan/API 四卡）在前，「其他」组首位为「创建自定义供应商」。
 * 点预设卡 → 带预填（kind/baseURL/模型列表）打开 ProviderEditorDialog，用户只填 API Key；
 * 点「创建自定义供应商」→ 空表单（原行为）。已添加的预设仅提示不拦截（v2 providerId 为
 * UUID，重复添加生成并列新渠道，由用户自行管理）。
 */

import { useTranslation } from 'react-i18next'
import { PROVIDER_PRESETS, isPresetAdded, type ProviderPreset } from '@/utils/providerPresets'
import '../styles/model-list-view.less'

const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(' ')

interface Props {
  /** 已存在的自定义渠道 baseURL（已添加提示，不拦截点击） */
  addedBaseURLs: string[]
  onPick: (preset: ProviderPreset) => void
  onCreateCustom: () => void
  onCancel: () => void
}

export function ProviderTemplatePickerDialog({ addedBaseURLs, onPick, onCreateCustom, onCancel }: Props) {
  const { t, i18n } = useTranslation()
  const zh = i18n.language?.startsWith('zh')
  const zhipu = PROVIDER_PRESETS.filter((p) => p.group === 'zhipu')
  const others = PROVIDER_PRESETS.filter((p) => p.group !== 'zhipu')

  const renderCard = (p: ProviderPreset) => {
    const added = isPresetAdded(p, addedBaseURLs)
    const label = zh ? p.name.zh : p.name.en
    return (
      <button
        type="button"
        key={p.id}
        className={cx('provider-template-picker__card', added && 'is-added')}
        onClick={() => onPick(p)}
        title={added ? `${label} · ${t('models.preset.addedHint')}` : label}
      >
        <span className="provider-template-picker__logo" style={{ background: p.accent }}>
          {p.badge}
        </span>
        <span className="provider-template-picker__card-body">
          <span className="provider-template-picker__name">{label}</span>
          <span className="provider-template-picker__sub">
            {t('models.preset.modelCount', { count: p.models.length })}
          </span>
        </span>
        {added && <span className="provider-template-picker__added">{t('models.preset.added')}</span>}
        <span className="codicon codicon-chevron-right provider-template-picker__chevron" />
      </button>
    )
  }

  return (
    <div className="modal-overlay" role="presentation">
      <div className="modal-content provider-template-picker">
        <h3>{t('models.preset.title')}</h3>
        <p className="provider-template-picker__hint">{t('models.preset.hint')}</p>
        <div className="provider-template-picker__scroll">
          <div className="provider-template-picker__group-title">{t('models.preset.groupZhipu')}</div>
          <div className="provider-template-picker__grid">{zhipu.map(renderCard)}</div>
          <div className="provider-template-picker__group-title">{t('models.preset.groupOther')}</div>
          <div className="provider-template-picker__grid">
            <button type="button" className="provider-template-picker__card is-create" onClick={onCreateCustom}>
              <span className="provider-template-picker__create-icon">
                <span className="codicon codicon-add" />
              </span>
              <span className="provider-template-picker__card-body">
                <span className="provider-template-picker__name">{t('models.preset.create')}</span>
                <span className="provider-template-picker__sub">{t('models.preset.createHint')}</span>
              </span>
              <span className="codicon codicon-chevron-right provider-template-picker__chevron" />
            </button>
            {others.map(renderCard)}
          </div>
        </div>
        <div className="modal-actions">
          <button className="modal-btn modal-btn-cancel" onClick={onCancel}>
            {t('models.dialog.dismiss')}
          </button>
        </div>
      </div>
    </div>
  )
}
