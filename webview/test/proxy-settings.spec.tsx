/**
 * 网络代理设置（issue #12）交互回归：
 * - 进入环境子页签拉取 getProxyConfig；快照同步进三输入框
 * - 保存发 setProxyConfig 带三字段（trim）；快照更新不覆盖用户编辑中的输入
 * - restartPending 时出现「重启生效」提示与重启按钮，点击发 restartAppServer
 */
// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react'

const sent: { op: string; [k: string]: unknown }[] = []
vi.mock('@/ipc/bridge', () => ({
  sendToJava: (req: { op: string }) => {
    sent.push(req)
  },
  isInJcef: () => false,
}))

import '@/i18n/config'
import { BasicSettingsView } from '@/components/BasicSettingsView'
import { useStore } from '@/store/useStore'

// BasicSettingsView 挂载链（useTheme/appearance）读 localStorage，jsdom 无实现须垫
Object.defineProperty(window, 'localStorage', {
  value: {
    getItem: () => null, setItem: () => {}, removeItem: () => {},
    clear: () => {}, get length() { return 0 }, key: () => null,
  },
  configurable: true,
  writable: true,
})

/** 切到环境子页签（代理分区所在地） */
function openEnvTab() {
  const envTab = document.querySelector('[role="tab"][aria-selected="false"]')
  // 两个子页签中未选中的即环境（默认外观）；文案匹配兜底 role 查询不稳定时
  const tabs = Array.from(document.querySelectorAll('[role="tab"]'))
  const target = tabs.find((t) => /环境|Environment|環境|환경/.test(t.textContent ?? '')) ?? envTab
  fireEvent.click(target!)
}

const INPUTS = () =>
  Array.from(document.querySelectorAll('.basic-settings__path-input')) as HTMLInputElement[]

describe('网络代理设置', () => {
  beforeEach(() => {
    sent.length = 0
    useStore.setState({ proxyConfig: null, proxySaving: false })
  })
  afterEach(cleanup)

  it('进入环境子页签即拉取 getProxyConfig', () => {
    render(<BasicSettingsView />)
    openEnvTab()
    expect(sent.some((m) => m.op === 'getProxyConfig')).toBe(true)
  })

  it('快照三字段同步进输入框', async () => {
    render(<BasicSettingsView />)
    openEnvTab()
    useStore.setState({
      proxyConfig: { httpProxy: 'http://127.0.0.1:7890', noProxy: 'localhost', caCertPath: '', restartPending: false },
    })
    await waitFor(() => {
      const inputs = INPUTS()
      expect(inputs.length).toBeGreaterThanOrEqual(5) // node/cli 两行 + 代理三行
      const proxyInputs = inputs.slice(2, 5).map((i) => i.value)
      expect(proxyInputs).toEqual(['http://127.0.0.1:7890', 'localhost', ''])
    })
  })

  it('保存发 setProxyConfig 且字段 trim', () => {
    render(<BasicSettingsView />)
    openEnvTab()
    useStore.setState({
      proxyConfig: { httpProxy: '', noProxy: '', caCertPath: '', restartPending: false },
    })
    const inputs = INPUTS()
    fireEvent.change(inputs[2], { target: { value: '  http://127.0.0.1:7890  ' } })
    fireEvent.change(inputs[3], { target: { value: ' localhost,127.0.0.1 ' } })
    // 保存按钮在代理分区第三行右侧
    const saveBtn = inputs[4].parentElement!.querySelector('button')!
    fireEvent.click(saveBtn)
    const req = sent.find((m) => m.op === 'setProxyConfig')
    expect(req).toBeTruthy()
    expect(req!.httpProxy).toBe('http://127.0.0.1:7890')
    expect(req!.noProxy).toBe('localhost,127.0.0.1')
    expect(req!.caCertPath).toBe('')
  })

  it('restartPending 时显示重启按钮，点击发 restartAppServer', async () => {
    render(<BasicSettingsView />)
    openEnvTab()
    useStore.setState({
      proxyConfig: { httpProxy: 'http://x:1', noProxy: '', caCertPath: '', restartPending: true },
    })
    const restartBtn = await waitFor(() => {
      const b = Array.from(document.querySelectorAll('button')).find((x) =>
        /立即重启|Restart now|今すぐ再起動|지금 재시작/.test(x.textContent ?? '')
      )
      expect(b).toBeTruthy()
      return b!
    })
    fireEvent.click(restartBtn)
    expect(sent.some((m) => m.op === 'restartAppServer')).toBe(true)
  })

  it('未配置代理时不显示已配置徽标，restartPending=false 不显示重启按钮', async () => {
    render(<BasicSettingsView />)
    openEnvTab()
    useStore.setState({
      proxyConfig: { httpProxy: '', noProxy: '', caCertPath: '', restartPending: false },
    })
    await waitFor(() => {
      expect(document.body.textContent).not.toContain('已配置')
    })
    const restartBtn = Array.from(document.querySelectorAll('button')).find((b) =>
      /立即重启|Restart now|今すぐ再起動|지금 재시작/.test(b.textContent ?? '')
    )
    expect(restartBtn).toBeFalsy()
  })

  it('默认加载（restartPending=false）不出现「已保存待重启」提示——首版实踩回归', async () => {
    render(<BasicSettingsView />)
    openEnvTab()
    useStore.setState({
      proxyConfig: { httpProxy: 'http://127.0.0.1:7890', noProxy: '', caCertPath: '', restartPending: false },
    })
    await waitFor(() => {
      const inputs = INPUTS()
      expect(inputs[2].value).toBe('http://127.0.0.1:7890')
    })
    expect(document.body.textContent).not.toContain('已保存')
    expect(sent.some((m) => m.op === 'restartAppServer')).toBe(false)
  })
})
