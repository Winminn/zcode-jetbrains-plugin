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
