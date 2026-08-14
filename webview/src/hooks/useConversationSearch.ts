/**
 * 会话内文本搜索 hook（移植 cc-gui useConversationSearch.ts）
 *
 * 铁律：本 hook 由 `messagesSignal`（渲染出的消息变化即变化）驱动，
 * 不订阅流式/生命周期事件 —— 保证 live 会话与历史回放行为一致。
 *
 * 匹配算法：
 *   1. 由 `query` + 三个搜索选项（大小写/整词/正则）构建单个 RegExp。
 *      非法正则返回 0 匹配并置 `isRegexInvalid` 供 UI 提示。
 *   2. TreeWalker 扫描消息容器内所有文本节点，跳过 <pre>/<script>/<style>/
 *      <input>/<textarea> 内文本（代码块按整块匹配）。
 *   3. 纯文本命中包上 <mark class="cc-search-match">。
 *   4. textContent 命中的 <pre> 块加 `.cc-search-block-match` 类，整块算 1 个匹配。
 *
 * 清理时 unwrap 所有 <mark>、移除所有块匹配类 —— 零残留。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

export interface SearchOptions {
  /** 区分大小写（默认不区分）*/
  matchCase: boolean
  /** 整词匹配（\b…\b）*/
  wholeWord: boolean
  /** 把搜索词当作正则 */
  regex: boolean
}

export const DEFAULT_SEARCH_OPTIONS: SearchOptions = {
  matchCase: false,
  wholeWord: false,
  regex: false,
}

/** 单个可导航的匹配项：要么是文本 mark，要么是整块 pre */
export interface ConversationSearchMatch {
  id: string
  markElement: HTMLElement | null
  blockElement: HTMLElement | null
}

export interface UseConversationSearchOptions {
  /** 扫描 + 装饰的容器（.messages-container）*/
  containerRef: React.RefObject<HTMLElement | null>
  /** 渲染消息变化信号（消息数 + 流式标志 + 末条指纹），驱动重扫 */
  messagesSignal: string | number
  /** 防抖毫秒数，默认 180 */
  debounceMs?: number
  /** 是否扫描 + 维护高亮（面板开关）*/
  enabled: boolean
  /** 三个搜索模式开关，变化即重扫 */
  searchOptions?: SearchOptions
}

export interface UseConversationSearchReturn {
  query: string
  setQuery: (next: string) => void
  matches: ConversationSearchMatch[]
  /** 0 起的当前匹配序号；无匹配为 -1 */
  currentIndex: number
  next: () => void
  previous: () => void
  isSearching: boolean
  /** 正则模式编译失败 */
  isRegexInvalid: boolean
  /** 清空搜索词 + 移除所有 DOM 高亮 */
  clear: () => void
}

/** CSS 类名 —— 与清理逻辑和样式文件保持一致 */
const MARK_CLASS = 'cc-search-match'
const CURRENT_CLASS = 'is-current'
const BLOCK_MATCH_CLASS = 'cc-search-block-match'

/**
 * TreeWalker NodeFilter：拒绝 <pre>/<script>/<style>/<input>/<textarea> 和
 * 已有 <mark>（我们自己的）内的文本节点。
 */
function buildNodeFilter(): NodeFilter {
  return {
    acceptNode(node: Node): number {
      const text = node.nodeValue
      if (!text || !text.trim()) return NodeFilter.FILTER_REJECT
      let parent = node.parentElement
      while (parent) {
        const tag = parent.tagName
        if (tag === 'PRE' || tag === 'SCRIPT' || tag === 'STYLE' ||
            tag === 'INPUT' || tag === 'TEXTAREA') {
          return NodeFilter.FILTER_REJECT
        }
        if (parent.classList.contains(MARK_CLASS)) {
          return NodeFilter.FILTER_REJECT
        }
        parent = parent.parentElement
      }
      return NodeFilter.FILTER_ACCEPT
    },
  }
}

/**
 * 移除容器内所有搜索装饰。幂等。
 * 最后对每个受影响父节点调一次 normalize()（合并被 splitText 拆开的文本节点），
 * 整体 O(n)。
 */
export function clearSearchDecorations(container: HTMLElement | null): void {
  if (!container) return
  const marks = container.querySelectorAll(`mark.${MARK_CLASS}`)
  const dirtyParents = new Set<Node>()
  marks.forEach((mark) => {
    const parent = mark.parentNode
    if (!parent) return
    while (mark.firstChild) {
      parent.insertBefore(mark.firstChild, mark)
    }
    parent.removeChild(mark)
    dirtyParents.add(parent)
  })
  dirtyParents.forEach((p) => p.normalize())
  const blocks = container.querySelectorAll(`.${BLOCK_MATCH_CLASS}`)
  blocks.forEach((el) => el.classList.remove(BLOCK_MATCH_CLASS, CURRENT_CLASS))
}

