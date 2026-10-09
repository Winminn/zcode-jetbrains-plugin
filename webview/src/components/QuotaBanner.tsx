/**
 * 会话额度横幅（输入框上方常驻条）
 *
 * 官方 sessionQuotaBanner 插件化简化版：耗尽/并发/服务商边界/限频四类错误触发 +
 * 低额主动提醒（≤10%，每窗口期一次），窗口点名与重置时间来自 quota/limit 数据
 * （60s 轮询，横幅纯响应式，不额外发请求）。
 *
 * 与顶栏错误条（lastError）并行不互斥：横幅负责「额度这件事」的持续态与窗口信息，
 * 错误条保留原始错误详情；窗口恢复（轮询显示剩余回来）后横幅自动消失。
 */

import { useTranslation } from 'react-i18next'
import { useStore } from '@/store/useStore'
import { useQuotaBanner } from '@/hooks/useQuotaBanner'
import { fmtResetTimeCompact, fmtResetDate } from '@/utils/format'
import { WINDOW_LABEL_I18N } from '@/utils/quotaWindows'
import type { QuotaWindowKey } from '@/utils/quotaWindows'
import '../styles/quota-banner.less'

/** 5 小时池重置在当日（HH:mm 语义），周/工具窗口用日期口径 */
function windowLabel(t: (k: string) => string, key: QuotaWindowKey): string {
  return t(WINDOW_LABEL_I18N[key])
}

export function QuotaBanner() {
  const { t } = useTranslation()
  const view = useQuotaBanner()
  const loadQuota = useStore((s) => s.loadQuota)
  const dismissQuotaBanner = useStore((s) => s.dismissQuotaBanner)

  if (!view) return null

  const names = view.windowKeys.map((k) => windowLabel(t, k))
  const reset =
    view.resetTime !== undefined
      ? view.windowKeys.includes('5h')
        ? fmtResetTimeCompact(view.resetTime)
        : fmtResetDate(view.resetTime)
      : null

  let text: string
  let detail: string | null = null
  switch (view.kind) {
    case 'window-exhausted':
      text = names.length
        ? reset
          ? t('usage.banner.exhaustedReset', { names: names.join('、'), time: reset })
          : t('usage.banner.exhausted', { names: names.join('、') })
        : t('usage.banner.exhaustedGeneric')
      break
    case 'window-low':
      text = reset
        ? t('usage.banner.lowReset', { name: names[0] ?? '', pct: t('usage.banner.remainPct', { pct: Math.round(view.remainPct ?? 0) }), time: reset })
        : t('usage.banner.low', { name: names[0] ?? '', pct: t('usage.banner.remainPct', { pct: Math.round(view.remainPct ?? 0) }) })
      break
    case 'concurrent-limit':
      text = t('usage.banner.concurrent')
      break
    case 'provider-limited':
      text = t('usage.banner.providerLimited')
      detail = view.rawMessage ?? null
      break
    case 'rate-limited':
      text = t('usage.banner.rateLimited')
      break
  }

  return (
    <div className={`quota-banner quota-banner--${view.kind === 'window-exhausted' || view.kind === 'provider-limited' ? 'error' : 'warning'}`}>
      <span className="quota-banner__text">
        {view.kind === 'window-low' ? '💡' : '⚠️'} {text}
        {detail && <span className="quota-banner__detail">：{detail}</span>}
      </span>
      <button className="quota-banner__action" onClick={loadQuota}>
        {t('usage.banner.refresh')}
      </button>
      <button
        className="quota-banner__close"
        onClick={() => dismissQuotaBanner(view.triggerKey)}
        title={t('usage.banner.dismiss')}
        aria-label={t('usage.banner.dismissAria')}
      >
        <span className="codicon codicon-close" />
      </button>
    </div>
  )
}
