/**
 * ProviderEditorDialog 组件测试（自定义渠道 CRUD 表单）
 *
 * 覆盖：
 * 1. 添加模式：默认一行模型（context 默认 1,000,000）；填表提交 → onConfirm draft 完整；
 *    apiKey 留空 / baseURL 非 http / 模型 ID 空 → 前端校验拦下（onConfirm 不触发）。
 * 2. 编辑模式：initial 回填（含 apiKey 明文，所见即所存）；不动表单提交 → 原值等价
 *    不变；清空 → 校验拦截；填新值 → 新 key。
 * 3. 模型行增删：添加行默认值、单行时删除禁用。
 */

// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { cleanup, render, screen, fireEvent } from '@testing-library/react'
import '@/i18n/config'

import { ProviderEditorDialog } from '@/components/ProviderEditorDialog'
import type { ProviderSaveDraft } from '@/types/messages'

const onConfirm = vi.fn()
const onCancel = vi.fn()

const editInitial = {
  name: 'DeepSeek',
  kind: 'anthropic' as const,
  baseURL: 'https://api.deepseek.com/anthropic',
  apiKey: 'sk-old',
  models: [
    { modelId: 'deepseek-chat', context: '128,000', output: '8,192', images: true, video: false, pdf: true },
  ],
}

function setup(mode: 'add' | 'edit', initial = editInitial) {
  render(
    <ProviderEditorDialog
      mode={mode}
      initial={mode === 'edit' ? initial : null}
      saving={false}
      error={null}
      onConfirm={onConfirm}
      onCancel={onCancel}
    />,
  )
}

/** 表单基础字段（名称/地址/key）按输入序定位（placeholder 由 kind=anthropic 决定）*/
function fillBase(name: string, url: string, key = 'sk-x') {
  const inputs = screen.getAllByRole('textbox') as HTMLInputElement[]
  // 输入序：名称、baseURL、apiKey(password 非 textbox→单独取)、模型行…
  fireEvent.change(inputs[0], { target: { value: name } })
  fireEvent.change(inputs[1], { target: { value: url } })
  const pwd = document.querySelector('input[type="password"]') as HTMLInputElement
  if (pwd) fireEvent.change(pwd, { target: { value: key } })
}

/** 模型 panel 输入（textbox 序：[0]=名称 [1]=baseURL [2]=modelId [3]=context [4]=output）*/
function fillModelRow(modelId: string, ctx = '1000000') {
  const inputs = screen.getAllByRole('textbox') as HTMLInputElement[]
  fireEvent.change(inputs[2], { target: { value: modelId } })
  fireEvent.change(inputs[3], { target: { value: ctx } })
}

function clickSave() {
  fireEvent.click(screen.getByRole('button', { name: /保存|Save/ }))
}

beforeEach(() => {
  onConfirm.mockClear()
  onCancel.mockClear()
})
afterEach(cleanup)

describe('添加模式', () => {
  it('默认一行模型，context 缺省 1,000,000（主流模型普遍 1M）', () => {
    setup('add')
    const inputs = screen.getAllByRole('textbox') as HTMLInputElement[]
    expect((inputs[3] as HTMLInputElement).value).toBe('1,000,000')
  })

  it('填表提交 → onConfirm 携带完整 draft', () => {
    setup('add')
    fillBase('DeepSeek', 'https://api.deepseek.com/anthropic', 'sk-new')
    fillModelRow('deepseek-chat', '1000000')
    clickSave()
    expect(onConfirm).toHaveBeenCalledTimes(1)
    const draft: ProviderSaveDraft = onConfirm.mock.calls[0][0]
    expect(draft).toEqual({
      name: 'DeepSeek',
      kind: 'anthropic',
      baseURL: 'https://api.deepseek.com/anthropic',
      apiKey: 'sk-new',
      models: [{ modelId: 'deepseek-chat', context: 1000000, output: undefined, images: undefined, video: undefined, pdf: undefined }],
    })
  })

  it('输入类型（panel 内全称勾选）：图片/PDF 进 draft、视频不勾；文本与输出恒锁定', () => {
    setup('add')
    fillBase('DeepSeek', 'https://x/anthropic', 'sk-x')
    fillModelRow('m1', '1000')
    // 可选 checkbox = 图片/视频/PDF（文本锁定与输出锁定均 disabled）
    const boxes = screen.getAllByRole('checkbox') as HTMLInputElement[]
    const selectable = boxes.filter((b) => !b.disabled)
    expect(selectable).toHaveLength(3)
    fireEvent.click(selectable[0]) // 图片
    fireEvent.click(selectable[2]) // PDF
    clickSave()
    const m = onConfirm.mock.calls[0][0].models[0]
    expect(m.images).toBe(true)
    expect(m.video).toBeFalsy() // false 规约为 undefined（可选位不落 config）
    expect(m.pdf).toBe(true)
    // 两个锁定项（输入文本 + 输出文本）：disabled 且 checked
    const locked = boxes.filter((b) => b.disabled)
    expect(locked).toHaveLength(2)
    expect(locked.every((b) => b.checked)).toBe(true)
  })

  it('apiKey 留空 → 校验拦截（onConfirm 不触发，显示错误）', () => {
    setup('add')
    fillBase('DeepSeek', 'https://api.deepseek.com/anthropic', '')
    fillModelRow('deepseek-chat')
    clickSave()
    expect(onConfirm).not.toHaveBeenCalled()
    expect(screen.getByText('API Key 不能为空')).toBeTruthy()
  })

  it('baseURL 非 http(s) → 校验拦截', () => {
    setup('add')
    fillBase('DeepSeek', 'ftp://x', 'sk-x')
    fillModelRow('deepseek-chat')
    clickSave()
    expect(onConfirm).not.toHaveBeenCalled()
  })

  it('模型 ID 留空 → 校验拦截', () => {
    setup('add')
    fillBase('DeepSeek', 'https://x/anthropic', 'sk-x')
    // 不填 modelId，只填 context
    const inputs = screen.getAllByRole('textbox') as HTMLInputElement[]
    fireEvent.change(inputs[3], { target: { value: '1000' } })
    clickSave()
    expect(onConfirm).not.toHaveBeenCalled()
  })
})

