/**
 * 内联粘贴文本 chip（issue #22②：超阈值粘贴跟随光标）：
 * - buildPasteChipHTML：chip 结构（data-paste-id 携带 id、原文不进 DOM）、label/tip 转义
 * - serializeEditor + pasteText resolver：原文按位展开、resolver 缺失降级占位
 * - hasAnyInlineChip：四类内联 chip 存在判定（canSend/hasText 兜底）
 * - 与文件 chip/正文混排时位置关系保留在文本流中（jsdom）
 */
// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import {
  buildPasteChipHTML,
  buildFileChipHTML,
  serializeEditor,
  hasAnyInlineChip,
  insertPasteChipAtCursor,
} from '../src/utils/inlineFileTags'

function mount(html: string): HTMLElement {
  const el = document.createElement('div')
  el.innerHTML = html
  return el
}

describe('buildPasteChipHTML 结构', () => {
  it('data-paste-id 携带 id，原文不进 DOM（调用方映射持有）', () => {
    const html = buildPasteChipHTML('paste_123_abc', '粘贴文本 · 1075 字', '点击预览完整内容')
    expect(html).toContain('data-paste-id="paste_123_abc"')
    expect(html).toContain('paste-ref--inline')
    expect(html).toContain('contenteditable="false"')
    expect(html).toContain('粘贴文本 · 1075 字')
    expect(html).not.toContain('data-paste-text')
  })

  it('label/tip HTML 转义', () => {
    const html = buildPasteChipHTML('paste_x', '粘贴 <b> & "文本"', 'tip <a>')
    expect(html).toContain('粘贴 &lt;b&gt; &amp; &quot;文本&quot;')
    expect(html).toContain('data-tip="tip &lt;a&gt;"')
    expect(html).not.toContain('<b>')
  })
})

describe('serializeEditor 粘贴 chip 按位展开', () => {
  const resolver = (id: string) => (id === 'p1' ? '第一段粘贴原文' : id === 'p2' ? 'second pasted\ntext' : undefined)

  it('chip 在正文中间时原文按位置展开（上下文顺序保留）', () => {
    const el = mount('实例代码如下：<span class="paste-ref--inline" data-paste-id="p1"></span> 请解析')
    const out = serializeEditor(el, { pasteText: resolver })
    expect(out).toBe('实例代码如下：第一段粘贴原文 请解析')
  })

  it('多 chip + 文件 chip 混排，各自按位展开', () => {
    const el = mount(
      '前' +
        '<span class="paste-ref--inline" data-paste-id="p1"></span>' +
        '中' +
        '<span class="file-ref--inline" data-path="C:\\a\\b.ts"></span>' +
        '<span class="paste-ref--inline" data-paste-id="p2"></span>' +
        '后',
    )
    const out = serializeEditor(el, { pasteText: resolver })
    expect(out).toBe('前第一段粘贴原文中@C:\\a\\b.tssecond pasted\ntext后')
  })

  it('resolver 未命中降级占位（原文映射与 chip 生命周期由调用方保证）', () => {
    const el = mount('<span class="paste-ref--inline" data-paste-id="missing"></span>')
    expect(serializeEditor(el, { pasteText: resolver })).toBe('[粘贴内容已丢失]')
  })

  it('不传 opts 同样降级占位，不抛错（旧调用点兼容）', () => {
    const el = mount('a<span class="paste-ref--inline" data-paste-id="p1"></span>b')
    expect(serializeEditor(el)).toBe('a[粘贴内容已丢失]b')
  })

  it('chip 是 contenteditable=false 子树：文本遍历不吸入其内部节点', () => {
    const html = buildPasteChipHTML('p1', '粘贴文本 · 7 字', 'tip')
    const el = mount(`x${html}y`)
    const out = serializeEditor(el, { pasteText: resolver })
    expect(out).toBe('x第一段粘贴原文y')
  })
})

describe('hasAnyInlineChip 四类 chip 判定', () => {
  it('粘贴 chip / 文件 chip 命中，纯文本不命中', () => {
    expect(hasAnyInlineChip(mount('<span class="paste-ref--inline" data-paste-id="p"></span>'))).toBe(true)
    expect(hasAnyInlineChip(mount(buildFileChipHTML('C:\\a\\b.ts')))).toBe(true)
    expect(hasAnyInlineChip(mount('纯文本正文'))).toBe(false)
    expect(hasAnyInlineChip(mount(''))).toBe(false)
  })
})

describe('insertPasteChipAtCursor 光标位置', () => {
  it('execCommand 成功路径：光标钉到尾随空格之后（空白编辑器 Chromium 会把光标收到 chip 前面）', () => {
    const el = mount('')
    document.body.appendChild(el)
    const doc = document as Document & { execCommand?: unknown }
    const orig = doc.execCommand
    // 模拟浏览器 insertHTML：同步插入 html 并返回 true（jsdom 无 execCommand）
    doc.execCommand = () => {
      el.innerHTML = buildPasteChipHTML('p_caret', '粘贴文本 · 10 字', 'tip') + ' '
      return true
    }
    try {
      expect(insertPasteChipAtCursor(el, 'p_caret', '粘贴文本 · 10 字', 'tip')).toBe(true)
    } finally {
      doc.execCommand = orig
    }
    const chip = el.querySelector('[data-paste-id="p_caret"]') as HTMLElement
    const sel = window.getSelection()!
    expect(sel.rangeCount).toBe(1)
    const r = sel.getRangeAt(0)
    // 光标必须钉到 chip 之后的尾随空格之后（setStartAfter(space) → container=el、
    // offset 越过 [chip, " "]），而非 chip 之前
    expect(chip.nextSibling?.nodeType).toBe(Node.TEXT_NODE)
    expect(r.startContainer).toBe(el)
    expect(r.startOffset).toBe(2)
    expect(r.collapsed).toBe(true)
  })

  it('execCommand 成功但尾随空格被浏览器清理：光标退而钉到 chip 之后', () => {
    const el = mount('')
    document.body.appendChild(el)
    const doc = document as Document & { execCommand?: unknown }
    const orig = doc.execCommand
    doc.execCommand = () => {
      el.innerHTML = buildPasteChipHTML('p_caret2', '标签', 'tip')
      return true
    }
    try {
      expect(insertPasteChipAtCursor(el, 'p_caret2', '标签', 'tip')).toBe(true)
    } finally {
      doc.execCommand = orig
    }
    const chip = el.querySelector('[data-paste-id="p_caret2"]') as HTMLElement
    const r = window.getSelection()!.getRangeAt(0)
    expect(r.startContainer).toBe(el)
    expect(el.childNodes[r.startOffset - 1]).toBe(chip)
  })
})
