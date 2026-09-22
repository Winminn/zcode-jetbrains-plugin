/**
 * 终端 Shell 偏好（插件自有配置，2026-09-21 开源协议面对照新增）
 *
 * 存储：persist kv 通道（key=zcode.terminalShell.config）。行为设置页下拉写入，
 * Kotlin 侧（ZCodeTerminalShellConfig）在 requestRuntimePreferences 应答时即时
 * 读取并转成官方 integratedTerminalShellSelection 形状——auto=不回字段走 CLI
 * 自动探测；git-bash/cmd 显式指定（协议枚举无 powershell）。
 */
import { getPersisted, setPersisted } from './persist'

export type TerminalShellSelection = 'auto' | 'git-bash' | 'cmd'

export interface TerminalShellConfig {
  selection: TerminalShellSelection
}

const KEY = 'zcode.terminalShell.config'

export const DEFAULT_TERMINAL_SHELL_CONFIG: TerminalShellConfig = {
  selection: 'auto',
}

export const TERMINAL_SHELL_OPTIONS: { value: TerminalShellSelection; labelKey: string }[] = [
  { value: 'auto', labelKey: 'settings.behavior.shellAuto' },
  { value: 'git-bash', labelKey: 'settings.behavior.shellGitBash' },
  { value: 'cmd', labelKey: 'settings.behavior.shellCmd' },
]

export function readTerminalShellConfig(): TerminalShellConfig {
  const raw = getPersisted(KEY)
  if (!raw) return { ...DEFAULT_TERMINAL_SHELL_CONFIG }
  try {
    const obj = JSON.parse(raw) as Partial<TerminalShellConfig>
    return {
      selection:
        obj.selection === 'git-bash' || obj.selection === 'cmd'
          ? obj.selection
          : DEFAULT_TERMINAL_SHELL_CONFIG.selection,
    }
  } catch {
    return { ...DEFAULT_TERMINAL_SHELL_CONFIG }
  }
}

export function writeTerminalShellConfig(config: TerminalShellConfig): void {
  setPersisted(KEY, JSON.stringify(config))
}
