/**
 * 电脑控制设置视图（设置页「电脑控制」条目）：
 *
 *   - 开关：启用后新建会话注入 zcgui-computer-use MCP server（AI 可截屏/点击/键入
 *     操作本机桌面），并安装配套技能到 ~/.zcode/skills/；关闭时同步卸载。
 *   - 已在跑的会话不受影响（注入在新建会话时读取，与工作区记忆开关同语义）。
 *   - 权限模式不做自动切换（用户决策撤出，官方客户端亦无此机制）——跟随用户在会话
 *     底栏自行选择；逐次审批走通用 requestPermission 链。
 */
import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { useStore } from '@/store/useStore'
import { SettingToggle } from './SettingToggle'
import '../styles/computer-settings.less'

export function ComputerSettingsView() {
  const { t } = useTranslation()
  const cuaEnabled = useStore((s) => s.cuaEnabled)
  const cuaToggling = useStore((s) => s.cuaToggling)
  const loadCuaEnabled = useStore((s) => s.loadCuaEnabled)
  const setCuaEnabled = useStore((s) => s.setCuaEnabled)
  const cuaInputButton = useStore((s) => s.cuaInputButton)
  const setCuaInputButton = useStore((s) => s.setCuaInputButton)

  useEffect(() => {
    loadCuaEnabled()
  }, [loadCuaEnabled])

  return (
    <div className="computer-settings">
      <h3 className="computer-settings__section">{t('computer.title')}</h3>
      <SettingToggle
        icon="codicon-device-desktop"
        title={t('computer.switchTitle')}
        desc={t('computer.switchDesc')}
        on={cuaEnabled === true}
        busy={cuaToggling}
        disabled={cuaEnabled == null}
        onHint={t('computer.onHint')}
        offHint={t('computer.offHint')}
        onToggle={() => cuaEnabled != null && setCuaEnabled(!cuaEnabled)}
      />
      {/* 输入框入口 chip 显隐（persist kv 纯前端偏好）：主开关未开时技能未安装，
          入口无意义——禁用并提示，与官方客户端「需先开启电脑控制」语义一致 */}
      <SettingToggle
        icon="codicon-pin"
        title={t('computer.inputButtonTitle')}
        desc={t('computer.inputButtonDesc')}
        on={cuaInputButton}
        disabled={cuaEnabled !== true}
        onToggle={() => setCuaInputButton(!cuaInputButton)}
      />
      <div className="computer-settings__notes">
        <div className="computer-settings__note">
          <span className="codicon codicon-shield computer-settings__note-icon" />
          {t('computer.notePermission')}
        </div>
        <div className="computer-settings__note">
          <span className="codicon codicon-info computer-settings__note-icon" />
          {t('computer.noteNewSession')}
        </div>
      </div>
    </div>
  )
}
