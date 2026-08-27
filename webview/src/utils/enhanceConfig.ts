/**
 * 提示词润色配置（前端镜像，纯前端消费）
 *
 * 默认关闭：输入框润色按钮（✨）不在 UI 上出现，在「基础设置→行为」开启后显示。
 * 可配置润色专用模型（默认跟随会话当前所选模型）；思考深度不随设置——
 * generateText 为裸 AI SDK 调用（实测 reasoningTokens=0），天然不思考。
 * 存储走 persist kv 通道（key=zcode.enhance.config）：localStorage 即时生效 +
 * 去抖回存 IDE PropertiesComponent，跨会话/重启保留。
 *
 * 同标签即时生效：写入后派发 ENHANCE_CONFIG_CHANGED_EVENT（InputBox 监听重读）；
 * 跨标签同步走 storage 事件（persist.ts 既有机制）；启动权威值到达走 KV_HYDRATED。
 *
 * 专用模型失效（provider 删除/订阅过期）由后端兜底：config.json 构造不出
 * runtimeModel 时回退默认 provider（handleEnhancePrompt 链路）。
 */
import { getPersisted, setPersisted } from './persist'

/** 润色专用模型（null = 跟随会话当前所选模型）*/
export interface EnhanceModel {
  providerId: string
  modelId: string
}

export interface EnhanceConfig {
  /** 输入框润色按钮开关（默认关闭）*/
  enhanceEnabled: boolean
  /** 润色专用模型（默认 null 跟随会话）*/
  enhanceModel: EnhanceModel | null
}

const KEY = 'zcode.enhance.config'

/** 写入后派发的同标签变更事件（storage 事件不回派到写入方自己的标签）*/
export const ENHANCE_CONFIG_CHANGED_EVENT = 'zcode:enhance-config-changed'

export const DEFAULT_ENHANCE_CONFIG: EnhanceConfig = {
  enhanceEnabled: false,
  enhanceModel: null,
}

function parseModel(v: unknown): EnhanceModel | null {
  if (typeof v !== 'object' || v === null) return null
  const o = v as Partial<EnhanceModel>
  return typeof o.providerId === 'string' && typeof o.modelId === 'string' && o.providerId && o.modelId
    ? { providerId: o.providerId, modelId: o.modelId }
    : null
}

export function readEnhanceConfig(): EnhanceConfig {
  const raw = getPersisted(KEY)
  if (!raw) return { ...DEFAULT_ENHANCE_CONFIG }
  try {
    const obj = JSON.parse(raw) as Partial<EnhanceConfig>
    return {
      enhanceEnabled:
        typeof obj.enhanceEnabled === 'boolean' ? obj.enhanceEnabled : DEFAULT_ENHANCE_CONFIG.enhanceEnabled,
      enhanceModel: parseModel(obj.enhanceModel),
    }
  } catch {
    return { ...DEFAULT_ENHANCE_CONFIG }
  }
}

export function writeEnhanceConfig(config: EnhanceConfig): void {
  setPersisted(KEY, JSON.stringify(config))
  try {
    window.dispatchEvent(new Event(ENHANCE_CONFIG_CHANGED_EVENT))
  } catch {
    /* 事件派发失败不影响写入本身 */
  }
}
