/**
 * 模型列表面板（设置页「模型」条目，参考 cc-gui ProviderList 的展示模式）
 *
 * 数据：modelManageList（Kotlin 端读 config.json——路径走 Credentials.defaultConfigPath()
 *       跟随 dataBaseDir 迁移；apiKey 缺失的无效 provider 过滤；内置渠道只返回生效的）
 * 交互：内置渠道只读展示（启停以 ZCode 客户端配置为准，插件不代写 config——客户端
 *       与插件两个写者互相覆盖易出状态错乱）；自定义渠道 CRUD 全在插件内完成——
 *       ProviderEditorDialog 添加/编辑（写 config.json provider 注册表，与客户端共用）、
 *       行内启用/禁用切换、渠道删除、模型行删除（均经 modelProviderSaved/modelToggled
 *       回包刷新，成功后输入框下拉经 loadModels 同步刷新）。
 */

import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useStore } from '@/store/useStore'
import { sendToJava } from '@/ipc/bridge'
import { ConfirmDialog } from './ConfirmDialog'
import { PlanBadge } from './PlanBadge'
import { ProviderEditorDialog } from './ProviderEditorDialog'
import type { EditorModelRow } from './ProviderEditorDialog'
import type { ModelManageModel, ModelManageProvider, ProviderSaveDraft } from '@/types/messages'
import '../styles/model-list-view.less'

const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(' ')

/** token 数 → K/M 缩写（1000000 → 1M、204800 → 200K）*/
function formatTokens(n: number): string {
  if (n >= 1_000_000) {
    const m = n / 1_000_000
    return `${Number.isInteger(m) ? m : m.toFixed(1)}M`
  }
  if (n >= 1_000) return `${Math.round(n / 1_000)}K`
  return `${n}`
}

/** 待确认的动作（deleteModel=自定义渠道模型行删除、removeProvider=渠道删除、key=内置渠道 key），null=关闭 */
type PendingAction =
  | { kind: 'deleteModel'; provider: ModelManageProvider; model: ModelManageModel }
  | { kind: 'removeProvider'; provider: ModelManageProvider }
  | { kind: 'lastModel'; provider: ModelManageProvider; model: ModelManageModel }
  | { kind: 'key'; providerId: string; providerName: string }

/** 自定义 key 输入值（PendingAction.kind=key 期间的受控状态；空=清除） */
type KeyDraft = { value: string; configured: boolean }

/** 单个模型行：名称 + ID + 上下文/输出徽章 + 删除（onDelete 缺省不渲染——内置渠道只读）*/
function ModelRow({
  model,
  onDelete,
  deleteBlockedTitle,
}: {
  model: ModelManageModel
  onDelete?: () => void
  /** 非空 = 渠道最后一个模型：按钮可点但点击转弹窗提醒（deleteBlockedTitle 作按钮 title）*/
  deleteBlockedTitle?: string
}) {
  const { t } = useTranslation()
  return (
    <div className="model-list-view__model">
      <span className="codicon codicon-symbol-method model-list-view__model-icon" />
      <span className="model-list-view__model-name" title={model.modelName}>
        {model.modelName}
      </span>
      <span className="model-list-view__model-id" title={model.modelId}>
        {model.modelId}
      </span>
      {model.supportsImages && (
        <span className="model-list-view__model-badge model-list-view__model-badge--vision" title={t('models.vision')}>
          {t('models.vision')}
        </span>
      )}
      {model.contextWindow != null && (
        <span className="model-list-view__model-badge" title={t('models.contextTitle')}>
          {t('models.contextBadge', { size: formatTokens(model.contextWindow) })}
        </span>
      )}
      {model.maxOutput != null && (
        <span className="model-list-view__model-badge" title={t('models.outputTitle')}>
          {t('models.outputBadge', { size: formatTokens(model.maxOutput) })}
        </span>
      )}
      {onDelete && (
        <button
          className="model-list-view__model-delete"
          onClick={onDelete}
          title={deleteBlockedTitle ?? t('models.deleteTitle')}
        >
          <span className="codicon codicon-trash" />
        </button>
      )}
    </div>
  )
}

