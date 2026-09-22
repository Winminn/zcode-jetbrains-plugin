import { describe, expect, it } from 'vitest'
import { parseFileChanges } from '../src/utils/parseStatus'
import { lineDiffStats } from '../src/utils/lineDiff'
import type { ZCodeMessage, ToolPart } from '../src/types/messages'

/**
 * issue #15 / 缺陷BR 回归：底部状态面板文件统计口径。
 * 原始诉求：改名类同行数替换不得归零（曾用净差公式导致统计消失）。
 * issue #23 跟进后口径 = 行级 LCS 对齐（与 diff 视图显示的变更行数一致）：
 * 不同内容的同行数替换仍计 +N/−N（本文件的回归点保持成立），
 * 仅新旧内容完全相同时计 0。
 */

let seq = 0
function editMsg(tool: string, input: Record<string, unknown>): ZCodeMessage {
  const part: ToolPart = {
    type: 'tool',
    callID: `call_${++seq}`,
    tool,
    state: { status: 'completed', input },
  }
  return {
    info: { role: 'assistant', time: { created: 1, completed: 2 }, id: `m_${seq}`, sessionID: 's' },
    parts: [part],
  }
}

function groupCardTotals(changes: ReturnType<typeof parseFileChanges>) {
  // 组卡口径（FileToolGroupCard.parseFileItem）的等价读法：逐编辑 LCS 对齐后求和
  return changes.reduce(
    (acc, f) => {
      for (const e of f.edits ?? []) {
        const s = lineDiffStats(e.oldContent, e.newContent)
        acc.add += s.additions
        acc.del += s.deletions
      }
      return acc
    },
    { add: 0, del: 0 },
  )
}

describe('issue#15 底部文件统计口径（修复后）', () => {
  it('单行改名：old/new 各 1 行 → +1/-1（与组卡一致，不再归零）', () => {
    const msgs = [editMsg('Edit', {
      file_path: 'C:\\proj\\src\\user.ts',
      old_string: 'const userName = fetchProfile().name',
      new_string: 'const fullName = fetchProfile().name',
    })]
    const changes = parseFileChanges(msgs)
    expect(changes).toHaveLength(1)
    expect(changes[0].additions).toBe(1)
    expect(changes[0].deletions).toBe(1)
    expect(changes[0].edits?.[0].oldContent).toContain('userName')
    expect(changes[0].edits?.[0].newContent).toContain('fullName')
    expect(groupCardTotals(changes)).toEqual({ add: 1, del: 1 }) // 两口径一致
  })

  it('多行块改名（4 行换 4 行仅首行变）：LCS 对齐计 +1/-1（与 diff 视图一致）', () => {
    const oldBlock = ['function getUserName(u) {', '  const p = load(u);', '  return p.name;', '}'].join('\n')
    const newBlock = ['function getFullName(u) {', '  const p = load(u);', '  return p.name;', '}'].join('\n')
    const changes = parseFileChanges([editMsg('Edit', { file_path: 'C:\\proj\\src\\api.ts', old_string: oldBlock, new_string: newBlock })])
    // 旧双侧口径记 +4/-4，但 diff 视图对齐后只显示首行变更——LCS 口径与其一致；
    // 非 0 仍满足 issue#15「统计不得消失」的原始诉求
    expect(changes[0].additions).toBe(1)
    expect(changes[0].deletions).toBe(1)
  })

  it('同文件多次改名按文件聚合（用户 8 文件全零场景的反例）', () => {
    const msgs = [
      editMsg('Edit', { file_path: 'C:\\proj\\a.ts', old_string: 'userName', new_string: 'fullName' }),
      editMsg('Edit', { file_path: 'C:\\proj\\a.ts', old_string: 'getUserById', new_string: 'fetchUserById' }),
      editMsg('Edit', { file_path: 'C:\\proj\\b.ts', old_string: 'MAX_RETRY = 3', new_string: 'MAX_ATTEMPTS = 3' }),
    ]
    const changes = parseFileChanges(msgs)
    expect(changes).toHaveLength(2)
    const a = changes.find((f) => f.filePath.endsWith('a.ts'))!
    expect(a.additions).toBe(2)
    expect(a.deletions).toBe(2)
    // tab 统计渲染条件（totalAdd>0/totalDel>0）满足 → 正常显示
    const totalAdd = changes.reduce((n, f) => n + f.additions, 0)
    const totalDel = changes.reduce((n, f) => n + f.deletions, 0)
    expect(totalAdd).toBe(3)
    expect(totalDel).toBe(3)
  })

  it('跨行数编辑（1 行换成 3 行）→ +3/-1（单次编辑同时可见增删）', () => {
    const msgs = [editMsg('Edit', {
      file_path: 'C:\\proj\\c.ts',
      old_string: 'return result;',
      new_string: 'const final = transform(result);\nlog(final);\nreturn final;',
    })]
    const changes = parseFileChanges(msgs)
    expect(changes[0].additions).toBe(3)
    expect(changes[0].deletions).toBe(1)
    expect(groupCardTotals(changes)).toEqual({ add: 3, del: 1 })
  })

  it('Write 新文件口径不变：+N/0', () => {
    const changes = parseFileChanges([editMsg('Write', { file_path: 'C:\\proj\\new.ts', content: 'a\nb\nc' })])
    expect(changes[0].additions).toBe(3)
    expect(changes[0].deletions).toBe(0)
  })
})
