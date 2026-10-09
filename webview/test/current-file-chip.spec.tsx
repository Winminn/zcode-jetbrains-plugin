/**
 * CurrentFileChip 渲染测试（阶段 A 基础 + 阶段 B 适配 prop-driven）
 *
 * 阶段 B 行为变更：enabled 状态从组件自管 useState + localStorage 改为 prop-driven
 * （InputBox 持有 single source of truth，组件 0 本地 state / 0 LS 调用）。本文件
 * 测试因此改为显式传 enabled + onEnabledChange。
 * 发完即关（2026-10-08）：localStorage 持久化整体移除（InputBox 也不再写），
 * 勾选只管下一条消息；发送链路行为断言见 current-file-send.spec.tsx。
 *
 * 视觉契约（阶段 A，2026-08-27 第三轮反馈）：
 *   - 永远渲染（开关 ON+ref=null 不再隐藏整行）
 *   - 整块 button 可点切换勾选（不再用 checkbox）
 *   - 左侧 FileIcon（始终展示）
 *   - 标签：
 *       未勾选 / 勾选+ref=null → "文件上下文"（i18n.currentFile.label）
 *       勾选+ref=@path         → chip 只显示 basename
 *       勾选+ref=@path#L10     → chip 显示 basename + #L10 后缀
 *       勾选+ref=@path#L131-133 → chip 显示 basename + #L131-133 后缀
 *   - 悬浮 tooltip：勾选+ref 时显示文件路径；否则显示功能说明
 */

// @vitest-environment jsdom
import { describe, it, expect, afterEach, vi } from 'vitest'
import { render, screen, cleanup, fireEvent } from '@testing-library/react'

import '@/i18n/config'
import { CurrentFileChip } from '@/components/CurrentFileChip'

// 本环境 jsdom 的 localStorage 是无 getItem/clear 的普通对象（current-file-send.spec
// 同款 mock）：「组件不直接写 localStorage」断言需要可读的 storage，整体换成 Map 实现
const storage = new Map<string, string>()
Object.defineProperty(window, 'localStorage', {
  configurable: true,
  writable: true,
  value: {
    getItem: (k: string) => storage.get(k) ?? null,
    setItem: (k: string, v: string) => { storage.set(k, v) },
    removeItem: (k: string) => { storage.delete(k) },
    key: (i: number) => Array.from(storage.keys())[i] ?? null,
    get length() { return storage.size },
    clear: () => storage.clear(),
  },
})

afterEach(() => {
  cleanup()
  storage.clear()
})

