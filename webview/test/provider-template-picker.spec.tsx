/**
 * 预设供应商选择弹窗交互测试（jsdom 挂载）
 *
 * 覆盖：卡片网格渲染（创建卡 + 全部预设卡）、点预设回调带 preset、
 *       已添加 baseURL 的预设置灰不可点（v2 providerId 按名称 slug 化，
 *       同预设重复添加会静默覆盖旧渠道）、点创建卡回调。
 */
// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, cleanup } from '@testing-library/react'

vi.mock('@/ipc/bridge', () => ({
  sendToJava: () => {},
  openExternalUrl: () => {},
}))

import '@/i18n/config'
import { ProviderTemplatePickerDialog } from '@/components/ProviderTemplatePickerDialog'
import { PROVIDER_PRESETS } from '@/utils/providerPresets'

beforeEach(() => vi.clearAllMocks())
afterEach(cleanup)

describe('ProviderTemplatePickerDialog', () => {
  it('渲染分组标题 + 创建卡 + 全部预设卡（含模型数提示）', () => {
    render(
      <ProviderTemplatePickerDialog
        addedBaseURLs={[]}
        onPick={() => {}}
        onCreateCustom={() => {}}
        onCancel={() => {}}
      />,
    )
    expect(screen.getByText('智谱')).toBeTruthy()
    expect(screen.getByText('其他')).toBeTruthy()
    expect(screen.getByText('创建自定义供应商')).toBeTruthy()
    for (const p of PROVIDER_PRESETS) {
      expect(screen.getByText(p.name.zh)).toBeTruthy()
    }
  })

  it('点预设卡 → onPick 带对应 preset', () => {
    const onPick = vi.fn()
    render(
      <ProviderTemplatePickerDialog
        addedBaseURLs={[]}
        onPick={onPick}
        onCreateCustom={() => {}}
        onCancel={() => {}}
      />,
    )
    fireEvent.click(screen.getByText('DeepSeek'))
    expect(onPick).toHaveBeenCalledTimes(1)
    expect(onPick.mock.calls[0][0].id).toBe('deepseek')
  })

  it('已添加预设仅提示不拦截：徽章 + 可点（v2 providerId 为 UUID，重复添加生成并列新渠道）', () => {
    const onPick = vi.fn()
    const added = PROVIDER_PRESETS[0]
    render(
      <ProviderTemplatePickerDialog
        addedBaseURLs={[added.baseURL + '/']}
        onPick={onPick}
        onCreateCustom={() => {}}
        onCancel={() => {}}
      />,
    )
    expect(screen.getByText('已添加')).toBeTruthy()
    const addedBtn = screen.getByText(added.name.zh).closest('button') as HTMLButtonElement
    expect(addedBtn.disabled).toBe(false)
    fireEvent.click(addedBtn)
    expect(onPick).toHaveBeenCalledTimes(1)
    expect(onPick.mock.calls[0][0].id).toBe(added.id)
  })

  it('点创建卡 → onCreateCustom', () => {
    const onCreateCustom = vi.fn()
    render(
      <ProviderTemplatePickerDialog
        addedBaseURLs={[]}
        onPick={() => {}}
        onCreateCustom={onCreateCustom}
        onCancel={() => {}}
      />,
    )
    fireEvent.click(screen.getByText('创建自定义供应商'))
    expect(onCreateCustom).toHaveBeenCalledTimes(1)
  })
})
