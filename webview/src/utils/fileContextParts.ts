/**
 * 当前文件上下文附件 → 用户气泡 chip 的数据桥（纯函数，无 React 依赖）。
 *
 * chip 数据有两个来源，渲染层统一消费：
 * - 服务端转录（快照回放 / 历史重拉）：currentFile 附件经 Java 转 kind:'file' +
 *   textContent 发出，zcode.cjs 落库为 user 消息的 file part（2026-10-08 本机
 *   db.part 实证形态）：{type:'file', mime, filename: basename, url: 完整路径,
 *   metadata: {originalUrl, preview: {text}}}——preview.text 头行自描述路径与
 *   行号区间（CurrentFileAttachment 装配的 "[Selected code from PATH, lines X-Y
 *   of N]" / "[Full content of PATH, N lines]"，正文每行带 N\t 行号前缀）。
 * - 本地乐观消息（useStore.sendMessage）：发送瞬间用附件描述构造同构轻 part，
 *   行号直接放 metadata.selection（结构化，无需解析）——乐观消息是"就地改名"
 *   接服务端 id（streamReducer），parts 不会被服务端帧覆盖，快照整包替换时才由
 *   同构的服务端 part 顶替，chip 无缝接管。
 *
 * 提取判据：type='file' 且 mime 非 image/*（图片附件走 collectImageParts 通道，
 * 两者互斥）。用户消息里 file part 的唯一来源就是上下文附件通道——正文里的
 * @路径/文件引用走文本序列化（renderUserRefChips 的 chip 化），不产生 part。
 */
import type { CurrentFileAttachmentInput, FilePart, MessagePart } from '@/types/messages'

export interface FileContextChip {
  /** 完整路径（服务端 url / metadata.originalUrl / 乐观附件 path 三源一致）*/
  path: string
  /** 展示名（服务端 part 已是 basename；乐观构造时同样归一）*/
  filename: string
  /** 选区行号（1 起，含）。整文件附件无行号 */
  lineStart?: number
  lineEnd?: number
}

/**
 * 从 preview.text 头行解析选区行号。头行格式（CurrentFileAttachment.kt）：
 *   "[Selected code from PATH, lines X-Y of N]" / "[Selected code from PATH, line X of N]"
 * 整文件头行 "[Full content of PATH, N lines]" 无行号 → 返回 null。
 */
export function parseSelectionFromPreview(text: string | undefined): { lineStart: number; lineEnd: number } | null {
  if (!text) return null
  const head = text.split('\n', 1)[0] ?? ''
  const range = head.match(/\[Selected code from .*?, lines (\d+)-(\d+) of \d+\]/)
  if (range) return { lineStart: Number(range[1]), lineEnd: Number(range[2]) }
  const single = head.match(/\[Selected code from .*?, line (\d+) of \d+\]/)
  if (single) return { lineStart: Number(single[1]), lineEnd: Number(single[1]) }
  return null
}

/** Windows 反斜杠归一后取 basename（IDE 侧路径分隔符不保证形态）*/
export function basenamePosix(path: string): string {
  return path.replace(/\\/g, '/').split('/').filter(Boolean).pop() ?? path
}

/**
 * file part → chip 信息。无路径 / 非磁盘路径（http 图片代理、zcode-artifact
 * 产物引用）返回 null 跳过——上下文附件的 url 恒为本地磁盘路径。
 */
export function fileContextChipFromPart(p: FilePart): FileContextChip | null {
  const meta = (p.metadata ?? {}) as Record<string, unknown>
  const path = (p.url ?? (meta.originalUrl as string | undefined) ?? '').trim()
  if (!path || /^https?:\/\//.test(path) || path.startsWith('zcode-artifact://')) return null
  const selection =
    (meta.selection as { lineStart?: number; lineEnd?: number } | undefined) ??
    parseSelectionFromPreview((meta.preview as { text?: string } | undefined)?.text)
  return {
    path,
    filename: p.filename ?? basenamePosix(path),
    ...(selection?.lineStart != null
      ? { lineStart: selection.lineStart, lineEnd: selection.lineEnd ?? selection.lineStart }
      : {}),
  }
}

/** 用户消息 parts → 上下文 chip 列表（保持出现顺序）*/
export function collectFileContextChips(parts: MessagePart[]): FileContextChip[] {
  const out: FileContextChip[] = []
  for (const p of parts) {
    if (p.type !== 'file') continue
    if ((p.mime ?? '').startsWith('image/')) continue
    const chip = fileContextChipFromPart(p)
    if (chip) out.push(chip)
  }
  return out
}

/**
 * 乐观消息用：附件描述 → 与服务端落库形态同构的 file part（气泡 chip 回显源）。
 * 行号放 metadata.selection（结构化，渲染层优先于 preview.text 解析）。
 */
export function fileContextPartFromAttachment(a: CurrentFileAttachmentInput): FilePart {
  return {
    type: 'file',
    mime: 'text/plain',
    filename: basenamePosix(a.path),
    url: a.path,
    metadata: {
      originalUrl: a.path,
      ...(a.lineStart != null
        ? { selection: { lineStart: a.lineStart, lineEnd: a.lineEnd ?? a.lineStart } }
        : {}),
    },
  }
}
