/**
 * parsePartialToolInput 单测：任意截断点安全是核心承诺
 * （tool_input_delta 累积的 inputRaw 在任意长度下被调用）
 */
import { describe, it, expect } from 'vitest'
import { parsePartialToolInput, lineCount, tailLines } from '@/utils/partialToolInput'

describe('parsePartialToolInput 完整 JSON', () => {
  it('解析全部字符串字段并解码转义', () => {
    const raw = '{"file_path":"/a/b.ts","content":"line1\\nline2\\n"}'
    const r = parsePartialToolInput(raw)
    expect(r.fields).toEqual({ file_path: '/a/b.ts', content: 'line1\nline2\n' })
    expect(r.openField).toBeUndefined()
    expect(r.openPrefix).toBeUndefined()
  })

  it('容忍字段间空白与换行（多行 JSON 格式）', () => {
    const raw = '{\n  "file_path": "x.ts",\n  "content": "a"\n}'
    const r = parsePartialToolInput(raw)
    expect(r.fields).toEqual({ file_path: 'x.ts', content: 'a' })
  })

  it('跳过非字符串值（布尔/数字）', () => {
    const raw = '{"file_path":"a.ts","replace_all":true,"line":42,"content":"c"}'
    const r = parsePartialToolInput(raw)
    expect(r.fields).toEqual({ file_path: 'a.ts', content: 'c' })
  })

  it('解码 Windows 路径的反斜杠转义', () => {
    const raw = '{"file_path":"G:\\\\AI\\\\x.ts","content":""}'
    const r = parsePartialToolInput(raw)
    expect(r.fields.file_path).toBe('G:\\AI\\x.ts')
  })

  it('解码 \\uXXXX 与全部标准转义', () => {
    const raw = '{"s":"a\\u4e2db\\t\\\\c\\"d\\/e\\rf"}'
    const r = parsePartialToolInput(raw)
    expect(r.fields.s).toBe('a中b\t\\c"d/e\rf')
  })
})

describe('parsePartialToolInput 截断矩阵', () => {
  // 完整 Write 输入样本（含各字段顺序与转义）
  const full = '{"file_path":"src/main.ts","content":"const a = 1;\\nconst b = 2;\\n"}'

  it('任意前缀不抛错且结果满足前缀不变量', () => {
    for (let len = 0; len <= full.length; len++) {
      const r = parsePartialToolInput(full.slice(0, len))
      // 不变量 1：file_path 一旦出现必须是完整值的逐段前缀
      const fp = (r.fields.file_path ?? '') + (r.openField === 'file_path' ? (r.openPrefix ?? '') : '')
      expect('src/main.ts'.startsWith(fp)).toBe(true)
      // 不变量 2：content 同理
      const ct = (r.fields.content ?? '') + (r.openField === 'content' ? (r.openPrefix ?? '') : '')
      expect('const a = 1;\nconst b = 2;\n'.startsWith(ct)).toBe(true)
    }
  })

  it('key 半截：无字段无 openField', () => {
    const r = parsePartialToolInput('{"file_pa')
    expect(r.fields).toEqual({})
    expect(r.openField).toBeUndefined()
  })

  it('key 完整但冒号未到', () => {
    const r = parsePartialToolInput('{"file_path"')
    expect(r.fields).toEqual({})
    expect(r.openField).toBeUndefined()
  })

  it('冒号后截断：openField 已知、前缀为空', () => {
    const r = parsePartialToolInput('{"file_path":')
    expect(r.openField).toBe('file_path')
    expect(r.openPrefix).toBe('')
    expect(r.fields).toEqual({})
  })

  it('值字符串半截', () => {
    const r = parsePartialToolInput('{"file_path":"src/ma')
    expect(r.openField).toBe('file_path')
    expect(r.openPrefix).toBe('src/ma')
  })

  it('尾部孤立反斜杠：pending 转义不计入前缀', () => {
    const r = parsePartialToolInput('{"content":"a\\')
    expect(r.openField).toBe('content')
    expect(r.openPrefix).toBe('a')
  })

  it('\\uXXXX 半截：pending 不计入前缀', () => {
    const r = parsePartialToolInput('{"content":"a\\u4e')
    expect(r.openPrefix).toBe('a')
  })

  it('多字段中途：前一字段完整、当前字段 open', () => {
    const r = parsePartialToolInput('{"file_path":"a.ts","content":"x\\ny')
    expect(r.fields.file_path).toBe('a.ts')
    expect(r.openField).toBe('content')
    expect(r.openPrefix).toBe('x\ny')
  })

  it('对象闭合后解析完成', () => {
    const r = parsePartialToolInput('{"file_path":"a.ts"}')
    expect(r.fields).toEqual({ file_path: 'a.ts' })
    expect(r.openField).toBeUndefined()
  })
})

describe('parsePartialToolInput 异常输入回退', () => {
  it('空串', () => {
    expect(parsePartialToolInput('')).toEqual({ fields: {} })
  })
  it('非对象 JSON（数组）', () => {
    expect(parsePartialToolInput('[1,2]')).toEqual({ fields: {} })
  })
  it('纯文本', () => {
    expect(parsePartialToolInput('something went wrong')).toEqual({ fields: {} })
  })
  it('只有左花括号', () => {
    const r = parsePartialToolInput('{')
    expect(r.fields).toEqual({})
    expect(r.openField).toBeUndefined()
  })
})

describe('lineCount（流式/完成态统一口径）', () => {
  it.each([
    ['', 0],
    ['a', 1],
    ['a\nb', 2],
    ['a\n', 1],
    ['a\nb\n', 2],
    ['\n\n', 2],
    ['a\nb\nc\nd', 4],
  ])('%j → %d 行', (s, want) => {
    expect(lineCount(s)).toBe(want)
  })

  it('流式前缀与完成态数字衔接不回跳', () => {
    // content = "a\nb\nc"（3 行）逐前缀累计，完成后 = 3
    const content = 'a\nb\nc'
    const counts: number[] = []
    for (let i = 1; i <= content.length; i++) counts.push(lineCount(content.slice(0, i)))
    // 每一步不小于上一步（单调不减）
    for (let i = 1; i < counts.length; i++) expect(counts[i]).toBeGreaterThanOrEqual(counts[i - 1])
    expect(counts[counts.length - 1]).toBe(3)
  })
})

describe('tailLines 尾部窗口', () => {
  it('空串', () => {
    expect(tailLines('')).toEqual({ text: '', truncated: false })
  })
  it('不足窗口：全文返回不截断', () => {
    expect(tailLines('a\nb\nc')).toEqual({ text: 'a\nb\nc', truncated: false })
  })
  it('刚好 15 行：不截断', () => {
    const s = Array.from({ length: 15 }, (_, i) => `l${i}`).join('\n')
    const r = tailLines(s)
    expect(r.truncated).toBe(false)
    expect(r.text).toBe(s)
  })
  it('超过 15 行：取尾部 15 行并标记截断', () => {
    const s = Array.from({ length: 20 }, (_, i) => `l${i}`).join('\n')
    const r = tailLines(s)
    expect(r.truncated).toBe(true)
    expect(r.text).toBe(Array.from({ length: 15 }, (_, i) => `l${i + 5}`).join('\n'))
  })
  it('自定义窗口大小', () => {
    const r = tailLines('a\nb\nc\nd', 2)
    expect(r).toEqual({ text: 'c\nd', truncated: true })
  })
})
