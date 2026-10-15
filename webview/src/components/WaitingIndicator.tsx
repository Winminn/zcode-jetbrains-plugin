/**
 * 等待指示器
 *
 * 规划文档第四节：
 *   ⊙ GLM-5.2 正在生成响应...（已 12 秒）
 *   - CSS 旋转圆环 spinner
 *   - 三个跳动省略号（500ms 切换 . → .. → ...）
 *   - 计时器用绝对时间差算（跨视图切换不重置）
 *
 * 生成速度（2026-10-14）：streaming 期 2s 轮询 session/debug，显示最近完成的
 * 模型请求真值 TPS（服务端算好，官方开发者工具面板同源）。单请求进行中数值
 * 不动，工具循环长回合里每完成一次请求跳新值；数据是 agent 进程内存，重启后
 * 从新回合开始积累。组件卸载（回合结束）轮询即停。
 * 显示门控：模型流式静默超 3s（工具执行/构建中）即隐藏速率，防陈旧值被误读
 * 为实时速度；毛刺样本（生成窗口塌缩/超物理上限）由 tps.ts 采样门槛过滤。
 */

import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useStore } from '@/store/useStore'
import { elapsedDesc } from '@/utils/time'
import { lastTpsRound, estimateTpsFromChars, calibrateCharsPerToken } from '@/utils/tps'
import { modelStreamActivity, streamCharsPerSec, streamCharsTotal } from '@/utils/streamReducer'
import '../styles/waiting-indicator.less'

/** 模型流式静默超过该时长即认为不在生成（工具执行/构建中），隐藏速率只留转圈计时 */
const STREAM_ACTIVE_MS = 3000
/** 工具忙碌持续超过该时长才隐藏速率：快速工具（Read/Grep 等亚秒~几秒）不闪隐，
 *  长执行（构建/子代理走查）3s 后照旧隐藏（真机反馈快速工具调用速率闪隐不合理） */
const TOOL_BUSY_GRACE_MS = 3000
/** 真值新鲜期：rounds 按请求完成落账（服务端统计周期=每请求完成，无周期性推送），
 *  长生成期间旧真值会冻结挂屏（真机反馈"真实数据很久不变"）——落账超 8s 即交估算接管 */
const REAL_FRESH_MS = 8000
/** 估算显示值保持时长：500ms 自刷会令估算数字跳太快（真机反馈），屏上数字 2s 一更新 */
const EST_HOLD_MS = 2000

interface Props {
  /** 开始等待的时间戳（毫秒）。不传则不显示计时 */
  since?: number
}

