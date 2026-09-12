/**
 * 编辑历史消息（对齐官方客户端 Edit History：zcode.z.ai/cn/docs/edit-history）
 *
 * 官方语义：只允许编辑【最后一轮】用户消息，改写后重新生成回复。
 * 协议实现（diag-edit-rewind.py / diag-rewind-meta.py 实测）：
 *   - 发送 `/rewind conversation <msgId>`（走 session/send 文本命令）——服务端
 *     把模型上下文截断到该消息之前（kept = 该消息之前的全部轮次）并落
 *     revert 元数据；rewind 自身作为一个 turn（turn.started → rewind.triggered
 *     → turn.completed，耗时百毫秒级）。
 *   - 重发编辑后的文本即完成「编辑重新生成」。
 *   - ⚠️ legacy 快照（session/messages）【不反映】截断：服务端 Rbt 过滤链要求
 *     revert.createdMessageID，而当前 CLI 的 rewind 实现不写该字段——目标轮
 *     在快照里原样保留（官方客户端走 v4 投影按 rewind.triggered 删行，不受
 *     影响）。前端必须自维护截断记忆：流式 rewind.triggered 事件实时截断内存
 *     消息 + persist kv 持久化各会话的已编辑轮，快照重拉时按「轮删除」规则
 *     重放（见 applyRewindCuts）。
 */

import type { ZCodeMessage, FilePart, ImagePart, JavaEditAttachment } from '@/types/messages'
import { isAgentNotification, isCompactSummaryMessage } from './parseNotification'
import { getPersisted, setPersisted } from './persist'

/** 构造编辑用的 rewind 命令文本 */
export function buildEditRewindCommand(msgId: string): string {
  return `/rewind conversation ${msgId}`
}

/** 是否本插件编辑流程发出的 rewind 命令（乐观消息防御性识别；快照中命令轮已被服务端剪除）*/
export function isEditRewindCommand(text: string): boolean {
  return /^\/rewind\s+conversation\s+\S+/.test(text.trim())
}

/** rewind.triggered 事件 payload（实测形态：rewindId/scope/strategy/targetMessageId/branchCutAfterMessageId/branchGeneration/reason）*/
export interface RewindTriggeredPayload {
  rewindId?: string
  scope?: string
  strategy?: string
  targetMessageId?: string
  branchCutAfterMessageId?: string
  [key: string]: unknown
}

/** 是成功的会话级 rewind 事件则返回 targetMessageId，否则 null（workspace-only / 失败回退不截断转录）*/
export function asConversationRewind(payload: unknown): string | null {
  const p = payload as Partial<RewindTriggeredPayload>
  if (
    p &&
    typeof p.targetMessageId === 'string' &&
    (p.scope === 'conversation' || p.scope === 'both') &&
    p.strategy === 'active_chain'
  ) {
    return p.targetMessageId
  }
  return null
}

// ============ 截断记忆（persist kv） ============

/**
 * kv key：sessionId → 已编辑轮的 targetMessageId 列表（按时间序追加）。
 * v2（2026-09-12 真机实锤）：v4 editUserQuery 回合中编辑是服务端【就地改写】语义——
 * 新消息复用被编辑消息的同一 id 且快照自行截断，v1 key 里无条件落的 cut 会在轮末
 * 重放时把编辑后的新轮删光（主界面永久空白）。v2 换 key 逃离旧毒数据，v4 通道的
 * cut 改为 staged 提交判别（见 stageRewindCut/commitStagedRewindCuts）后入库。
 */
const CUTS_KEY = 'zcode.edit.rewind-cuts-v2'

type RewindCutsMap = Record<string, string[]>

function readCutsMap(): RewindCutsMap {
  const raw = getPersisted(CUTS_KEY)
  if (!raw) return {}
  try {
    const obj = JSON.parse(raw) as RewindCutsMap
    if (obj && typeof obj === 'object') return obj
  } catch {
    /* 损坏按空处理 */
  }
  return {}
}

/** 读取会话的已编辑轮列表（空数组 = 无编辑史）*/
export function loadRewindCuts(sessionId: string): string[] {
  const cuts = readCutsMap()[sessionId]
  return Array.isArray(cuts) ? cuts.filter((x) => typeof x === 'string') : []
}

