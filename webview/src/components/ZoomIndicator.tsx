/**
 * 浏览器级缩放指示器（Ctrl+滚轮 = Chromium 原生缩放，webview 收不到 wheel 前置事件）
 *
 * 信号链：缩放改变 devicePixelRatio 并触发 window resize（拖分割条等普通 resize
 * 不改 dPR，以此区分）→ 节流查询 Java 权威值 → 百分比胶囊。
 *
 * 百分比是「相对基准档」的值（Java 侧换算：基准 = 原生 120%，显示为 100%——
 * webview 观感按此档调校，原生 100% 在 HiDPI 下过小）。启动时 Java 会把原生
 * 100% 的新 origin 抬到基准档，首查即返回 100%，正常启动不弹胶囊。
 *
 * 交互：对齐浏览器习惯——缩放时浮现胶囊、连续缩放刷新数值与驻留倒计时；
 * 点击胶囊或 Ctrl+0 重置回基准（Java setZoomLevel(基准档)）。
 * 驻留 3s（真机反馈 1s 来不及点）；悬停暂停倒计时，移开再计时。
 */
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { onMessage, sendToJava } from '@/ipc/bridge'

/** dPR 变化后的查询节流（毫秒）：首查立即，连续缩放合并到 150ms 一次 */
const QUERY_THROTTLE_MS = 150
/** 最后一次数值刷新后胶囊驻留时长（毫秒；悬停暂停） */
const TOAST_STAY_MS = 3000

export function ZoomIndicator() {
  const { t } = useTranslation()
  const [percent, setPercent] = useState(100)
  const [visible, setVisible] = useState(false)
  // reset/悬停暂停定义在 effect 内（共享节流/隐藏定时器句柄），经 ref 暴露给 JSX
  const apiRef = useRef<{ reset: () => void; hold: () => void; resume: () => void }>({
    reset: () => {},
    hold: () => {},
    resume: () => {},
  })

  useEffect(() => {
    let lastDpr = window.devicePixelRatio
    let lastQueryAt = 0
    let trailingTimer: ReturnType<typeof setTimeout> | undefined
    let hideTimer: ReturnType<typeof setTimeout> | undefined
    let booted = false // 启动首查仅恢复非 100% 的既有缩放，正常启动不打扰

    const show = (pct: number) => {
      setPercent(pct)
      setVisible(true)
      if (hideTimer) clearTimeout(hideTimer)
      hideTimer = setTimeout(() => setVisible(false), TOAST_STAY_MS)
    }
    const query = () => {
      lastQueryAt = Date.now()
      sendToJava({ op: 'zoomQuery' })
    }
    const reset = () => {
      // 乐观展示 100%；Java 回包与缩放变化引发的 resize→zoomQuery 双通道校正
      show(100)
      sendToJava({ op: 'zoomReset' })
    }
    apiRef.current = {
      reset,
      // 悬停暂停倒计时：移开后重新计满驻留时长
      hold: () => {
        if (hideTimer) clearTimeout(hideTimer)
      },
      resume: () => {
        if (hideTimer) clearTimeout(hideTimer)
        hideTimer = setTimeout(() => setVisible(false), TOAST_STAY_MS)
      },
    }

    const off = onMessage((msg) => {
      if (msg.op !== 'zoomLevel') return
      if (!booted) {
        booted = true
        if (msg.percent === 100) return
      }
      show(msg.percent)
    })
    const onResize = () => {
      const dpr = window.devicePixelRatio
      if (dpr === lastDpr) return
      lastDpr = dpr
      const since = Date.now() - lastQueryAt
      if (since >= QUERY_THROTTLE_MS) {
        query()
      } else if (trailingTimer === undefined) {
        trailingTimer = setTimeout(query, QUERY_THROTTLE_MS - since)
      }
    }
    const onKeydown = (e: KeyboardEvent) => {
      if (e.isComposing || e.altKey || e.shiftKey || e.key !== '0') return
      if (!(e.ctrlKey || e.metaKey)) return
      e.preventDefault()
      reset()
    }

    window.addEventListener('resize', onResize)
    document.addEventListener('keydown', onKeydown, true)
    query() // 持久 origin（dev/单文件）重载后缩放可能非 100%，启动对齐真实值
    return () => {
      off()
      window.removeEventListener('resize', onResize)
      document.removeEventListener('keydown', onKeydown, true)
      if (trailingTimer) clearTimeout(trailingTimer)
      if (hideTimer) clearTimeout(hideTimer)
    }
  }, [])

  if (!visible) return null
  return (
    <button
      type="button"
      className="app__zoom-indicator"
      title={t('app.zoomResetTitle')}
      onClick={() => apiRef.current.reset()}
      onMouseEnter={() => apiRef.current.hold()}
      onMouseLeave={() => apiRef.current.resume()}
    >
      {percent}%
    </button>
  )
}
