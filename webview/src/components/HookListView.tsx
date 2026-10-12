/**
 * 钩子管理页（设置页「钩子」tab，形态对齐官方 ZCode 客户端）
 *
 * 数据：op=workspaceHooksList（Kotlin 起一次性 `zcode hooks trust status`
 *   子进程，单次约 1s：node 冷启动 + 加载 zcode.cjs bundle，无热加速）。
 * 动作：
 *   - 信任：grant 单条 digest（Kotlin grant+status 两次调用约 2s）
 *   - 启停：toggle（Kotlin 写 .zcode/config.json + status 约 1s）
 *   未通过信任审核（trustState !== trusted_persistent）的钩子开关强制关闭并禁用，
 *   必须先信任（与官方客户端同一判定）。
 * 体验：CLI 子进程约 1~2s 是结构性延迟（官方是进程内服务才瞬发），
 *   故采用乐观更新——点击瞬间翻转，列表响应到达后按权威 status 对账，
 *   error 响应回退到上一份权威数据并报错（busy 全局串行，防 config 读-改-写竞争）。
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { onMessage, sendToJava } from '@/ipc/bridge'
import { useStore } from '@/store/useStore'
import type {
  JavaResponse,
  WorkspaceHooksStatus,
  WorkspaceHooksStatusItem,
} from '@/types/messages'
import '../styles/hook-list-view.less'

const TRUSTED_STATE = 'trusted_persistent'

/** 权威行 + 本地乐观覆盖（信任/启停）合成的视图模型 */
interface RowView {
  item: WorkspaceHooksStatusItem
  trusted: boolean
  enabled: boolean
}

/** 需要信任审核：非 trusted_persistent 一律算（pending/blocked/revoked/stale） */
function requiresTrust(state: string): boolean {
  return state !== TRUSTED_STATE
}

/** 工作区 basename（显示用） */
function workspaceBasename(p: string): string {
  const norm = p.replace(/\\/g, '/').replace(/\/+$/, '')
  return norm.slice(norm.lastIndexOf('/') + 1)
}

