/**
 * CUA 应用图标（工具卡 header 用）：按 server 落盘的图标 key 懒取图渲染。
 *
 * 数据链：server（CuaApps/CuaIcons）把主窗口图标 PNG 落盘 ~/.zcode/cli/zcgui-cua-icons/
 * 并在工具结果文本附 icon=<key> → store.loadCuaIcon 经桥 op=getCuaIcon 取 base64 →
 * cuaIcons[key] 缓存（''=已请求但缺失，不再重发）。历史会话无 key、key 拉取中或缺失时
 * 渲染 fallback（默认 codicon 图标），与官方客户端「行前显示目标应用图标」对齐。
 */

import { useEffect } from 'react'
import { useStore } from '@/store/useStore'

export function CuaAppIcon({
  iconKey,
  className,
  fallback,
}: {
  iconKey?: string | null
  className?: string
  /** key 为空/拉取中/缺失时渲染的兜底节点（通常是原 codicon 图标）*/
  fallback: React.ReactNode
}) {
  const cached = useStore((s) => (iconKey ? s.cuaIcons[iconKey] : undefined))
  const loadCuaIcon = useStore((s) => s.loadCuaIcon)
  useEffect(() => {
    if (iconKey) loadCuaIcon(iconKey)
  }, [iconKey, loadCuaIcon])
  if (!iconKey || !cached) return fallback
  // eslint-disable-next-line jsx-a11y/alt-text
  return <img className={className} src={cached} alt="" draggable={false} />
}
