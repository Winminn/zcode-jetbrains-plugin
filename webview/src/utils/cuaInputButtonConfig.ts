/**
 * 「在输入框显示电脑操作按钮」配置（纯前端消费，2026-10-10）
 *
 * showInInputBox（默认关闭）：电脑控制启用后，输入框顶栏（当前文件 chip 左侧）
 * 显示「电脑操作」入口 chip，点击把 zcgui-computer-use 技能提及内联插进输入框。
 * 上游还有一道电脑控制主开关（Kotlin PropertiesComponent，store.cuaEnabled）——
 * 主开关关闭时技能未安装，此开关即使为 true 也不渲染入口（两道条件与官方客户端
 * 「需先开启电脑控制，才能在输入框显示该按钮」语义一致）。
 *
 * 存储走 persist kv 通道（key=zcode.cua.inputButton.config）：localStorage 即时
 * 生效 + 去抖回存 IDE PropertiesComponent，跨重启保留。读取方均在调用时取值；
 * store 侧监听 KV 水合事件在水合完成后校正（启动读持久值勿直读一次了事）。
 */
import { getPersisted, setPersisted } from './persist'

export interface CuaInputButtonConfig {
  showInInputBox: boolean
}

const KEY = 'zcode.cua.inputButton.config'

export const DEFAULT_CUA_INPUT_BUTTON_CONFIG: CuaInputButtonConfig = {
  showInInputBox: false,
}

export function readCuaInputButtonConfig(): CuaInputButtonConfig {
  const raw = getPersisted(KEY)
  if (!raw) return { ...DEFAULT_CUA_INPUT_BUTTON_CONFIG }
  try {
    const obj = JSON.parse(raw) as Partial<CuaInputButtonConfig>
    return {
      showInInputBox:
        typeof obj.showInInputBox === 'boolean'
          ? obj.showInInputBox
          : DEFAULT_CUA_INPUT_BUTTON_CONFIG.showInInputBox,
    }
  } catch {
    return { ...DEFAULT_CUA_INPUT_BUTTON_CONFIG }
  }
}

export function writeCuaInputButtonConfig(config: CuaInputButtonConfig): void {
  setPersisted(KEY, JSON.stringify(config))
}
