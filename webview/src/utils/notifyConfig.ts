/**
 * 对话结束提醒配置（前端镜像；Kotlin 侧 ZCodeNotifyService 同源解析）
 *
 * 仅系统通知，无提示音、无焦点门控（开启即始终弹）。存储走 persist kv 通道
 * （key=zcode.notify.config）：localStorage 即时生效 + 去抖回存 IDE
 * PropertiesComponent——Kotlin 触发通知时即时读取，无需消息往返。
 *
 * 默认关闭（不弹）；首次改动才落盘。旧配置里的 notifyOnlyUnfocused 字段已废弃，
 * 解析时直接忽略。
 */
import { getPersisted, setPersisted } from './persist'

export interface NotifyConfig {
  /** 对话结束系统通知开关（默认关闭）*/
  notifyEnabled: boolean
}

const KEY = 'zcode.notify.config'

export const DEFAULT_NOTIFY_CONFIG: NotifyConfig = {
  notifyEnabled: false,
}

export function readNotifyConfig(): NotifyConfig {
  const raw = getPersisted(KEY)
  if (!raw) return { ...DEFAULT_NOTIFY_CONFIG }
  try {
    const obj = JSON.parse(raw) as Partial<NotifyConfig>
    return {
      notifyEnabled:
        typeof obj.notifyEnabled === 'boolean' ? obj.notifyEnabled : DEFAULT_NOTIFY_CONFIG.notifyEnabled,
    }
  } catch {
    return { ...DEFAULT_NOTIFY_CONFIG }
  }
}

export function writeNotifyConfig(config: NotifyConfig): void {
  setPersisted(KEY, JSON.stringify(config))
}
