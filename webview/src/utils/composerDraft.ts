/**
 * 输入框草稿持久化（per-session scope，候选池 O8「Composer 草稿持久化」）：
 *
 * - scope = sessionId；待命态（无会话输入）="__draft__"。发送后清 scope（官方
 *   composerDraftStore 同款语义的简化：插件无随 scope 保存的 mode/modelSelection，
 *   直接删 scope 更干净）。
 * - 存储走 persist 通道（IDE PropertiesComponent 权威源）：生产模式内置 server 随机
 *   端口 → origin 每次重启变化 → 直存 localStorage 重启即丢。kvSave 单值上限 64KB
 *   （IDE 侧阈值），本文件序列化预算 48KB 留余量，超限从最旧 scope 淘汰。
 * - 内容：编辑器 innerHTML（四类内联 chip 的 DOM 保真——file/cmd/sess/skill/paste
 *   chip 的 data-* 属性全在 HTML 里）+ 顶栏文件引用 + 技能引用 + 粘贴 chip 原文映射
 *   （chip 只带 data-paste-id，原文在 InputBox 内存映射，恢复时一并回填）。
 * - 不存：图片附件（base64 载荷大，官方同裁剪）、模式/模型/思考级别（全局态非会话隔离）。
 * - 粘贴原文单条 2000 字符上限（对齐输入历史单条上限，防撑爆 kv 值），超限条目丢弃
 *   （恢复后该 chip 序列化降级「粘贴内容已丢失」占位，属可接受边界）。
 * - ready 判定：KV_HYDRATED/KV_DISABLED 双信号。水合前 localStorage 是空缓存，读草稿
 *   恒空——消费方（InputBox）在 ready 前不做「恢复/清空」动作，防止把用户当前输入
 *   或未水合的历史草稿误清。
 * - StrictMode 安全：persist 空/纯空白编辑器 = no-op（不覆盖不清 scope）——dev 双挂载
 *   的 cleanup 不会把已有草稿清掉；显式清 scope 只有 clearComposerDraft（发送后）。
 * - 恢复时 HTML 净化：草稿内容理论上是本插件自己序列化的（chip+文本+br），仍按
 *   白名单净化防御 localStorage/kv 被篡改的注入面。
 */

import { getPersisted, setPersisted, removePersisted, KV_HYDRATED_EVENT, KV_DISABLED_EVENT } from '@/utils/persist'
import type { SlashCommand } from '@/types/messages'

const DRAFTS_KEY = 'zcode.composer.drafts.v1'
/** 待命态（无会话）输入的草稿 scope */
export const DRAFT_SCOPE_ROOT = '__draft__'

/** kvSave 单值上限 64KB（IDE 侧拒绝超限整批），留余量取 48KB */
const MAX_KV_VALUE_LENGTH = 48 * 1024
/** 单条草稿 HTML 上限（防单条吃满整文件预算）*/
const MAX_DRAFT_HTML_LENGTH = 24 * 1024
/** 保留 scope 数上限（按 updatedAt 淘汰最旧；会话数量会无限增长）*/
const MAX_SCOPES = 30
/** 粘贴原文单条上限（对齐输入历史 MAX_INPUT_HISTORY_TEXT_LENGTH）*/
const MAX_PASTE_TEXT_LENGTH = 2000
/** 粘贴原文映射条目上限 */
const MAX_PASTE_ENTRIES = 10

export interface ComposerDraft {
  html: string
  fileRefs: string[]
  skillRefs: SlashCommand[]
  pasteTexts: [string, string][]
  updatedAt: number
}

interface DraftFile {
  version: 1
  scopes: Record<string, DraftEntry>
}

interface DraftEntry {
  html: string
  fileRefs?: string[]
  skillRefs?: SlashCommand[]
  pasteTexts?: [string, string][]
  updatedAt: number
}

/** 持久化通道就绪判定：水合完成（权威 kv 已写回）或 dev/mock 降级（localStorage 即权威源）*/
let storeReady = false
if (typeof window !== 'undefined') {
  const mark = () => { storeReady = true }
  window.addEventListener(KV_HYDRATED_EVENT, mark)
  window.addEventListener(KV_DISABLED_EVENT, mark)
  // 兜底：事件可能已错过（模块晚于水合加载）——持久层 kvHydrated 无法直接探，
  // 读到非空 drafts 键即视为就绪（空键时等下一次事件，代价只是延迟恢复）
  if (getPersisted(DRAFTS_KEY)) storeReady = true
}

export function isDraftStoreReady(): boolean {
  return storeReady
}

export function draftScope(sessionId: string | null | undefined): string {
  return sessionId || DRAFT_SCOPE_ROOT
}

function loadDraftFile(): DraftFile {
  try {
    const raw = getPersisted(DRAFTS_KEY)
    if (!raw) return { version: 1, scopes: {} }
    const parsed: unknown = JSON.parse(raw)
    if (
      typeof parsed !== 'object' || parsed === null ||
      (parsed as DraftFile).version !== 1 ||
      typeof (parsed as DraftFile).scopes !== 'object' || (parsed as DraftFile).scopes === null
    ) {
      return { version: 1, scopes: {} }
    }
    const out: DraftFile = { version: 1, scopes: {} }
    for (const [scope, value] of Object.entries((parsed as DraftFile).scopes)) {
      const e = value as DraftEntry
      if (typeof e?.html !== 'string') continue
      out.scopes[scope] = {
        html: e.html,
        ...(Array.isArray(e.fileRefs) ? { fileRefs: e.fileRefs.filter((x) => typeof x === 'string') } : {}),
        ...(Array.isArray(e.skillRefs) ? { skillRefs: e.skillRefs.filter((c) => c && typeof c.name === 'string') } : {}),
        ...(Array.isArray(e.pasteTexts)
          ? {
              pasteTexts: e.pasteTexts.filter(
                (p): p is [string, string] =>
                  Array.isArray(p) && typeof p[0] === 'string' && typeof p[1] === 'string',
              ),
            }
          : {}),
        updatedAt: typeof e.updatedAt === 'number' ? e.updatedAt : 0,
      }
    }
    return out
  } catch {
    return { version: 1, scopes: {} }
  }
}

