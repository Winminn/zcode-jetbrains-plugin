import { describe, expect, it } from 'vitest'
import { formatUptime } from '../src/components/ProcessSettingsView'

/** formatUptime 运行时长格式化（进程管理面板行内元信息） */
describe('formatUptime 进程运行时长', () => {
  const now = 1_800_000_000_000

  it('启动时刻缺省/非法显示占位符', () => {
    expect(formatUptime(undefined, now)).toBe('—')
    expect(formatUptime(0, now)).toBe('—')
    expect(formatUptime(now + 5000, now)).toBe('—')
  })

  it('秒级（刚启动）', () => {
    expect(formatUptime(now - 7_000, now)).toBe('7s')
  })

  it('分钟秒混合（"2m 45s" 形态）', () => {
    expect(formatUptime(now - 165_000, now)).toBe('2m 45s')
  })

  it('小时分钟（分钟补零对齐）', () => {
    expect(formatUptime(now - (3 * 3600 + 5 * 60) * 1000, now)).toBe('3h 05m')
  })

  it('天级只到 d+h 粒度', () => {
    expect(formatUptime(now - (2 * 86400 + 4 * 3600 + 33 * 60) * 1000, now)).toBe('2d 4h')
  })
})