export function HookListView() {
  const { t } = useTranslation()
  const workspace = useStore((s) => s.currentWorkspacePath)
  const [status, setStatus] = useState<WorkspaceHooksStatus | null>(null)
  const [query, setQuery] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  /** 乐观覆盖：reviewItemId → enabled；权威 status 到达后清空 */
  const [enabledOverride, setEnabledOverride] = useState<Record<string, boolean>>({})
  /** 乐观信任：点击信任后到权威响应前按已信任渲染 */
  const [trustOverride, setTrustOverride] = useState<Set<string>>(new Set())

  const refresh = useCallback(() => {
    if (!workspace) return
    setBusy(true)
    setError('')
    sendToJava({ op: 'workspaceHooksList', workspace })
  }, [workspace])

  useEffect(() => {
    const off = onMessage((msg: JavaResponse) => {
      if (msg.op === 'workspaceHooksList') {
        setStatus(msg.status)
        setBusy(false)
        // 权威数据到达：乐观覆盖全部清账
        setEnabledOverride({})
        setTrustOverride(new Set())
      } else if (msg.op === 'error') {
        // 失败回退：丢弃乐观覆盖，界面回到上一份权威 status（busy 仅属于列表加载）
        setEnabledOverride({})
        setTrustOverride(new Set())
        setError(msg.message)
      }
    })
    refresh()
    return off
  }, [refresh])

  /** 权威行叠加乐观覆盖；搜索过滤在合成视图上做 */
  const rows = useMemo<RowView[]>(() => {
    const q = query.trim().toLowerCase()
    return (status?.items ?? [])
      .map((item) => {
        const trusted = trustOverride.has(item.reviewItemId) || item.trustState === TRUSTED_STATE
        const enabled = enabledOverride[item.reviewItemId] ?? item.configuredEnabled
        return { item, trusted, enabled }
      })
      .filter((v) => {
        if (!q) return true
        return (
          v.item.event.toLowerCase().includes(q) ||
          v.item.displayCommand.toLowerCase().includes(q) ||
          (v.item.matcher ?? '').toLowerCase().includes(q)
        )
      })
  }, [status, query, trustOverride, enabledOverride])

  /** 信任：乐观置信任态（按钮消失、开关立即可用并呈当前配置态），响应对账；
   *  并发安全由 Kotlin WorkspaceHooksCli 锁保证，行控件不做 busy 禁用 */
  const grant = (it: WorkspaceHooksStatusItem) => {
    setError('')
    setTrustOverride((prev) => new Set(prev).add(it.reviewItemId))
    sendToJava({
      op: 'workspaceHooksGrant',
      workspace,
      hookDigests: [it.hookDeclarationDigest],
    })
  }

  /** 启停：乐观翻转；未信任行不允许（UI 已禁用，双保险） */
  const toggleEnabled = (v: RowView) => {
    if (!v.trusted) return
    setError('')
    setEnabledOverride((prev) => ({ ...prev, [v.item.reviewItemId]: !v.enabled }))
    sendToJava({
      op: 'workspaceHooksToggle',
      workspace,
      reviewItemId: v.item.reviewItemId,
      sourcePath: v.item.sourcePath,
      enabled: !v.enabled,
    })
  }

  if (!workspace) {
    return <div className="hook-list-empty">{t('hooks.noWorkspace')}</div>
  }

  const hasUntrusted = (status?.items ?? []).some((it) => requiresTrust(it.trustState))

  return (
    <div className="hook-list">
      {/* 工具栏：工作区标识 + 搜索 + 刷新 */}
      <div className="hook-list__toolbar">
        <span className="hook-list__workspace">
          <span className="codicon codicon-folder" />
          {workspaceBasename(workspace)}
        </span>
        <span className="hook-list__divider" />
        <span className="hook-list__count">
          {t('hooks.titleCount', { count: status?.items.length ?? 0 })}
        </span>
        <div className="hook-list__search">
          <span className="codicon codicon-search" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t('hooks.searchPlaceholder')}
            spellCheck={false}
          />
        </div>
        <button
          type="button"
          className="hook-list__refresh"
          onClick={refresh}
          disabled={busy}
          title={t('hooks.refresh')}
        >
          <span className="codicon codicon-refresh" />
        </button>
      </div>

      {/* 安全提示：存在任意未通过信任审核的工作区钩子时显示（与官方同一判定；乐观信任不影响它） */}
      {hasUntrusted && (
        <div className="hook-list__notice" role="note">
          <span className="codicon codicon-warning" />
          <span>{t('hooks.sandboxNotice')}</span>
        </div>
      )}

      {error && (
        <div className="hook-list__error">
          <span>⚠️ {error}</span>
        </div>
      )}

      {/* 标题行 */}
      <div className="hook-list__header-row">
        <span className="hook-list__installed">
          {t('hooks.installed', { count: rows.length })}
        </span>
      </div>

      {/* 钩子列表 */}
      {rows.length === 0 ? (
        <div className="hook-list-empty">{busy ? t('hooks.loading') : t('hooks.empty')}</div>
      ) : (
        <div className="hook-list__cards">
          {rows.map((v) => {
            const { item: it, trusted, enabled } = v
            return (
              <div key={it.reviewItemId} className="hook-row">
                <div className="hook-row__main">
                  <div className="hook-row__top">
                    <span className="hook-row__name">
                      {it.event}
                      {it.matcher ? <span className="hook-row__matcher"> · {it.matcher}</span> : null}
                    </span>
                    <HookTrustBadge trusted={trusted} state={it.trustState} />
                  </div>
                  {/* 钩子条目的入口：当前版本=在 IDE 打开命令对应的钩子脚本文件
                      （Kotlin 从 displayCommand 解析，失败回退来源配置）；
                      后续新建/编辑页版本在此分流：打开脚本 vs 内置编辑页 */}
                  <button
                    type="button"
                    className="hook-row__command"
                    title={t('hooks.openScript')}
                    onClick={() => sendToJava({
                      op: 'workspaceHooksOpenScript',
                      workspace,
                      displayCommand: it.displayCommand,
                      sourcePath: it.sourcePath,
                    })}
                  >
                    <code>{it.displayCommand}</code>
                  </button>
                </div>
                {/* 未信任：显示单条信任按钮，开关强制关闭并禁用 */}
                {!trusted && (
                  <button
                    type="button"
                    className="hook-row__trust"
                    onClick={() => grant(it)}
                  >
                    <span className="codicon codicon-shield" />
                    {t('hooks.trust')}
                  </button>
                )}
                <button
                  type="button"
                  className={`hook-switch ${trusted && enabled ? 'hook-switch--on' : ''}`}
                  role="switch"
                  aria-checked={trusted && enabled}
                  disabled={!trusted}
                  onClick={() => toggleEnabled(v)}
                  title={!trusted ? t('hooks.trustFirst') : (enabled ? t('hooks.disable') : t('hooks.enable'))}
                >
                  <span className="hook-switch__knob" />
                </button>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

/** 信任状态徽标（颜色按状态；乐观信任瞬间直接呈已信任） */
function HookTrustBadge({ state, trusted }: { state: string; trusted: boolean }) {
  const { t } = useTranslation()
  const key: Record<string, string> = {
    trusted_persistent: 'stateTrusted',
    pending_trust: 'stateUntrusted',
    blocked_untrusted: 'stateUntrusted',
    revoked: 'stateRevoked',
    stale_digest: 'stateStale',
  }
  const labelKey = trusted ? 'stateTrusted' : (key[state] ?? 'stateUntrusted')
  return (
    <span className={`hook-badge hook-badge--${labelKey}`}>
      {/* 信任/未信任不用图标，纯文字徽标；撤销/过期加符号（乐观信任期间不显示旧符号） */}
      {!trusted && state === 'revoked' && '✕ '}
      {!trusted && state === 'stale_digest' && '⚠ '}
      {t(`hooks.${labelKey}`)}
    </span>
  )
}
