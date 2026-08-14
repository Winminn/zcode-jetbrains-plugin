/**
 * 历史视图（cc-gui HistoryView 移植）
 *
 * 结构（对齐 cc-gui）：
 *   .history-header
 *     ├─ .history-header-main：左侧信息条「共 N 个会话」/「已选择 N 个会话」
 *     │                       右侧工具栏（多选 + 刷新；多选模式：全选 + 删除所选 + 退出）
 *     └─ .history-search-container：搜索框（非多选模式显示，300ms 防抖 + mark 高亮）
 *   .history-list：会话列表（多选模式显示 checkbox）
 *
 * 删除确认：modal（对齐 cc-gui，单删/批量删共用）
 */

import { useEffect, useMemo, useRef, useState } from 'react'
import type { SessionInfo } from '@/types/messages'
import { SessionItem } from './SessionItem'
import '../styles/history-view.less'

interface Props {
  sessions: SessionInfo[]
  currentSessionId: string | null
  onSelect: (session: SessionInfo) => void
  /** 切回 chat 视图 */
  onBack: () => void
  onDelete: (sessionId: string) => void
  /** 刷新会话列表 */
  onRefresh: () => void
}

/** 标题高亮（cc-gui highlightText：<mark> 标黄匹配词）*/
function Highlight({ text, query }: { text: string; query: string }) {
  if (!query) return <>{text}</>
  const lower = text.toLowerCase()
  const q = query.toLowerCase()
  const idx = lower.indexOf(q)
  if (idx < 0) return <>{text}</>
  return (
    <>
      {text.slice(0, idx)}
      <mark style={{ backgroundColor: '#ffd700', color: '#000' }}>{text.slice(idx, idx + q.length)}</mark>
      {text.slice(idx + q.length)}
    </>
  )
}

export function HistoryView({ sessions, currentSessionId, onSelect, onBack, onDelete, onRefresh }: Props) {
  const [query, setQuery] = useState('')
  const [debouncedQuery, setDebouncedQuery] = useState('')
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // 多选模式状态（cc-gui selection mode）
  const [selectionMode, setSelectionMode] = useState(false)
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  // 删除确认 modal（存储待删除的 sessionId 列表，null = 不显示）
  const [deleteTargets, setDeleteTargets] = useState<string[] | null>(null)

  // 搜索防抖 300ms
  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(() => {
      setDebouncedQuery(query.trim().toLowerCase())
    }, 300)
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current)
    }
  }, [query])

  const filtered = useMemo(() => {
    if (!debouncedQuery) return sessions
    return sessions.filter(
      (s) =>
        s.title.toLowerCase().includes(debouncedQuery) ||
        s.sessionId.toLowerCase().includes(debouncedQuery),
    )
  }, [sessions, debouncedQuery])

  const allVisibleSelected =
    filtered.length > 0 && filtered.every((s) => selectedIds.has(s.sessionId))

  // ============ 多选操作 ============
  const toggleSelection = (sessionId: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (next.has(sessionId)) next.delete(sessionId)
      else next.add(sessionId)
      return next
    })
  }

  const toggleSelectAllVisible = () => {
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (allVisibleSelected) {
        filtered.forEach((s) => next.delete(s.sessionId))
      } else {
        filtered.forEach((s) => next.add(s.sessionId))
      }
      return next
    })
  }

  const exitSelectionMode = () => {
    setSelectionMode(false)
    setSelectedIds(new Set())
  }

  const handleItemClick = (session: SessionInfo) => {
    if (selectionMode) {
      toggleSelection(session.sessionId)
    } else {
      onSelect(session)
      onBack() // 切回 chat 视图
    }
  }

  // ============ 删除确认 ============
  const requestDelete = (sessionId: string) => setDeleteTargets([sessionId])
  const requestDeleteSelected = () => setDeleteTargets([...selectedIds])

  const confirmDelete = () => {
    if (deleteTargets) deleteTargets.forEach((id) => onDelete(id))
    setDeleteTargets(null)
    exitSelectionMode()
  }

  const cancelDelete = () => setDeleteTargets(null)

  return (
    <div className="history-view">
      <div className="history-header">
        <div className="history-header-main">
          {selectionMode ? (
            <div className="history-selection-summary">已选择 {selectedIds.size} 个会话</div>
          ) : (
            <div className="history-info">共 {sessions.length} 个会话</div>
          )}

          {/* 工具栏（cc-gui HistoryActions）*/}
          <div className="history-header-actions">
            {selectionMode ? (
              <>
                <button
                  className="history-toolbar-btn"
                  onClick={toggleSelectAllVisible}
                  disabled={filtered.length === 0}
                  title={allVisibleSelected ? '清除' : '全选'}
                >
                  <span className={`codicon ${allVisibleSelected ? 'codicon-clear-all' : 'codicon-check-all'}`} />
                  <span>{allVisibleSelected ? '清除' : '全选'}</span>
                </button>
                <button
                  className="history-toolbar-btn history-toolbar-danger"
                  onClick={requestDeleteSelected}
                  disabled={selectedIds.size === 0}
                  title="删除所选"
                >
                  <span className="codicon codicon-trash" />
                  <span>删除所选</span>
                </button>
                <button
                  className="history-toolbar-btn"
                  onClick={exitSelectionMode}
                  title="退出多选"
                >
                  <span className="codicon codicon-close" />
                </button>
              </>
            ) : (
              <>
                <button
                  className="history-toolbar-btn"
                  onClick={() => setSelectionMode(true)}
                  title="多选"
                >
                  <span className="codicon codicon-checklist" />
                  <span>多选</span>
                </button>
                <button
                  className="history-toolbar-btn"
                  onClick={onRefresh}
                  title="刷新"
                >
                  <span className="codicon codicon-refresh" />
                </button>
              </>
            )}
          </div>
        </div>

        {/* 搜索框（非多选模式显示，cc-gui HistoryFilters）*/}
        {!selectionMode && (
          <div className="history-search-container">
            <input
              type="text"
              className="history-search-input"
              placeholder="搜索会话…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            <span className="codicon codicon-search history-search-icon" />
          </div>
        )}
      </div>

      <div className="history-list">
        {filtered.length === 0 ? (
          <div className="history-empty">
            {debouncedQuery ? '🔍 无匹配会话' : '📭 暂无会话，点击右上角 + 新建'}
          </div>
        ) : (
          <ul className="history-items">
            {filtered.map((s) => (
              <SessionItem
                key={s.sessionId}
                session={s}
                active={s.sessionId === currentSessionId}
                onSelect={handleItemClick}
                onDelete={requestDelete}
                renderTitle={(title) => <Highlight text={title} query={debouncedQuery} />}
                selectionMode={selectionMode}
                selected={selectedIds.has(s.sessionId)}
                onToggle={toggleSelection}
              />
            ))}
          </ul>
        )}
      </div>

      {/* 删除确认 modal（cc-gui modal-overlay/modal-content）*/}
      {deleteTargets && (
        <div className="modal-overlay" onClick={cancelDelete} role="presentation">
          <div className="modal-content" onClick={(e) => e.stopPropagation()}>
            <h3>确认删除</h3>
            <p>
              {deleteTargets.length > 1
                ? `确定要删除所选 ${deleteTargets.length} 个会话吗？`
                : '确定要删除这个会话吗？'}
              此操作不可撤销。
            </p>
            <div className="modal-actions">
              <button className="modal-btn modal-btn-cancel" onClick={cancelDelete}>
                取消
              </button>
              <button className="modal-btn modal-btn-danger" onClick={confirmDelete}>
                删除
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
