/**
 * Node 进程管理视图（设置页「进程」条目）：
 * 区块结构对齐 browser-settings（section 标题 + action-row 卡片 + 底部 hint）。
 * 常驻 app-server 卡内嵌子进程行（plugin-host/无头浏览器等），疑似孤立独立分区
 * 警告色呈现。kill 走 Kotlin 所有权守卫（pid 必须在最新快照内）；常驻/子进程
 * 结束前二次确认（打断运行中会话），孤立直接结束——快速清理正是本功能用途。
 * 打开拉一次 + 手动刷新 + kill 后补拉，无后台轮询。
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { onMessage, sendToJava } from '@/ipc/bridge'
import type { JavaResponse, NodeProcessInfo } from '@/types/messages'
import { ConfirmDialog } from './ConfirmDialog'
import '../styles/process-settings.less'

const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(' ')

/** 运行时长格式化（"45s" / "2m 45s" / "3h 05m" / "2d 4h"），纯函数单测覆盖 */
export function formatUptime(startedAt: number | undefined, now: number): string {
  if (!startedAt || startedAt <= 0 || now < startedAt) return '—'
  let s = Math.floor((now - startedAt) / 1000)
  const d = Math.floor(s / 86400)
  s -= d * 86400
  const h = Math.floor(s / 3600)
  s -= h * 3600
  const m = Math.floor(s / 60)
  const sec = s - m * 60
  if (d > 0) return `${d}d ${h}h`
  if (h > 0) return `${h}h ${String(m).padStart(2, '0')}m`
  if (m > 0) return `${m}m ${sec}s`
  return `${sec}s`
}

type KillTarget = { kind: 'server' | 'child'; info: NodeProcessInfo } | { kind: 'allOrphans'; count: number }

