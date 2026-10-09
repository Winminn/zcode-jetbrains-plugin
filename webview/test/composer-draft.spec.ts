/**
 * 输入框草稿持久化（O8）：scope 读写 / 空编辑器 no-op 守卫（StrictMode 安全）/
 * 预算淘汰 / 粘贴映射限量 / 恢复 HTML 净化
 */
// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest'
import {
  persistComposerDraft,
  readComposerDraft,
  clearComposerDraft,
  sanitizeDraftHtml,
  draftScope,
  type ComposerDraft,
} from '../src/utils/composerDraft'
import type { SlashCommand } from '../src/types/messages'

// jsdom 29 的 window.localStorage 是空壳（setItem 等方法缺失）：Map 实现替换
// （agent-enhance.spec / breakdown-cache.spec 同款手法）
const storage = new Map<string, string>()
const lsMock = {
  getItem: (k: string) => storage.get(k) ?? null,
  setItem: (k: string, v: string) => { storage.set(k, v) },
  removeItem: (k: string) => { storage.delete(k) },
  key: (i: number) => Array.from(storage.keys())[i] ?? null,
  get length() { return storage.size },
  clear: () => storage.clear(),
}
Object.defineProperty(window, 'localStorage', { configurable: true, value: lsMock })

const skill = (name: string): SlashCommand => ({ name, kind: 'skill', source: 'user' })

function draftOf(partial: Partial<ComposerDraft>): {
  html: string
  fileRefs: string[]
  skillRefs: SlashCommand[]
  pasteTexts: [string, string][]
} {
  return {
    html: partial.html ?? '',
    fileRefs: partial.fileRefs ?? [],
    skillRefs: partial.skillRefs ?? [],
    pasteTexts: partial.pasteTexts ?? [],
  }
}

beforeEach(() => {
  localStorage.clear()
})

describe('scope 语义', () => {
  it('sessionId 归一：无会话走 __draft__ 待命态', () => {
    expect(draftScope(null)).toBe('__draft__')
    expect(draftScope(undefined)).toBe('__draft__')
    expect(draftScope('sess_abc')).toBe('sess_abc')
  })
})

describe('读写往返', () => {
  it('html + 顶栏引用 + 粘贴映射全保真', () => {
    persistComposerDraft('sess_a', draftOf({
      html: '正文<span class="cmd-ref" data-skill="1" data-cmd="review"></span>',
      fileRefs: ['C:\\a.ts'],
      skillRefs: [skill('review')],
      pasteTexts: [['paste_1', '大段粘贴原文']],
    }))
    const d = readComposerDraft('sess_a')!
    expect(d.html).toContain('data-cmd="review"')
    expect(d.fileRefs).toEqual(['C:\\a.ts'])
    expect(d.skillRefs.map((s) => s.name)).toEqual(['review'])
    expect(d.pasteTexts).toEqual([['paste_1', '大段粘贴原文']])
  })

  it('scope 互不干扰', () => {
    persistComposerDraft('sess_a', draftOf({ html: 'A 的草稿' }))
    persistComposerDraft('sess_b', draftOf({ html: 'B 的草稿' }))
    expect(readComposerDraft('sess_a')?.html).toBe('A 的草稿')
    expect(readComposerDraft('sess_b')?.html).toBe('B 的草稿')
  })

  it('显式清除后读不到；无该 scope 为无害空操作', () => {
    persistComposerDraft('sess_a', draftOf({ html: '内容' }))
    clearComposerDraft('sess_a')
    expect(readComposerDraft('sess_a')).toBeNull()
    expect(() => clearComposerDraft('sess_missing')).not.toThrow()
  })
})

describe('空内容 no-op 守卫（StrictMode 安全）', () => {
  it('空编辑器 persist 不清除已有草稿（dev 双挂载 cleanup 不误清）', () => {
    persistComposerDraft('sess_a', draftOf({ html: '重要草稿' }))
    // StrictMode cleanup 场景：空编辑器跑一次 persist
    persistComposerDraft('sess_a', draftOf({}))
    expect(readComposerDraft('sess_a')?.html).toBe('重要草稿')
  })

  it('顶栏引用也算内容（纯 chip 输入可保存）', () => {
    persistComposerDraft('sess_c', draftOf({ fileRefs: ['/x.ts'] }))
    expect(readComposerDraft('sess_c')?.fileRefs).toEqual(['/x.ts'])
  })
})

describe('预算与淘汰', () => {
  it('超长 html（>24KB）不持久化', () => {
    persistComposerDraft('sess_big', draftOf({ html: 'x'.repeat(25 * 1024) }))
    expect(readComposerDraft('sess_big')).toBeNull()
  })

  it('粘贴原文超长条目丢弃、条目数截断', () => {
    persistComposerDraft('sess_p', draftOf({
      html: '有内容',
      pasteTexts: [
        ['p1', 'a'.repeat(3000)], // 超单条上限，丢弃
        ['p2', 'ok'],
        ...Array.from({ length: 15 }, (_, i) => [`px${i}`, 'v'] as [string, string]), // 超条目数
      ],
    }))
    const d = readComposerDraft('sess_p')!
    expect(d.pasteTexts.find(([k]) => k === 'p1')).toBeUndefined()
    expect(d.pasteTexts.find(([k]) => k === 'p2')).toEqual(['p2', 'ok'])
    expect(d.pasteTexts.length).toBeLessThanOrEqual(10)
  })

  it('scope 超 30 个淘汰最旧，当前 scope 免淘', () => {
    const base = Date.now()
    // 直接铺 30 个旧 scope（按 updatedAt 排序用 updatedAt 字段——persist 用 Date.now()，
    // 依次写自然递增，最后写的最新）
    for (let i = 0; i < 30; i++) {
      persistComposerDraft(`old_${i}`, draftOf({ html: `旧草稿${i}` }))
    }
    persistComposerDraft('current', draftOf({ html: '当前草稿' }))
    expect(readComposerDraft('current')?.html).toBe('当前草稿')
    expect(readComposerDraft('old_0')).toBeNull() // 最旧被淘汰
    expect(readComposerDraft('old_29')?.html).toBe('旧草稿29')
  })
})

describe('恢复净化（sanitizeDraftHtml）', () => {
  it('chip span 与 data-* 属性保留', () => {
    const html = '<span class="cmd-ref" contenteditable="false" data-cmd="x" data-tip="t">x</span>'
    expect(sanitizeDraftHtml(html)).toContain('data-cmd="x"')
  })

  it('script/iframe 剥除但文本保留', () => {
    const out = sanitizeDraftHtml('正文<script>alert(1)</script>后续')
    expect(out).not.toContain('<script')
    expect(out).toContain('正文')
    expect(out).toContain('后续')
  })

  it('on* 事件属性与 javascript: 链接剥除', () => {
    const out = sanitizeDraftHtml('<span onclick="evil()" data-a="1">t</span>')
    expect(out).not.toContain('onclick')
    expect(out).toContain('data-a="1"')
    const out2 = sanitizeDraftHtml('<span href="javascript:evil()">t</span>')
    expect(out2).not.toContain('javascript:')
  })
})
