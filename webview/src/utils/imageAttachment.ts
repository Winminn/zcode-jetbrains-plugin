/**
 * 粘贴图片附件处理（参考 cc-gui usePasteAndDrop + AttachmentList）
 *
 * 剪贴板图片 → FileReader 读 base64 → canvas 重编码控制体积：
 * - 最长边 > MAX_EDGE 等比缩到 MAX_EDGE（服务端还会再压到 2000px，这里提前压，
 *   避免 JBCefJSQuery 桥传超大字符串拖慢 IDE；截图场景 1280px 足够）
 * - base64 > MAX_BASE64 时转 JPEG（PNG/GIF 透明合成白底；原 JPEG 保持）
 * - SVG 是文本形态天然小，跳过 canvas 直接透传
 *
 * 决策逻辑抽成纯函数（calcScaledSize / needsReencode / pickReencodeFormat /
 * decodeBase64Size）便于单测，canvas 操作集中在 readImageFile。
 */

export const MAX_EDGE = 1280
export const MAX_BASE64 = 900 * 1024 // 约 675KB 原始字节，桥与协议双保险

export interface ImageAttachmentResult {
  /** 形如 pasted-image-<ts>.png */
  filename: string
  /** MIME，如 image/png / image/jpeg */
  mediaType: string
  /** 裸 base64（不含 data URL 前缀）*/
  base64: string
  /** base64 解码后的真实字节数 */
  sizeBytes: number
  width: number
  height: number
}

export interface Size2D {
  width: number
  height: number
}

/** 等比缩放：最长边超 maxEdge 缩到 maxEdge，未超原样返回（0 尺寸防御）*/
export function calcScaledSize(width: number, height: number, maxEdge = MAX_EDGE): Size2D {
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    return { width: Math.max(1, width | 0), height: Math.max(1, height | 0) }
  }
  const longest = Math.max(width, height)
  if (longest <= maxEdge) return { width, height }
  const ratio = maxEdge / longest
  return { width: Math.max(1, Math.round(width * ratio)), height: Math.max(1, Math.round(height * ratio)) }
}

/** 是否需要重编码：超尺寸 或 超体积 */
export function needsReencode(width: number, height: number, base64Length: number): boolean {
  return Math.max(width, height) > MAX_EDGE || base64Length > MAX_BASE64
}

/**
 * 重编码输出格式决策：
 * - svg 不进来（调用方透传）；jpeg 保持 jpeg（超体积时降质量）
 * - png/gif 超体积才转 jpeg（透明合成白底），否则 png 保真
 * - webp 保持 webp（JCEF = Chromium 支持 canvas 输出 webp）
 * - 其余（bmp 等）一律 png
 */
export function pickReencodeFormat(mediaType: string, base64Length: number): 'png' | 'jpeg' | 'webp' {
  if (mediaType === 'image/jpeg' || mediaType === 'image/jpg') return 'jpeg'
  if (mediaType === 'image/webp') return 'webp'
  if (mediaType === 'image/png' || mediaType === 'image/gif') {
    return base64Length > MAX_BASE64 ? 'jpeg' : 'png'
  }
  return 'png'
}

/** base64 解码后的字节数（按 padding 精确计算）*/
export function decodeBase64Size(b64: string): number {
  const pad = b64.endsWith('==') ? 2 : b64.endsWith('=') ? 1 : 0
  return Math.floor((b64.length * 3) / 4) - pad
}

function extOf(mediaType: string): string {
  const ext = mediaType.split('/')[1]?.toLowerCase()
  if (!ext) return 'png'
  if (ext === 'jpeg' || ext === 'jpg') return 'jpg'
  return ext
}

function readAsDataURL(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as string)
    reader.onerror = () => reject(new Error('file read failed'))
    reader.readAsDataURL(file)
  })
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('image decode failed'))
    img.src = src
  })
}

/** canvas 重编码（尺寸缩放 + 可选格式转换）；canvas 不可用时降级返回原始数据 */
function reencode(
  img: HTMLImageElement,
  size: Size2D,
  fmt: 'png' | 'jpeg' | 'webp',
  fallback: { base64: string; mediaType: string },
): { base64: string; mediaType: string } {
  const canvas = document.createElement('canvas')
  canvas.width = size.width
  canvas.height = size.height
  const ctx = canvas.getContext('2d')
  if (!ctx) return fallback
  if (fmt === 'jpeg') {
    // JPEG 无透明通道，透明 PNG/GIF 合成白底
    ctx.fillStyle = '#fff'
    ctx.fillRect(0, 0, size.width, size.height)
  }
  ctx.drawImage(img, 0, 0, size.width, size.height)
  const mime = fmt === 'jpeg' ? 'image/jpeg' : fmt === 'webp' ? 'image/webp' : 'image/png'
  const dataUrl = canvas.toDataURL(mime, fmt === 'jpeg' ? 0.82 : undefined)
  const comma = dataUrl.indexOf(',')
  if (comma < 0) return fallback
  return { base64: dataUrl.slice(comma + 1), mediaType: mime }
}

/**
 * 读入图片文件（剪贴板 getAsFile() / 未来拖拽 files）→ 压缩 → 附件载荷。
 * 失败（解码失败等）抛错，由调用方决定是否提示。
 */
export async function readImageFile(file: File): Promise<ImageAttachmentResult> {
  const raw = await readAsDataURL(file)
  const match = raw.match(/^data:([^;,]+);base64,(.*)$/s)
  if (!match) throw new Error('unsupported data url')
  const mediaType = match[1] || file.type || 'image/png'
  const base64 = match[2]

  // SVG 是文本形态天然小，canvas 渲染也有外部资源风险，直接透传
  if (mediaType === 'image/svg+xml') {
    return {
      filename: `pasted-image-${Date.now()}.svg`,
      mediaType,
      base64,
      sizeBytes: decodeBase64Size(base64),
      width: 0,
      height: 0,
    }
  }

  const img = await loadImage(raw)
  const width = img.naturalWidth
  const height = img.naturalHeight
  const scaled = calcScaledSize(width, height)

  let out: { base64: string; mediaType: string } = { base64, mediaType }
  if (needsReencode(scaled.width, scaled.height, base64.length)) {
    const fmt = pickReencodeFormat(mediaType, base64.length)
    out = reencode(img, scaled, fmt, out)
  }

  return {
    filename: `pasted-image-${Date.now()}.${extOf(out.mediaType)}`,
    mediaType: out.mediaType,
    base64: out.base64,
    sizeBytes: decodeBase64Size(out.base64),
    width: scaled.width,
    height: scaled.height,
  }
}
