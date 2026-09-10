/**
 * 内联会话引用 chip（#会话引用，2026-09-10 协议定案）：
 * - sessionRefText：markdown 链接 [#标题](#sess_id) 序列化 + 标题转义（桌面端 ky 同款）
 * - buildSessionChipHTML：chip 结构/转义/裸 token 无标题降级
 * - convertCompletedSessionRefs：粘贴/回填场景的 markdown 链接与裸 token 回显（jsdom）
 * - serializeEditor：会话 chip → markdown 链接文本
 */
// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import {
  sessionRefText,
  escapeSessionTitle,
  buildSessionChipHTML,
  convertCompletedSessionRefs,
  serializeEditor,
} from '../src/utils/inlineFileTags'

describe('sessionRefText 序列化', () => {
  it('常规形态：[#标题](#sess_id)', () => {
    expect(sessionRefText('sess_abc-123', '暗号测试')).toBe('[#暗号测试](#sess_abc-123)')
  })

  it('标题含 [ ] \\ 时转义（markdown 链接不破损）', () => {
    expect(sessionRefText('sess_x', '修复 List[] \\bug')).toBe(
      '[#修复 List\\[\\] \\\\bug](#sess_x)',
    )
  })

  it('空标题退化为裸 token #sess_id', () => {
    expect(sessionRefText('sess_x', '')).toBe('#sess_x')
    expect(sessionRefText('sess_x', '   ')).toBe('#sess_x')
  })

  it('escapeSessionTitle 与探针正典一致（\\ [ ]）', () => {
    expect(escapeSessionTitle('a[b]c\\d')).toBe('a\\[b\\]c\\\\d')
  })
})

describe('buildSessionChipHTML', () => {
  it('结构：sess-ref chip + 图标 + 标题 + 删除按钮 + data 属性', () => {
    const html = buildSessionChipHTML('sess_abc', '暗号测试')
    expect(html).toContain('sess-ref--inline')
    expect(html).toContain('codicon-comment-discussion')
    expect(html).toContain('data-sess="sess_abc"')
    expect(html).toContain('data-title="暗号测试"')
    expect(html).toContain('sess-ref__remove')
    expect(html).toContain('contenteditable="false"')
  })

  it('标题 HTML 转义（防注入）', () => {
    const html = buildSessionChipHTML('sess_x', '<img src=x onerror=alert(1)>')
    expect(html).not.toContain('<img')
    expect(html).toContain('&lt;img')
  })

  it('裸 token 无标题：label 用 id 前缀，tooltip 给完整 id', () => {
    const html = buildSessionChipHTML('sess_deadbeef-1234', '')
    expect(html).toContain('deadbeef…')
    expect(html).toContain('data-tip="sess_deadbeef-1234"')
    expect(html).toContain('data-title=""')
  })
})

describe('convertCompletedSessionRefs（jsdom DOM）', () => {
  function makeEditor(text: string): HTMLDivElement {
    const el = document.createElement('div')
    el.textContent = text
    document.body.appendChild(el)
    return el
  }

  it('markdown 链接形态转 chip', () => {
    const el = makeEditor('参考 [#暗号测试](#sess_abc-123) 这个会话')
    expect(convertCompletedSessionRefs(el)).toBe(true)
    const chip = el.querySelector('.sess-ref--inline')
    expect(chip).not.toBeNull()
    expect(chip!.getAttribute('data-sess')).toBe('sess_abc-123')
    expect(chip!.getAttribute('data-title')).toBe('暗号测试')
    // chip 外正文保留
    expect(el.textContent).toContain('参考')
    expect(el.textContent).toContain('这个会话')
  })

  it('markdown 链接标题反转义回 chip', () => {
    const el = makeEditor('[#修复 List\\[\\] bug](#sess_x)')
    convertCompletedSessionRefs(el)
    expect(el.querySelector('.sess-ref--inline')!.getAttribute('data-title')).toBe(
      '修复 List[] bug',
    )
  })

  it('裸 token 词边界转换 + resolver 反查标题', () => {
    const el = makeEditor('见 #sess_abc-123 详情')
    expect(
      convertCompletedSessionRefs(el, (id) => (id === 'sess_abc-123' ? '已命名会话' : undefined)),
    ).toBe(true)
    expect(el.querySelector('.sess-ref--inline')!.getAttribute('data-title')).toBe('已命名会话')
    expect(el.textContent).toContain('详情')
  })

  it('英文字母粘连的 #sess_xxx 不转换（词边界），行首与中文边界转换', () => {
    const el = makeEditor('abc#sess_abc-123 不转\n看看#sess_def-456 中文边界转')
    convertCompletedSessionRefs(el)
    const chips = el.querySelectorAll('.sess-ref--inline')
    expect(chips.length).toBe(1)
    expect(chips[0].getAttribute('data-sess')).toBe('sess_def-456')
  })

  it('非 sess_ 前缀的 #话题标签不转换', () => {
    const el = makeEditor('这是 #普通话题 标签')
    expect(convertCompletedSessionRefs(el)).toBe(false)
    expect(el.querySelector('.sess-ref--inline')).toBeNull()
  })

  it('无引用文本返回 false（幂等安全）', () => {
    const el = makeEditor('普通文本')
    expect(convertCompletedSessionRefs(el)).toBe(false)
  })
})

describe('serializeEditor 会话 chip 分支', () => {
  it('chip → [#标题](#sess_id)；与文件 chip/正文共存保持顺序', () => {
    const el = document.createElement('div')
    el.innerHTML =
      '<span>前文 </span>' +
      buildSessionChipHTML('sess_a1', '暗号测试') +
      '<span> 中段 </span>'
    document.body.appendChild(el)
    const out = serializeEditor(el)
    expect(out).toBe('前文 [#暗号测试](#sess_a1) 中段 ')
  })

  it('无标题 chip → 裸 token', () => {
    const el = document.createElement('div')
    el.innerHTML = buildSessionChipHTML('sess_b2', '')
    document.body.appendChild(el)
    expect(serializeEditor(el)).toContain('#sess_b2')
  })
})
