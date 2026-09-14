/**
 * 会话引用文本形态的唯一来源（review smell 收口：识别正则与 chip 截断标签此前在
 * inlineFileTags（输入框内联 chip）与 userRefChips（消息只读 chip）双份手写）。
 *
 * 消费方共享 /g 正则对象：JS 单线程且两处使用都遵守「exec/test 前重置 lastIndex」
 * 纪律（同模块内 hasUserRefChips 与主函数本就如此），跨模块共享同样安全。
 */

/** 会话 id 形态（服务端 ReadSessionContext input schema 同款）*/
export const SESS_ID_PATTERN = String.raw`sess_[A-Za-z0-9._-]+`

/** markdown 会话链接（标题允许 \x 转义序列——序列化会转义 [ ] \）。
 *  捕获组：1=转义标题，2=裸会话 id（不含 #）*/
export const SESS_MD_RE = new RegExp(`\\[#((?:\\\\.|[^\\]])*)\\]\\(#(${SESS_ID_PATTERN})\\)`, 'g')

/** 词边界裸 token（前缀字符算进 match，拼接时保留在文本段；中文边界对齐中文输入习惯）。
 *  捕获组：1=边界前缀，2=裸会话 id（不含 #，消费方算 end 时自行 +1 补 #）*/
export const SESS_BARE_RE = new RegExp(`(^|[\\s\\u4e00-\\u9fa5])#(${SESS_ID_PATTERN})(?=$|[\\s\\u4e00-\\u9fa5])`, 'g')

/** 会话 chip 的截断显示：标题为空退化「id 前 8 位 + …」（sess_ 前缀不占位数）*/
export function sessionRefShortLabel(sessionId: string, title: string): string {
  const t = title.trim()
  return t || `${sessionId.replace(/^sess_/, '').slice(0, 8)}…`
}

/** 会话 chip 的悬停提示：有标题「标题 · id」，无标题完整 id */
export function sessionRefTip(sessionId: string, title: string): string {
  const t = title.trim()
  return t ? `${t} · ${sessionId}` : sessionId
}
