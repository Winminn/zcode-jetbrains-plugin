/**
 * 当前打开文件上下文 chip（InputBox 顶部 topbar，AgentSelect 右侧）
 *
 * 阶段 A（docs/internal/feat/当前文件chip-前端交互重做.md）：
 *   - 永远渲染此区域（不随 ref/勾选态隐藏）
 *   - 点击整块 = 切换勾选态
 *   - 视觉：左侧文件图标 + 标签
 *       未勾选                → 标签 = "文件上下文"（轻文字）
 *       未勾选 + ref 非空     → 标签 = "文件上下文"（仍不显示文件）
 *       勾选 + ref=null       → 标签 = "文件上下文"（IDE 端无打开编辑器）
 *       勾选 + ref=@path      → 标签 = FileRef 视觉（basename + 可选 :L 行号后缀）
 *   - 阶段 A 自身落 localStorage（zcode.currentFile.enabled）
 *   - 悬浮 tooltip：
 *       未勾选             → 功能说明（"勾选后随下一条消息附上当前打开的文件路径…"）
 *       勾选 + ref 非空    → 当前文件路径（工作区内显相对路径，超 50 字符中间省略）
 *       勾选 + ref=null    → 不显示路径，仅功能说明
 *   - 本组件不接 onSend（勾选只控视觉）；发送链路把勾选值拼进 content 文本，
 *     见《当前文件chip-发送链路实现.md》（协议层无独立字段通道，已证实）
 *
 * 阶段 B（同上，发送链路见 docs/internal/feat/当前文件chip-发送链路实现.md）：
 *   - enabled 状态从自管 useState 改为 prop-driven：InputBox 持有 single source of truth
 *     并负责 localStorage 持久化；本组件 0 本地 state、0 localStorage 调用
 *   - 视觉效果与 A 阶段完全一致；行为契约变化仅在父组件层处理 filesToInput 推送守门
 *
 * 发完即关（2026-10-08 拍板，取代阶段 B 的 localStorage 持久化）：
 *   - 勾选只管下一条消息：InputBox doSend 发送成功即自动取消勾选——附件全文经
 *     history 持久化留在会话里，后续轮次 AI 可见无需每轮重发；切去别的文件
 *     查看也不会被下一轮误带。想让 AI 看新版/新选区时重新点一下即可
 *   - 持久化整体移除（InputBox 也不再写 LS）；本组件依旧 prop-driven 零变化
 *
 * 视觉：复用 file-ref.less 的 .file-ref（chip 形态），本组件只加图标 + 文字标签样式。
 */

import { memo } from 'react'
import { useTranslation } from 'react-i18next'
import { FileIcon } from './FileIcon'
import { basename, splitReference } from './FileRef'
import '../styles/current-file-chip.less'

interface Props {
  /** 当前打开文件 ref（`@path` / `@path#L10` / `@path#L10-20`）；null = 无文件 */
  ref: string | null
  /** 是否启用文件上下文（InputBox 持有 single source of truth）*/
  enabled: boolean
  /** 勾选态变化回调（用户点击整块 chip）*/
  onEnabledChange: (next: boolean) => void
  /** 工作区绝对路径（tooltip 显示相对路径用；null/缺省 = 不去前缀）*/
  workspace?: string | null
}

/** 路径过长时中间省略（全局 tooltip 是 nowrap 单行，过长会被视口裁掉尾部——global.less 约定调用侧截断）*/
export function truncateMiddle(text: string, max: number): string {
  if (text.length <= max) return text
  const head = Math.ceil((max - 1) / 2)
  const tail = max - 1 - head
  return `${text.slice(0, head)}…${text.slice(text.length - tail)}`
}

/**
 * 去工作区前缀：ref 在 workspace 下时 tooltip 显示相对路径——开头一大段工作区
 * 路径是纯噪音（用户就在这个工作区里）。不在 workspace 下（外部文件）保留绝对
 * 路径。仅显示层转换，不改 ref 原值（发送链路将来用绝对路径）。
 * 比较前统一分隔符、忽略大小写（Windows 路径不敏感）。
 */
function stripWorkspacePrefix(path: string, workspace?: string | null): string {
  if (!workspace) return path
  const norm = (s: string) => s.replace(/\\/g, '/').replace(/\/+$/, '')
  const ws = norm(workspace).toLowerCase()
  const p = norm(path)
  if (p.toLowerCase().startsWith(`${ws}/`)) return p.slice(ws.length + 1)
  return path
}

function CurrentFileChipInner({ ref, enabled, onEnabledChange, workspace = null }: Props) {
  const { t } = useTranslation()

  // 永远渲染：未勾选 / 勾选 + ref=null → 文字标签 "文件上下文"
  // 勾选 + ref 非空 → chip 形态：basename + #L行号（连续拼接，不用 : 分隔）
  const renderLabel = () => {
    if (enabled && ref) {
      const { file, lines } = splitReference(ref)
      const name = basename(file)
      // 长文件名缩略（用户提案）：>28 字符中段省略保头尾——尾=扩展名不丢
      // （如 SomeVeryLo…Going.tsx）；.file-ref__name 的 280px tail-ellipsis 只作兜底
      const display = name.length > 28 ? truncateMiddle(name, 28) : name
      return (
        <span className="file-ref current-file-chip__ref">
          <span className="file-ref__name">{display}</span>
          {lines && <span className="file-ref__lines">#{lines}</span>}
        </span>
      )
    }
    return <span className="current-file-chip__label">{t('input.currentFile.label')}</span>
  }

  // 悬浮 tooltip 走全局 [data-tip] 系统（global.less，0.3.8 起；单气泡、宿主上方、
  // nowrap 单行、长文本调用侧截断）：
  //   未勾选 → 功能说明（用户尚未启用此功能时解释它是干什么的）
  //   勾选 + ref 非空 → ref 去 @ 前缀（行号保留 #L15-16 形式）；工作区内文件显相对
  //     路径（stripWorkspacePrefix），外部文件显绝对路径；超 50 字符中间省略
  //   勾选 + ref=null → 功能说明（IDE 端无打开编辑器，不显示路径）
  const tip = enabled && ref
    ? truncateMiddle(stripWorkspacePrefix(ref.replace(/^@/, ''), workspace), 50)
    : t('input.currentFile.tooltip')

  return (
    <button
      type="button"
      className={`current-file-chip tip-align-right${enabled ? ' current-file-chip--active' : ''}`}
      data-testid="current-file-chip"
      data-tip={tip}
      // tip-align-right：chip 钉顶栏最右后，居中气泡会伸出 webview 容器被裁剪
      // （global.less 已知坑，附件/状态栏开关同款），改贴按钮内缘右对齐
      // 不设 title（JCEF 原生 tooltip 不可控）；也不再渲染自定义 __tip span——
      // 0.3.8 的全局 [data-tip]:hover::after 与它叠加成双 tooltip（真机实测，缺陷见
      // docs/internal/feat/当前文件chip-前端交互重做.md）
      onClick={() => onEnabledChange(!enabled)}
      aria-pressed={enabled}
    >
      {/* 图标：仅勾选+有 ref 时显示文件类型图标；否则显示通用占位图标 */}
      <FileIcon
        path={enabled && ref ? splitReference(ref).file : ''}
        className="current-file-chip__icon"
      />
      {renderLabel()}
    </button>
  )
}

export const CurrentFileChip = memo(CurrentFileChipInner)
