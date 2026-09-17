/**
 * 自定义模型渠道编辑弹窗（添加 / 编辑共用，design-research/自定义模型渠道CRUD实现方案）
 *
 * 数据：ProviderSaveDraft → Kotlin modelAddProvider/modelUpdateProvider 写 config.json
 *       provider 注册表（与 Zcode 客户端共用，客户端可见可编辑）；内置渠道不经此弹窗。
 * apiKey 所见即所存：编辑态回填渠道已存明文（config 源），改什么存什么；清空会被
 *       校验拦截——缺 key 的渠道进不了模型管理页（可用性过滤），想弃用直接删渠道。
 * 模型配置：每模型一个两行 panel（上行 ID/上下文/最大输出，下行输入类型+删除），
 * context 必填（autocompact 阈值依赖 limit.context，虚报会误触发压缩）。
 */

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ProviderModelDraft, ProviderSaveDraft } from '@/types/messages'
import '../styles/model-list-view.less'

const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(' ')

/**
 * 弹窗内单个模型配置（两行 panel：上行 = 模型 ID/上下文/最大输出，下行 = 输入类型 + 删除；
 * 输入态全字符串，提交时转数字）。显示名不配置（Zcode 客户端无此配置，模型名即 ID）。
 */
export interface EditorModelRow {
  modelId: string
  context: string
  output: string
  /** 输入类型位（对齐客户端：文本恒选锁定；图片/视频/PDF 可选）*/
  images: boolean
  video: boolean
  pdf: boolean
}

export function emptyModelRow(): EditorModelRow {
  return { modelId: '', context: withCommas('1000000'), output: '', images: false, video: false, pdf: false }
}

