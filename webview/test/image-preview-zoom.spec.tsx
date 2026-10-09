/**
 * 图片预览缩放测试（2026-09-16；2026-09-30 随光栅化修复适配）
 *
 * 行为锁定：
 *   - 滚轮上/下以光标为锚放大/缩小，倍率夹取 1x~8x
 *   - 缩放落在布局尺寸上（zoom 盒 width/height = fit×scale，浏览器按新布局
 *     重新光栅化源图），transform 只承担平移——scale 若走 transform 只会
 *     拉伸按 fit 尺寸光栅化的纹理，窄窗口放大必糊
 *   - +/-/0 键缩放与复位；工具条百分比随动、点击百分比复位
 *   - 放大后拖拽平移（夹取在内），transform 带平移量
 *   - 拖拽后短窗口内遮罩点击不关闭（防松手误关）；未拖拽时遮罩点击关闭
 *   - 多图左右切换后缩放复位
 */
// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, cleanup } from '@testing-library/react'

vi.mock('@/ipc/bridge', () => ({
  sendToJava: () => {},
}))

import '@/i18n/config'
import { ImagePreview } from '@/components/ImagePreview'

const SRC = 'data:image/png;base64,AAA'

// jsdom 无布局：mock 图片盒为 800x600（位于 100,50），缩放/平移的锚点数学才有输入；
// mock natural 400x300（小于 jsdom 视口 1024x768 的 90%/80% 约束 → fit 基准=自然尺寸）
const IMG_RECT = { left: 100, top: 50, width: 800, height: 600, right: 900, bottom: 650, x: 100, y: 50 }

beforeEach(() => {
  vi.spyOn(HTMLImageElement.prototype, 'getBoundingClientRect').mockImplementation(
    () => IMG_RECT as DOMRect,
  )
  Object.defineProperty(HTMLImageElement.prototype, 'naturalWidth', {
    configurable: true,
    get: () => 400,
  })
  Object.defineProperty(HTMLImageElement.prototype, 'naturalHeight', {
    configurable: true,
    get: () => 300,
  })
  // jsdom 不实现指针捕获：补空桩让 pointer 拖拽路径可测
  const proto = HTMLImageElement.prototype as unknown as Record<string, unknown>
  if (typeof proto.setPointerCapture !== 'function') proto.setPointerCapture = () => {}
  if (typeof proto.releasePointerCapture !== 'function') proto.releasePointerCapture = () => {}
})

afterEach(cleanup)

function overlay(): HTMLElement {
  return document.querySelector('.image-preview-overlay') as HTMLElement
}
function overlayImg(): HTMLImageElement {
  return document.querySelector('.image-preview-content img') as HTMLImageElement
}
function zoomBox(): HTMLElement {
  return document.querySelector('.image-preview-zoom') as HTMLElement
}
function transform(): string {
  return zoomBox().style.transform
}
/** 触发 load 让 fit 基准就绪（jsdom 不自动 load） */
function loadImg() {
  fireEvent.load(overlayImg())
}

describe('ImagePreview 滚轮缩放', () => {
  it('滚轮上放大到 120%，缩放落在布局宽高（400x300 → 480x360）', () => {
    render(<ImagePreview src={SRC} onClose={() => {}} />)
    loadImg()
    fireEvent.wheel(overlay(), { deltaY: -100, clientX: 500, clientY: 350 })
    expect(zoomBox().style.width).toBe('480px')
    expect(zoomBox().style.height).toBe('360px')
    expect(transform()).not.toContain('scale')
    expect(screen.getByText('120%')).toBeTruthy()
  })

  it('滚轮上=放大、滚轮下=缩小，1x 下限夹取不小于 100%', () => {
    render(<ImagePreview src={SRC} onClose={() => {}} />)
    loadImg()
    fireEvent.wheel(overlay(), { deltaY: -100, clientX: 500, clientY: 350 })
    fireEvent.wheel(overlay(), { deltaY: -100, clientX: 500, clientY: 350 })
    expect(zoomBox().style.width).toBe('576px')
    fireEvent.wheel(overlay(), { deltaY: 100, clientX: 500, clientY: 350 })
    fireEvent.wheel(overlay(), { deltaY: 100, clientX: 500, clientY: 350 })
    fireEvent.wheel(overlay(), { deltaY: 100, clientX: 500, clientY: 350 })
    expect(zoomBox().style.width).toBe('400px')
    expect(screen.getByText('100%')).toBeTruthy()
  })

  it('滚轮落在工具条上不缩放', () => {
    render(<ImagePreview src={SRC} onClose={() => {}} />)
    loadImg()
    fireEvent.wheel(screen.getByText('100%'), { deltaY: -100, clientX: 500, clientY: 350 })
    expect(zoomBox().style.width).toBe('400px')
  })
})

