/**
 * 会话置顶分层排序纯函数测试（sortSessionsByPin）
 *
 * 契约：置顶组在前、组内保持原序（稳定）；上游已按 updatedAt 倒序收口，
 * 此处不重排时间——置顶组内先后仍按活动时间，unpin 后回到原时间序位置。
 */
import { describe, it, expect } from 'vitest'
import { sortSessionsByPin } from '@/utils/sessionPinSort'

interface Row {
  id: string
  label: string
}

const row = (id: string, label: string): Row => ({ id, label })

describe('sortSessionsByPin', () => {
  it('无置顶集：原数组原样返回（引用相等，零拷贝快路径）', () => {
    const list = [row('a', '1'), row('b', '2')]
    expect(sortSessionsByPin(list, [], (r) => r.id)).toBe(list)
  })

  it('置顶项提到最前，未置顶组保持原序', () => {
    const list = [row('a', '1'), row('b', '2'), row('c', '3')]
    const out = sortSessionsByPin(list, ['c'], (r) => r.id)
    expect(out.map((r) => r.id)).toEqual(['c', 'a', 'b'])
  })

  it('多置顶项组内保持原序（不按 pinnedIds 顺序重排）', () => {
    const list = [row('a', '1'), row('b', '2'), row('c', '3'), row('d', '4')]
    const out = sortSessionsByPin(list, ['d', 'b'], (r) => r.id)
    // b 在 d 前（原数组序），置顶组整体在前
    expect(out.map((r) => r.id)).toEqual(['b', 'd', 'a', 'c'])
  })

  it('pinnedIds 含列表外 id（跨工作区/已删会话）不影响结果', () => {
    const list = [row('a', '1'), row('b', '2')]
    const out = sortSessionsByPin(list, ['ghost', 'a'], (r) => r.id)
    expect(out.map((r) => r.id)).toEqual(['a', 'b'])
  })

  it('重复置顶 id 只提升一次（Set 去重，无重复项产出）', () => {
    const list = [row('a', '1'), row('b', '2')]
    const out = sortSessionsByPin(list, ['a', 'a'], (r) => r.id)
    expect(out.map((r) => r.id)).toEqual(['a', 'b'])
  })
})