describe('编辑模式', () => {
  it('initial 回填名称/地址/key/模型行（所见即所存）', () => {
    setup('edit')
    const inputs = screen.getAllByRole('textbox') as HTMLInputElement[]
    expect(inputs[0].value).toBe('DeepSeek')
    expect(inputs[1].value).toBe('https://api.deepseek.com/anthropic')
    expect(inputs[2].value).toBe('deepseek-chat')
    expect((inputs[3] as HTMLInputElement).value).toBe('128,000')
    expect((inputs[4] as HTMLInputElement).value).toBe('8,192')
    // apiKey 回填明文（password 遮蔽显示，value 在）
    const pwd = document.querySelector('input[type="password"]') as HTMLInputElement
    expect(pwd.value).toBe('sk-old')
    // 输入类型回填：图片勾选（editInitial images=true）、视频不勾
    const selectable = (screen.getAllByRole('checkbox') as HTMLInputElement[]).filter((b) => !b.disabled)
    expect(selectable[0].checked).toBe(true)
    expect(selectable[1].checked).toBe(false)
    expect(selectable[2].checked).toBe(true) // editInitial pdf=true
  })

  it('不动表单直接提交 → draft.apiKey=回填的原值（等价不变）', () => {
    setup('edit')
    clickSave()
    expect(onConfirm).toHaveBeenCalledTimes(1)
    expect(onConfirm.mock.calls[0][0].apiKey).toBe('sk-old')
  })

  it('清空 apiKey → 校验拦截（缺 key 渠道会从列表隐藏，弃用请删渠道）', () => {
    setup('edit')
    const pwd = document.querySelector('input[type="password"]') as HTMLInputElement
    fireEvent.change(pwd, { target: { value: '' } })
    clickSave()
    expect(onConfirm).not.toHaveBeenCalled()
    expect(screen.getByText('API Key 不能为空')).toBeTruthy()
  })

  it('填新 key 提交 → draft.apiKey=新值', () => {
    setup('edit')
    const pwd = document.querySelector('input[type="password"]') as HTMLInputElement
    fireEvent.change(pwd, { target: { value: 'sk-rotated' } })
    clickSave()
    expect(onConfirm.mock.calls[0][0].apiKey).toBe('sk-rotated')
  })
})

describe('模型行编辑', () => {
  it('添加模型 panel 带默认 context；单 panel 时删除禁用', () => {
    setup('add')
    // 单 panel 时删除禁用
    expect((screen.getByTitle('移除此模型') as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: /添加模型|Add Model/ }))
    const inputs = screen.getAllByRole('textbox') as HTMLInputElement[]
    // 第二 panel context = 基础 2 + panel 宽 3 + 偏移 1
    expect((inputs[6] as HTMLInputElement).value).toBe('1,000,000')
    const removeBtns = screen.getAllByTitle('移除此模型')
    expect((removeBtns[0] as HTMLButtonElement).disabled).toBe(false)
    expect((removeBtns[1] as HTMLButtonElement).disabled).toBe(false)
  })

  it('多 panel 时可删除第一个', () => {
    setup('edit')
    fireEvent.click(screen.getByRole('button', { name: /添加模型|Add Model/ }))
    const removeBtns = screen.getAllByTitle('移除此模型')
    fireEvent.click(removeBtns[0])
    const inputs = screen.getAllByRole('textbox') as HTMLInputElement[]
    // 剩一个空 panel：基础 2 + panel 宽 3
    expect(inputs).toHaveLength(5)
    expect(inputs[2].value).toBe('')
  })
})
