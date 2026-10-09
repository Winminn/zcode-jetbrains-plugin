/**
 * 当前文件上下文配置（纯前端消费）
 *
 * autoOnNewSession（默认关闭）：「新建会话」按钮进入待命态时自动点亮输入框的
 * 文件上下文 chip——仅影响新会话首条消息（发完即关：发送成功勾选即自动取消，
 * 后续消息仍按当时 chip 取值）。默认关闭的理由（2026-10-08 讨论拍板）：两种
 * 错误代价不对称——默认关的代价是想要时多点一下；默认开的代价是误带文件进
 * 会话历史（history 持久化不可撤回）且 AI 首答锚定错文件。高频用户自行开启。
 *
 * 应用点两处（2026-10-08 用户实测拍板"新建标签页=新建会话"后补上第二处）：
 *   1. store.resetToNewSession——「新建会话」按钮手势；
 *   2. listSessions boot 待命分支——新标签无注入会话绑定 / 绑定会话已被删除而
 *     进待命态（懒标签激活后走同一 boot 路径；只点不灭，防抹掉 boot 往返窗口
 *     内的手动勾选）。
 * 不应用的路径：切换会话、删除当前会话进待命态、Java 自动 newSession（旧会话
 * 模型不可用兜底）、createSession 响应（懒创建完成时首条已发出，点亮只对第二条
 * 生效属语义错乱）——保守不点亮，避免把"只是看着"的文件误带进对话。
 *
 * 存储走 persist kv 通道（key=zcode.currentFile.config）：localStorage 即时生效 +
 * 去抖回存 IDE PropertiesComponent，跨重启保留。读取方均在调用时取值，无同标签
 * 变更事件需求（设置改动等下一次新建会话/新标签自然生效）。
 */
import { getPersisted, setPersisted } from './persist'

export interface CurrentFileConfig {
  /** 新建会话时自动点亮文件上下文 chip（默认关闭）*/
  autoOnNewSession: boolean
}

const KEY = 'zcode.currentFile.config'

export const DEFAULT_CURRENT_FILE_CONFIG: CurrentFileConfig = {
  autoOnNewSession: false,
}

export function readCurrentFileConfig(): CurrentFileConfig {
  const raw = getPersisted(KEY)
  if (!raw) return { ...DEFAULT_CURRENT_FILE_CONFIG }
  try {
    const obj = JSON.parse(raw) as Partial<CurrentFileConfig>
    return {
      autoOnNewSession:
        typeof obj.autoOnNewSession === 'boolean'
          ? obj.autoOnNewSession
          : DEFAULT_CURRENT_FILE_CONFIG.autoOnNewSession,
    }
  } catch {
    return { ...DEFAULT_CURRENT_FILE_CONFIG }
  }
}

export function writeCurrentFileConfig(config: CurrentFileConfig): void {
  setPersisted(KEY, JSON.stringify(config))
}