/** 追加一条编辑记录（legacy /rewind 通道专用：该路径快照永不截断，事件到达即可确定要落 kv）*/
export function addRewindCut(sessionId: string, targetMsgId: string): void {
  const map = readCutsMap()
  const cuts = map[sessionId] ?? []
  if (!cuts.includes(targetMsgId)) cuts.push(targetMsgId)
  map[sessionId] = cuts
  setPersisted(CUTS_KEY, JSON.stringify(map))
}

// ============ v4 通道 staged cut（快照落地时判别提交） ============
// v4 editUserQuery 的服务端行为分叉（真机 sess_4c4c67ae / sess_34858bc0 实锤）：
// - 回合中编辑：就地改写——新消息【复用被编辑消息的同一 id】+ 快照自行截断旧轮。
//   此时落 kv cut = 轮末重放把编辑后的新轮删光（主界面永久空白，2026-09-12 实测事故）。
// - 空闲编辑：快照保留旧轮 + 重发为新 id 消息——需要 kv cut 隐藏旧轮（与 legacy 一致）。
// 两者在 rewind.triggered 时无法区分，快照落地时可以：目标消息不在快照（已被服务端
// 截断）或目标后存在同文本的新 user 消息（重发为新 id，旧轮是残留）→ 才值得落 kv。

interface StagedCut {
  targetId: string
  text: string
}

const stagedCutsBySession = new Map<string, StagedCut[]>()

/** 暂存一条 v4 编辑的截断候选（rewind.triggered 确认时调用，不直接落 kv）*/
export function stageRewindCut(sessionId: string, targetMsgId: string, editedText: string): void {
  const list = stagedCutsBySession.get(sessionId) ?? []
  if (!list.some((c) => c.targetId === targetMsgId)) {
    list.push({ targetId: targetMsgId, text: editedText })
  }
  stagedCutsBySession.set(sessionId, list)
}

/**
 * 快照落地时判别提交 staged cuts（applyMessagesSnapshot 在 applyRewindCuts 之前调用，
 * 传【未截断的原始快照】）。判别规则：
 * - 目标消息不在快照 → 服务端已自行截断（回合中编辑就地改写）→ 丢弃，绝不落 kv
 * - 目标消息之后存在同文本的 user 消息 → 重发落成了新 id、旧轮是快照残留 → 落 kv
 * - 其余（目标在且其后无同文本重发）→ 无法证明旧轮该隐藏，保守丢弃
 * 无论判别结果如何一律清空 staged（一次 rewind 只判一次）。
 */
export function commitStagedRewindCuts(sessionId: string, snapshot: ZCodeMessage[]): void {
  const staged = stagedCutsBySession.get(sessionId)
  if (!staged?.length) return
  stagedCutsBySession.set(sessionId, [])
  for (const { targetId, text } of staged) {
    const tIdx = snapshot.findIndex((m) => m.info.id === targetId && m.info.role === 'user')
    if (tIdx < 0) continue
    const trimmed = text.trim()
    const resent = snapshot
      .slice(tIdx + 1)
      .some((m) => m.info.role === 'user' && userEditText(m).trim() === trimmed && trimmed !== '')
    if (resent) addRewindCut(sessionId, targetId)
  }
}

/**
 * 对消息列表重放编辑截断（轮删除规则）：
 * 每个 cut = 删除 target 用户消息所在轮——从该消息起到下一条用户消息前的全部
 * （该轮的 assistant 回复一并删除），之后的轮次（编辑后新发的消息）保留。
 * cut 顺序应用即可还原多次编辑；targetId 找不到（已被更早 cut 覆盖 / 服务端
 * 未来版本自行截断了快照）时该 cut 无害跳过。
 */
export function applyRewindCuts(messages: ZCodeMessage[], cuts: string[]): ZCodeMessage[] {
  if (cuts.length === 0 || messages.length === 0) return messages
  let out = messages
  for (const targetId of cuts) {
    const tIdx = out.findIndex((m) => m.info.id === targetId && m.info.role === 'user')
    if (tIdx < 0) continue
    // 轮终点：其后第一条用户消息（真实用户消息——合成通知/摘要卡不算轮界）
    let end = out.length
    for (let i = tIdx + 1; i < out.length; i++) {
      const m = out[i]
      if (m.info.role === 'user' && !isAgentNotification(m.info) && !isCompactSummaryMessage(m.info)) {
        end = i
        break
      }
    }
    out = [...out.slice(0, tIdx), ...out.slice(end)]
  }
  return out
}

