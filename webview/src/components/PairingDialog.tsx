import { useEffect, useState } from 'react'
import QRCode from 'qrcode'
import { useStore } from '@/store/useStore'
import { useTranslation } from 'react-i18next'
import { copyText, useCopyFeedback } from '@/utils/clipboard'
import '@/styles/pairing-dialog.less'

/**
 * 手机远程配对弹窗（对齐 ZCode 客户端「移动远程控制」布局）：
 * 状态卡（连接状态独立展示+主操作）→ 备用链接卡 → 恒定 QR 区。
 * QR URL 内嵌 passHash（设备配对凭据），仅在弹窗内渲染展示，不落日志不外传。
 */
export function PairingDialog() {
  const { t } = useTranslation()
  const open = useStore((s) => s.remotePairingOpen)
  const state = useStore((s) => s.remoteState)
  const qrUrl = useStore((s) => s.remoteQrUrl)
  const deviceName = useStore((s) => s.remoteDeviceName)
  const error = useStore((s) => s.remoteError)
  const close = useStore((s) => s.closeRemotePairing)
  const refresh = useStore((s) => s.refreshRemoteState)
  const stop = useStore((s) => s.stopRemote)
  const unpair = useStore((s) => s.unpairRemote)

  const [qrSvg, setQrSvg] = useState<string | null>(null)
  const [confirmingUnpair, setConfirmingUnpair] = useState(false)
  const copy = useCopyFeedback()

  useEffect(() => {
    if (open) {
      refresh()
      setConfirmingUnpair(false)
    }
  }, [open, refresh])

  useEffect(() => {
    let cancelled = false
    if (qrUrl) {
      QRCode.toString(qrUrl, { type: 'svg', margin: 1, width: 232, errorCorrectionLevel: 'M' })
        .then((svg) => { if (!cancelled) setQrSvg(svg) })
        .catch(() => { if (!cancelled) setQrSvg(null) })
    } else {
      setQrSvg(null)
    }
    return () => { cancelled = true }
  }, [qrUrl])

  // 打开状态下周期刷新状态（waiting→paired 的转正靠广播，此处兜底重连等场景）
  useEffect(() => {
    if (!open) return
    const timer = window.setInterval(() => refresh(), 5000)
    return () => window.clearInterval(timer)
  }, [open, refresh])

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, close])

  if (!open) return null

  const stateText = () => {
    switch (state) {
      case 'connecting': return t('remote.state.connecting')
      case 'waiting': return t('remote.state.waiting')
      case 'paired': return t('remote.state.paired')
      case 'error': return t('remote.state.error')
      case 'kicked': return t('remote.state.kicked')
      default: return t('remote.state.off')
    }
  }

  // 状态卡主操作：运行中（connecting/waiting/paired）→ 停止/断开；静止态 → 重新连接
  const running = state === 'connecting' || state === 'waiting' || state === 'paired'
  const primaryLabel = running
    ? state === 'paired' ? t('remote.action.disconnect') : t('remote.action.stop')
    : t('remote.action.reconnect')
  const primaryAction = () => {
    if (running) { stop(); setConfirmingUnpair(false) }
    else { setConfirmingUnpair(false); useStore.getState().openRemotePairing() }
  }

  // 状态卡描述行：error 显示错误详情，其余按状态给引导语
  const cardDesc = () => {
    if (state === 'error' && error) return error
    switch (state) {
      case 'waiting': return t('remote.card.waitingDesc')
      case 'paired': return t('remote.hint.paired')
      case 'connecting': return t('remote.hint.connecting')
      default: return t('remote.card.offDesc')
    }
  }

  // QR 恒定区块：有码渲染码，无码（connecting/off/error）给占位说明
  const qrPlaceholder = state === 'connecting' ? t('remote.hint.connecting') : t('remote.hint.noQr')

  return (
    <div className="pairing-overlay" onClick={close}>
      <div className="pairing-dialog" onClick={(e) => e.stopPropagation()}>
        <div className="pairing-dialog__header">
          <span className="codicon codicon-remote" />
          <span className="pairing-dialog__title">{t('remote.title')}</span>
          <button className="icon-button" onClick={close} data-tooltip={t('remote.close')}>
            <span className="codicon codicon-close" />
          </button>
        </div>

        <div className="pairing-dialog__body">
          <div className={`pairing-card pairing-card--${state}`}>
            <div className="pairing-card__main">
              <div className="pairing-card__title-row">
                <span className="pairing-card__state">{stateText()}</span>
                <span className={`pairing-card__dot pairing-card__dot--${state}`} />
                {deviceName && <span className="pairing-card__device" title={deviceName}>{deviceName}</span>}
              </div>
              <div className="pairing-card__desc">{cardDesc()}</div>
            </div>
            <button className="pairing-card__action" onClick={primaryAction}>
              <span className={`codicon ${running ? 'codicon-debug-stop' : 'codicon-play'}`} />
              {primaryLabel}
            </button>
          </div>

          <div className="pairing-fallback">
            <div className="pairing-fallback__title">{t('remote.fallback.title')}</div>
            <div className="pairing-fallback__actions">
              <button className="pairing-fallback__btn" onClick={() => useStore.getState().refreshRemoteQr()}>
                <span className="codicon codicon-refresh" />
                {t('remote.action.refreshQr')}
              </button>
              <button
                className={`pairing-fallback__btn${copy.state === 'ok' ? ' pairing-fallback__btn--ok' : ''}${copy.state === 'fail' ? ' pairing-fallback__btn--fail' : ''}`}
                onClick={() => { if (qrUrl) copy.showResult(() => copyText(qrUrl)) }}
                disabled={!qrUrl}
              >
                <span className={`codicon ${copy.state === 'ok' ? 'codicon-check' : copy.state === 'fail' ? 'codicon-close' : 'codicon-copy'}`} />
                {copy.state === 'ok' ? t('remote.action.copied') : t('remote.action.copyLink')}
              </button>
            </div>
          </div>

          <div className="pairing-qr-panel">
            {qrSvg ? (
              <div className="pairing-qr-panel__code" dangerouslySetInnerHTML={{ __html: qrSvg }} />
            ) : (
              <div className="pairing-qr-panel__placeholder">
                <span className="codicon codicon-plug" />
                {qrPlaceholder}
              </div>
            )}
            <div className="pairing-qr-panel__note">{t('remote.hint.secret')}</div>
          </div>
        </div>

        <div className="pairing-dialog__footer">
          <button
            className={`pairing-dialog__btn ${confirmingUnpair ? 'pairing-dialog__btn--danger-confirm' : 'pairing-dialog__btn--ghost'}`}
            onClick={() => { if (confirmingUnpair) { unpair(); setConfirmingUnpair(false) } else setConfirmingUnpair(true) }}
          >
            {confirmingUnpair ? t('remote.action.unpairConfirm') : t('remote.action.unpair')}
          </button>
        </div>
      </div>
    </div>
  )
}
