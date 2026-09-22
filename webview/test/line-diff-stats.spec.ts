import { describe, expect, it } from 'vitest'
import { lineDiffStats } from '../src/utils/lineDiff'

/**
 * 行级 LCS 对齐统计（utils/lineDiff）单元测试。
 * 口径与 git / IDEA diff 一致：additions = 新行数 − 公共行数（LCS）、
 * deletions = 旧行数 − 公共行数。背景见 issue #23 跟进
 * （双侧计数 +8/-2 vs diff 实际 6 条新增的对不上问题）。
 */

describe('lineDiffStats LCS 对齐口径', () => {
  it('内容完全相同 → +0/0（此时 diff 视图也无变更可显示）', () => {
    expect(lineDiffStats('a\nb\nc', 'a\nb\nc')).toEqual({ additions: 0, deletions: 0 })
  })

  it('单行改名（同行数不同内容）→ +1/-1（issue#15 诉求：不归零）', () => {
    expect(lineDiffStats('const userName = 1', 'const fullName = 1')).toEqual({ additions: 1, deletions: 1 })
  })

  it('多行块仅一行变更（3 行换 3 行）→ +1/-1，而非双侧口径的 +3/-3', () => {
    const oldBlock = 'function a() {\n  return 1;\n}\n'
    const newBlock = 'function b() {\n  return 1;\n}\n'
    expect(lineDiffStats(oldBlock, newBlock)).toEqual({ additions: 1, deletions: 1 })
  })

  it('用户实报场景：new 保留 old 全部 2 行再追加 6 行 → +6/0（双侧口径误报 +8/-2）', () => {
    const oldBlock = 'const a = 1;\nconst b = 2;'
    const newBlock = 'const a = 1;\nconst b = 2;\nconst c = 3;\nconst d = 4;\nconst e = 5;\nconst f = 6;\nconst g = 7;\nconst h = 8;'
    expect(lineDiffStats(oldBlock, newBlock)).toEqual({ additions: 6, deletions: 0 })
  })

  it('行序对调（a,b → b,a）→ +1/-1（LCS 要求保序，公共行只有 1）', () => {
    expect(lineDiffStats('a\nb', 'b\na')).toEqual({ additions: 1, deletions: 1 })
  })

  it('间隔变更（A B C D E → A X C Y E）→ +2/-2', () => {
    expect(lineDiffStats('A\nB\nC\nD\nE', 'A\nX\nC\nY\nE')).toEqual({ additions: 2, deletions: 2 })
  })

  it('单侧为空：Write 全新增 / 全删除', () => {
    expect(lineDiffStats('', 'a\nb\nc')).toEqual({ additions: 3, deletions: 0 })
    expect(lineDiffStats('a\nb\nc', '')).toEqual({ additions: 0, deletions: 3 })
  })

  it('结尾换行符不参与比较（同 lineCount 口径）', () => {
    expect(lineDiffStats('a\nb\n', 'a\nb')).toEqual({ additions: 0, deletions: 0 })
  })

  it('超过 DP 单元格上限退回双侧计数（宁多勿漏）', () => {
    // 4×4=16 > maxCells=10 → 不跑 LCS，退回双侧 {4,4}
    expect(lineDiffStats('l1\nl2\nl3\nl4', 'l1\nl2\nl3\nl5', 10)).toEqual({ additions: 4, deletions: 4 })
    // 恰好不超限（16 ≤ 16）→ 正常 LCS：公共 3 行 → {1,1}
    expect(lineDiffStats('l1\nl2\nl3\nl4', 'l1\nl2\nl3\nl5', 16)).toEqual({ additions: 1, deletions: 1 })
  })
})