describe('ImagePreview 键盘与工具条缩放', () => {
  it('+ 键放大、- 键缩小、0 键复位', () => {
    render(<ImagePreview src={SRC} onClose={() => {}} />)
    loadImg()
    fireEvent.keyDown(document, { key: '+' })
    expect(zoomBox().style.width).toBe('480px')
    fireEvent.keyDown(document, { key: '-' })
    expect(zoomBox().style.width).toBe('400px')
    fireEvent.keyDown(document, { key: '+' })
    fireEvent.keyDown(document, { key: '0' })
    expect(zoomBox().style.width).toBe('400px')
  })

  it('工具条 +/− 按钮缩放；点击百分比复位；边界禁用', () => {
    render(<ImagePreview src={SRC} onClose={() => {}} />)
    loadImg()
    const btns = document.querySelectorAll('.image-preview-zoom-btn')
    const zoomOut = btns[0] as HTMLButtonElement
    const zoomIn = btns[1] as HTMLButtonElement
    expect(zoomOut.disabled).toBe(true)
    fireEvent.click(zoomIn)
    expect(zoomBox().style.width).toBe('480px')
    expect(zoomOut.disabled).toBe(false)
    fireEvent.click(zoomOut)
    expect(zoomBox().style.width).toBe('400px')
    fireEvent.click(screen.getByText('100%'))
    expect(zoomBox().style.width).toBe('400px')
  })
})

describe('ImagePreview 拖拽平移与误关抑制', () => {
  function zoomIn() {
    fireEvent.wheel(overlay(), { deltaY: -100, clientX: 500, clientY: 350 })
  }

  it('放大后拖拽平移，transform 带平移量；松手后遮罩点击不关闭', () => {
    const onClose = vi.fn()
    render(<ImagePreview src={SRC} onClose={onClose} />)
    loadImg()
    zoomIn()
    const img = overlayImg()
    fireEvent.pointerDown(img, { button: 0, pointerId: 1, clientX: 500, clientY: 350 })
    fireEvent.pointerMove(img, { pointerId: 1, clientX: 380, clientY: 310 })
    fireEvent.pointerUp(img, { pointerId: 1 })
    expect(transform()).toContain('translate(')
    expect(transform()).not.toContain('scale')
    // 拖拽松手后的 click（落在遮罩上）不当关闭
    fireEvent.click(overlay())
    expect(onClose).not.toHaveBeenCalled()
  })

  it('未缩放时拖拽不产生平移；普通遮罩点击仍关闭', () => {
    const onClose = vi.fn()
    render(<ImagePreview src={SRC} onClose={onClose} />)
    loadImg()
    const img = overlayImg()
    fireEvent.pointerDown(img, { button: 0, pointerId: 1, clientX: 500, clientY: 350 })
    fireEvent.pointerMove(img, { pointerId: 1, clientX: 380, clientY: 310 })
    fireEvent.pointerUp(img, { pointerId: 1 })
    expect(transform()).toBe('translate(0px, 0px)')
    expect(zoomBox().style.width).toBe('400px')
    fireEvent.click(overlay())
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})

describe('ImagePreview 切图复位', () => {
  it('放大后左右切换，缩放复位到 100%', () => {
    render(
      <ImagePreview
        images={[
          { src: 'data:image/png;base64,AAA' },
          { src: 'data:image/png;base64,BBB' },
        ]}
        initialIndex={0}
        onClose={() => {}}
      />,
    )
    loadImg()
    fireEvent.wheel(overlay(), { deltaY: -100, clientX: 500, clientY: 350 })
    expect(zoomBox().style.width).toBe('480px')
    fireEvent.click(document.querySelector('.image-preview-nav--next')!)
    expect(zoomBox().style.width).toBe('400px')
    expect(screen.getByText('100%')).toBeTruthy()
  })
})