interface MatchOccurrence {
  textNode: Text
  start: number
  end: number
}

/** 转义正则元字符，使模式按字面量匹配 */
export function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * 构建用于文本与代码块匹配的 RegExp。
 * 搜索词为空或正则编译失败时返回 null。
 * 始终带 global 标志（regex.exec 步进 + 显式 lastIndex=0 复位）。
 */
export function buildMatchRegex(query: string, options: SearchOptions): RegExp | null {
  if (!query) return null
  let pattern: string
  if (options.regex) {
    pattern = query
  } else {
    pattern = escapeRegExp(query)
  }
  if (options.wholeWord) {
    pattern = `\\b${pattern}\\b`
  }
  const flags = options.matchCase ? 'g' : 'gi'
  try {
    return new RegExp(pattern, flags)
  } catch {
    return null
  }
}

/** 收集容器内所有 (textNode, start, end) 命中 */
function collectTextMatches(
  container: HTMLElement,
  query: string,
  options: SearchOptions = DEFAULT_SEARCH_OPTIONS,
): MatchOccurrence[] {
  const regex = buildMatchRegex(query, options)
  if (!regex) return []
  const walker = container.ownerDocument.createTreeWalker(
    container,
    NodeFilter.SHOW_TEXT,
    buildNodeFilter(),
  )
  const occurrences: MatchOccurrence[] = []
  let node = walker.nextNode()
  while (node) {
    const text = node.nodeValue ?? ''
    regex.lastIndex = 0
    let m: RegExpExecArray | null
    while ((m = regex.exec(text)) !== null) {
      // 零宽匹配（如 /a*/ 匹 "bbb"）会死循环 —— 跳过
      if (m[0].length === 0) {
        regex.lastIndex++
        continue
      }
      occurrences.push({
        textNode: node as Text,
        start: m.index,
        end: m.index + m[0].length,
      })
    }
    node = walker.nextNode()
  }
  return occurrences
}

/**
 * 每个命外包 <mark class="cc-search-match">，按文档序返回。
 * 同一文本节点内的命中从右往左处理，避免 splitText 使左侧区间失效。
 */
function wrapOccurrences(
  occurrences: MatchOccurrence[],
  doc: Document,
): HTMLElement[] {
  const byNode = new Map<Text, MatchOccurrence[]>()
  for (const occ of occurrences) {
    const list = byNode.get(occ.textNode)
    if (list) list.push(occ)
    else byNode.set(occ.textNode, [occ])
  }

  const marks: HTMLElement[] = []
  const marksByOccurrence = new Map<MatchOccurrence, HTMLElement>()

  byNode.forEach((occs) => {
    // 从右往左排序：先切右侧区间，保住左侧区间偏移
    const sorted = [...occs].sort((a, b) => b.start - a.start)
    sorted.forEach((occ) => {
      // 右侧切分后 occ.textNode 仍是该命中区间的左段，splitText(start) 有效
      const afterStart = occ.textNode.splitText(occ.start)
      afterStart.splitText(occ.end - occ.start)
      const mark = doc.createElement('mark')
      mark.className = MARK_CLASS
      mark.textContent = afterStart.nodeValue ?? ''
      afterStart.parentNode?.replaceChild(mark, afterStart)
      marksByOccurrence.set(occ, mark)
    })
  })

  // 按原始命中顺序（左→右）产出
  for (const occ of occurrences) {
    const m = marksByOccurrence.get(occ)
    if (m) marks.push(m)
  }
  return marks
}

/** textContent 命中的 <pre> 块加标记类，返回命中的 pre 列表 */
function tagCodeBlocks(
  container: HTMLElement,
  query: string,
  options: SearchOptions = DEFAULT_SEARCH_OPTIONS,
): HTMLElement[] {
  const regex = buildMatchRegex(query, options)
  if (!regex) return []
  const result: HTMLElement[] = []
  const pres = container.querySelectorAll('pre')
  pres.forEach((pre) => {
    const text = pre.textContent ?? ''
    regex.lastIndex = 0
    if (regex.test(text)) {
      pre.classList.add(BLOCK_MATCH_CLASS)
      result.push(pre as HTMLElement)
    }
  })
  return result
}

