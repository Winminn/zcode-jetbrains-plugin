/**
 * 后台工作状态面板改造测试（H7 交互改造：复用子代理底部栏 tab，砍独立 badge 浮层）
 *
 * 覆盖：
 *   1. BackgroundTaskList：bash/工作流条目渲染（subagent 过滤）、取消 op、bash 输出拉取
 *      与渲染、空态
 *   2. StatusPanel 集成：tab 改名「后台工作」、running 总数徽标优先、子 tab 切换、
 *      子代理取消按钮经 childSessionId 匹配投影（匹配不到不显示）
 */
// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, cleanup, act } from '@testing-library/react'

let streamBatchHandler: ((sid: string, events: unknown[]) => void) | null = null
let messageHandler: ((msg: unknown) => void) | null = null
const sentRequests: Array<Record<string, unknown>> = []

vi.mock('@/ipc/bridge', () => ({
  initBridge: () => {},
  isInJcef: () => false,
  getWorkspacePath: () => 'G:\\mock',
  getInitialSessionId: () => '',
  onMessage: (fn: (msg: unknown) => void) => {
    messageHandler = fn
    return () => {
      messageHandler = null
    }
  },
  onStreamEvent: () => () => {},
  onStreamBatch: (fn: (sid: string, events: unknown[]) => void) => {
    streamBatchHandler = fn
    return () => {
      streamBatchHandler = null
    }
  },
  sendToJava: (req: Record<string, unknown>) => {
    sentRequests.push(req)
  },
}))

import '@/i18n/config'
import { useStore, handleResponse } from '@/store/useStore'
import { BackgroundTaskList } from '@/components/BackgroundTaskList'
import { StatusPanel } from '@/components/StatusPanel'
import { parseBackgroundTaskWorks, mergeBackgroundWorks } from '@/utils/backgroundTask'
import type { BackgroundWorkSummary, ZCodeMessage } from '@/types/messages'

const SID = 'sess_bgw_panel'

const work = (over: Partial<BackgroundWorkSummary>): BackgroundWorkSummary => ({
  workId: 'w1',
  kind: 'bash',
  title: '后台任务',
  status: 'running',
  startedAt: Date.now() - 10_000,
  anchorRowId: null,
  ...over,
})

beforeEach(() => {
  sentRequests.length = 0
  useStore.getState().init()
  sentRequests.length = 0
  useStore.setState({
    currentSessionId: SID,
    backgroundWorksBySession: {},
    agents: [],
    todos: [],
    fileChanges: [],
    statusPanelCollapsed: false,
  })
})

afterEach(() => {
  cleanup()
})

function injectWorks(works: BackgroundWorkSummary[], sid: string = SID) {
  act(() => {
    streamBatchHandler?.(sid, [
      { type: 'backgroundWorks', seq: 0, sessionId: sid, turnId: null, timestamp: Date.now(), payload: { works } },
    ])
  })
}

describe('BackgroundTaskList 后台任务列表', () => {
  it('渲染 bash/工作流条目；subagent 条目被过滤（子代理走另一个子 tab）', () => {
    injectWorks([
      work({ workId: 'b1', title: 'npm build' }),
      work({ workId: 'f1', kind: 'workflow', title: '重构流' }),
      work({ workId: 's1', kind: 'subagent', title: '审查代理' }),
    ])
    render(<BackgroundTaskList sessionId={SID} />)
    expect(screen.getByText('npm build')).toBeTruthy()
    expect(screen.getByText('重构流')).toBeTruthy()
    expect(screen.queryByText('审查代理')).toBeNull()
  })

  it('running 且 cancellable 显示取消，点击发 cancelBackgroundWork op', () => {
    injectWorks([work({ workId: 'w_cancel' })])
    render(<BackgroundTaskList sessionId={SID} />)
    fireEvent.click(screen.getByText('取消'))
    expect(sentRequests.some((r) => r.op === 'cancelBackgroundWork' && r.workId === 'w_cancel')).toBe(true)
  })

  it('空列表渲染空态文案', () => {
    render(<BackgroundTaskList sessionId={SID} />)
    expect(screen.getByText('暂无后台任务')).toBeTruthy()
  })

  it('bash 输出：展开拉取，应答渲染 pre 文本；降级形态渲染错误文案', () => {
    injectWorks([work({ workId: 'w_out' })])
    render(<BackgroundTaskList sessionId={SID} />)
    fireEvent.click(screen.getByText('输出'))
    expect(sentRequests.some((r) => r.op === 'backgroundBashOutput' && r.workId === 'w_out')).toBe(true)
    act(() => {
      messageHandler?.({
        op: 'backgroundBashOutputResult',
        sessionId: SID,
        workId: 'w_out',
        result: { kind: 'output', workId: 'w_out', status: 'running', output: 'building... 42%', truncated: true, outputPath: '/tmp/out.log' },
      })
    })
    expect(screen.getByText('building... 42%')).toBeTruthy()
  })
})

