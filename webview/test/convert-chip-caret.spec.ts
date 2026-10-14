/**
 * convertCompletedXxx 系列转换后的光标钉位（缺陷DY 同族：粘贴文件地址/#会话引用/
 * $技能提及文本转 chip 后光标回到 chip 前面，接着输入的字插进 chip 之前）：
 * - pinCaretAfterChip：Chromium 把恰好落在被转换文本边界上的光标规范到删除点=
 *   chip 之前（父节点 offset 命中 chip 下标 / 收进 chip 前文本节点末尾两种形态）。
 *   jsdom 复现不了该形态（deleteContents 保留删空文本节点，光标语义已在 chip 后），
 *   故导出后直接构造两种形态单测；
 * - convert 端到端（jsdom 形态）：光标停在粘贴文本末尾时转换后不受误伤，
 *   转换功能（chip 生成/序列化）不回归。
 */
// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import {
  pinCaretAfterChip,
  buildFileChipHTML,
  convertCompletedPaths,
  convertCompletedSessionRefs,
  convertCompletedSkillRefs,
  serializeEditor,
} from '../src/utils/inlineFileTags'

function mount(html: string): HTMLElement {
  const el = document.createElement('div')
  el.innerHTML = html
  document.body.appendChild(el)
  return el
}

function setCaret(node: Node, offset: number): void {
  const sel = window.getSelection()!
  const r = document.createRange()
  r.setStart(node, offset)
  r.collapse(true)
  sel.removeAllRanges()
  sel.addRange(r)
}

/** el.childNodes 中 chip 的下标（形态A 光标的 offset 位置）*/
function childIndex(el: HTMLElement, node: Node): number {
  return Array.prototype.indexOf.call(el.childNodes, node)
}

describe('pinCaretAfterChip 光标恰在 chip 前时钉回 chip 后', () => {
  it('形态A（父节点 offset 命中 chip 下标）+ 尾随空格是 nextSibling 首字符：跳过空格', () => {
    const el = mount(`前文${buildFileChipHTML('C:\\a\\b.ts')} 后文`)
    const chip = el.querySelector('.file-ref--inline')!
    setCaret(el, childIndex(el, chip)) // Chromium 规范化形态：删除点=chip 前
    pinCaretAfterChip(chip)
    const r = window.getSelection()!.getRangeAt(0)
    expect(r.startContainer).toBe(chip.nextSibling) // " 后文" 文本节点
    expect(r.startOffset).toBe(1) // 跳过尾随空格
    expect(r.collapsed).toBe(true)
  })

  it('形态A + nextSibling 非空白开头：退而 setStartAfter(chip)', () => {
    const el = mount(`前文${buildFileChipHTML('C:\\a\\b.ts')}后文`)
    const chip = el.querySelector('.file-ref--inline')!
    setCaret(el, childIndex(el, chip))
    pinCaretAfterChip(chip)
    const r = window.getSelection()!.getRangeAt(0)
    expect(r.startContainer).toBe(el)
    expect(r.startOffset).toBe(childIndex(el, chip) + 1)
  })

  it('形态B（光标被收进 chip 前文本节点末尾）：同样钉回 chip 后', () => {
    const el = mount(`前文${buildFileChipHTML('C:\\a\\b.ts')} 后文`)
    const chip = el.querySelector('.file-ref--inline')!
    setCaret(chip.previousSibling!, 2) // "前文"末尾
    pinCaretAfterChip(chip)
    const r = window.getSelection()!.getRangeAt(0)
    expect(r.startContainer).toBe(chip.nextSibling)
    expect(r.startOffset).toBe(1)
  })

  it('光标不在 chip 前（chip 后文本中间）：不动，保持原语义位置', () => {
    const el = mount(`前文${buildFileChipHTML('C:\\a\\b.ts')} 后文`)
    const chip = el.querySelector('.file-ref--inline')!
    setCaret(chip.nextSibling!, 2) // "后文"中间
    pinCaretAfterChip(chip)
    const r = window.getSelection()!.getRangeAt(0)
    expect(r.startContainer).toBe(chip.nextSibling)
    expect(r.startOffset).toBe(2)
  })

  it('光标不在 chip 前（chip 前文本节点中间）：不动', () => {
    const el = mount(`前文${buildFileChipHTML('C:\\a\\b.ts')} 后文`)
    const chip = el.querySelector('.file-ref--inline')!
    setCaret(chip.previousSibling!, 1) // "前文"中间
    pinCaretAfterChip(chip)
    const r = window.getSelection()!.getRangeAt(0)
    expect(r.startContainer).toBe(chip.previousSibling)
    expect(r.startOffset).toBe(1)
  })
})

describe('convert 端到端（jsdom 形态）：光标不受误伤、转换不回归', () => {
  it('convertCompletedPaths：光标停在粘贴文本末尾，转换后仍在编辑器内且 chip 生成正确', () => {
    const el = mount('看这个 G:\\a\\b.txt')
    setCaret(el.firstChild!, el.textContent!.length)
    expect(convertCompletedPaths(el, true)).toBe(true)
    const chip = el.querySelector('.file-ref--inline')
    expect(chip).toBeTruthy()
    // 末尾路径转换后补尾随空格（既有行为：便于继续输入）
    expect(serializeEditor(el)).toBe('看这个 @G:\\a\\b.txt ')
    // jsdom 保留截断文本节点、光标语义在 chip 后：pin 检测不命中、不误伤
    const r = window.getSelection()!.getRangeAt(0)
    expect(el.contains(r.startContainer)).toBe(true)
  })

  it('convertCompletedSessionRefs：同上', () => {
    const el = mount('见 [#旧会话](#sess_abc-123-def)')
    setCaret(el.firstChild!, el.textContent!.length)
    expect(convertCompletedSessionRefs(el)).toBe(true)
    expect(el.querySelector('.sess-ref--inline')).toBeTruthy()
    const r = window.getSelection()!.getRangeAt(0)
    expect(el.contains(r.startContainer)).toBe(true)
  })

  it('convertCompletedSkillRefs：同上', () => {
    const el = mount('跑 [$code-review](/s/review)')
    setCaret(el.firstChild!, el.textContent!.length)
    expect(convertCompletedSkillRefs(el)).toBe(true)
    expect(el.querySelector('[data-skill="1"]')).toBeTruthy()
    const r = window.getSelection()!.getRangeAt(0)
    expect(el.contains(r.startContainer)).toBe(true)
  })
})
