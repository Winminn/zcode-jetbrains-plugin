/**
 * 行级 LCS 对齐统计（git / IDEA diff 同口径）
 *
 * 背景（issue #23 跟进）：此前的统计是「新旧块行数各自计数」（双侧口径），
 * 而 diff 视图按内容对齐——new_string 原样保留了 old_string 的部分行时，
 * 统计记 +8/-2、diff 实际只显示 6 条新增，两边对不上。
 * 本工具只算数字不产出 diff 块：additions = 新行数 − 公共行数（LCS）、
 * deletions = 旧行数 − 公共行数，与 diff 视图显示的变更行数一致。
 *
 * 与 issue #15（缺陷BR）「净差归零」的关系：净差是纯行数差（1 行换 1 行
 * 不同内容 → ±0），LCS 是内容对齐（同行数但内容不同 → +1/−1），改名类
 * 重构不会归零；只有新旧内容完全相同时才计 0（此时 diff 视图也确实无变更）。
 */

export interface DiffStats {
  additions: number
  deletions: number
}

/** DP 单元格上限：超过则退回双侧计数（宁多勿漏，防 O(n·m) 时间/空间失控）*/
const MAX_DP_CELLS = 1_000_000

/** 文本行数切分（与 partialToolInput.lineCount 同规：结尾换行不算多一行）*/
function splitLines(s: string): string[] {
  if (!s) return []
  const lines = s.split('\n')
  if (lines[lines.length - 1] === '') lines.pop()
  return lines
}

/**
 * 两段文本的行级增删统计。maxCells 仅供测试注入（验证超限回退分支），
 * 业务调用一律省略。
 */
export function lineDiffStats(oldStr: string, newStr: string, maxCells = MAX_DP_CELLS): DiffStats {
  const oldLines = splitLines(oldStr)
  const newLines = splitLines(newStr)
  // 单侧为空：全部新增/删除，无需 DP（Write 新文件即此形态）
  if (oldLines.length === 0) return { additions: newLines.length, deletions: 0 }
  if (newLines.length === 0) return { additions: 0, deletions: oldLines.length }
  if (oldLines.length * newLines.length > maxCells) {
    return { additions: newLines.length, deletions: oldLines.length }
  }
  // 滚动两行求 LCS 长度：O(n·m) 时间 / O(m) 空间，只留计数不需要回溯路径
  const m = newLines.length
  let prev = new Int32Array(m + 1)
  let cur = new Int32Array(m + 1)
  for (let i = 1; i <= oldLines.length; i++) {
    const oc = oldLines[i - 1]
    for (let j = 1; j <= m; j++) {
      cur[j] = oc === newLines[j - 1] ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1])
    }
    const t = prev
    prev = cur
    cur = t
  }
  const lcs = prev[m]
  return { additions: m - lcs, deletions: oldLines.length - lcs }
}