/**
 * provider 分组卡片：头部（选择控件/名称/套餐徽章/ID/状态徽章/baseURL/计数）+ 模型行列表。
 * builtin=true（内置渠道）：只读展示当前生效的渠道（状态徽章），启停以 ZCode 客户端
 * 配置为准，插件不代写；否则（自定义供应商）：行内 toggle 开关独立启停 + 编辑/删除
 * 渠道入口（插件内完成 CRUD，写 config.json 与客户端共用注册表）。
 */
function ProviderCard({
  provider,
  builtin = false,
  onDeleteModel,
  onBlockedModelDelete,
  onEditKey,
  onEditProvider,
  onDeleteProvider,
  onMove,
  moveDisabled = false,
  isFirst = false,
  isLast = false,
}: {
  provider: ModelManageProvider
  builtin?: boolean
  /** 模型行删除入口（缺省不渲染删除按钮——内置渠道只读，模型以客户端配置为准）*/
  onDeleteModel?: (provider: ModelManageProvider, model: ModelManageModel) => void
  /** 渠道最后一个模型的删除点击（转弹窗提醒，不触发真删）*/
  onBlockedModelDelete?: (provider: ModelManageProvider, model: ModelManageModel) => void
  onEditKey?: (provider: ModelManageProvider) => void
  onEditProvider?: (provider: ModelManageProvider) => void
  onDeleteProvider?: (provider: ModelManageProvider) => void
  /** 上移/下移排序（v2 专属：target 传 '__up__'/'__down__' 表示与相邻渠道交换，写 providerOrder）；缺省不渲染箭头 */
  onMove?: (source: string, target: string) => void
  /** 排序写回中（防连点）*/
  moveDisabled?: boolean
  isFirst?: boolean
  isLast?: boolean
}) {
  const { t } = useTranslation()
  const modelTogglingId = useStore((s) => s.modelTogglingId)
  const toggleModelProvider = useStore((s) => s.toggleModelProvider)
  const toggling = modelTogglingId === provider.providerId
  // 激活 key 眼睛切换（常态脱敏，点开看全）
  const [keyVisible, setKeyVisible] = useState(false)

  const handleToggle = () => {
    if (!toggling) toggleModelProvider(provider.providerId, !provider.enabled)
  }

  return (
    <div className={cx('model-list-view__provider', !provider.enabled && 'disabled')}>
      <div className="model-list-view__provider-header">
        <div className="model-list-view__provider-main">
          {builtin ? (
            <span
              className="model-list-view__provider-active"
              title={t('models.builtinReadonlyHint')}
            >
              <span className="codicon codicon-pass-filled" />
            </span>
          ) : (          <button
              className={cx('model-list-view__toggle', provider.enabled && 'on')}
              onClick={handleToggle}
              disabled={toggling}
              title={provider.enabled ? t('models.disableHint') : t('models.enableHint')}
            >
              <span
                className={cx(
                  'codicon',
                  toggling ? 'codicon-loading spin' : provider.enabled ? 'codicon-check' : 'codicon-circle-slash',
                )}
              />
            </button>
          )}
          {builtin && provider.via && (() => {
            // 兜底原因细分：captchaGated（体验套餐被门控排除）换专属文案，区分于凭证失效
            const captchaFallback = provider.via === 'fallback' && provider.viaReason === 'captchaGated'
            const viaText = captchaFallback
              ? t('models.viaFallbackCaptcha')
              : provider.via === 'fallback'
                ? t('models.viaFallback')
                : t('models.viaSelected')
            const viaTitle = captchaFallback
              ? t('models.viaFallbackCaptchaHint')
              : provider.via === 'fallback'
                ? t('models.viaFallbackHint')
                : t('models.viaSelectedHint')
            return (
              <span
                className={cx(
                  'model-list-view__provider-via',
                  provider.via === 'fallback' && 'is-fallback',
                )}
                title={viaTitle}
              >
                {viaText}
              </span>
            )
          })()}
          <span className={cx('codicon', provider.enabled ? 'codicon-server-environment' : 'codicon-server-process')} />
          <span className="model-list-view__provider-name">{provider.providerName}</span>
          <PlanBadge plan={provider.plan} />
          {/* 自定义 key 入口=状态合一的文字按钮：已配置紫色、未配置灰色弱化，点击打开编辑弹窗 */}
          {builtin && (
            <button
              className={cx('model-list-view__provider-key-btn', provider.customKey && 'is-set')}
              onClick={() => onEditKey?.(provider)}
              title={provider.customKey ? t('models.customKeyBadgeHint') : t('models.customKeyTitle')}
            >
              <span className="codicon codicon-key" />
              {t('models.customKeyBadge')}
            </button>
          )}
          {!provider.enabled && (
            <span className="model-list-view__provider-off">{t('models.providerDisabled')}</span>
          )}
          <span className="model-list-view__provider-count">
            {t('models.modelsCount', { count: provider.models.length })}
          </span>
          {!builtin && (
            <span className="model-list-view__provider-actions">
              {onMove && !isFirst && (
                <button
                  className="model-list-view__provider-action"
                  disabled={moveDisabled}
                  onClick={() => onMove(provider.providerId, '__up__')}
                  title={t('models.moveUp')}
                >
                  <span className="codicon codicon-arrow-up" />
                </button>
              )}
              {onMove && !isLast && (
                <button
                  className="model-list-view__provider-action"
                  disabled={moveDisabled}
                  onClick={() => onMove(provider.providerId, '__down__')}
                  title={t('models.moveDown')}
                >
                  <span className="codicon codicon-arrow-down" />
                </button>
              )}
              <button
                className="model-list-view__provider-action"
                onClick={() => onEditProvider?.(provider)}
                title={t('models.editor.titleEdit')}
              >
                <span className="codicon codicon-edit" />
              </button>
              <button
                className="model-list-view__provider-action model-list-view__provider-action--danger"
                onClick={() => onDeleteProvider?.(provider)}
                title={t('models.removeProviderTitle')}
              >
                <span className="codicon codicon-trash" />
              </button>
            </span>
          )}
        </div>
        <div className="model-list-view__provider-meta">
          <span className="model-list-view__provider-id" title={provider.providerId}>
            {provider.providerId}
          </span>
          {provider.baseURL && (
            <span className="model-list-view__provider-url" title={provider.baseURL}>
              {provider.baseURL}
            </span>
          )}
        </div>
        {/* 实际生效的计费 key（与 RuntimeModels 构造同优先级，所见即所扣） */}
        {provider.activeKeyMasked && (
          <div className="model-list-view__active-key">
            <span className="model-list-view__active-key-label">
              <span className="codicon codicon-key" />
              {t('models.activeKeyLabel')}
            </span>
            <span className="model-list-view__active-key-value" title={keyVisible ? undefined : t('models.showKeyTitle')}>
              {keyVisible ? provider.activeKeyValue : provider.activeKeyMasked}
            </span>
            <span className={cx('model-list-view__active-key-src', `src-${provider.activeKeySource}`)}>
              {provider.activeKeySource === 'custom'
                ? t('models.activeKeyCustom')
                : provider.activeKeySource === 'config'
                  ? t('models.activeKeyConfig')
                  : t('models.activeKeyOauth')}
            </span>
            <button
              type="button"
              className="model-list-view__key-eye"
              onClick={() => setKeyVisible((v) => !v)}
              title={keyVisible ? t('models.hideKey') : t('models.showKey')}
            >
              <span className={cx('codicon', keyVisible ? 'codicon-eye-closed' : 'codicon-eye')} />
            </button>
          </div>
        )}
        {/* 团队选中未配覆盖：实际按个人 key 计费（黄色提醒 + 直达配置） */}
        {provider.teamPlanNoOverride && (
          <div className="model-list-view__bill-warn" role="alert">
            <span className="codicon codicon-warning" />
            <span className="model-list-view__bill-warn-text">{t('models.teamNoOverrideWarn')}</span>
            <button type="button" className="model-list-view__bill-warn-btn" onClick={() => onEditKey?.(provider)}>
              {t('models.teamNoOverrideAction')}
            </button>
          </div>
        )}
        {/* 个人选中配了覆盖：客户端 key 未使用（歧义消解提示） */}
        {provider.overrideOnPersonal && (
          <div className="model-list-view__bill-note" role="status">
            <span className="codicon codicon-info" />
            <span>{t('models.overrideOnPersonalWarn')}</span>
          </div>
        )}
      </div>
      {provider.models.length > 0 ? (
        <div className="model-list-view__models">
          {provider.models.map((m) => (
            <ModelRow
              key={m.modelId}
              model={m}
              onDelete={
                onDeleteModel
                  ? () => {
                      // 最后一个模型不可单删：转弹窗提醒（2026-09-17 用户反馈 disabled 按钮无
                      // 悬停无反馈像坏了，改为可点+弹窗说明）
                      if (provider.models.length <= 1) onBlockedModelDelete?.(provider, m)
                      else onDeleteModel(provider, m)
                    }
                  : undefined
              }
              deleteBlockedTitle={
                onDeleteModel && provider.models.length <= 1 ? t('models.lastModelTitle') : undefined
              }
            />
          ))}
        </div>
      ) : (
        <div className="model-list-view__models-empty">{t('models.providerNoModels')}</div>
      )}
    </div>
  )
}

