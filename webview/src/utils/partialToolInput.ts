/**
 * 部分 JSON 工具输入解析（Write/Edit 流式进度渲染的基础）
 *
 * 数据背景：模型生成工具参数时，tool_input_delta 事件把 JSON 文本片段
 * 逐段累积进 ToolPart.state.inputRaw（streamReducer.ts）。参数没生成完
 * 之前 JSON 不完整、无法 JSON.parse——但 file_path 排在前面很快完整，
 * content/new_string 是最后未闭合的大字符串。本模块用线性状态机从
 * "任意截断点的部分 JSON"里增量提取：
 *   - fields：已完整出现的字符串字段（转义已解码）
 *   - openField/openPrefix：正在生成中的字段名与已解码前缀
 *
 * 渲染层据此在流式期间显示文件名 + 已写入行数（随 delta 累计），
 * 替代旧版"展开看原始 JSON 一行行变长"的展示。
 *
 * 容错约定：输入不是 '{' 开头的对象（异常/非 JSON）返回空 fields，
 * 调用方回退原文展示——本解析器只做增强，不做阻断。
 */

export interface PartialToolInput {
  /** 已完整出现（字符串闭合）的字段，值为转义解码后的文本 */
  fields: Record<string, string>
  /** 正在生成中的字段名（JSON 字符串尚未闭合）*/
  openField?: string
  /** openField 的已解码前缀 */
  openPrefix?: string
}

interface ReadStringResult {
  closed: boolean
  /** 解码后内容（closed=true 为完整值；false 为已到达的前缀）*/
  value: string
  /** closed 时为闭合引号后一位；未闭合时等于 raw.length */
  end: number
}

/** JSON 字符串转义表（\uXXXX 单独处理）*/
const ESCAPES: Record<string, string> = {
  '"': '"',
  '\\': '\\',
  '/': '/',
  b: '\b',
  f: '\f',
  n: '\n',
  r: '\r',
  t: '\t',
}

function isWs(code: number): boolean {
  return code === 0x20 || code === 0x09 || code === 0x0a || code === 0x0d
}

/** 读一个 JSON 字符串（start 指向开头引号），容忍任意截断点与非法转义 */
function readString(raw: string, start: number): ReadStringResult {
  let i = start + 1
  const n = raw.length
  const out: string[] = []
  let chunkStart = i
  while (i < n) {
    const c = raw.charCodeAt(i)
    if (c === 0x22 /* " */) {
      out.push(raw.slice(chunkStart, i))
      return { closed: true, value: out.join(''), end: i + 1 }
    }
    if (c === 0x5c /* \ */) {
      out.push(raw.slice(chunkStart, i))
      const next = i + 1
      if (next >= n) {
        // 尾部孤立反斜杠：转义半截，pending 部分不计入前缀
        return { closed: false, value: out.join(''), end: n }
      }
      const e = raw[next]
      if (ESCAPES[e] !== undefined) {
        out.push(ESCAPES[e])
        i = next + 1
      } else if (e === 'u') {
        // \uXXXX：需要 next+1..next+4 四个 hex
        if (next + 5 > n) {
          return { closed: false, value: out.join(''), end: n }
        }
        const hex = raw.slice(next + 1, next + 5)
        if (!/^[0-9a-fA-F]{4}$/.test(hex)) {
          // 非法 hex：按字面 u 容错
          out.push('u')
          i = next + 1
        } else {
          out.push(String.fromCharCode(parseInt(hex, 16)))
          i = next + 5
        }
      } else {
        // 非法转义：按字面容错
        out.push(e)
        i = next + 1
      }
      chunkStart = i
      continue
    }
    i++
  }
  out.push(raw.slice(chunkStart, n))
  return { closed: false, value: out.join(''), end: n }
}

/**
 * 解析部分 JSON 对象文本（tool_input_delta 累积的 inputRaw）。
 * 任意截断点安全：key 半截 / 冒号未到 / 值字符串未闭合 / 转义半截均正确停留。
 */
export function parsePartialToolInput(raw: string): PartialToolInput {
  const result: PartialToolInput = { fields: {} }
  if (!raw) return result
  let i = 0
  const n = raw.length
  while (i < n && isWs(raw.charCodeAt(i))) i++
  if (i >= n || raw.charCodeAt(i) !== 0x7b /* { */) return result
  i++

  while (i < n) {
    // 跳过空白与逗号（字段分隔）
    while (i < n && (isWs(raw.charCodeAt(i)) || raw.charCodeAt(i) === 0x2c /* , */)) i++
    if (i >= n) break
    const c = raw.charCodeAt(i)
    if (c === 0x7d /* } */) break // 对象闭合，解析完成
    if (c !== 0x22 /* " */) {
      i++ // 非法字符（非字符串 key），跳过容错
      continue
    }
    const keyRes = readString(raw, i)
    if (!keyRes.closed) break // key 半截
    const key = keyRes.value
    i = keyRes.end
    while (i < n && isWs(raw.charCodeAt(i))) i++
    if (i >= n) break
    if (raw.charCodeAt(i) !== 0x3a /* : */) continue // 无冒号，回到主循环容错
    i++
    while (i < n && isWs(raw.charCodeAt(i))) i++
    if (i >= n) {
      // 冒号后截断：值即将开始
      result.openField = key
      result.openPrefix = ''
      break
    }
    if (raw.charCodeAt(i) === 0x22 /* " */) {
      const valRes = readString(raw, i)
      if (!valRes.closed) {
        result.openField = key
        result.openPrefix = valRes.value
        break
      }
      result.fields[key] = valRes.value
      i = valRes.end
    } else {
      // 非字符串值（数字/true/false/null）：跳到 , 或 }
      while (i < n) {
        const cc = raw.charCodeAt(i)
        if (cc === 0x2c || cc === 0x7d) break
        i++
      }
    }
  }
  return result
}

/** 行数统计（末尾换行不计）。流式前缀与完成态共用同一口径，保证数字累计不回跳 */
export function lineCount(s: string): number {
  if (!s) return 0
  const t = s.replace(/\n$/, '')
  return t ? t.split('\n').length : 0
}

/** 流式预览只渲染尾部窗口：返回最后 max 行与是否发生截断 */
export function tailLines(s: string, max = 15): { text: string; truncated: boolean } {
  if (!s) return { text: '', truncated: false }
  let count = 0
  let i = s.length
  while (count < max) {
    const nl = s.lastIndexOf('\n', i - 1)
    if (nl < 0) return { text: s, truncated: false }
    i = nl
    count++
  }
  return { text: s.slice(i + 1), truncated: true }
}