// ============ 可编辑判定 ============

/**
 * 用户消息的可编辑附件检查（v4 editUserQuery ref 重发的前提：每张要保留的图都
 * 必须能换算出磁盘文件 ref）：
 * - type:'image' 带 dataBase64（乐观消息本地形态，刚发出未重拉）→ 可编辑：字节在
 *   前端手里，Java 落临时文件作 ref（2026-09-12 真机回归补：带图消息回合中可编辑）
 * - type:'image' 无 dataBase64 → 不可编辑（字节不可 recover）
 * - type:'file' 非 image mime（pdf 等）→ 不可编辑（无磁盘 ref 可重建）
 * - type:'file' 图片缺 url（ImageArtifactMapper 映射失败且 filename 兜底也没救回）
 *   → 不可编辑
 */
function hasEditableUnsupportedParts(m: ZCodeMessage): boolean {
  return (m.parts ?? []).some((p) => {
    if (p.type === 'image') return !p.dataBase64
    if (p.type === 'file') {
      if (!(p.mime ?? '').startsWith('image/')) return true
      return !p.url
    }
    return false
  })
}

function userEditText(m: ZCodeMessage): string {
  return (m.parts ?? [])
    .filter((p) => p.type === 'text')
    .map((p) => (p as { text?: string }).text ?? '')
    .join('\n')
}

/**
 * 找【最后一条】可编辑的真实用户消息（官方 Edit History 语义：仅最后一轮可编辑）。
 * 排除：子代理/任务回调通知卡、compact 摘要卡、乐观消息（local_u_，无服务端 id
 * 无法作为编辑目标）、rewind 命令自身。
 * @param opts.allowImages 默认 true（v4 editUserQuery 通道：图片可保留/增删，纯图
 *   消息也可编辑补文字）；false = legacy /rewind 通道（附件无法经 rewind 保留重发，
 *   维持一期限制：带附件与纯图消息、空文本消息不可编辑）。
 * 消息列表应传 ChatView 渲染用的（已滤合成/已应用编辑截断）列表。
 */
export function findEditableUserMessage(
  messages: ZCodeMessage[],
  opts?: { allowImages?: boolean },
): ZCodeMessage | null {
  const allowImages = opts?.allowImages !== false
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i]
    if (m.info.role !== 'user') continue
    if (isAgentNotification(m.info) || isCompactSummaryMessage(m.info)) continue
    if (m.info.id.startsWith('local_u_')) continue
    const text = userEditText(m)
    if (!allowImages) {
      if ((m.parts ?? []).some((p) => p.type === 'image' || p.type === 'file')) continue
      if (!text.trim() || isEditRewindCommand(text)) continue
      return m
    }
    if (hasEditableUnsupportedParts(m)) continue
    if (isEditRewindCommand(text)) continue
    return m
  }
  return null
}

/** 消息里的可编辑图片附件（历史消息=file part + cache url；刚发出的乐观消息=image part + dataBase64）*/
export function userMessageImageParts(m: ZCodeMessage): Array<FilePart | ImagePart> {
  return (m.parts ?? []).filter(
    (p): p is FilePart | ImagePart =>
      (p.type === 'file' && (p.mime ?? '').startsWith('image/') && !!p.url) ||
      (p.type === 'image' && !!p.dataBase64),
  )
}

// ============ 编辑附件载荷（webview → Java op:editUserQuery） ============

/**
 * 编辑提交的图片附件清单（保留的原图 + 新增的粘贴图 − 删除的图 = 全量列表）。
 * Java 端解析成 v4 ref 引用形态：cache=url 背后的 image-cache 落盘文件直接引用，
 * inline=dataBase64 落临时文件供服务端读。诊断结论：带图消息必须显式传全量
 * 列表——不传时服务端沿用原 intent 的 ref 附件，而 legacy 内联发的图 intent 里
 * ref=原文件名解析不了，实测重发后图变文字占位。线格式定义见 types/messages
 * （JavaEditAttachment），此处仅别名收口。
 */
export type EditAttachmentInput = JavaEditAttachment
