/**
 * 会话额度横幅派生 hook（组件渲染唯一入口）
 *
 * 职责：把「错误触发源（store）+ 额度数据（60s 轮询）+ 已关闭集合」合成展示视图，
 * 并负责两条自动收口：
 *  - 窗口恢复：耗尽触发源在额度数据显示窗口已恢复（剩余 > 阈值）时清除触发源
 *    （额度拉取失败/无窗口数据时不清，横幅保守保留）
 *  - 关闭动作：dismissQuotaBanner 记录当前视图的去重键，同源触发不再打扰
 */

import { useEffect, useMemo } from 'react'
import { useStore, isBigmodelProvider } from '@/store/useStore'
import { deriveQuotaBanner, hasStandardWindowData, pickExhaustedWindows } from '@/utils/quotaWindows'

export function useQuotaBanner() {
  const quota = useStore((s) => s.quota)
  const error = useStore((s) => s.quotaBannerError)
  const dismissed = useStore((s) => s.quotaBannerDismissed)
  const currentModel = useStore((s) => s.currentModel)
  const clearQuotaBannerError = useStore((s) => s.clearQuotaBannerError)

  // 低额提醒按当前模型门控：切到第三方模型后不再用 bigmodel 额度数据派生提醒
  // （错误触发源在写入时已门控，这里只兜低额派生路）
  const lowQuotaAllowed = isBigmodelProvider(currentModel?.providerId)

  const view = useMemo(
    () => deriveQuotaBanner({
      limits: lowQuotaAllowed ? (quota?.limits ?? null) : null,
      error,
      dismissed,
    }),
    [quota?.limits, error, dismissed, lowQuotaAllowed],
  )

  // 窗口恢复自动收口：耗尽触发源 + 窗口数据已恢复 → 清触发源（横幅随之消失）。
  // dismissed 导致 view 为 null 不影响本判定（触发源仍需清理，防下轮重新点亮）
  useEffect(() => {
    if (error?.kind !== 'window-exhausted') return
    const limits = quota?.limits
    if (!hasStandardWindowData(limits)) return
    if (pickExhaustedWindows(limits).length === 0) clearQuotaBannerError()
  }, [error, quota?.limits, clearQuotaBannerError])

  return view
}
