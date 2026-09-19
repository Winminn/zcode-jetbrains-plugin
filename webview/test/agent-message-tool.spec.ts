import { describe, expect, it } from 'vitest'
import { isAgentMessageTool, shortAgentId, parseAgentMessageReceipt } from '../src/utils/agentMessage'

describe('isAgentMessageTool', () => {
  it('只识别代理间消息族两个工具', () => {
    expect(isAgentMessageTool('SendMessage')).toBe(true)
    expect(isAgentMessageTool('RespondToCoordinator')).toBe(true)
    expect(isAgentMessageTool('Bash')).toBe(false)
    expect(isAgentMessageTool('Agent')).toBe(false)
  })
})

describe('shortAgentId', () => {
  it('agent_<uuid> 截到前 8 位', () => {
    expect(shortAgentId('agent_d8b9704-ec27-4fc0-bf29-35401ad12add')).toBe('agent_d8b9704')
  })
  it('非 agent_ 前缀原样返回', () => {
    expect(shortAgentId('call_abc12345')).toBe('call_abc12345')
    expect(shortAgentId('')).toBe('')
  })
})

describe('parseAgentMessageReceipt', () => {
  it('SendMessage 成功回执：delivery 三态透传', () => {
    expect(parseAgentMessageReceipt(JSON.stringify({ status: 'success', messageId: 'msg_1', delivery: 'queued' })))
      .toMatchObject({ status: 'success', id: 'msg_1', delivery: 'queued' })
    expect(parseAgentMessageReceipt(JSON.stringify({ status: 'success', messageId: 'msg_2', delivery: 'steered' })))
      .toMatchObject({ delivery: 'steered' })
    expect(parseAgentMessageReceipt(JSON.stringify({ status: 'success', messageId: 'msg_3', delivery: 'resumed_background' })))
      .toMatchObject({ delivery: 'resumed_background' })
  })
  it('无 delivery 的成功回执 delivery 为空（渲染层回退 sent 文案）', () => {
    const r = parseAgentMessageReceipt(JSON.stringify({ status: 'success', messageId: 'msg_4' }))
    expect(r).toMatchObject({ status: 'success', id: 'msg_4' })
    expect(r?.delivery).toBeUndefined()
  })
  it('RespondToCoordinator 回执：responseId 归一到 id', () => {
    expect(parseAgentMessageReceipt(JSON.stringify({ status: 'success', responseId: 'resp_1', message: 'ok' })))
      .toMatchObject({ status: 'success', id: 'resp_1', message: 'ok' })
  })
  it('失败回执带 error', () => {
    expect(parseAgentMessageReceipt(JSON.stringify({ status: 'failed', messageId: 'msg_5', error: 'agent not found' })))
      .toMatchObject({ status: 'failed', error: 'agent not found' })
  })
  it('非 JSON / 非法 status / 空值 → null 回退原文', () => {
    expect(parseAgentMessageReceipt('not json')).toBeNull()
    expect(parseAgentMessageReceipt('{"status":"weird"}')).toBeNull()
    expect(parseAgentMessageReceipt('{"foo":1}')).toBeNull()
    expect(parseAgentMessageReceipt(null)).toBeNull()
    expect(parseAgentMessageReceipt(undefined)).toBeNull()
    expect(parseAgentMessageReceipt('')).toBeNull()
  })
})