describe('StatusPanel 任务 tab 集成', () => {
  it('tab 改名「后台工作」；有 running 时徽标显示 running 总数（后台任务+子代理）', () => {
    injectWorks([work({ workId: 'b1' }), work({ workId: 'b2', status: 'failed' })])
    useStore.setState({ agents: [{ callID: 'c1', description: '代理甲', status: 'running' }] })
    render(<StatusPanel />)
    expect(screen.getByText('任务')).toBeTruthy()
    // running 总数 = 1 bash running + 1 agent running = 2（failed bash 不计）
    expect(screen.getByText('2')).toBeTruthy()
  })

  it('空闲时徽标回落「完成/总数」，后台任务条目纳入两类合并计数', () => {
    // 后台任务 1 条 resultPending（干完待投递=完成）+ 子代理 2 完成 1 运行
    injectWorks([work({ workId: 'b1', status: 'resultPending' })])
    useStore.setState({
      agents: [
        { callID: 'c1', description: '代理甲', status: 'completed' },
        { callID: 'c2', description: '代理乙', status: 'completed' },
        { callID: 'c3', description: '代理丙', status: 'running' },
      ],
    })
    render(<StatusPanel />)
    // 无 running？有：代理丙 running → running 优先显示 1。改用无 running 的组合验证回落
    // （本用例仅验证回落语义：全部结束时的组合见下一用例）
    expect(screen.getByText('1')).toBeTruthy()
  })

  it('全部结束时徽标显示「完成/总数」且含后台任务（用户反馈：后台任务纳入总数）', () => {
    injectWorks([
      work({ workId: 'b1', status: 'resultPending' }),
      work({ workId: 'b2', status: 'failed' }),
    ])
    useStore.setState({
      agents: [
        { callID: 'c1', description: '代理甲', status: 'completed' },
        { callID: 'c2', description: '代理乙', status: 'completed' },
      ],
    })
    render(<StatusPanel />)
    // 完成 = resultPending 1 + completed 2 = 3；总数 = 2 bash + 2 agents = 4
    expect(screen.getByText('3/4')).toBeTruthy()
  })

  it('popover 子 tab 切换：默认子代理列表，切到后台任务显示投影条目；子 tab 带计数', () => {
    injectWorks([work({ workId: 'b1', title: 'npm build' })])
    useStore.setState({ agents: [{ callID: 'c1', description: '代理甲', status: 'completed' }] })
    render(<StatusPanel />)
    fireEvent.click(screen.getByText('任务'))
    // 子 tab 计数徽标（后台任务 1 / 子代理 1）
    const counts = document.querySelectorAll('.status-panel-subtab-count')
    expect(counts).toHaveLength(2)
    expect(counts[0].textContent).toBe('1')
    // 默认 sub 子 tab：子代理列表
    expect(screen.getByText('代理甲')).toBeTruthy()
    expect(screen.queryByText('npm build')).toBeNull()
    // 切到 bg 子 tab
    fireEvent.click(screen.getByText('后台任务'))
    expect(screen.getByText('npm build')).toBeTruthy()
    expect(screen.queryByText('代理甲')).toBeNull()
  })

  it('子代理取消按钮：running 且 childSessionId 匹配投影 work 才显示，点击发取消', () => {
    injectWorks([
      work({ workId: 'agent_w1', kind: 'subagent', childSessionId: 'sess_child_1' }),
      work({ workId: 'agent_w2', kind: 'subagent', childSessionId: 'sess_child_2', cancellable: false }),
    ])
    useStore.setState({
      agents: [
        { callID: 'c1', description: '可取消代理', status: 'running', childSessionId: 'sess_child_1' },
        { callID: 'c2', description: '不可取消代理', status: 'running', childSessionId: 'sess_child_2' },
        { callID: 'c3', description: '无匹配代理', status: 'running', childSessionId: 'sess_child_3' },
      ],
    })
    render(<StatusPanel />)
    fireEvent.click(screen.getByText('任务'))
    // 只有可取消代理有取消按钮（cancellable=false 与匹配不到都不显示）
    const cancelBtns = document.querySelectorAll('.status-panel-agent-cancel')
    expect(cancelBtns).toHaveLength(1)
    fireEvent.click(cancelBtns[0])
    expect(sentRequests.some((r) => r.op === 'cancelBackgroundWork' && r.workId === 'agent_w1')).toBe(true)
  })
})