function saveDraftFile(file: DraftFile): void {
  // 序列化超预算：从最旧 scope 淘汰直到达标（一条大草稿会把全部历史挤掉的病态场景
  // 由 MAX_DRAFT_HTML_LENGTH 单条上限兜住）
  let serialized = JSON.stringify(file)
  let guard = 0
  while (serialized.length > MAX_KV_VALUE_LENGTH && guard++ < MAX_SCOPES) {
    const oldest = Object.entries(file.scopes)
      .sort((a, b) => a[1].updatedAt - b[1].updatedAt)[0]
    if (!oldest) break
    delete file.scopes[oldest[0]]
    serialized = JSON.stringify(file)
  }
  if (!Object.keys(file.scopes).length) {
    removePersisted(DRAFTS_KEY)
    return
  }
  setPersisted(DRAFTS_KEY, serialized)
}

/**
 * 读某 scope 草稿（纯读 localStorage：水合前读到的是空缓存、KV_DISABLED 下即权威值，
 * 读本身无害；「ready 前不做恢复动作」的门控由调用方 InputBox 用 isDraftStoreReady
 * 承担——read 在此不拦，避免恢复链路与存储水合时序耦合）
 */
export function readComposerDraft(scopeId: string): ComposerDraft | null {
  const e = loadDraftFile().scopes[scopeId]
  if (!e) return null
  return {
    html: e.html,
    fileRefs: e.fileRefs ?? [],
    skillRefs: e.skillRefs ?? [],
    pasteTexts: e.pasteTexts ?? [],
    updatedAt: e.updatedAt,
  }
}

/**
 * 保存草稿。空/纯空白编辑器 = 完全 no-op（不动已有 scope）：StrictMode dev 双挂载
 * 的 cleanup 会以空编辑器跑到这里，若 delete scope 会把「已落 kv 但尚未水合恢复」
 * 的草稿误清。代价：用户手动删光正文后切走再切回会看到旧草稿复活——低频场景，
 * 「宁保留勿误清」与 persist 通道防覆盖哲学一致。
 */
export function persistComposerDraft(
  scopeId: string,
  draft: { html: string; fileRefs: string[]; skillRefs: SlashCommand[]; pasteTexts: [string, string][] },
): void {
  const hasContent =
    draft.html.trim().length > 0 ||
    draft.fileRefs.length > 0 ||
    draft.skillRefs.length > 0
  if (!hasContent) return
  if (draft.html.length > MAX_DRAFT_HTML_LENGTH) return // 超长草稿不持久化（内容仍在编辑器）
  const file = loadDraftFile()
  const entry: DraftEntry = {
    html: draft.html,
    ...(draft.fileRefs.length ? { fileRefs: draft.fileRefs } : {}),
    ...(draft.skillRefs.length ? { skillRefs: draft.skillRefs } : {}),
    ...(draft.pasteTexts.length
      ? {
          pasteTexts: draft.pasteTexts
            .filter(([_, v]) => v.length <= MAX_PASTE_TEXT_LENGTH)
            .slice(0, MAX_PASTE_ENTRIES),
        }
      : {}),
    updatedAt: Date.now(),
  }
  file.scopes[scopeId] = entry
  // 淘汰超量 scope（当前 scope 免淘）
  const ids = Object.keys(file.scopes)
  if (ids.length > MAX_SCOPES) {
    ids
      .filter((id) => id !== scopeId)
      .sort((a, b) => file.scopes[a].updatedAt - file.scopes[b].updatedAt)
      .slice(0, ids.length - MAX_SCOPES)
      .forEach((id) => delete file.scopes[id])
  }
  saveDraftFile(file)
}

/** 显式清除 scope（发送后；无该 scope 为无害空操作）*/
export function clearComposerDraft(scopeId: string): void {
  const file = loadDraftFile()
  if (!(scopeId in file.scopes)) return
  delete file.scopes[scopeId]
  saveDraftFile(file)
}

/**
 * 恢复前净化草稿 HTML：白名单标签 + 去 on* 事件属性与 javascript: 链接。
 * 草稿理论是自家序列化产物，此处防御 kv 被篡改/旧版本格式异变的注入面。
 */
export function sanitizeDraftHtml(html: string): string {
  if (typeof DOMParser === 'undefined') return ''
  const doc = new DOMParser().parseFromString(html, 'text/html')
  const ALLOWED = new Set(['SPAN', 'BR', 'DIV', 'P', 'B', 'STRONG', 'I', 'EM', 'U', 'CODE', 'BUTTON'])
  const walk = (node: Element): void => {
    for (const child of Array.from(node.children)) {
      if (!ALLOWED.has(child.tagName)) {
        // 不允许的元素：保留其文本（防误杀正文），移除元素本身
        child.replaceWith(...Array.from(child.childNodes))
        continue
      }
      for (const attr of Array.from(child.attributes)) {
        const name = attr.name.toLowerCase()
        if (name.startsWith('on') || (name === 'href' && attr.value.trim().toLowerCase().startsWith('javascript:'))) {
          child.removeAttribute(attr.name)
        }
      }
      walk(child)
    }
  }
  walk(doc.body)
  return doc.body.innerHTML
}
