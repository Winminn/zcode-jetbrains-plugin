/**
 * Zai 品牌图标（来源 logo/Zai.svg，黑底白 Z 方块）
 *
 * 用途：
 *   - 输入框发送按钮（InputBox）：variant="mark" 只画白色 Z 笔画（适配彩色按钮背景）
 *   - 模型图标 fallback（未知厂商 / ZCode 内置 provider）：variant="block" 完整黑底白 Z
 *   - 其他插件自身涉及的图标位置
 */

interface Props {
  /** 图标尺寸（px） */
  size?: number
  /** 额外 className */
  className?: string
  /**
   * - 'block'（默认）：完整黑底白 Z 方块（独立展示用）
   * - 'mark'：只画 Z 笔画，颜色跟随 currentColor（嵌在彩色按钮里用）
   */
  variant?: 'block' | 'mark'
}

export function ZaiIcon({ size = 16, className, variant = 'block' }: Props) {
  if (variant === 'mark') {
    // 只画 Z 笔画，fill=currentColor，适合放在彩色背景的按钮里
    return (
      <svg
        className={className}
        width={size}
        height={size}
        viewBox="0 0 1024 1024"
        xmlns="http://www.w3.org/2000/svg"
        aria-label="Zai"
        role="img"
      >
        <path
          d="M528.043 242.347l-44.374 63.146c-6.826 9.899-18.432 16.043-30.72 16.043H210.603v-79.53c-0.342 0.34 317.44 0.34 317.44 0.34z m301.397 0L448.512 781.995H194.56l380.928-539.648zM495.957 781.995l44.715-63.488c6.827-9.899 18.432-16.043 30.72-16.043h242.005v79.53h-317.44z"
          fill="currentColor"
        />
      </svg>
    )
  }
  // block：完整黑底白 Z（独立展示）
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 1024 1024"
      xmlns="http://www.w3.org/2000/svg"
      aria-label="Zai"
      role="img"
    >
      <path
        d="M836.608 973.141H187.392c-75.435 0-136.533-61.098-136.533-136.533V187.392c0-75.435 61.098-136.533 136.533-136.533h649.557c75.435 0 136.534 61.098 136.534 136.533v649.557c-0.342 75.094-61.44 136.192-136.875 136.192z"
        fill="currentColor"
      />
      <path
        d="M528.043 242.347l-44.374 63.146c-6.826 9.899-18.432 16.043-30.72 16.043H210.603v-79.53c-0.342 0.34 317.44 0.34 317.44 0.34z m301.397 0L448.512 781.995H194.56l380.928-539.648zM495.957 781.995l44.715-63.488c6.827-9.899 18.432-16.043 30.72-16.043h242.005v79.53h-317.44z"
        fill="#fff"
      />
    </svg>
  )
}
