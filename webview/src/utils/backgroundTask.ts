/**
 * 后台任务识别共享判据（单点定义，缺陷Z 教训：判据注释勿逐字引用官方句子）
 *
 * zcode.cjs 的后台化确认（Bash run_in_background / 手动后台化）输出固定形态：
 *   ① 以 `Command` 动作前缀开头（三种官方动作之一）
 *   ② 任务 ID 恒为 `exec_` + 标准 UUID（8-4-4-4-12 十六进制，2026-08-25 起
 *      多个真实事件实测确认）
 * 两者同时要求即足够特异：普通命令输出/源码注释里的占位 ID（exec_xxx、短 ID）
 * 或残缺文案都会被拒绝（缺陷Z 双判据 + 2026-08-26 变体：完整句子 + exec_xxx
 * 占位被 UUID 形态拒绝）。
 */

const RE_BG_CMD = /Command (?:running in background|was manually backgrounded by user|was moved to the background)/i
const RE_BG_ID = /with ID:\s*(exec_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/i

/** 从工具输出文本提取后台任务 ID；非官方后台化确认返回 null */
export function extractBackgroundTaskIdFromContent(content: string): string | null {
  if (!RE_BG_CMD.test(content)) return null
  const m = content.match(RE_BG_ID)
  return m ? m[1] : null
}

/** 渲染层判定：工具输出是否为官方后台化确认（历史消息静态识别用，无账本也能判定） */
export function isBackgroundTaskOutput(output: string | undefined | null): boolean {
  return typeof output === 'string' && extractBackgroundTaskIdFromContent(output) !== null
}

/**
 * 从转录重建历史后台任务条目（refreshStatus 派生，与 parseAgents 同思路）：
 * 遍历 Bash 工具行，state.output 命中官方后台化确认 → 提取 exec_ 任务 ID 合成条目。
 * status 恒为 'ended'（本地合成值）——转录只能证明「启动过」，运行时真实状态由
 * 投影（backgroundWorksBySession）承载，读取处 mergeBackgroundWorks 合并、投影优先：
 * 本进程内同 workId 以投影为准（running 可取消），重启后投影消失即剩 ended 历史。
 */
export function parseBackgroundTaskWorks(messages: import('@/types/messages').ZCodeMessage[]): import('@/types/messages').BackgroundWorkSummary[] {
  const byId = new Map<string, import('@/types/messages').BackgroundWorkSummary>()
  for (const msg of messages) {
    for (const part of msg.parts ?? []) {
      if (part.type !== 'tool' || part.tool !== 'Bash') continue
      const out = part.state?.output
      const id = typeof out === 'string' ? extractBackgroundTaskIdFromContent(out) : null
      if (!id || byId.has(id)) continue
      const command = String(part.state?.input?.command ?? '').slice(0, 200)
      byId.set(id, {
        workId: id,
        kind: 'bash',
        title: command || id,
        status: 'ended',
        startedAt: part.state?.time?.start ?? 0,
        cancellable: false,
      })
    }
  }
  return [...byId.values()]
}

/**
 * 投影 ∪ 转录重建合并（读取处用）：按 workId 去重、投影优先（运行时权威状态覆盖
 * 重建的 ended 猜测）；投影组在前——运行中条目天然置顶显示。
 */
export function mergeBackgroundWorks(
  projection: import('@/types/messages').BackgroundWorkSummary[],
  fromTranscript: import('@/types/messages').BackgroundWorkSummary[],
): import('@/types/messages').BackgroundWorkSummary[] {
  if (fromTranscript.length === 0) return projection
  const seen = new Set(projection.map((w) => w.workId))
  return [...projection, ...fromTranscript.filter((w) => !seen.has(w.workId))]
}
