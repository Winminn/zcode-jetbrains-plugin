/**
 * .md-file-link 点击 → openFile op（MarkdownBlock / ThinkingBlock 事件委托共用）。
 *
 * 通道与 ToolCallCard/FileToolGroupCard 同款（Kotlin handleOpenFile 零改动）：
 * filePath 必传（linkifyFilePaths 已解析成绝对路径），line 有则带（1 基，
 * Kotlin 侧超界自动 clamp）。
 *
 * 返回 true = 命中并已处理（调用方不再走其它点击分支）。
 */

import type { MouseEvent } from 'react'
import { sendToJava } from '@/ipc/bridge'

export function openFileLinkFromEvent(e: MouseEvent<HTMLElement>): boolean {
  const el = (e.target as HTMLElement | null)?.closest<HTMLElement>('.md-file-link')
  if (!el) return false
  e.preventDefault()
  const filePath = el.dataset.filePath
  if (!filePath) return true
  const line = Number(el.dataset.fileLine)
  sendToJava(line > 0 ? { op: 'openFile', filePath, line } : { op: 'openFile', filePath })
  return true
}