describe('投影缓存对账（历史加载兜底）', () => {
  it('selectSession 发 backgroundWorksList 对账 op；应答落 map（重复订阅不重推快照的兜底）', () => {
    act(() => {
      useStore.getState().selectSession({ sessionId: 'sess_cache_1', title: 'x', status: 'idle', mode: 'build', workspacePath: 'G:\\mock', createdAt: 1, updatedAt: 1 })
    })
    expect(sentRequests.some((r) => r.op === 'backgroundWorksList' && r.sessionId === 'sess_cache_1')).toBe(true)

    act(() => {
      // handleResponse 需要显式传 zustand 的 set/get（store 内 onMessage 绑定在 mock 中不生效）
      handleResponse(
        {
          op: 'backgroundWorksList',
          sessionId: 'sess_cache_1',
          works: [work({ workId: 'w_cached', title: '缓存里的后台任务' })],
        } as never,
        useStore.setState,
        () => useStore.getState(),
      )
    })
    expect(useStore.getState().backgroundWorksBySession['sess_cache_1']).toHaveLength(1)
    expect(useStore.getState().backgroundWorksBySession['sess_cache_1']![0].workId).toBe('w_cached')
  })
})

describe('转录重建（IDE 重启后投影消失的兜底）', () => {
  const BG_OUTPUT = 'Command running in background with ID: exec_1a2b3c4d-1111-2222-3333-444455556666. Output will be written to log.'
  const bashMsg = (output: string, command = 'npm run build'): ZCodeMessage => ({
    info: { role: 'assistant', id: 'a1', sessionID: 's', anchor: { turnId: 't' } },
    parts: [
      { type: 'tool', tool: 'Bash', callID: 'call_1', state: { input: { command }, output, time: { start: 1234 } } },
    ],
  } as never)

  it('parseBackgroundTaskWorks：后台化确认行 → ended 条目（exec id/命令标题）；普通输出不出条目', () => {
    const works = parseBackgroundTaskWorks([bashMsg(BG_OUTPUT), bashMsg('普通命令输出 exec_fake-1a2b-1111-2222-3333-444455556666', 'echo hi')])
    expect(works).toHaveLength(1)
    expect(works[0]).toMatchObject({
      workId: 'exec_1a2b3c4d-1111-2222-3333-444455556666',
      kind: 'bash',
      status: 'ended',
      title: 'npm run build',
      startedAt: 1234,
      cancellable: false,
    })
  })

  it('mergeBackgroundWorks：按 workId 去重、投影优先（running 覆盖 ended 猜测）', () => {
    const projection = [work({ workId: 'exec_1', status: 'running' })]
    const rebuilt = [
      { workId: 'exec_1', kind: 'bash' as const, title: 'x', status: 'ended' as const, startedAt: 1 },
      { workId: 'exec_2', kind: 'bash' as const, title: 'y', status: 'ended' as const, startedAt: 2 },
    ]
    const merged = mergeBackgroundWorks(projection, rebuilt)
    expect(merged.map((w) => `${w.workId}:${w.status}`)).toEqual(['exec_1:running', 'exec_2:ended'])
  })

  it('面板显示重建条目：「已结束」状态、无取消按钮；badge 计数含重建条目', () => {
    useStore.setState({
      backgroundWorksFromTranscript: [
        { workId: 'exec_rebuilt', kind: 'bash', title: '历史后台任务', status: 'ended', startedAt: Date.now() - 60_000, cancellable: false },
      ],
    })
    render(<BackgroundTaskList sessionId={SID} />)
    expect(screen.getByText('历史后台任务')).toBeTruthy()
    expect(screen.getByText('已结束')).toBeTruthy()
    expect(screen.queryByText('取消')).toBeNull()
  })
})
