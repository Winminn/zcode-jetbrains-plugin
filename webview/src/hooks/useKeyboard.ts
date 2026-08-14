/**
 * IME 安全的键盘处理 hook
 *
 * 规划文档第二节第 2 点（来源 cc-gui useKeyboardHandler.ts）：
 *
 * 关键问题：中文/日文输入法（IME）在按 Enter 确认候选词时，
 * 浏览器会触发 keydown(Enter)，如果直接监听 Enter 发送消息，
 * 会把"确认输入"误判为"发送消息"。
 *
 * cc-gui 解法（三重保护）：
 *   1. isComposing 期间（合成中）不响应 Enter
 *   2. 合成结束后记录时间戳 lastCompositionEndTime
 *   3. 合成结束后 100ms 内的 Enter 也忽略（防 IME"确认回车"延迟触发）
 *
 * 发送键策略：
 *   - 'enter'（默认）：Enter 发送，Shift+Enter 换行
 *   - 'cmdEnter'：Cmd/Ctrl+Enter 发送，Enter 换行
 */

import { useCallback, useRef } from 'react'

export type SendKeyMode = 'enter' | 'cmdEnter'

/** IME 确认后需要忽略 Enter 的时间窗口（毫秒）*/
const IME_GUARD_MS = 100

interface Options {
  /** 发送键模式 */
  mode?: SendKeyMode
  /** 发送回调 */
  onSend: () => void
  /** 是否禁用（生成中）*/
  disabled?: boolean
}

export function useKeyboard({ mode = 'enter', onSend, disabled = false }: Options) {
  // 合成结束时间戳（用于 100ms 窗口判断）
  const lastCompositionEnd = useRef(0)

  /**
   * 判断一个 keydown 事件是否应该触发发送。
   * 调用方在 onKeyDown 里调此函数，返回 true 则发送。
   */
  const shouldSend = useCallback(
    (e: React.KeyboardEvent): boolean => {
      if (disabled) return false

      // 保护1：IME 合成中不响应
      if (e.nativeEvent.isComposing) return false

      // 保护2：合成结束后 100ms 内的 Enter 忽略
      if (Date.now() - lastCompositionEnd.current < IME_GUARD_MS) return false

      if (mode === 'enter') {
        // Enter 发送，Shift+Enter 换行
        if (e.key === 'Enter' && !e.shiftKey && !e.metaKey && !e.ctrlKey) {
          return true
        }
      } else {
        // cmdEnter 模式：Cmd/Ctrl+Enter 发送
        if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
          return true
        }
      }
      return false
    },
    [mode, disabled],
  )

  /** 处理 keydown：返回 true 表示已处理（应 preventDefault）*/
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent): boolean => {
      if (shouldSend(e)) {
        e.preventDefault()
        onSend()
        return true
      }
      return false
    },
    [shouldSend, onSend],
  )

  /** compositionend 事件处理：记录结束时间 */
  const handleCompositionEnd = useCallback(() => {
    lastCompositionEnd.current = Date.now()
  }, [])

  return { handleKeyDown, handleCompositionEnd, shouldSend }
}