/** 数字输入千分位（en-US 习惯）：仅保留数字并每 3 位插逗号，方便阅读大数 */
export function withCommas(digits: string): string {
  const d = digits.replace(/\D/g, '')
  return d.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/** 千分位串转数字（非法/负数/零由调用方校验）*/
function toNum(commas: string): number {
  return Number(commas.replace(/,/g, ''))
}

interface Props {
  mode: 'add' | 'edit'
  /** 编辑态回填（渠道名/协议/baseURL/key 明文/模型行）；添加态 null */
  initial: {
    name: string
    kind: 'anthropic' | 'openai-compatible'
    baseURL: string
    apiKey: string
    models: EditorModelRow[]
  } | null
  saving: boolean
  /** 保存失败文案（store providerSaveError，弹窗内提示）*/
  error: string | null
  onConfirm: (draft: ProviderSaveDraft) => void
  onCancel: () => void
}

export function ProviderEditorDialog({ mode, initial, saving, error, onConfirm, onCancel }: Props) {
  const { t } = useTranslation()
  const [name, setName] = useState(initial?.name ?? '')
  const [kind, setKind] = useState<'anthropic' | 'openai-compatible'>(initial?.kind ?? 'anthropic')
  const [baseURL, setBaseURL] = useState(initial?.baseURL ?? '')
  const [apiKey, setApiKey] = useState(initial?.apiKey ?? '')
  const [keyVisible, setKeyVisible] = useState(false)
  const [rows, setRows] = useState<EditorModelRow[]>(initial?.models ?? [emptyModelRow()])
  const [localError, setLocalError] = useState<string | null>(null)
  const isEdit = mode === 'edit'

  const setRow = (i: number, raw: Partial<EditorModelRow>) => {
    // 数字字段输入即格式化千分位（用户只管敲数字，逗号自动补）
    const patch = { ...raw }
    if (typeof patch.context === 'string') patch.context = withCommas(patch.context)
    if (typeof patch.output === 'string') patch.output = withCommas(patch.output)
    setRows((rs) => rs.map((r, idx) => (idx === i ? { ...r, ...patch } : r)))
  }
  const removeRow = (i: number) => setRows((rs) => rs.filter((_, idx) => idx !== i))

  const submit = () => {
    setLocalError(null)
    const n = name.trim()
    if (!n) return setLocalError(t('models.editor.errName'))
    const url = baseURL.trim()
    if (!/^https?:\/\//.test(url)) return setLocalError(t('models.editor.errUrl'))
    const key = apiKey.trim()
    if (!key) return setLocalError(t('models.editor.errKey'))
    const ids = rows.map((r) => r.modelId.trim()).filter(Boolean)
    if (ids.length === 0) return setLocalError(t('models.editor.errModels'))
    if (new Set(ids).size !== ids.length) return setLocalError(t('models.editor.errDup'))
    const models: ProviderModelDraft[] = []
    for (const r of rows) {
      const mid = r.modelId.trim()
      if (!mid) return setLocalError(t('models.editor.errModelId'))
      const ctx = toNum(r.context)
      if (!Number.isInteger(ctx) || ctx <= 0) return setLocalError(t('models.editor.errContext'))
      const out = r.output.trim() === '' ? undefined : toNum(r.output)
      if (out != null && (!Number.isInteger(out) || out <= 0)) return setLocalError(t('models.editor.errOutput'))
      models.push({
        modelId: mid,
        context: ctx,
        output: out,
        images: r.images || undefined,
        video: r.video || undefined,
        pdf: r.pdf || undefined,
      })
    }
    onConfirm({
      name: n,
      kind,
      baseURL: url,
      apiKey: key,
      models,
    })
  }

  const shownError = localError ?? error

  return (
    <div className="modal-overlay" role="presentation">
      <div className="modal-content provider-editor">
        <h3>{isEdit ? t('models.editor.titleEdit') : t('models.editor.titleAdd')}</h3>
        <div className="provider-editor__body">
          <label className="provider-editor__field">
            <span className="provider-editor__label">{t('models.editor.name')}</span>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={t('models.editor.namePlaceholder')}
              spellCheck={false}
              autoFocus
            />
          </label>
          <label className="provider-editor__field">
            <span className="provider-editor__label">{t('models.editor.kind')}</span>
            <select value={kind} onChange={(e) => setKind(e.target.value as 'anthropic' | 'openai-compatible')}>
              <option value="anthropic">{t('models.editor.kindAnthropic')}</option>
              <option value="openai-compatible">{t('models.editor.kindOpenaiCompat')}</option>
            </select>
          </label>
          <label className="provider-editor__field">
            <span className="provider-editor__label">{t('models.editor.baseURL')}</span>
            <input
              value={baseURL}
              onChange={(e) => setBaseURL(e.target.value)}
              placeholder={kind === 'anthropic' ? 'https://api.example.com/anthropic' : 'https://api.example.com/v1'}
              spellCheck={false}
            />
          </label>
          <div className="provider-editor__field">
            <span className="provider-editor__label">{t('models.editor.apiKey')}</span>
            <div className="provider-editor__key-row">
              <input
                type={keyVisible ? 'text' : 'password'}
                value={apiKey}
                onChange={(e) => setApiKey(e.target.value)}
                placeholder={t('models.editor.apiKeyPlaceholderAdd')}
                spellCheck={false}
              />
              <button
                type="button"
                className="provider-editor__icon-btn"
                onClick={() => setKeyVisible((v) => !v)}
                title={keyVisible ? t('models.hideKey') : t('models.showKey')}
              >
                <span className={cx('codicon', keyVisible ? 'codicon-eye-closed' : 'codicon-eye')} />
              </button>
            </div>
          </div>

          <div className="provider-editor__models-head">
            <span className="provider-editor__label">{t('models.editor.modelsTitle')}</span>
            <button
              type="button"
              className="provider-editor__add-row"
              onClick={() => setRows((rs) => [...rs, emptyModelRow()])}
            >
              <span className="codicon codicon-add" />
              {t('models.editor.addModelRow')}
            </button>
          </div>
          <div className="provider-editor__models">
            {rows.map((r, i) => (
              <div className="provider-editor__model-panel" key={i}>
                <div className="provider-editor__model-grid">
                  <label className="provider-editor__field provider-editor__field--grow">
                    <span className="provider-editor__label">{t('models.editor.modelId')}</span>
                    <input value={r.modelId} onChange={(e) => setRow(i, { modelId: e.target.value })} placeholder="model-id" spellCheck={false} />
                  </label>
                  <label className="provider-editor__field provider-editor__field--num">
                    <span className="provider-editor__label" title={t('models.editor.contextHint')}>{t('models.editor.context')}</span>
                    <input value={r.context} onChange={(e) => setRow(i, { context: e.target.value })} inputMode="numeric" title={t('models.editor.contextHint')} />
                  </label>
                  <label className="provider-editor__field provider-editor__field--num">
                    <span className="provider-editor__label">{t('models.editor.output')}</span>
                    <input value={r.output} onChange={(e) => setRow(i, { output: e.target.value })} inputMode="numeric" placeholder="—" />
                  </label>
                </div>
                <div className="provider-editor__model-types">
                  <span className="provider-editor__label">{t('models.editor.inTypesLabel')}</span>
                  {/* 文本恒选锁定（对齐客户端 🔒 语义）*/}
                  <label className="provider-editor__check is-locked" title={t('models.editor.inLockedHint')}>
                    <input type="checkbox" checked disabled readOnly />
                    <span>{t('models.editor.inText')}</span>
                    <span className="codicon codicon-lock" />
                  </label>
                  <label className="provider-editor__check" title={t('models.editor.inImage')}>
                    <input type="checkbox" checked={r.images} onChange={(e) => setRow(i, { images: e.target.checked })} />
                    <span>{t('models.editor.inImage')}</span>
                  </label>
                  <label className="provider-editor__check" title={t('models.editor.inVideo')}>
                    <input type="checkbox" checked={r.video} onChange={(e) => setRow(i, { video: e.target.checked })} />
                    <span>{t('models.editor.inVideo')}</span>
                  </label>
                  <label className="provider-editor__check" title={t('models.editor.inPdf')}>
                    <input type="checkbox" checked={r.pdf} onChange={(e) => setRow(i, { pdf: e.target.checked })} />
                    <span>{t('models.editor.inPdf')}</span>
                  </label>
                  <span className="provider-editor__types-spacer" />
                  <button
                    type="button"
                    className="provider-editor__icon-btn"
                    onClick={() => removeRow(i)}
                    disabled={rows.length <= 1}
                    title={t('models.editor.removeRow')}
                  >
                    <span className="codicon codicon-trash" />
                  </button>
                </div>
              </div>
            ))}
          </div>
          {/* 输出类型（对齐客户端语义）：文本恒选锁定，暂无其他输出形态可配 */}
          <div className="provider-editor__out-row">
            <span className="provider-editor__label">{t('models.editor.outTypes')}</span>
            <label className="provider-editor__check is-locked" title={t('models.editor.outLockedHint')}>
              <input type="checkbox" checked disabled readOnly />
              <span>{t('models.editor.outText')}</span>
              <span className="codicon codicon-lock" />
            </label>
          </div>
          <p className="provider-editor__hint">{t('models.editor.contextHint')}</p>

          {shownError && (
            <div className="provider-editor__error" role="alert">
              <span className="codicon codicon-error" />
              {shownError}
            </div>
          )}
        </div>
        <div className="modal-actions">
          <button className="modal-btn modal-btn-cancel" onClick={onCancel}>
            {t('models.dialog.dismiss')}
          </button>
          <button className="modal-btn modal-btn-primary" onClick={submit} disabled={saving}>
            <span className={cx('codicon', saving && 'codicon-loading spin')} />
            {saving ? t('models.editor.saving') : t('models.editor.save')}
          </button>
        </div>
      </div>
    </div>
  )
}