export function ProcessSettingsView() {
  const { t } = useTranslation()
  const [processes, setProcesses] = useState<NodeProcessInfo[]>([])
  const [loading, setLoading] = useState(true)
  const [now, setNow] = useState(() => Date.now())
  const [killingPid, setKillingPid] = useState<number | null>(null)
  const [confirming, setConfirming] = useState<KillTarget | null>(null)
  const [feedback, setFeedback] = useState<{ ok: boolean; text: string } | null>(null)
  // 旧快照防复活：kill 后的补拉与手动刷新并发时只认更新的 snapshotAt
  const seenSnapshotAt = useRef(0)

  const fetchProcesses = useCallback(() => {
    setLoading(true)
    sendToJava({ op: 'getNodeProcesses' })
  }, [])

  const kill = useCallback((pid: number) => {
    setKillingPid(pid)
    sendToJava({ op: 'killNodeProcess', pid })
  }, [])

  useEffect(() => {
    fetchProcesses()
    // 运行时长局部跳动（15s 粒度足够，不追求秒级刷新整树重渲）
    const ticker = window.setInterval(() => setNow(Date.now()), 15_000)
    const unsub = onMessage((msg: JavaResponse) => {
      if (msg.op === 'nodeProcesses') {
        if (msg.snapshotAt && msg.snapshotAt <= seenSnapshotAt.current) return
        seenSnapshotAt.current = msg.snapshotAt
        setProcesses(msg.processes)
        setLoading(false)
      } else if (msg.op === 'nodeProcessKillResult') {
        setKillingPid(null)
        setFeedback(
          msg.ok
            ? { ok: true, text: t('settings.processesView.killed', { pid: msg.pid }) }
            : { ok: false, text: t('settings.processesView.killFailed', { error: msg.error ?? '' }) },
        )
        // kill 同步回执后进程树消亡可能滞后一拍：立即补拉 + 1.2s 后再补一拍
        fetchProcesses()
        window.setTimeout(fetchProcesses, 1200)
      }
    })
    return () => {
      window.clearInterval(ticker)
      unsub()
    }
  }, [fetchProcesses, kill, t])

  const servers = processes.filter((p) => p.kind === 'appServer')
  const children = processes.filter((p) => p.kind === 'descendant')
  const orphans = processes.filter((p) => p.kind === 'orphan')
  const childrenByParent = new Map<number, NodeProcessInfo[]>()
  for (const c of children) {
    if (c.parentPid == null) continue
    const list = childrenByParent.get(c.parentPid) ?? []
    list.push(c)
    childrenByParent.set(c.parentPid, list)
  }
  const orphanChildren = children.filter((c) => c.parentPid == null || !servers.some((s) => s.pid === c.parentPid))

  const doKill = (info: NodeProcessInfo) => {
    if (info.kind === 'orphan') {
      kill(info.pid) // 孤立进程免确认：快速清理正是本功能用途
    } else {
      setConfirming({ kind: info.kind === 'appServer' ? 'server' : 'child', info })
    }
  }

  const renderMeta = (p: NodeProcessInfo, withProcess = false) => (
    <span className="process-view__meta">
      {withProcess && p.process ? `${p.process} · ` : ''}PID {p.pid} · {formatUptime(p.startedAt, now)}
    </span>
  )

  return (
    <div className="process-view">
      {/* 常驻进程（各项目 app-server + 直接子进程） */}
      <section className="process-view__section">
        <div className="process-view__section-head">
          <h3 className="process-view__section-title">
            {t('settings.processesView.groupAppServer')}
            {servers.length > 0 && <span className="process-view__count">{servers.length}</span>}
          </h3>
          <button
            className="process-view__icon-btn"
            data-tooltip={t('settings.processesView.refresh')}
            onClick={fetchProcesses}
          >
            <span className={cx('codicon', loading ? 'codicon-loading spin' : 'codicon-refresh')} />
          </button>
        </div>

        {!loading && servers.length === 0 && orphans.length === 0 && (
          <div className="process-view__empty">{t('settings.processesView.empty')}</div>
        )}

        {servers.map((s) => (
          <div key={s.pid} className="process-view__card">
            <div className="process-view__row">
              <span className="process-view__tile">
                <span className="codicon codicon-vm" />
              </span>
              <div className="process-view__body">
                <div className="process-view__name-row">
                  <span className="process-view__name">{s.project || s.label}</span>
                  {s.role === 'enhance' && <em className="process-view__badge">{t('settings.processesView.roleEnhance')}</em>}
                </div>
                {renderMeta(s, true)}
              </div>
              <button
                className="process-view__btn process-view__btn--danger"
                disabled={killingPid != null}
                onClick={() => doKill(s)}
              >
                <span className={cx('codicon', killingPid === s.pid ? 'codicon-loading spin' : 'codicon-close')} />
                {t('settings.processesView.killShort')}
              </button>
            </div>
            {(childrenByParent.get(s.pid) ?? []).map((c) => (
              <div key={c.pid} className="process-view__row process-view__row--child">
                <span className="process-view__tile process-view__tile--small">
                  <span className="codicon codicon-terminal" />
                </span>
                <div className="process-view__body">
                  <span className="process-view__name process-view__name--child">{c.label}</span>
                  {renderMeta(c)}
                </div>
                <button
                  className="process-view__icon-btn process-view__icon-btn--kill"
                  data-tooltip={t('settings.processesView.kill')}
                  disabled={killingPid != null}
                  onClick={() => doKill(c)}
                >
                  <span className={cx('codicon', killingPid === c.pid ? 'codicon-loading spin' : 'codicon-close')} />
                </button>
              </div>
            ))}
          </div>
        ))}

        {orphanChildren.length > 0 && (
          <div className="process-view__card">
            {orphanChildren.map((c) => (
              <div key={c.pid} className="process-view__row process-view__row--child">
                <span className="process-view__tile process-view__tile--small">
                  <span className="codicon codicon-terminal" />
                </span>
                <div className="process-view__body">
                  <span className="process-view__name process-view__name--child">
                    {c.label}
                    {c.parentPid != null && <span className="process-view__meta"> ← #{c.parentPid}</span>}
                  </span>
                  {renderMeta(c)}
                </div>
                <button
                  className="process-view__icon-btn process-view__icon-btn--kill"
                  data-tooltip={t('settings.processesView.kill')}
                  disabled={killingPid != null}
                  onClick={() => doKill(c)}
                >
                  <span className={cx('codicon', killingPid === c.pid ? 'codicon-loading spin' : 'codicon-close')} />
                </button>
              </div>
            ))}
          </div>
        )}
      </section>

      {feedback && (
        <div className={cx('process-view__feedback', feedback.ok ? 'ok' : 'err')}>
          <span className={cx('codicon', feedback.ok ? 'codicon-pass' : 'codicon-error')} />
          <span>{feedback.text}</span>
        </div>
      )}

      {/* 疑似孤立（仅存在时出现；警告色分区） */}
      {orphans.length > 0 && (
        <section className="process-view__section">
          <div className="process-view__section-head">
            <h3 className="process-view__section-title process-view__section-title--warn">
              {t('settings.processesView.groupOrphan')}
              <span className="process-view__count process-view__count--warn">{orphans.length}</span>
            </h3>
            {orphans.length > 1 && (
              <button
                className="process-view__btn process-view__btn--danger process-view__btn--ghost"
                onClick={() => setConfirming({ kind: 'allOrphans', count: orphans.length })}
              >
                <span className="codicon codicon-clear-all" />
                {t('settings.processesView.killAll')}
              </button>
            )}
          </div>
          <small className="process-view__hint process-view__hint--warn">
            <span className="codicon codicon-warning" />
            <span>{t('settings.processesView.orphanNote')}</span>
          </small>
          {orphans.map((p) => (
            <div key={p.pid} className="process-view__card process-view__card--orphan">
              <div className="process-view__row">
                <span className="process-view__tile process-view__tile--warn">
                  <span className="codicon codicon-bug" />
                </span>
                <div className="process-view__body">
                  <span className="process-view__name">{p.label}</span>
                  <span className="process-view__meta process-view__cmdline" title={p.commandLine}>
                    {p.commandLine}
                  </span>
                </div>
                <button
                  className="process-view__btn process-view__btn--danger"
                  disabled={killingPid != null}
                  onClick={() => doKill(p)}
                >
                  <span className={cx('codicon', killingPid === p.pid ? 'codicon-loading spin' : 'codicon-clear-all')} />
                  {t('settings.processesView.cleanShort')}
                </button>
              </div>
            </div>
          ))}
        </section>
      )}

      <small className="process-view__hint">
        <span className="codicon codicon-info" />
        <span>{t('settings.processesView.desc')}</span>
      </small>

      {confirming && confirming.kind !== 'allOrphans' && (
        <ConfirmDialog
          title={confirming.kind === 'server' ? t('settings.processesView.killServerTitle') : t('settings.processesView.killChildTitle')}
          message={
            confirming.kind === 'server'
              ? t('settings.processesView.killServerMsg', { label: confirming.info.project ?? confirming.info.label, pid: confirming.info.pid })
              : t('settings.processesView.killChildMsg', { pid: confirming.info.pid })
          }
          confirmText={t('settings.processesView.killShort')}
          danger
          onConfirm={() => {
            kill(confirming.info.pid)
            setConfirming(null)
          }}
          onCancel={() => setConfirming(null)}
        />
      )}
      {confirming?.kind === 'allOrphans' && (
        <ConfirmDialog
          title={t('settings.processesView.killAllTitle')}
          message={t('settings.processesView.killAllMsg', { count: confirming.count })}
          confirmText={t('settings.processesView.killAll')}
          danger
          onConfirm={() => {
            processes.filter((p) => p.kind === 'orphan').forEach((p) => kill(p.pid))
            setConfirming(null)
          }}
          onCancel={() => setConfirming(null)}
        />
      )}
    </div>
  )
}
