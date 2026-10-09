/**
 * AI 提交信息附加要求（行为设置页 → AI Commit 按钮，C1）
 *
 * 存储：persist kv 通道（key=zcode.commit.prompt），与 ZCodeCommitPromptConfig
 * （Kotlin）同源即时读取，无消息往返。空串=未配置（只用内置规约+仓库风格参照）。
 */
import { getPersisted, setPersisted } from './persist'

const KEY = 'zcode.commit.prompt'

export function readCommitPromptConfig(): string {
  return (getPersisted(KEY) ?? '').trim()
}

export function writeCommitPromptConfig(text: string): void {
  setPersisted(KEY, text)
}
