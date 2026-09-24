/**
 * 手机远程设置视图（设置页「手机远程」条目）：
 * 连接状态卡（off/connecting/waiting/paired/error/kicked）+ 连接/断开操作 +
 * 解除配对（清凭据，下次需重新扫码）。扫码配对的 QR 展示在 Header 手机图标的
 * 弹窗（PairingDialog），这里做状态与生命周期管理。
 */
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useStore } from '@/store/useStore'
import { ConfirmDialog } from './ConfirmDialog'
import '../styles/remote-settings.less'

const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(' ')

export function RemoteSettingsView() {
  const { t } = useTranslation()
  const state = useStore((s) => s.remoteState)
  const error = useStore((s) => s.remoteError)
  const refresh = useStore((s) => s.refreshRemoteState)
  const openPairing = useStore((s) => s.openRemotePairing)
  const stopRemote = useStore((s) => s.stopRemote)
  const unpairRemote = useStore((s) => s.unpairRemote)
  const [confirmingUnpair, setConfirmingUnpair] = useState(false)

  useEffect(() => {
    refresh()
    const timer = window.setInterval(() => refresh(), 5000)
    return () => window.clearInterval(timer)
  }, [refresh])

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

  const active = state === 'waiting' || state === 'paired' || state === 'connecting'

  return (
    <div className="remote-settings">
      <div className={cx('remote-settings__status', `remote-settings__status--${state}`)}>
        <span className={cx('remote-settings__dot', `remote-settings__dot--${state}`)} />
        <span className="remote-settings__state-text">{stateText()}</span>
        {error && <span className="remote-settings__error">{error}</span>}
      </div>

      <div className="remote-settings__desc">{t('remote.settings.desc')}</div>

      <div className="remote-settings__actions">
        <button className="remote-settings__btn" onClick={openPairing}>
          <span className="codicon codicon-qrcode" /> {t('remote.settings.showQr')}
        </button>
        <button className="remote-settings__btn" onClick={() => stopRemote()} disabled={!active}>
          <span className="codicon codicon-debug-disconnect" /> {t('remote.settings.disconnect')}
        </button>
        <button
          className={cx('remote-settings__btn', confirmingUnpair && 'remote-settings__btn--danger')}
          onClick={() => setConfirmingUnpair(true)}
        >
          <span className="codicon codicon-link-break" /> {t('remote.settings.unpair')}
        </button>
      </div>

      <div className="remote-settings__note">{t('remote.settings.note')}</div>

      {confirmingUnpair && (
        <ConfirmDialog
          title={t('remote.settings.unpairConfirmTitle')}
          message={t('remote.settings.unpairConfirmMsg')}
          confirmText={t('remote.action.unpairConfirm')}
          onConfirm={() => {
            unpairRemote()
            setConfirmingUnpair(false)
          }}
          onCancel={() => setConfirmingUnpair(false)}
        />
      )}
    </div>
  )
}
