package com.zcode.ideaplugin.ui

import com.zcode.ideaplugin.protocol.model.AttachmentInput
import java.nio.charset.Charset
import java.nio.charset.MalformedInputException
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * 当前文件上下文附件解析（webview op 层 kind:'currentFile' 描述 → zcode.cjs kind:'file' 附件）。
 *
 * 通道依据（ZCode-main 源码 + 沙箱实测坐实，2026-10-08 修正）：
 * - mapProtocolPromptAttachment（apps/zcode-cli packages/bootstrap zcode-protocol/
 *   server-operations.ts:501-592）：textContent → {content, path=filename} 内联文本块；
 *   localPath → 路径引用（文本类服务端读全文、二进制仅注入路径占位）。
 * - inline 文本块经 resolvedInlineTextAttachment（core runtime/helpers/
 *   attachment-path-reference.ts:15-38）不带 source，**走不到伪装 Read 工具结果分支**
 *   （conversation.ts shouldAddReadLikePromptAttachmentReminder 要求 source，仅服务端
 *   本地读文件路线才有），而是落 inline_text system-reminder（conversation.ts:249-259
 *   → prompt-attachment.ts buildInlineAttachmentReminderBodies）：模型可见 =
 *   `Attached inline text: <basename(path)>` + 原文逐字。label 被 basename 剥掉目录、
 *   正文不带任何行号——2026-10-08 实测 AI 因此不知道"这里"是哪个文件哪几行。
 *
 * 因此 textContent 必须自描述：首行头标注完整绝对路径 + 原始行号区间 + 总行数，
 * 正文每行带原始行号前缀（`N\t` 与 zcode Read 工具输出 addReadLineNumbers 同构，
 * read-text.ts），inline_text 分支逐字透传不会二次编号。模型据此知道"这里"是
 * 哪个文件哪几行；需要更多上下文时可直接 Read 该绝对路径。
 *
 * 本对象纯函数/纯 java.nio，无 IntelliJ 依赖，JUnit 可直接测。文件内容来源由
 * 调用方（ZCodeToolWindowPanel）提供：IDE Document 优先（未保存修改可见），
 * VFS/磁盘兜底；本对象只负责切片、上限与附件装配。
 *
 * 切片策略：
 * - 带行号区间（chip 显示 #Lx-y，选区是此功能的灵魂）：只发选区行；超
 *   [MAX_SELECTION_LINES] 行截断并补说明行。
 * - 整文件（无行号）：≤ [MAX_WHOLE_FILE_BYTES] 内联（含行号前缀后的整体计）；
 *   超限降级 localPath 引用。
 * - fullText=null（二进制/不可读）：localPath 引用（服务端注入路径占位引导模型
 *   用文件读取工具，优雅降级）。
 */
internal object CurrentFileAttachment {

    private const val MAX_SELECTION_LINES = 500
    private const val MAX_WHOLE_FILE_BYTES = 128 * 1024

    /**
     * 装配 kind:'file' 附件。fullText 由调用方按"Document 优先、磁盘兜底"取好；
     * 恒有返回（localPath 兜底），是否发送由调用方按文件存在性决定。
     */
    fun resolve(path: String, lineStart: Int?, lineEnd: Int?, fullText: String?): AttachmentInput {
        val mime = mimeTypeFor(path)

        // 选区切片内联（自描述：头行带完整路径与原始行号区间，正文带原始行号前缀）
        if (lineStart != null && lineEnd != null && fullText != null) {
            val lines = fullText.lines()
            val from = lineStart.coerceIn(1, lines.size)
            val to = lineEnd.coerceIn(from, lines.size)
            val slice = lines.subList(from - 1, to)
            val truncated = slice.size > MAX_SELECTION_LINES
            val kept = if (truncated) slice.subList(0, MAX_SELECTION_LINES) else slice
            val rangeDesc = if (from == to) "line $from" else "lines $from-$to"
            val content = buildString {
                append("[Selected code from ").append(path)
                    .append(", ").append(rangeDesc).append(" of ").append(lines.size).append("]\n")
                kept.forEachIndexed { i, line ->
                    if (i > 0) append('\n')
                    append(from + i).append('\t').append(line)
                }
                if (truncated) append("\n… (selection truncated to first ").append(MAX_SELECTION_LINES).append(" lines)")
            }
            return AttachmentInput(
                kind = "file",
                filename = path,
                mimeType = mime,
                sizeBytes = utf8Size(content),
                textContent = content,
            )
        }

        // 整文件内联（行号前缀后的整体 ≤ 上限才内联，超限降级 localPath）
        if (fullText != null) {
            val lines = fullText.lines()
            val content = buildString {
                append("[Full content of ").append(path).append(", ").append(lines.size).append(" lines]\n")
                lines.forEachIndexed { i, line ->
                    if (i > 0) append('\n')
                    append(i + 1).append('\t').append(line)
                }
            }
            if (utf8Size(content) <= MAX_WHOLE_FILE_BYTES) {
                return AttachmentInput(
                    kind = "file",
                    filename = path,
                    mimeType = mime,
                    sizeBytes = utf8Size(content),
                    textContent = content,
                )
            }
        }

        // 降级：路径引用（二进制/不可读/整文件超限）
        return AttachmentInput(
            kind = "file",
            filename = path,
            mimeType = mime,
            sizeBytes = fullText?.let { utf8Size(it) },
            localPath = path,
        )
    }

    /** UTF-8 优先读取，失败回退平台默认编码（中文 Windows 常见 GBK 源码），再失败 = null（按二进制处理）*/
    fun readTextLossy(p: Path): String? {
        return try {
            p.readText(Charsets.UTF_8)
        } catch (e: MalformedInputException) {
            runCatching { p.readText(Charset.defaultCharset()) }.getOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /** 极小扩展名→MIME 表（内联文本时 mime 只是元数据，缺省 text/plain 即诚实形态）*/
    internal fun mimeTypeFor(path: String): String {
        return when (path.substringAfterLast('.', "").lowercase()) {
            "md", "markdown" -> "text/markdown"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "csv" -> "text/csv"
            else -> "text/plain"
        }
    }

    private fun utf8Size(s: String): Long = s.toByteArray(Charsets.UTF_8).size.toLong()
}