describe('CurrentFileChip（阶段 A 视觉 + 阶段 B prop-driven）', () => {
  it('默认未勾选：整块 button + 文件图标 + 文字标签 "文件上下文"（不论 ref 是否有值）', () => {
    render(<CurrentFileChip ref={null} enabled={false} onEnabledChange={() => {}} />)
    const btn = screen.getByTestId('current-file-chip')
    expect(btn.tagName).toBe('BUTTON')
    expect(btn.getAttribute('aria-pressed')).toBe('false')
    expect(screen.getByText('文件上下文')).toBeTruthy()
    // 左侧有 FileIcon（.current-file-chip__icon）
    expect(btn.querySelector('.current-file-chip__icon')).toBeTruthy()
  })

  it('有 ref 但未勾选：仍展示文字标签 "文件上下文"（不直接展示 chip）', () => {
    render(<CurrentFileChip ref="@E:/projects/ChangeType.vue" enabled={false} onEnabledChange={() => {}} />)
    expect(screen.getByText('文件上下文')).toBeTruthy()
    expect(screen.queryByText('ChangeType.vue')).toBeNull()
  })

  it('勾选 + ref=@path：chip 只显示 basename', () => {
    render(<CurrentFileChip ref="@E:/projects/ChangeType.vue" enabled={true} onEnabledChange={() => {}} />)
    const btn = screen.getByTestId('current-file-chip')
    expect(btn.getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByText('ChangeType.vue')).toBeTruthy()
    expect(screen.queryByText(/^#L\d+/)).toBeNull()
    expect(screen.queryByText('文件上下文')).toBeNull()
  })

  it('勾选 + ref=@path#L10：chip 显示 basename + #L10 后缀（连续拼接，不用 : 分隔）', () => {
    render(<CurrentFileChip ref="@E:/projects/ChangeType.vue#L10" enabled={true} onEnabledChange={() => {}} />)
    expect(screen.getByText('ChangeType.vue')).toBeTruthy()
    expect(screen.getByText('#L10')).toBeTruthy()
  })

  it('勾选 + ref=@path#L131-133：chip 显示 basename + #L131-133 后缀（连续拼接）', () => {
    render(<CurrentFileChip ref="@E:/projects/ChangeType.vue#L131-133" enabled={true} onEnabledChange={() => {}} />)
    expect(screen.getByText('ChangeType.vue')).toBeTruthy()
    expect(screen.getByText('#L131-133')).toBeTruthy()
  })

  it('长文件名（>28 字符）中段省略：保开头与扩展名，#L 后缀不受影响', () => {
    const longName = 'SomeVeryLongComponentNameThatKeepsGoing.tsx' // 43 字符
    render(
      <CurrentFileChip ref={`@E:/projects/${longName}#L120-135`} enabled={true} onEnabledChange={() => {}} />,
    )
    const name = screen.getByText(/…/) as HTMLElement
    expect(name.textContent!.length).toBe(28)
    expect(name.textContent!.startsWith('SomeVeryLong')).toBe(true)
    expect(name.textContent!.endsWith('.tsx')).toBe(true)
    expect(screen.getByText('#L120-135')).toBeTruthy()
  })

  it('28 字符以内的文件名不缩略（原样显示）', () => {
    render(<CurrentFileChip ref="@E:/projects/exactly-28-chars-long-ok.vue" enabled={true} onEnabledChange={() => {}} />)
    expect(screen.getByText('exactly-28-chars-long-ok.vue')).toBeTruthy()
  })

  it('勾选 + ref=null：回退到文字标签 "文件上下文"（永远显示，不隐藏）', () => {
    render(<CurrentFileChip ref={null} enabled={true} onEnabledChange={() => {}} />)
    const btn = screen.getByTestId('current-file-chip')
    expect(btn).toBeTruthy()
    expect(btn.getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByText('文件上下文')).toBeTruthy()
  })

  it('点 button：调 onEnabledChange(true) → onEnabledChange(false)（组件不再写 localStorage）', () => {
    const onChange = vi.fn()
    render(<CurrentFileChip ref="@E:/projects/ChangeType.vue" enabled={false} onEnabledChange={onChange} />)
    const btn = screen.getByTestId('current-file-chip')
    expect(btn.getAttribute('aria-pressed')).toBe('false')
    fireEvent.click(btn)
    expect(onChange).toHaveBeenLastCalledWith(true)
    // 父组件没回写 enabled：组件仍显示未勾选（prop 没变）
    expect(btn.getAttribute('aria-pressed')).toBe('false')
    // 组件不直接写 localStorage（持久化已随发完即关整体移除，InputBox 也不写）
    expect(localStorage.getItem('zcode.currentFile.enabled')).toBeNull()
  })

  it('勾选切换：父组件回写 enabled 后，未勾选态显示文字，勾选态显示 chip', () => {
    // 阶段 B：父组件持 enabled state 并回写，本测试模拟父组件
    const Stateful = () => {
      const [enabled, setEnabled] = (require('react') as typeof import('react')).useState(false)
      return (
        <CurrentFileChip
          ref="@E:/projects/ChangeType.vue"
          enabled={enabled}
          onEnabledChange={setEnabled}
        />
      )
    }
    render(<Stateful />)
    const btn = screen.getByTestId('current-file-chip')
    expect(screen.getByText('文件上下文')).toBeTruthy()
    expect(screen.queryByText('ChangeType.vue')).toBeNull()
    fireEvent.click(btn)
    expect(screen.getByText('ChangeType.vue')).toBeTruthy()
    expect(screen.queryByText('文件上下文')).toBeNull()
    fireEvent.click(btn)
    expect(screen.getByText('文件上下文')).toBeTruthy()
  })

  it('悬浮 tooltip：未勾选时显示功能说明；勾选+ref 时显示文件路径（无"当前文件："前缀）', () => {
    // 未勾选：ref 不论有无都显示说明文档，不显示路径
    const { unmount } = render(
      <CurrentFileChip ref="@E:/projects/ChangeType.vue" enabled={false} onEnabledChange={() => {}} />,
    )
    const btn1 = screen.getByTestId('current-file-chip')
    const tipOff = btn1.getAttribute('title') || btn1.getAttribute('data-tip') || ''
    expect(tipOff).toContain('勾选')
    expect(tipOff).not.toContain('当前文件')
    unmount()

    // 勾选 + ref 非空：直接显示路径文本（去 @ 前缀）
    render(
      <CurrentFileChip ref="@E:/projects/ChangeType.vue#L10" enabled={true} onEnabledChange={() => {}} />,
    )
    const btn2 = screen.getByTestId('current-file-chip')
    const tipOn = btn2.getAttribute('title') || btn2.getAttribute('data-tip') || ''
    expect(tipOn).toContain('ChangeType.vue')
    expect(tipOn).not.toContain('当前文件')
  })

  it('悬浮 tooltip：组件不再渲染自定义 __tip（0.3.8 全局 [data-tip] 会与之叠成双 tooltip）', () => {
    render(
      <CurrentFileChip ref="@E:/projects/ChangeType.vue" enabled={true} onEnabledChange={() => {}} />,
    )
    expect(screen.queryByTestId('current-file-chip-tip')).toBeNull()
  })

  it('悬浮 tooltip：长路径超 50 字符时中间省略（全局 nowrap 单行，调用侧截断约定）', () => {
    const longRef = '@E:/myIdeaProject/some-very-long-project-name/src/deep/dir/gradlew.bat#L9-13'
    render(<CurrentFileChip ref={longRef} enabled={true} onEnabledChange={() => {}} />)
    const tip = screen.getByTestId('current-file-chip').getAttribute('data-tip') || ''
    // truncateMiddle(50)：恒 50 字符，保头尾（盘符定位 + 文件名/行号完整），中间 …
    expect(tip.length).toBe(50)
    expect(tip).toContain('…')
    expect(tip.startsWith('E:/myIdeaProject')).toBe(true)
    expect(tip.endsWith('.bat#L9-13')).toBe(true)
  })

  it('悬浮 tooltip：工作区相对路径仍超 50 字符时同样中间省略', () => {
    render(
      <CurrentFileChip
        ref="@E:/myIdeaProject/zcode-jetbrains-plugin/intellij-plugin/src/main/kotlin/com/zcode/ideaplugin/ui/ZCodeToolWindowPanel.kt"
        enabled={true}
        onEnabledChange={() => {}}
        workspace="E:/myIdeaProject/zcode-jetbrains-plugin"
      />,
    )
    const tip = screen.getByTestId('current-file-chip').getAttribute('data-tip') || ''
    // 先去工作区前缀（82 字符相对路径）再截断：头尾可见，文件名不丢
    expect(tip.length).toBe(50)
    expect(tip).toContain('…')
    expect(tip.startsWith('intellij-plugin')).toBe(true)
    expect(tip.endsWith('ZCodeToolWindowPanel.kt')).toBe(true)
  })

  it('悬浮 tooltip：超长文件名同样按 50 字符中间省略', () => {
    const hugeName = `@D:/p/${'a'.repeat(80)}.java`
    render(<CurrentFileChip ref={hugeName} enabled={true} onEnabledChange={() => {}} />)
    const tip = screen.getByTestId('current-file-chip').getAttribute('data-tip') || ''
    expect(tip.length).toBe(50)
    expect(tip).toContain('…')
    expect(tip.endsWith('.java')).toBe(true)
  })

  it('悬浮 tooltip：路径未超 50 字符时原样展示（不省略）', () => {
    render(
      <CurrentFileChip ref="@E:/projects/ChangeType.vue#L10" enabled={true} onEnabledChange={() => {}} />,
    )
    const btn = screen.getByTestId('current-file-chip')
    expect(btn.getAttribute('data-tip')).toBe('E:/projects/ChangeType.vue#L10')
  })

  it('悬浮 tooltip：工作区内文件显示相对路径（开头工作区前缀是噪音，去掉后不再触发省略）', () => {
    render(
      <CurrentFileChip
        ref="@E:/myIdeaProject/zcode-jetbrains-plugin/CHANGELOG.md#L25-27"
        enabled={true}
        onEnabledChange={() => {}}
        workspace={'E:\\myIdeaProject\\zcode-jetbrains-plugin'}
      />,
    )
    const tip = screen.getByTestId('current-file-chip').getAttribute('data-tip') || ''
    expect(tip).toBe('CHANGELOG.md#L25-27')
  })

  it('悬浮 tooltip：workspace 分隔符/大小写不一致也能匹配（Windows 路径不区分大小写；尾部斜杠容忍）', () => {
    render(
      <CurrentFileChip
        ref="@e:/MYIDEAPROJECT/zcode-jetbrains-plugin/src/App.tsx"
        enabled={true}
        onEnabledChange={() => {}}
        workspace="E:/myIdeaProject/zcode-jetbrains-plugin/"
      />,
    )
    const tip = screen.getByTestId('current-file-chip').getAttribute('data-tip') || ''
    expect(tip).toBe('src/App.tsx')
  })

  it('悬浮 tooltip：工作区外文件保留绝对路径（不去前缀）', () => {
    render(
      <CurrentFileChip
        ref="@D:/other-project/CHANGELOG.md"
        enabled={true}
        onEnabledChange={() => {}}
        workspace="E:/myIdeaProject/zcode-jetbrains-plugin"
      />,
    )
    const tip = screen.getByTestId('current-file-chip').getAttribute('data-tip') || ''
    expect(tip).toBe('D:/other-project/CHANGELOG.md')
  })
})
