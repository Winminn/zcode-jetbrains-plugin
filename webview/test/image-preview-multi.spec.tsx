/**
 * 多图左右切换预览测试（2026-09-12）
 *
 * 行为锁定：
 *   - ImagePreview 传 images + initialIndex：序号 N/M、左右按钮、←/→ 键导航、
 *     边界禁用不循环（首张 prev disabled、末张 next disabled）
 *   - 单图形态（src/title）无切换 UI，向后兼容
 *   - UserBubble 多图消息：点击第 i 张从第 i 张打开，overlay 内整组可切换
 *   - Esc / 点遮罩关闭
 */
// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent, cleanup } from '@testing-library/react'

vi.mock('@/ipc/bridge', () => ({
  sendToJava: () => {},
}))

import '@/i18n/config'
import { ImagePreview } from '@/components/ImagePreview'
import { MessageBubble } from '@/components/MessageBubble'
import type { ZCodeMessage, MessagePart } from '@/types/messages'

const IMGS = [
  { src: 'data:image/png;base64,AAA', title: 'one.png' },
  { src: 'data:image/png;base64,BBB', title: 'two.png' },
  { src: 'data:image/png;base64,CCC', title: 'three.png' },
]

function overlayImg(): HTMLImageElement {
  return document.querySelector('.image-preview-content img') as HTMLImageElement
}
function nav(dir: 'prev' | 'next'): HTMLButtonElement {
  return document.querySelector(`.image-preview-nav--${dir}`) as HTMLButtonElement
}
function counter(): HTMLElement | null {
  return document.querySelector('.image-preview-counter')
}

afterEach(cleanup)

describe('ImagePreview 多图切换', () => {
  it('初始落在 initialIndex：显示第 2 张 + 序号 2 / 3', () => {
    render(<ImagePreview images={IMGS} initialIndex={1} onClose={() => {}} />)
    expect(overlayImg().getAttribute('src')).toBe(IMGS[1].src)
    expect(counter()!.textContent).toBe('2 / 3')
  })

  it('next/prev 按钮切换 src 与序号；末张 next 禁用', () => {
    render(<ImagePreview images={IMGS} initialIndex={0} onClose={() => {}} />)
    expect(nav('prev').disabled).toBe(true)
    fireEvent.click(nav('next'))
    expect(overlayImg().getAttribute('src')).toBe(IMGS[1].src)
    expect(counter()!.textContent).toBe('2 / 3')
    fireEvent.click(nav('next'))
    expect(overlayImg().getAttribute('src')).toBe(IMGS[2].src)
    expect(nav('next').disabled).toBe(true)
    fireEvent.click(nav('prev'))
    expect(counter()!.textContent).toBe('2 / 3')
  })

  it('←/→ 键导航，Esc 关闭', () => {
    const onClose = vi.fn()
    render(<ImagePreview images={IMGS} initialIndex={0} onClose={onClose} />)
    fireEvent.keyDown(document, { key: 'ArrowRight' })
    expect(overlayImg().getAttribute('src')).toBe(IMGS[1].src)
    fireEvent.keyDown(document, { key: 'ArrowLeft' })
    expect(overlayImg().getAttribute('src')).toBe(IMGS[0].src)
    // 首张再按 ← 不越界
    fireEvent.keyDown(document, { key: 'ArrowLeft' })
    expect(overlayImg().getAttribute('src')).toBe(IMGS[0].src)
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('点击遮罩关闭、点图片不关闭', () => {
    const onClose = vi.fn()
    render(<ImagePreview images={IMGS} initialIndex={0} onClose={onClose} />)
    fireEvent.click(overlayImg())
    expect(onClose).not.toHaveBeenCalled()
    fireEvent.click(document.querySelector('.image-preview-overlay')!)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('单图形态无切换 UI（src/title 向后兼容）', () => {
    render(<ImagePreview src={IMGS[0].src} title={IMGS[0].title} onClose={() => {}} />)
    expect(document.querySelector('.image-preview-nav')).toBeNull()
    expect(counter()).toBeNull()
    expect(document.querySelector('.image-preview-title')!.textContent).toBe('one.png')
  })

  it('切换按钮点击不冒泡到遮罩（不触发关闭）', () => {
    const onClose = vi.fn()
    render(<ImagePreview images={IMGS} initialIndex={0} onClose={onClose} />)
    fireEvent.click(nav('next'))
    expect(onClose).not.toHaveBeenCalled()
  })
})

describe('UserBubble 多图消息预览', () => {
  function imgMsg(withSecond: boolean): ZCodeMessage {
    const parts: MessagePart[] = [{ type: 'image', dataUrl: IMGS[0].src }]
    if (withSecond) parts.push({ type: 'image', dataUrl: IMGS[1].src })
    parts.push({ type: 'text', text: '看这两张图' })
    return {
      info: { id: 'm_u1', sessionID: 's1', role: 'user', time: { created: 1787283860314 } },
      parts,
    }
  }

  it('多图消息点击第 2 张：预览从第 2 张打开且可切换回第 1 张', () => {
    render(<MessageBubble message={imgMsg(true)} />)
    const thumbs = document.querySelectorAll('.msg__images .msg__image')
    expect(thumbs.length).toBe(2)
    fireEvent.click(thumbs[1])
    expect(overlayImg().getAttribute('src')).toBe(IMGS[1].src)
    expect(counter()!.textContent).toBe('2 / 2')
    fireEvent.click(nav('prev'))
    expect(overlayImg().getAttribute('src')).toBe(IMGS[0].src)
  })

  it('单图消息点击打开无切换 UI', () => {
    render(<MessageBubble message={imgMsg(false)} />)
    fireEvent.click(document.querySelector('.msg__images .msg__image')!)
    expect(overlayImg().getAttribute('src')).toBe(IMGS[0].src)
    expect(document.querySelector('.image-preview-nav')).toBeNull()
  })
})
