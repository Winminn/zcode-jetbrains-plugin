/**
 * 工作区钩子准入横幅（官方同款形态，v4 workspaceHookAdmission 投影）
 *
 * 官方客户端（2026-10-13 computer-use 实测）：未信任钩子在本会话被阻止时，
 * 输入区上方弹横幅「N 个工作区 Hook 待审核，本会话暂未启用」+ 去审核/忽略——
 * 数据源是 state.updated 的 workspaceHookAdmission 投影（pendingCount/bundleDigest），
 * 不是 pendingInteractions 评审交互（该路线官方不发，旧评审卡已移除）。
 *
 * 去审核 = 打开设置页钩子 tab（信任入口）；忽略 = 按待审束（bundleDigest+count）
 * 本地忽略，新的待审束到达时重新出现；admission 归 null 即整体收敛。
 */

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useStore } from '@/store/useStore'
import '../styles/hook-admission-banner.less'

export function WorkspaceHookAdmissionBanner({
  visible,
  onGoReview,
}: {
  /** 仅主界面 chat 视图显示（设置/历史视图不遮挡）；由 App 按 currentView 传入 */
  visible: boolean
  /** 去审核：打开设置页钩子 tab（App 侧切视图 + 落 hooks 设置段意图） */
  onGoReview: () => void
}) {
  const { t } = useTranslation()
  const currentSessionId = useStore((s) => s.currentSessionId)
  const admission = useStore((s) =>
    currentSessionId ? s.workspaceHookAdmissionBySession[currentSessionId] ?? null : null,
  )
  // 忽略按待审束记忆：同一束（digest+count）不再打扰，新束或数量变化重新出现
  const [dismissedKey, setDismissedKey] = useState('')

  if (!visible) return null
  if (!admission || admission.pendingCount <= 0) return null
  const pendingKey = `${admission.bundleDigest ?? ''}|${admission.pendingCount}`
  if (dismissedKey === pendingKey) return null

  const goReview = onGoReview

  return (
    <div className="hook-admission-banner" role="note">
      <span className="codicon codicon-shield hook-admission-banner__icon" />
      <span className="hook-admission-banner__text">
        {t('app.hookAdmission.banner', { count: admission.pendingCount })}
      </span>
      <button type="button" className="hook-admission-banner__review-btn" onClick={goReview}>
        {t('app.hookAdmission.goReview')}
      </button>
      <button
        type="button"
        className="hook-admission-banner__ignore-btn"
        onClick={() => setDismissedKey(pendingKey)}
      >
        {t('app.hookAdmission.ignore')}
      </button>
    </div>
  )
}
