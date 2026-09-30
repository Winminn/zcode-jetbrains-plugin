/**
 * 预设供应商表测试（纯数据 + 纯函数，node 环境零依赖）
 *
 * 覆盖：每条预设的结构合法性（kind 枚举/https baseURL/模型行必填位/无重复 modelId/
 *       context-output 正数）——预设预填值直接进 config.json（喂 autocompact 阈值），
 *       脏数据会静默写坏渠道；baseURL 全局唯一（判重按 baseURL，重复预设会互相置灰）；
 *       normalizeBaseURL/isPresetAdded 尾斜杠归一口径。
 */

import { describe, it, expect } from 'vitest'
import { PROVIDER_PRESETS, normalizeBaseURL, isPresetAdded, type ProviderPreset } from '../src/utils/providerPresets'

const KINDS = new Set(['anthropic', 'openai-compatible'])
const GROUPS = new Set(['zhipu', 'other'])

function assertPresetShape(p: ProviderPreset) {
  expect(p.id, p.id).toBeTruthy()
  expect(p.name.zh.trim(), p.id).toBeTruthy()
  expect(p.name.en.trim(), p.id).toBeTruthy()
  expect(KINDS.has(p.kind), `${p.id} kind=${p.kind}`).toBe(true)
  expect(GROUPS.has(p.group), `${p.id} group=${p.group}`).toBe(true)
  expect(p.baseURL, p.id).toMatch(/^https:\/\//)
  if (p.keyUrl) expect(p.keyUrl, p.id).toMatch(/^https:\/\//)
  expect(p.accent, p.id).toMatch(/^#[0-9a-fA-F]{3,8}$/)
  expect(p.badge.trim(), p.id).toBeTruthy()
  expect(p.models.length, p.id).toBeGreaterThan(0)
}

describe('providerPresets 数据完整性', () => {
  it('预设表非空且结构合法', () => {
    expect(PROVIDER_PRESETS.length).toBeGreaterThanOrEqual(14)
    for (const p of PROVIDER_PRESETS) assertPresetShape(p)
  })

  it('分组口径：智谱组恰为 4 条（Coding Plan/API × BigModel/Z.ai）且排在最前', () => {
    const zhipu = PROVIDER_PRESETS.filter((p) => p.group === 'zhipu')
    expect(zhipu.map((p) => p.id)).toEqual(['bigmodel-api', 'zai-api', 'bigmodel-standard-api', 'zai-standard-api'])
  })

  it('每条预设模型行无重复 modelId，context/output 为正数', () => {
    for (const p of PROVIDER_PRESETS) {
      const ids = p.models.map((m) => m.modelId)
      expect(new Set(ids).size, p.id).toBe(ids.length)
      for (const m of p.models) {
        expect(m.modelId.trim(), p.id).toBeTruthy()
        expect(Number.isInteger(m.context) && m.context > 0, `${p.id}/${m.modelId} context`).toBe(true)
        if (m.output != null) {
          expect(Number.isInteger(m.output) && m.output > 0, `${p.id}/${m.modelId} output`).toBe(true)
        }
      }
    }
  })

  it('baseURL 全局唯一（判重按 baseURL，重复预设会互相置灰）', () => {
    const urls = PROVIDER_PRESETS.map((p) => normalizeBaseURL(p.baseURL))
    expect(new Set(urls).size).toBe(urls.length)
  })

  it('关键预设口径抽查（官方 templateRules 对齐：Kimi/DeepSeek/百炼中国/智谱族）', () => {
    const kimi = PROVIDER_PRESETS.find((p) => p.id === 'moonshot-kimi')
    expect(kimi?.baseURL).toBe('https://api.moonshot.cn/anthropic')
    expect(kimi?.kind).toBe('anthropic')
    const deepseek = PROVIDER_PRESETS.find((p) => p.id === 'deepseek')
    // 客户端实拍：第三方默认 anthropic 协议 + /anthropic 兼容端点
    expect(deepseek?.baseURL).toBe('https://api.deepseek.com/anthropic')
    const qwenCn = PROVIDER_PRESETS.find((p) => p.id === 'qwen-alibaba-model-studio-cn')
    expect(qwenCn?.kind).toBe('anthropic')
    expect(qwenCn?.baseURL).toBe('https://dashscope.aliyuncs.com/apps/anthropic')
    // 国际站官方走 openai-chat-completions（无 anthropic 兼容端点）
    const qwenIntl = PROVIDER_PRESETS.find((p) => p.id === 'qwen-alibaba-model-studio-intl')
    expect(qwenIntl?.kind).toBe('openai-compatible')
    // 智谱族：Coding Plan = anthropic-messages 端点；标准 API = openai-chat-completions 端点
    const bigmodelPlan = PROVIDER_PRESETS.find((p) => p.id === 'bigmodel-api')
    expect(bigmodelPlan?.kind).toBe('anthropic')
    expect(bigmodelPlan?.baseURL).toBe('https://open.bigmodel.cn/api/anthropic')
    const zaiStd = PROVIDER_PRESETS.find((p) => p.id === 'zai-standard-api')
    expect(zaiStd?.kind).toBe('openai-compatible')
    expect(zaiStd?.baseURL).toBe('https://api.z.ai/api/paas/v4')
  })
})

describe('normalizeBaseURL / isPresetAdded', () => {
  it('尾斜杠归一', () => {
    expect(normalizeBaseURL('https://api.example.com/')).toBe('https://api.example.com')
    expect(normalizeBaseURL(' https://api.example.com/// ')).toBe('https://api.example.com')
    expect(normalizeBaseURL('https://api.example.com')).toBe('https://api.example.com')
  })

  it('isPresetAdded 按归一后的 baseURL 精确匹配', () => {
    const preset = PROVIDER_PRESETS[0]
    expect(isPresetAdded(preset, [preset.baseURL + '/'])).toBe(true)
    expect(isPresetAdded(preset, ['https://other.example.com'])).toBe(false)
    expect(isPresetAdded(preset, [])).toBe(false)
  })
})
