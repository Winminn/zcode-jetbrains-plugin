/**
 * 电脑控制动作组卡（CUA 工具同名相邻聚组，见 utils/groupParts.ts 'cua' 类别）。
 *
 * 桌面操作高频连发（连点/连截/连键），逐张单卡会把消息区拉得很长——对齐
 * BashCommandGroupCard/FileToolGroupCard 的组卡壳：
 *   - header：应用图标（组内最后带 icon= 的成员优先，退调用方顺扫的会话级 key）
 *     + 工具短名 + 次数 + 最新截图缩略图（成员自尾向头找第一份 state.media）
 *   - 成员行：状态字符 + 动作一行摘要（utils/cuaTools.cuaActionSummary），
 *     超 3 行内部滚动（与文件组卡同款跟滚语义）
 *   - 缩略图点击开全屏 ImagePreview（stopPropagation，收起状态也可点）
 *
 * 截图缩略图补齐单卡 ToolCallCard 的 cuaMedia 能力（聚组双路径：单卡+组卡须全覆盖）。
 */

import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ToolPart } from '@/types/messages'
import { cuaToolShortName, cuaActionSummary, cuaIconKeyFromOutput } from '@/utils/cuaTools'
import { ImagePreview } from './ImagePreview'
import { CuaAppIcon } from './CuaAppIcon'
import { NEAR_BOTTOM_PX } from './ScrollJumpButton'
import '../styles/tool-call-card.less'

/** 列表最大可见行数，超出内部滚动（与文件组卡同款）*/
const MAX_VISIBLE_ITEMS = 3
const ITEM_HEIGHT = 28

/**
 * 状态字符（与文件/命令组卡一致）。softenError：子代理弹窗活动 running 期间
 * error 降级「↻ 重试中」中性样式，收尾后恢复 ✗。
 */
function statusChar(
  status: ToolPart['state']['status'],
  softenError?: boolean,
): { text: string; cls: string } {
  switch (status) {
    case 'completed': return { text: '✓', cls: 'completed' }
    case 'error': return softenError
      ? { text: '↻', cls: 'retry' }
      : { text: '✗', cls: 'error' }
    default: return { text: '⟳', cls: 'pending' }
  }
}

export function CuaActionGroupCard({ parts, softenError, cuaIconKey }: {
  parts: ToolPart[]
  softenError?: boolean
  /** 会话内最近一次解析到的 CUA 应用图标 key（组内成员都没带时兜底）*/
  cuaIconKey?: string | null
}) {
  const { t } = useTranslation()
  const [expanded, setExpanded] = useState(true)
  const [previewOpen, setPreviewOpen] = useState(false)
  const listRef = useRef<HTMLDivElement>(null)
  const prevCountRef = useRef(0)
  const stickToBottom = useRef(true)

  const count = parts.length
  const shortName = cuaToolShortName(parts[0].tool)

  // 最新截图缩略图：成员自尾向头找第一份 media（同名连截时=最新一张）
  const media = useMemo(() => {
    for (let i = parts.length - 1; i >= 0; i--) {
      const m = parts[i].state.media
      if (m && m.length > 0) return m
    }
    return null
  }, [parts])

  // 应用图标 key：组内最后带 icon= 的成员优先，退调用方顺扫的会话级最近 key
  const iconKey = useMemo(() => {
    for (let i = parts.length - 1; i >= 0; i--) {
      const k = cuaIconKeyFromOutput(parts[i].state.output)
      if (k) return k
    }
    return cuaIconKey ?? null
  }, [parts, cuaIconKey])

  // 流式追加新项时自动滚到底部（用户上滚查看历史时不打扰）
  useEffect(() => {
    if (listRef.current && count > prevCountRef.current && stickToBottom.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight
    }
    prevCountRef.current = count
  }, [count])

  const handleListScroll = () => {
    const el = listRef.current
    if (!el) return
    stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < NEAR_BOTTOM_PX
  }

  const listMaxHeight = (count > MAX_VISIBLE_ITEMS ? MAX_VISIBLE_ITEMS : count) * ITEM_HEIGHT
  const overflowY = count > MAX_VISIBLE_ITEMS ? 'auto' : 'hidden'

  return (
    <div className="bash-group">
      <div
        className={`bash-group__header${expanded ? ' bash-group__header--open' : ''}`}
        onClick={() => setExpanded((v) => !v)}
      >
        <span className="tool-card__icon bash-group__icon">
          <CuaAppIcon
            iconKey={iconKey}
            className="tool-card__app-icon"
            fallback={<span className="codicon codicon-device-desktop" />}
          />
        </span>
        <span className="bash-group__title" title={parts[0].tool}>{shortName} ({count})</span>
        <span className="bash-group__spacer" />
        {media && media.length > 0 && (
          <img
            src={`data:${media[0].mimeType};base64,${media[0].data}`}
            alt={t('tool.cua.screenshot', '屏幕截图')}
            className="tool-card__cua-thumb"
            title={t('tool.cua.viewFull', '点击放大查看截图')}
            onClick={(e) => {
              e.stopPropagation()
              setPreviewOpen(true)
            }}
          />
        )}
        <span className="tool-card__toggle">{expanded ? '▼' : '▶'}</span>
      </div>

      {expanded && (
        <div
          ref={listRef}
          className="file-group__list"
          style={{ maxHeight: listMaxHeight, overflowY }}
          onScroll={handleListScroll}
        >
          {parts.map((p, index) => {
            const st = statusChar(p.state.status, softenError)
            const summary = cuaActionSummary(p.tool, p.state.input as Record<string, unknown> | undefined)
            return (
              <div key={p.callID || index} className="file-group__row" title={p.tool}>
                <span className={`codicon codicon-device-desktop file-group__tool-ic`} />
                <span className="file-group__pattern">
                  {summary || cuaToolShortName(p.tool)}
                </span>
                <span className={`bash-group__status bash-group__status--${st.cls}`}>{st.text}</span>
              </div>
            )
          })}
        </div>
      )}
      {previewOpen && media && media.length > 0 && (
        <ImagePreview
          images={media.map((m) => ({ src: `data:${m.mimeType};base64,${m.data}`, title: t('tool.cua.screenshot', '屏幕截图') }))}
          initialIndex={0}
          onClose={() => setPreviewOpen(false)}
        />
      )}
    </div>
  )
}
