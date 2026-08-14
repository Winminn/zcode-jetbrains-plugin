/**
 * 流式 Markdown 安全补全
 *
 * 规划文档第二节第 1 点（来源 cc-gui MarkdownBlock.tsx 的 makeStreamSafe）：
 *   流式 token 逐字到达时，文本可能停在代码围栏 ``` 或数学公式 $$ 中间。
 *   渲染前先检测未闭合的结构，临时补闭合符，让 marked 能正常解析，
 *   等真正闭合的 token 到达后补全变 no-op，无闪烁。
 */

/**
 * 检测并补全未闭合的 Markdown 结构。
 * @param md 原始 markdown 文本（可能不完整）
 * @returns 补全后的文本（可直接交给 marked 解析）
 */
export function makeStreamSafe(md: string): string {
  let result = md

  // 1. 代码围栏 ``` 或 ~~~
  result = closeUnclosedFence(result)

  // 2. 行内代码反引号（奇数个未配对）
  result = closeUnclosedInlineCode(result)

  return result
}

/**
 * 补全未闭合的代码围栏。
 * 只处理围栏的"开"和"闭"，不关心围栏内的内容（围栏内的 ``` 不是结束）。
 */
function closeUnclosedFence(md: string): string {
  const lines = md.split('\n')
  let openFence: string | null = null // 当前打开的围栏标记 "```" 或 "~~~"
  let fenceIndent = ''

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const match = line.match(/^(\s*)(`{3,}|~{3,})/)
    if (!match) continue

    const indent = match[1]
    const marker = match[2]
    const char = marker[0] // ` 或 ~
    const markerPrefix = char.repeat(3)

    if (openFence === null) {
      // 可能是新开的围栏（只有以 ``` 或 ~~~ 开头才算）
      if (line.trimStart().startsWith(markerPrefix)) {
        openFence = char
        fenceIndent = indent
      }
    } else if (openFence === char && indent === fenceIndent) {
      // 同样缩进的同字符围栏 → 闭合
      openFence = null
    }
    // 围栏内的其他行（包括其他 ``` ）忽略
  }

  // 如果还有未闭合的围栏，补一个闭合行
  if (openFence) {
    return md + '\n' + openFence.repeat(3)
  }
  return md
}

/**
 * 补全未配对的行内代码反引号。
 * 统计不在代码围栏内的反引号数量，奇数则在末尾补一个。
 */
function closeUnclosedInlineCode(md: string): string {
  // 简化处理：统计整段里反引号出现次数（不区分围栏内外，围栏场景已由上面处理）
  // 这个近似在多数场景下足够，cc-gui 也是近似处理
  const backtickCount = (md.match(/`/g) || []).length
  if (backtickCount % 2 !== 0) {
    return md + '`'
  }
  return md
}
