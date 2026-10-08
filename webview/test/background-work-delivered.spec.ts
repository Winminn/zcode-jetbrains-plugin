/**
 * 后台任务投递收敛测试（真机实勘修复：CLI 的 backgroundWorks 数组在结果投递进
 * 转录后不推收敛帧，投影条目无限期停 resultPending——通知卡已渲染、面板还挂
 * 「待投递」。投递证据 = task-notification 合成消息的 task-id + status）。
 *
 * 覆盖：
 *   1. parseDeliveredWorkStatuses：completed/failed 终态收集、非通知消息忽略、
 *      子代理 agent_ 前缀同样收集、subagent-message 与非终态忽略
 *   2. mergeBackgroundWorks 投递升级：resultPending → 终态（标题/启动时间保留
 *      投影值）、running 不碰、无证据时行为与旧版完全一致
 */
import { describe, it, expect } from 'vitest'
import { parseDeliveredWorkStatuses, mergeBackgroundWorks } from '@/utils/backgroundTask'
import type { BackgroundWorkSummary, ZCodeMessage } from '@/types/messages'

const notifMessage = (taskId: string, status: string): ZCodeMessage => ({
  info: {
    id: `msg_${taskId}`,
    sessionID: 'sess_main',
    role: 'user',
    synthetic: true,
    source: 'background_task',
    semantics: { origin: 'agent_runtime', kind: 'background_notification', uiVisibility: 'hidden' },
    time: { created: 1789729692000 },
  } as ZCodeMessage['info'],
  parts: [
    {
      type: 'text',
      text: `<task-notification><task-id>${taskId}</task-id><status>${status}</status><summary>Background command completed</summary></task-notification>`,
    },
  ] as ZCodeMessage['parts'],
})

const work = (over: Partial<BackgroundWorkSummary>): BackgroundWorkSummary => ({
  workId: 'w1',
  kind: 'bash',
  title: '投影条目标题',
  status: 'running',
  startedAt: 1000,
  anchorRowId: null,
  ...over,
})

describe('parseDeliveredWorkStatuses', () => {
  it('completed → ended、failed → failed', () => {
    const messages = [
      notifMessage('exec_a1', 'completed'),
      notifMessage('exec_a2', 'failed'),
    ]
    const delivered = parseDeliveredWorkStatuses(messages)
    expect(delivered.get('exec_a1')).toBe('ended')
    expect(delivered.get('exec_a2')).toBe('failed')
    expect(delivered.size).toBe(2)
  })

  it('非合成消息（正文碰巧含 task-notification 字样）不构成投递证据', () => {
    const messages: ZCodeMessage[] = [
      {
        info: { id: 'm1', sessionID: 's', role: 'user', synthetic: false, time: { created: 1 } } as ZCodeMessage['info'],
        parts: [{ type: 'text', text: '<task-notification><task-id>exec_x</task-id><status>completed</status></task-notification>' }] as ZCodeMessage['parts'],
      },
    ]
    expect(parseDeliveredWorkStatuses(messages).size).toBe(0)
  })

  it('子代理 agent_ 前缀完成通知同样收集；subagent-message 与无 task-notification 的通知忽略', () => {
    const agentDone = notifMessage('agent_abc', 'completed')
    const subagentMessage: ZCodeMessage = {
      info: {
        id: 'm2', sessionID: 's', role: 'user', synthetic: true, source: 'subagent_message',
        time: { created: 2 },
      } as ZCodeMessage['info'],
      parts: [{ type: 'text', text: '<subagent-message><agent-id>agent_abc</agent-id></subagent-message>' }] as ZCodeMessage['parts'],
    }
    const delivered = parseDeliveredWorkStatuses([agentDone, subagentMessage])
    expect(delivered.get('agent_abc')).toBe('ended')
    expect(delivered.size).toBe(1)
  })
})

describe('mergeBackgroundWorks 投递升级', () => {
  it('resultPending 按证据翻终态：标题/启动时间保留投影值、取消按钮位收口', () => {
    const projection = [
      work({ workId: 'exec_a1', status: 'resultPending', title: '签名构建全链', startedAt: 42, cancellable: true }),
      work({ workId: 'exec_a2', status: 'resultPending' }),
    ]
    const delivered = new Map([['exec_a1', 'ended' as const], ['exec_a2', 'failed' as const]])
    const merged = mergeBackgroundWorks(projection, [], delivered)
    expect(merged[0]).toMatchObject({ workId: 'exec_a1', status: 'ended', title: '签名构建全链', startedAt: 42, cancellable: false })
    expect(merged[1]).toMatchObject({ workId: 'exec_a2', status: 'failed' })
  })

  it('running 不升级（单向升级：只碰 resultPending）', () => {
    const projection = [work({ workId: 'exec_a1', status: 'running' })]
    const delivered = new Map([['exec_a1', 'ended' as const]])
    expect(mergeBackgroundWorks(projection, [], delivered)[0].status).toBe('running')
  })

  it('不传证据时行为与旧版一致（投影优先去重合并转录重建）', () => {
    const projection = [work({ workId: 'exec_1', status: 'running' })]
    const rebuilt: BackgroundWorkSummary[] = [
      { workId: 'exec_1', kind: 'bash', title: 'x', status: 'ended', startedAt: 1 },
      { workId: 'exec_2', kind: 'bash', title: 'y', status: 'ended', startedAt: 2 },
    ]
    const merged = mergeBackgroundWorks(projection, rebuilt)
    expect(merged.map((w) => `${w.workId}:${w.status}`)).toEqual(['exec_1:running', 'exec_2:ended'])
  })
})
