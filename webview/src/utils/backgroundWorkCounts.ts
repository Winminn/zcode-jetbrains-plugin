/**
 * 后台工作汇总计数（官方 ConversationBackgroundWorkTrigger.getComposerBackgroundWorkCounts
 * 移植）：只统计 status==='running' 的条目，按 kind 分型。workflow 单独计数不并入 bash。
 * totalCount 是 badge 显隐判据（0 = 不渲染入口）。
 */
import type { BackgroundWorkSummary } from '@/types/messages'

export interface BackgroundWorkCounts {
  bashCount: number
  workflowCount: number
  subagentCount: number
  totalCount: number
}

export function getBackgroundWorkCounts(works: BackgroundWorkSummary[]): BackgroundWorkCounts {
  let bashCount = 0
  let workflowCount = 0
  let subagentCount = 0
  for (const work of works) {
    if (work.status !== 'running') continue
    if (work.kind === 'bash') bashCount += 1
    else if (work.kind === 'workflow') workflowCount += 1
    else if (work.kind === 'subagent') subagentCount += 1
  }
  return { bashCount, workflowCount, subagentCount, totalCount: bashCount + workflowCount + subagentCount }
}
