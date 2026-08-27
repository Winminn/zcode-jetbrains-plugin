/**
 * 粘贴图片附件压缩管线纯函数测试（utils/imageAttachment）
 *
 * 覆盖：
 * 1. calcScaledSize：等比缩放（超边缩、未超不变、0/非法尺寸防御）
 * 2. needsReencode：尺寸/体积双阈值边界
 * 3. pickReencodeFormat：格式保持与 JPEG 降级决策
 * 4. decodeBase64Size：padding 精确字节数
 *
 * canvas/FileReader 部分（readImageFile）依赖浏览器实现，走 runIde 手工验收，
 * 这里只测可纯函数化的决策逻辑。
 */

import { describe, it, expect } from 'vitest'
import {
  MAX_EDGE,
  MAX_BASE64,
  calcScaledSize,
  needsReencode,
  pickReencodeFormat,
  decodeBase64Size,
} from '../src/utils/imageAttachment'

describe('calcScaledSize', () => {
  it('超过最长边时等比缩放', () => {
    expect(calcScaledSize(2560, 1440, 1280)).toEqual({ width: 1280, height: 720 })
    expect(calcScaledSize(1440, 2560, 1280)).toEqual({ width: 720, height: 1280 })
    expect(calcScaledSize(4000, 3000, 1280)).toEqual({ width: 1280, height: 960 })
  })

  it('未超最长边时原样返回', () => {
    expect(calcScaledSize(800, 600)).toEqual({ width: 800, height: 600 })
    expect(calcScaledSize(1280, 1280)).toEqual({ width: 1280, height: 1280 })
  })

  it('非法/零尺寸防御：不小于 1', () => {
    expect(calcScaledSize(0, 0).width).toBeGreaterThanOrEqual(1)
    expect(calcScaledSize(Number.NaN, 100).height).toBeGreaterThanOrEqual(1)
    expect(calcScaledSize(-5, -5).width).toBeGreaterThanOrEqual(1)
  })
})

describe('needsReencode', () => {
  it('尺寸超限触发重编码', () => {
    expect(needsReencode(2000, 1000, 100)).toBe(true)
    expect(needsReencode(1281, 100, 100)).toBe(true)
  })

  it('体积超限触发重编码', () => {
    expect(needsReencode(800, 600, MAX_BASE64 + 1)).toBe(true)
    expect(needsReencode(800, 600, MAX_BASE64)).toBe(false)
  })

  it('尺寸与体积都达标时不重编码', () => {
    expect(needsReencode(1280, 720, 100)).toBe(false)
  })
})

describe('pickReencodeFormat', () => {
  it('jpeg 保持 jpeg', () => {
    expect(pickReencodeFormat('image/jpeg', 100)).toBe('jpeg')
    expect(pickReencodeFormat('image/jpg', MAX_BASE64 * 2)).toBe('jpeg')
  })

  it('webp 保持 webp', () => {
    expect(pickReencodeFormat('image/webp', 100)).toBe('webp')
  })

  it('png/gif 体积超限转 jpeg（透明合成白底），未超保持 png', () => {
    expect(pickReencodeFormat('image/png', MAX_BASE64 * 2)).toBe('jpeg')
    expect(pickReencodeFormat('image/gif', MAX_BASE64 * 2)).toBe('jpeg')
    expect(pickReencodeFormat('image/png', 100)).toBe('png')
    expect(pickReencodeFormat('image/gif', 100)).toBe('png')
  })

  it('其他格式一律 png', () => {
    expect(pickReencodeFormat('image/bmp', 100)).toBe('png')
    expect(pickReencodeFormat('image/x-icon', MAX_BASE64 * 2)).toBe('png')
  })
})

describe('decodeBase64Size', () => {
  it('padding 精确计算', () => {
    // "A" -> base64 "QQ==" : 1 字节
    expect(decodeBase64Size('QQ==')).toBe(1)
    // "AB" -> base64 "QUI=" : 2 字节
    expect(decodeBase64Size('QUI=')).toBe(2)
    // "ABC" -> base64 "QUJD" : 3 字节
    expect(decodeBase64Size('QUJD')).toBe(3)
  })

  it('空串为 0', () => {
    expect(decodeBase64Size('')).toBe(0)
  })
})