export function useConversationSearch(
  options: UseConversationSearchOptions,
): UseConversationSearchReturn {
  const { containerRef, messagesSignal, debounceMs = 180, enabled, searchOptions = DEFAULT_SEARCH_OPTIONS } = options
  const [query, setQuery] = useState<string>('')
  const [matches, setMatches] = useState<ConversationSearchMatch[]>([])
  const [currentIndex, setCurrentIndex] = useState<number>(-1)
  const [isSearching, setIsSearching] = useState<boolean>(false)
  const [isRegexInvalid, setIsRegexInvalid] = useState<boolean>(false)
  const debounceRef = useRef<number | null>(null)

  // 选项形状签名：任一开关变化都触发重扫
  const optionsKey = `${searchOptions.matchCase ? '1' : '0'}|${searchOptions.wholeWord ? '1' : '0'}|${searchOptions.regex ? '1' : '0'}`

  /** 实际扫描 DOM */
  const performScan = useCallback((rawQuery: string, opts: SearchOptions): void => {
    const container = containerRef.current
    if (!container) {
      setMatches([])
      setCurrentIndex(-1)
      return
    }
    clearSearchDecorations(container)
    const trimmed = rawQuery.trim()
    if (!trimmed) {
      setMatches([])
      setCurrentIndex(-1)
      setIsRegexInvalid(false)
      return
    }

    // 正则校验 —— 向 UI 报错但不崩溃
    if (opts.regex && !buildMatchRegex(trimmed, opts)) {
      setIsRegexInvalid(true)
      setMatches([])
      setCurrentIndex(-1)
      return
    }
    setIsRegexInvalid(false)

    const doc = container.ownerDocument
    const occurrences = collectTextMatches(container, trimmed, opts)
    const wrapped = wrapOccurrences(occurrences, doc)
    const blocks = tagCodeBlocks(container, trimmed, opts)

    // 合并 mark 与 pre 块，按文档序排序（compareDocumentPosition）
    const all: ConversationSearchMatch[] = []
    wrapped.forEach((mark, i) => {
      all.push({ id: `m-${i}`, markElement: mark, blockElement: null })
    })
    blocks.forEach((pre, i) => {
      all.push({ id: `b-${i}`, markElement: null, blockElement: pre })
    })
    all.sort((a, b) => {
      const aNode = a.markElement ?? a.blockElement
      const bNode = b.markElement ?? b.blockElement
      if (!aNode || !bNode) return 0
      const pos = aNode.compareDocumentPosition(bNode)
      if (pos & Node.DOCUMENT_POSITION_FOLLOWING) return -1
      if (pos & Node.DOCUMENT_POSITION_PRECEDING) return 1
      return 0
    })

    setMatches(all)
    setCurrentIndex(all.length > 0 ? 0 : -1)
  }, [containerRef])

  /** 搜索词 / 消息信号 / 选项 / 开关 变化时防抖重扫 */
  useEffect(() => {
    if (!enabled) {
      clearSearchDecorations(containerRef.current)
      setMatches([])
      setCurrentIndex(-1)
      setIsSearching(false)
      setIsRegexInvalid(false)
      return
    }
    if (debounceRef.current !== null) {
      window.clearTimeout(debounceRef.current)
    }
    setIsSearching(true)
    debounceRef.current = window.setTimeout(() => {
      debounceRef.current = null
      performScan(query, searchOptions)
      setIsSearching(false)
    }, debounceMs)
    return () => {
      if (debounceRef.current !== null) {
        window.clearTimeout(debounceRef.current)
        debounceRef.current = null
      }
    }
    // optionsKey 是 searchOptions 内容变化的标准键
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, messagesSignal, enabled, debounceMs, performScan, containerRef, optionsKey])

  /** currentIndex 同步 .is-current 类 + 滚动定位 */
  useEffect(() => {
    if (!enabled) return
    const target = matches[currentIndex]
    matches.forEach((m, i) => {
      if (m.markElement) m.markElement.classList.toggle(CURRENT_CLASS, i === currentIndex)
      if (m.blockElement) m.blockElement.classList.toggle(CURRENT_CLASS, i === currentIndex)
    })
    if (!target) return
    const el = target.markElement ?? target.blockElement
    if (!el) return
    try {
      el.scrollIntoView({ block: 'center', behavior: 'smooth' })
    } catch {
      // jsdom 可能未实现 scrollIntoView
    }
  }, [currentIndex, matches, enabled])

  /** 卸载清理 */
  useEffect(() => {
    return () => {
      clearSearchDecorations(containerRef.current)
    }
  }, [containerRef])

  const next = useCallback(() => {
    setCurrentIndex((i) => {
      if (matches.length === 0) return -1
      return (i + 1) % matches.length
    })
  }, [matches.length])

  const previous = useCallback(() => {
    setCurrentIndex((i) => {
      if (matches.length === 0) return -1
      return (i - 1 + matches.length) % matches.length
    })
  }, [matches.length])

  const clear = useCallback(() => {
    setQuery('')
    setMatches([])
    setCurrentIndex(-1)
    setIsRegexInvalid(false)
    clearSearchDecorations(containerRef.current)
  }, [containerRef])

  return useMemo(
    () => ({ query, setQuery, matches, currentIndex, next, previous, isSearching, isRegexInvalid, clear }),
    [query, matches, currentIndex, next, previous, isSearching, isRegexInvalid, clear],
  )
}
