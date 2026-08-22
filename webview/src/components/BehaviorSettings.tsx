/**
 * 基础设置「行为」子页签（BasicSettingsView 第三个子页签）
 *
 * 对话结束系统通知（仅系统消息，无提示音、无焦点门控——开启即始终弹，默认关闭）：
 * 配置走 persist kv 通道（utils/notifyConfig.ts），Kotlin ZCodeNotifyService
 * 触发通知时即时读同一 key——前端无请求往返，改动即时生效。
 * 手动 stop 的回合不通知（Kotlin 侧 markManualStop 语义，无需前端配置）。
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SettingToggle } from './SettingToggle'
import { readNotifyConfig, writeNotifyConfig } from '@/utils/notifyConfig'

export function BehaviorSettings() {
  const { t } = useTranslation()
  const [config, setConfig] = useState(readNotifyConfig)

  const update = (patch: Partial<typeof config>) => {
    const next = { ...config, ...patch }
    setConfig(next)
    writeNotifyConfig(next)
  }

  return (
    <>
      <section className="basic-settings__section">
        <div className="basic-settings__field-header">
          <span className="codicon codicon-bell" />
          <span className="basic-settings__field-label">{t('settings.behavior.notifyTitle')}</span>
        </div>
        <SettingToggle
          icon="codicon-bell"
          title={t('settings.behavior.notifyEnabled.title')}
          desc={t('settings.behavior.notifyEnabled.desc')}
          on={config.notifyEnabled}
          onToggle={() => update({ notifyEnabled: !config.notifyEnabled })}
          onHint={t('settings.behavior.notifyEnabled.offHint')}
          offHint={t('settings.behavior.notifyEnabled.onHint')}
        />
        <small className="basic-settings__hint">
          <span className="codicon codicon-info" />
          <span>{t('settings.behavior.notifyEnabled.hint')}</span>
        </small>
      </section>
    </>
  )
}