/** 新建/回填缺失时的默认上下文窗口（2026-09-17：主流模型普遍 1M，取代原 128K 默认）*/
const DEFAULT_CONTEXT = 1000000

/** ModelManageProvider → 编辑弹窗行（modelName 与 modelId 相同时不回填 label）*/
function providerToRows(p: ModelManageProvider): EditorModelRow[] {
  return p.models.map((m) => ({
    modelId: m.modelId,
    context: (m.contextWindow ?? DEFAULT_CONTEXT).toLocaleString('en-US'),
    output: m.maxOutput != null ? m.maxOutput.toLocaleString('en-US') : '',
    images: !!m.supportsImages,
    video: !!m.supportsVideo,
    pdf: !!m.supportsPdf,
  }))
}

/** ModelManageProvider → 编辑弹窗回填初始值（apiKey 取 config 源明文；oauth/覆盖源不回填）*/
function providerToInitial(p: ModelManageProvider) {
  return {
    name: p.providerName,
    kind: (p.kind === 'openai-compatible' ? 'openai-compatible' : 'anthropic') as 'anthropic' | 'openai-compatible',
    baseURL: p.baseURL ?? '',
    apiKey: p.activeKeySource === 'config' ? (p.activeKeyValue ?? '') : '',
    models: providerToRows(p),
  }
}