export function WaitingIndicator({ since }: Props) {
  const { t } = useTranslation()
  const currentModel = useStore((s) => s.currentModel)
  const messages = useStore((s) => s.messages)
  const streamingMessageId = useStore((s) => s.streamingMessageId)
  const sessionDebug = useStore((s) => s.sessionDebug)
  const loadSessionDebug = useStore((s) => s.loadSessionDebug)
  const [, force] = useState(0)
  // 工具忙碌起点（宽限计时锚点）：组件 500ms 自刷，ref 记录不触发重渲染
  const toolBusySince = useRef(0)
  // 自校准取样锚点：上次观察到的真值回合 id 与当时的字符累计值
  const lastRoundIdRef = useRef<string | null>(null)
  const charsMarkRef = useRef(0)
  // 估算显示值保持槽（2s 一更新，防数字跳太快）
  const estHoldRef = useRef<{ v: number | null; at: number }>({ v: null, at: 0 })

  // 每 500ms 重渲染（更新省略号 + 计时）；另挂 2s 轮询拉 session/debug 真值 TPS——
  // 数值只在模型请求完成时变（单请求秒级~几十秒），2s 已贴合变化节拍，1s 属同值空转
  useEffect(() => {
    const timer = setInterval(() => force((n) => n + 1), 500)
    loadSessionDebug()
    const tpsTimer = setInterval(loadSessionDebug, 2000)
    return () => {
      clearInterval(timer)
      clearInterval(tpsTimer)
    }
  }, [])

  // 模型名优先取 currentModel，回退最后一条 assistant 消息的 modelID
  const modelName = currentModel?.modelId
    ?? (() => {
      for (let i = messages.length - 1; i >= 0; i--) {
        const m = messages[i].info.modelID
        if (m) return m
      }
      return null
    })()

  const dots = '.'.repeat(Math.floor(Date.now() / 500) % 3 + 1)
  const elapsed = since ? elapsedDesc(since) : ''
  // 速率只在模型流式活跃时显示：rounds 是请求完成后的静态值，工具执行/构建期间
  // 挂出来会被误读成实时速度（真机反馈"构建中为何还有值"）；500ms 自刷保证
  // 静默 3s 后即消失、恢复输出即回归
  const streamActive = Date.now() - modelStreamActivity.lastAt < STREAM_ACTIVE_MS
  // 流式消息上有执行中/待执行的工具（Agent 子代理并行走查、Bash 构建等）即视为
  // "模型没在出字"：v4 面子代理内容帧会重映射进父流刷新 lastAt，单靠时间门控
  // 拦不住（真机反馈"子代理执行中速率一直不变"——挂的是派发轮的静态值）。
  // 但快速工具亚秒即完，立即隐藏会闪隐（真机反馈）——持续超宽限期才隐藏
  const toolBusy = (() => {
    const msg = streamingMessageId ? messages.find((m) => m.info.id === streamingMessageId) : null
    if (!msg) return false
    return msg.parts.some(
      (p) => p.type === 'tool' && (p.state.status === 'running' || p.state.status === 'pending'),
    )
  })()
  if (toolBusy) {
    if (!toolBusySince.current) toolBusySince.current = Date.now()
  } else {
    toolBusySince.current = 0
  }
  const toolBusyLong =
    toolBusy && toolBusySince.current > 0 && Date.now() - toolBusySince.current > TOOL_BUSY_GRACE_MS
  const generating = streamActive && !toolBusyLong
  const lastRound = generating && sessionDebug ? lastTpsRound(sessionDebug.rounds) : null
  // 自校准：新真值回合落账 → 用"两次落账间的流式字符增量 ÷ 服务端 outputTokens"
  // 反推实测 chars/token，EMA 进估算除数（首回合仅记锚点不校准——那笔回合的字符
  // 不在本进程计数里）。generating 门控防止工具/子代理期间的重映射帧污染样本
  if (lastRound && lastRound.requestId !== lastRoundIdRef.current) {
    if (lastRoundIdRef.current !== null) {
      calibrateCharsPerToken(streamCharsTotal() - charsMarkRef.current, lastRound.usage.outputTokens ?? 0)
    }
    lastRoundIdRef.current = lastRound.requestId
    charsMarkRef.current = streamCharsTotal()
  }
  // 估算兜底：真值新鲜期内不介入；真值落账超 8s（长生成中旧值冻结）即接管，
  // 新真值落账自动让位。显示值 2s 保持一更新
  const realFresh = lastRound != null && Date.now() - lastRound.recordedAt < REAL_FRESH_MS
  const realTps = realFresh ? (lastRound?.tokensPerSecond ?? null) : null
  const estRaw =
    generating && realTps == null ? estimateTpsFromChars(streamCharsPerSec()) : null
  const estHold = estHoldRef.current
  if (estRaw == null) {
    if (estHold.v != null) estHoldRef.current = { v: null, at: 0 }
  } else if (estHold.v == null || Date.now() - estHold.at >= EST_HOLD_MS) {
    estHoldRef.current = { v: estRaw, at: Date.now() }
  }
  const estTps = estHoldRef.current.v

  return (
    <div className="waiting">
      <div className="waiting__spinner" />
      <span className="waiting__text">
        {(modelName ? t('common.waiting.generatingWithModel', { model: modelName }) : t('common.waiting.generating'))}
        {/* 省略号定宽：动画 1~3 个点宽度变化不再推挤后面的速率/计时（真机反馈抖动）*/}
        <span className="waiting__dots">{dots}</span>
      </span>
      {realTps != null && (
        <span className="waiting__rate">{t('usage.speed.live', { tps: realTps.toFixed(1) })}</span>
      )}
      {realTps == null && estTps != null && (
        <span className="waiting__rate waiting__rate--estimate">
          {t('usage.speed.estimate', { tps: estTps.toFixed(1) })}
        </span>
      )}
      {elapsed && <span className="waiting__time">{t('common.waiting.elapsed', { time: elapsed })}</span>}
    </div>
  )
}
