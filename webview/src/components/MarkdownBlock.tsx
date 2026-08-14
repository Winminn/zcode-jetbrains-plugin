/**
 * Markdown 渲染块
 *
 * 规划文档第四节：assistant 消息满宽 Markdown 渲染。
 * 内部用 BlockSection 逐块 memo，流式优化。
 *
 * 用法：
 *   <MarkdownBlock markdown={text} />
 *   <MarkdownBlock markdown={text} streaming />  // 流式中的最后一段
 */

import { useMemo } from 'react'
import { BlockSection, splitMarkdownBlocks } from './BlockSection'
import '../styles/markdown.less'

interface Props {
  markdown: string
  /** 是否在流式中（影响最后一个块的 streamSafe 补全）*/
  streaming?: boolean
}

export function MarkdownBlock({ markdown, streaming = false }: Props) {
  const blocks = useMemo(() => splitMarkdownBlocks(markdown), [markdown])

  if (blocks.length === 0) return null

  return (
    <div className="markdown-body">
      {blocks.map((block, i) => (
        <BlockSection
          key={i}
          markdown={block}
          // 只有最后一个块可能是流式中（在增长），前面的块都是完整的
          isStreaming={streaming && i === blocks.length - 1}
        />
      ))}
      {streaming && <span className="markdown-body__cursor">▋</span>}
    </div>
  )
}
