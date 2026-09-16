import { describe, expect, it } from 'vitest'
import { parseFileChanges } from '../src/utils/parseStatus'
import type { ZCodeMessage, ToolPart } from '../src/types/messages'

/**
 * issue #15 / 缺陷BR 回归：底部状态面板文件统计口径。
 * 修复后与编辑组卡同口径（改动行双侧计数）：改名类同行数替换显示 +N/−N，
 * 不再净差归零；单次编辑也能同时出现增删（净差公式结构上必有一边为 0 的限制消除）。
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
  // 组卡口径（FileToolGroupCard.parseFileItem）的等价读法：新块/旧块行数各自求和
  return changes.reduce(
    (acc, f) => ({
      add: acc.add + (f.edits ?? []).reduce((n, e) => n + e.newContent.split('\n').filter((l, i, a) => l !== '' || i < a.length - 1).length, 0),
      del: acc.del + (f.edits ?? []).reduce((n, e) => n + e.oldContent.split('\n').filter((l, i, a) => l !== '' || i < a.length - 1).length, 0),
    }),
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

  it('多行块改名（4 行换 4 行）：+4/-4', () => {
    const oldBlock = ['function getUserName(u) {', '  const p = load(u);', '  return p.name;', '}'].join('\n')
    const newBlock = ['function getFullName(u) {', '  const p = load(u);', '  return p.name;', '}'].join('\n')
    const changes = parseFileChanges([editMsg('Edit', { file_path: 'C:\\proj\\src\\api.ts', old_string: oldBlock, new_string: newBlock })])
    expect(changes[0].additions).toBe(4)
    expect(changes[0].deletions).toBe(4)
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
