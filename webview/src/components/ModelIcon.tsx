/**
 * 模型厂商图标（对齐 cc-gui ProviderModelIcon）
 *
 * 数据源：@lobehub/icons（全厂商品牌图标包）
 * 映射：modelIconMapping.ts（modelId/providerId 正则 → vendor key）
 *
 * 简化（与 cc-gui 差异）：
 * - 只渲染彩色 Color 版（无 Color 的厂商回退 Mono）
 * - 未知厂商 / ZCode 内置 provider → ZaiIcon（插件品牌 fallback）
 */

import { resolveIconVendor, type ModelVendor } from '@/utils/modelIconMapping'
import { ZaiIcon } from './ZaiIcon'

// 按需导入常用厂商图标（彩色 Color 优先，无 Color 用 Mono）
import ClaudeColor from '@lobehub/icons/es/Claude/components/Color'
import GeminiColor from '@lobehub/icons/es/Gemini/components/Color'
import QwenColor from '@lobehub/icons/es/Qwen/components/Color'
import DeepSeekColor from '@lobehub/icons/es/DeepSeek/components/Color'
import KimiColor from '@lobehub/icons/es/Kimi/components/Color'
import ZhipuColor from '@lobehub/icons/es/Zhipu/components/Color'
import MinimaxColor from '@lobehub/icons/es/Minimax/components/Color'
import DoubaoColor from '@lobehub/icons/es/Doubao/components/Color'
import HunyuanColor from '@lobehub/icons/es/Hunyuan/components/Color'
import SparkColor from '@lobehub/icons/es/Spark/components/Color'
import BaichuanColor from '@lobehub/icons/es/Baichuan/components/Color'
import YiColor from '@lobehub/icons/es/Yi/components/Color'
import MistralColor from '@lobehub/icons/es/Mistral/components/Color'
import MetaColor from '@lobehub/icons/es/Meta/components/Color'
import CohereColor from '@lobehub/icons/es/Cohere/components/Color'
// 以下厂商无 Color 版，用 Mono
import OpenAIMono from '@lobehub/icons/es/OpenAI/components/Mono'
import MoonshotMono from '@lobehub/icons/es/Moonshot/components/Mono'
import GrokMono from '@lobehub/icons/es/Grok/components/Mono'
import OpenRouterMono from '@lobehub/icons/es/OpenRouter/components/Mono'

import type { ComponentType } from 'react'

/** vendor → 图标组件（Color 优先）*/
const VENDOR_ICON: Partial<Record<ModelVendor, ComponentType<{ size?: number }>>> = {
  claude: ClaudeColor,
  gemini: GeminiColor,
  qwen: QwenColor,
  deepseek: DeepSeekColor,
  kimi: KimiColor,
  zhipu: ZhipuColor,
  minimax: MinimaxColor,
  doubao: DoubaoColor,
  hunyuan: HunyuanColor,
  spark: SparkColor,
  baichuan: BaichuanColor,
  yi: YiColor,
  mistral: MistralColor,
  meta: MetaColor,
  cohere: CohereColor,
  // 无 Color 版的厂商
  openai: OpenAIMono,
  moonshot: MoonshotMono,
  grok: GrokMono,
  openrouter: OpenRouterMono,
}

interface Props {
  /** 模型 ID（用于厂商正则匹配，如 "deepseek-v4-flash"）*/
  modelId?: string
  /** provider ID（modelId 匹配不到时回退）*/
  providerId?: string
  /** 图标尺寸（px），默认 16 */
  size?: number
  /** 额外 className */
  className?: string
}

export function ModelIcon({ modelId, providerId, size = 16, className }: Props) {
  // ZCode 内置 provider（builtin:xxx / UUID 形式）→ 直接用 Zai 品牌图标
  if (!modelId && (!providerId || providerId.startsWith('builtin:') || isUuid(providerId))) {
    return <ZaiIcon size={size} className={className} />
  }

  const vendor = resolveIconVendor(providerId, modelId)
  const Icon = VENDOR_ICON[vendor]

  // 已知厂商 → 渲染品牌图标；未知 → Zai fallback
  if (Icon) {
    return <Icon size={size} />
  }
  return <ZaiIcon size={size} className={className} />
}

/** 简单 UUID 判断（ZCode 的 provider 注册表 key 是 UUID）*/
function isUuid(s: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(s)
}
