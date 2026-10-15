/**
 * session/debug 快照聚合（生成速度显示用）。
 *
 * 口径与官方 calculateOutputTps 对齐：TPS = outputTokens ÷ (generationDurationMs/1000)，
 * 只统计 tokensPerSecond/generationDurationMs 非空的完成请求。
 */

import type { SessionDebugRound } from '@/types/messages'

/**
 * 采样有效性门槛（真机反馈 83181.8 tok/s 毛刺后加）：
 * - 生成窗口 <300ms 的样本是测量毛刺——TTFC 占满几乎全部请求耗时窗口塌缩，
 *   outputTokens÷毫秒级窗口数学上成立物理上荒谬（官方面板表格同样会出现这种行）；
 * - TPS >1000 超出模型物理上限（用户裁定阈值：高速模型可超 500，取 1000）。
 * 不合格样本不用于展示、不计入加权平均。
 */
const MIN_GENERATION_MS = 300
const MAX_SANE_TPS = 1000

/**
 * 估算换算系数：chars/token。默认 3.5（GLM 中英混合粗估计：中文≈1.5、英文/代码≈4），
 * 会话内出现第一笔真值回合后开始自校准（真机实证英文为主会话实测≈4.0，固定 2.5
 * 偏高 1.5 倍+）。EMA 平滑+夹取防单样本毛刺，模块级状态（同机同模型，跨标签共享无妨）。
 */
const DEFAULT_CHARS_PER_TOKEN = 3.5
let calibratedCharsPerToken = DEFAULT_CHARS_PER_TOKEN

/** 真值回合落账时喂样本：该请求期间流过的字符数 ÷ 服务端 outputTokens。字符增量
 * 过小（<200，噪音大）或 token 为 0 时弃样。*/
export function calibrateCharsPerToken(charsDelta: number, outputTokens: number): void {
  if (outputTokens <= 0 || charsDelta < 200) return
  const clamped = Math.min(8, Math.max(1.5, charsDelta / outputTokens))
  calibratedCharsPerToken = calibratedCharsPerToken * 0.6 + clamped * 0.4
}

/** 当前生效的 chars/token 比例（自校准后的估算除数）*/
export function currentCharsPerToken(): number {
  return calibratedCharsPerToken
}

/** 字符速率 → 估算 TPS（~ 前缀近似值；charsPerSec=null 透传）*/
export function estimateTpsFromChars(charsPerSec: number | null): number | null {
  if (charsPerSec == null) return null
  return charsPerSec / currentCharsPerToken()
}

function isValidSample(r: SessionDebugRound): boolean {
  return (
    r.tokensPerSecond != null &&
    (r.generationDurationMs ?? 0) >= MIN_GENERATION_MS &&
    r.tokensPerSecond <= MAX_SANE_TPS
  )
}

/** 最近一条有效的真值 TPS 请求（从尾部向前找；null = 尚无合格样本）*/
export function lastTpsRound(rounds: SessionDebugRound[]): SessionDebugRound | null {
  for (let i = rounds.length - 1; i >= 0; i--) {
    if (isValidSample(rounds[i])) return rounds[i]
  }
  return null
}

/**
 * 会话平均 TPS：Σ outputTokens ÷ Σ generationDurationMs 加权平均
 * （长短轮权重不同，各轮 TPS 简单平均会偏差；毛刺样本成对剔除不加总）。
 * 无可用样本时 null。
 */
export function weightedAvgTps(rounds: SessionDebugRound[]): number | null {
  let tokens = 0
  let ms = 0
  for (const r of rounds) {
    const out = r.usage.outputTokens
    if (out != null && isValidSample(r)) {
      tokens += out
      ms += r.generationDurationMs!
    }
  }
  return ms > 0 ? (tokens * 1000) / ms : null
}

/** 累计输出 tokens（缺 usage 的请求按 0 计）*/
export function totalOutputTokens(rounds: SessionDebugRound[]): number {
  return rounds.reduce((sum, r) => sum + (r.usage.outputTokens ?? 0), 0)
}