export function ModelListView() {
  const { t } = useTranslation()
  const providers = useStore((s) => s.modelProviders)
  const loading = useStore((s) => s.modelManageLoading)
  const error = useStore((s) => s.modelManageError)
  const configPath = useStore((s) => s.modelConfigPath)
  const newCli = useStore((s) => s.modelManageNewCli)
  const loadModelManage = useStore((s) => s.loadModelManage)
  const providerSaving = useStore((s) => s.providerSaving)
  const providerSaveError = useStore((s) => s.providerSaveError)
  const addModelProvider = useStore((s) => s.addModelProvider)
  const updateModelProvider = useStore((s) => s.updateModelProvider)
  const removeModelProvider = useStore((s) => s.removeModelProvider)
  const reorderModelProviders = useStore((s) => s.reorderModelProviders)
  const modelProvidersReordering = useStore((s) => s.modelProvidersReordering)

  const [query, setQuery] = useState('')
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null)
  /** 编辑弹窗目标：'add'=新增、provider=编辑、null=关闭 */
  const [editorTarget, setEditorTarget] = useState<'add' | ModelManageProvider | null>(null)
  const setProviderKey = useStore((s) => s.setProviderKey)
  // 自定义 key 对话框的受控草稿（configured=当前已配置，供"清除"语义提示）
  const [keyDraft, setKeyDraft] = useState<KeyDraft>({ value: '', configured: false })
  // 输入框明文切换（密码态常态，眼睛看全——与卡片激活 key 同模式）
  const [keyInputVisible, setKeyInputVisible] = useState(false)

  useEffect(() => {
    loadModelManage()
  }, [loadModelManage])

  // 保存成功（saving true→false 且无错误）自动关闭编辑弹窗；失败留在弹窗内提示
  const prevSavingRef = useRef(false)
  useEffect(() => {
    if (prevSavingRef.current && !providerSaving && !providerSaveError) setEditorTarget(null)
    prevSavingRef.current = providerSaving
  }, [providerSaving, providerSaveError])

  // 搜索过滤：provider 名/ID 直接命中保留整组；否则按模型名/ID 过滤组内条目
  const visible = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return providers ?? []
    return (providers ?? [])
      .map((p) => ({
        ...p,
        models: p.models.filter(
          (m) => m.modelId.toLowerCase().includes(q) || m.modelName.toLowerCase().includes(q),
        ),
      }))
      .filter(
        (p) =>
          p.models.length > 0 ||
          p.providerName.toLowerCase().includes(q) ||
          p.providerId.toLowerCase().includes(q),
      )
  }, [providers, query])

  const openConfig = () => {
    if (configPath) sendToJava({ op: 'openFile', filePath: configPath, line: 1 })
    setPendingAction(null)
  }

  const handleDeleteModel = (provider: ModelManageProvider, model: ModelManageModel) => {
    setPendingAction({ kind: 'deleteModel', provider, model })
  }

  const handleEditProvider = (provider: ModelManageProvider) => {
    // 打开即清残留错误（弹窗关闭期间的后台失败文案不带入新会话的弹窗）
    if (providerSaveError) useStore.setState({ providerSaveError: null })
    setEditorTarget(provider)
  }

  const handleDeleteProvider = (provider: ModelManageProvider) => {
    setPendingAction({ kind: 'removeProvider', provider })
  }

  // 最后一个模型的删除点击：弹窗说明（不触发真删，Kotlin 端 min-1 拒绝仅兜底）
  const handleBlockedModelDelete = (provider: ModelManageProvider, model: ModelManageModel) => {
    setPendingAction({ kind: 'lastModel', provider, model })
  }

  // 模型行删除 = 整表替换减一行（apiKey null=不变；name/baseURL/kind 传现值等价不变）
  const commitDeleteModel = () => {
    if (pendingAction?.kind !== 'deleteModel') return
    const { provider, model } = pendingAction
    const remaining = provider.models
      .filter((m) => m.modelId !== model.modelId)
      .map((m) => ({
        modelId: m.modelId,
        context: m.contextWindow ?? DEFAULT_CONTEXT,
        output: m.maxOutput,
        images: m.supportsImages || undefined,
        video: m.supportsVideo || undefined,
        pdf: m.supportsPdf || undefined,
      }))
    updateModelProvider(provider.providerId, {
      name: provider.providerName,
      kind: provider.kind === 'openai-compatible' ? 'openai-compatible' : 'anthropic',
      baseURL: provider.baseURL ?? '',
      apiKey: null,
      models: remaining,
    })
    setPendingAction(null)
  }

  const commitRemoveProvider = () => {
    if (pendingAction?.kind !== 'removeProvider') return
    removeModelProvider(pendingAction.provider.providerId)
    setPendingAction(null)
  }

  const commitEditor = (draft: ProviderSaveDraft) => {
    if (editorTarget && editorTarget !== 'add') updateModelProvider(editorTarget.providerId, draft)
    else addModelProvider(draft)
  }

  // 上移/下移排序：与相邻渠道交换后传完整顺序（v2 写 providerOrder）。
  // target 用 '__up__'/'__down__' 语义方向（箭头按钮在首/尾隐藏，越界天然不触发）
  const commitReorder = (source: string, dir: string) => {
    const list = (providers ?? []).map((p) => p.providerId)
    const from = list.indexOf(source)
    const to = dir === '__up__' ? from - 1 : dir === '__down__' ? from + 1 : -1
    if (from < 0 || to < 0 || to >= list.length) return
    list.splice(to, 0, list.splice(from, 1)[0])
    reorderModelProviders(list)
  }

  const openKeyEditor = (provider: ModelManageProvider) => {
    // 已存覆盖回填明文（本地手填值）；无覆盖开空表单
    setKeyDraft({ value: provider.customKeyValue ?? '', configured: !!provider.customKey })
    setPendingAction({ kind: 'key', providerId: provider.providerId, providerName: provider.providerName })
  }

  const commitKey = () => {
    if (pendingAction?.kind === 'key') {
      setProviderKey(pendingAction.providerId, keyDraft.value.trim())
    }
    setPendingAction(null)
  }

  // 一键清空：等价留空保存，但明确告诉用户清空后回到什么（客户端配置 key / OAuth）
  const commitClear = () => {
    if (pendingAction?.kind === 'key') {
      setProviderKey(pendingAction.providerId, '')
    }
    setPendingAction(null)
  }

  return (
    <div className="model-list-view">
      <div className="model-list-view__toolbar">
        <div className="model-list-view__toolbar-row">
          <span className="model-list-view__hint">
            <span className="codicon codicon-info" />
            {t('models.toolbarHint')}
          </span>
          {newCli && (
            <span className="model-list-view__gen-badge" title={t('models.genBadgeV2Hint')}>
              {t('models.genBadgeV2')}
            </span>
          )}
        </div>
        <div className="model-list-view__toolbar-row">
        <div className="model-list-view__search">
          <span className="codicon codicon-search" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t('models.searchPlaceholder')}
            spellCheck={false}
          />
          {query && (
            <button
              className="model-list-view__search-clear"
              onClick={() => setQuery('')}
              title={t('models.searchClear')}
            >
              <span className="codicon codicon-close" />
            </button>
          )}
        </div>
        <button
          className="model-list-view__refresh"
          onClick={() => loadModelManage()}
          disabled={loading}
          title={t('models.refreshTitle')}
        >
          <span className={cx('codicon', loading ? 'codicon-loading spin' : 'codicon-refresh')} />
        </button>
        <button
          className="model-list-view__add"
          onClick={() => {
            if (providerSaveError) useStore.setState({ providerSaveError: null })
            setEditorTarget('add')
          }}
        >
          <span className="codicon codicon-add" />
          {t('models.add')}
        </button>
        </div>
      </div>

      {configPath && (
        <div
          className="model-list-view__config-path"
          onClick={openConfig}
          title={t('models.configPathOpenTitle')}
        >
          <span className="codicon codicon-file-code" />
          <span className="model-list-view__config-label">{t('models.configPathLabel')}</span>
          <span className="model-list-view__config-value">{configPath}</span>
          <span className="codicon codicon-go-to-file" />
          <button
            className="model-list-view__config-action"
            onClick={(e) => {
              // 阻断路径条整体的「编辑器打开」，只做资源管理器定位
              e.stopPropagation()
              sendToJava({ op: 'revealInFileManager', path: configPath })
            }}
            title={t('models.configPathRevealTitle')}
          >
            <span className="codicon codicon-folder" />
          </button>
        </div>
      )}

      {error && <div className="model-list-view__error">{t('models.errorLoad', { error })}</div>}

      {loading && !providers ? (
        <div className="model-list-view__loading">
          <span className="codicon codicon-loading spin" /> {t('models.loading')}
        </div>
      ) : visible.length === 0 ? (
        <div className="model-list-view__empty">
          <span className="codicon codicon-server-process" />
          <span>{t('models.empty')}</span>
          <span className="model-list-view__empty-hint">{t('models.emptyHint')}</span>
        </div>
      ) : (
        <div className="model-list-view__list">
          {/* 内置渠道区：只读展示生效渠道（启停以 ZCode 客户端配置为准，禁用不展示）*/}
          {visible.some((p) => p.providerId.startsWith('builtin:')) && (
            <div className="model-list-view__section">
              <span className="model-list-view__section-title">{t('models.section.builtin')}</span>
              <span className="model-list-view__section-hint">{t('models.section.builtinHint')}</span>
            </div>
          )}
          {visible
            .filter((p) => p.providerId.startsWith('builtin:'))
            .map((p) => (
              <ProviderCard
                key={p.providerId}
                provider={p}
                builtin
                onEditKey={openKeyEditor}
              />
            ))}

          {/* 自定义供应商区：插件内增删改 + 独立启停 + 上移/下移排序（v2 写 providerOrder）。
              拖拽方案废弃（缺陷BX）：draggable 挂整卡与卡内 toggle/按钮点击冲突，按下即灰 */}
          {visible.some((p) => !p.providerId.startsWith('builtin:')) && (
            <div className="model-list-view__section">
              <span className="model-list-view__section-title">{t('models.section.custom')}</span>
              {newCli && (
                <span className="model-list-view__section-hint">{t('models.section.reorderHint')}</span>
              )}
            </div>
          )}
          {visible
            .filter((p) => !p.providerId.startsWith('builtin:'))
            .map((p, idx, arr) => (
              <ProviderCard
                key={p.providerId}
                provider={p}
                onDeleteModel={handleDeleteModel}
                onBlockedModelDelete={handleBlockedModelDelete}
                onEditProvider={handleEditProvider}
                onDeleteProvider={handleDeleteProvider}
                onMove={newCli ? commitReorder : undefined}
                moveDisabled={modelProvidersReordering}
                isFirst={idx === 0}
                isLast={idx === arr.length - 1}
              />
            ))}
        </div>
      )}

      {pendingAction?.kind === 'key' && (
        <ConfirmDialog
          title={t('models.customKeyTitle')}
          message={
            <div className="model-list-view__dialog-body">
              <p>
                {t('models.customKeyHint', { provider: pendingAction.providerName })}
              </p>
              <div className="model-list-view__key-input-wrap">
                <input
                  className="model-list-view__key-input"
                  type={keyInputVisible ? 'text' : 'password'}
                  value={keyDraft.value}
                  onChange={(e) => setKeyDraft({ ...keyDraft, value: e.target.value })}
                  placeholder={t('models.customKeyPlaceholder')}
                  spellCheck={false}
                  autoFocus
                />
                <button
                  type="button"
                  className="model-list-view__key-eye"
                  onClick={() => setKeyInputVisible((v) => !v)}
                  title={keyInputVisible ? t('models.hideKey') : t('models.showKey')}
                >
                  <span className={cx('codicon', keyInputVisible ? 'codicon-eye-closed' : 'codicon-eye')} />
                </button>
              </div>
              <p className="model-list-view__key-sub">
                {keyDraft.value.trim() === ''
                  ? keyDraft.configured
                    ? t('models.customKeyClearHint')
                    : t('models.customKeyKeepHint')
                  : t('models.customKeyApplyHint')}
              </p>
              {keyDraft.configured && (
                <div className="model-list-view__key-clear-row">
                  <button type="button" className="model-list-view__key-clear-btn" onClick={commitClear}>
                    {t('models.customKeyClearBtn')}
                  </button>
                  <span className="model-list-view__key-clear-note">{t('models.customKeyClearNote')}</span>
                </div>
              )}
            </div>
          }
          confirmText={t('models.customKeySave')}
          cancelText={t('models.dialog.dismiss')}
          onConfirm={commitKey}
          onCancel={() => setPendingAction(null)}
        />
      )}

      {/* 最后一个模型删除点击：纯提示弹窗（不提供删除动作）*/}
      {pendingAction?.kind === 'lastModel' && (
        <ConfirmDialog
          title={t('models.lastModelTitle')}
          message={t('models.lastModelBody', {
            name: pendingAction.model.modelName,
            provider: pendingAction.provider.providerName,
          })}
          confirmText={t('models.dialog.dismiss')}
          cancelable={false}
          onConfirm={() => setPendingAction(null)}
          onCancel={() => setPendingAction(null)}
        />
      )}

      {/* 自定义渠道：模型行删除确认（danger，整表替换减一行）*/}
      {pendingAction?.kind === 'deleteModel' && (
        <ConfirmDialog
          title={t('models.dialog.deleteTitle')}
          message={t('models.dialog.deleteModelBody', {
            name: pendingAction.model.modelName,
            provider: pendingAction.provider.providerName,
          })}
          confirmText={t('models.dialog.deleteConfirm')}
          cancelText={t('models.dialog.dismiss')}
          danger
          onConfirm={commitDeleteModel}
          onCancel={() => setPendingAction(null)}
        />
      )}

      {/* 自定义渠道删除确认（danger，含全部模型）*/}
      {pendingAction?.kind === 'removeProvider' && (
        <ConfirmDialog
          title={t('models.dialog.removeProviderTitle')}
          message={t('models.dialog.removeProviderBody', {
            provider: pendingAction.provider.providerName,
            count: pendingAction.provider.models.length,
          })}
          confirmText={t('models.dialog.deleteConfirm')}
          cancelText={t('models.dialog.dismiss')}
          danger
          onConfirm={commitRemoveProvider}
          onCancel={() => setPendingAction(null)}
        />
      )}

      {/* 添加 / 编辑自定义渠道（保存成功自动关闭，失败弹窗内提示）*/}
      {editorTarget && (
        <ProviderEditorDialog
          mode={editorTarget === 'add' ? 'add' : 'edit'}
          initial={editorTarget === 'add' ? null : providerToInitial(editorTarget)}
          saving={providerSaving}
          error={providerSaveError}
          onConfirm={commitEditor}
          onCancel={() => {
            setEditorTarget(null)
            // 弹窗关闭即清残留错误，避免下次打开闪现上次的失败文案
            if (providerSaveError) useStore.setState({ providerSaveError: null })
          }}
        />
      )}
    </div>
  )
}
