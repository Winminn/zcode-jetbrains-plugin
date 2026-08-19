/**
 * ZC GUI 品牌图标（自研斜杠像素图案，非 Z.ai 官方标识，无侵权风险）
 *
 * 用途：
 *   - 输入框发送按钮（InputBox）：variant="mark" 只画 Z 笔画（适配彩色按钮背景）
 *   - 模型图标 fallback（未知厂商 / ZCode 内置 provider）：variant="block" 完整品牌图标
 *   - 欢迎页 logo：variant="block"
 */

interface Props {
  /** 图标尺寸（px） */
  size?: number
  /** 额外 className */
  className?: string
  /**
   * - 'block'（默认）：完整品牌图标（深色渐变底 + 白色斜杠像素图案）
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
        aria-label="ZC GUI"
        role="img"
      >
        <path
          d="M528.043 242.347l-44.374 63.146c-6.826 9.899-18.432 16.043-30.72 16.043H210.603v-79.53c-0.342 0.34 317.44 0.34 317.44 0.34z m301.397 0L448.512 781.995H194.56l380.928-539.648zM495.957 781.995l44.715-63.488c6.827-9.899 18.432-16.043 30.72-16.043h242.005v79.53h-317.44z"
          fill="currentColor"
        />
      </svg>
    )
  }
  // block：完整品牌图标（深色渐变底 + 白色斜杠像素图案）
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 1024 1024"
      xmlns="http://www.w3.org/2000/svg"
      aria-label="ZC GUI"
      role="img"
    >
      <defs>
        <linearGradient id="zcgui-logo-bg" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#1E293B" />
          <stop offset="1" stopColor="#312E81" />
        </linearGradient>
      </defs>
      <path fill="url(#zcgui-logo-bg)" d="M836.608 973.141H187.392c-75.435 0-136.533-61.098-136.533-136.533V187.392c0-75.435 61.098-136.533 136.533-136.533h649.557c75.435 0 136.534 61.098 136.534 136.533v649.557c-0.342 75.094-61.44 136.192-136.875 136.192z" />
      <g fill="#FFFFFF">
        <rect x="244" y="242" width="26" height="36" fillOpacity="1.00" /><rect x="274" y="242" width="26" height="36" fillOpacity="1.00" /><rect x="304" y="242" width="26" height="36" fillOpacity="1.00" /><rect x="334" y="242" width="26" height="36" fillOpacity="1.00" /><rect x="364" y="242" width="26" height="36" fillOpacity="1.00" /><rect x="394" y="242" width="26" height="36" fillOpacity="1.00" /><rect x="424" y="242" width="26" height="36" fillOpacity="1.00" /><rect x="454" y="242" width="26" height="36" fillOpacity="1.00" /><rect x="484" y="242" width="26" height="36" fillOpacity="1.00" /><rect x="514" y="242" width="26" height="36" fillOpacity="1.00" /><rect x="544" y="242" width="26" height="36" fillOpacity="1.00" /><rect x="604" y="242" width="26" height="36" fillOpacity="1.00" /><rect x="634" y="242" width="26" height="36" fillOpacity="1.00" /><rect x="664" y="242" width="26" height="36" fillOpacity="1.00" /><rect x="694" y="242" width="26" height="36" fillOpacity="1.00" /><rect x="724" y="242" width="26" height="36" fillOpacity="1.00" /><rect x="754" y="242" width="26" height="36" fillOpacity="1.00" />
        <rect x="244" y="284" width="26" height="36" fillOpacity="0.96" /><rect x="274" y="284" width="26" height="36" fillOpacity="0.96" /><rect x="304" y="284" width="26" height="36" fillOpacity="0.96" /><rect x="334" y="284" width="26" height="36" fillOpacity="0.96" /><rect x="364" y="284" width="26" height="36" fillOpacity="0.96" /><rect x="394" y="284" width="26" height="36" fillOpacity="0.96" /><rect x="424" y="284" width="26" height="36" fillOpacity="0.96" /><rect x="454" y="284" width="26" height="36" fillOpacity="0.96" /><rect x="484" y="284" width="26" height="36" fillOpacity="0.96" /><rect x="514" y="284" width="26" height="36" fillOpacity="0.96" /><rect x="574" y="284" width="26" height="36" fillOpacity="0.96" /><rect x="604" y="284" width="26" height="36" fillOpacity="0.96" /><rect x="634" y="284" width="26" height="36" fillOpacity="0.96" /><rect x="664" y="284" width="26" height="36" fillOpacity="0.96" /><rect x="694" y="284" width="26" height="36" fillOpacity="0.96" /><rect x="724" y="284" width="26" height="36" fillOpacity="0.96" />
        <rect x="544" y="326" width="26" height="36" fillOpacity="0.92" /><rect x="574" y="326" width="26" height="36" fillOpacity="0.92" /><rect x="604" y="326" width="26" height="36" fillOpacity="0.92" /><rect x="634" y="326" width="26" height="36" fillOpacity="0.92" /><rect x="664" y="326" width="26" height="36" fillOpacity="0.92" /><rect x="694" y="326" width="26" height="36" fillOpacity="0.92" />
        <rect x="514" y="368" width="26" height="36" fillOpacity="0.88" /><rect x="544" y="368" width="26" height="36" fillOpacity="0.88" /><rect x="574" y="368" width="26" height="36" fillOpacity="0.88" /><rect x="604" y="368" width="26" height="36" fillOpacity="0.88" /><rect x="634" y="368" width="26" height="36" fillOpacity="0.88" /><rect x="664" y="368" width="26" height="36" fillOpacity="0.88" />
        <rect x="484" y="410" width="26" height="36" fillOpacity="0.83" /><rect x="514" y="410" width="26" height="36" fillOpacity="0.83" /><rect x="544" y="410" width="26" height="36" fillOpacity="0.83" /><rect x="574" y="410" width="26" height="36" fillOpacity="0.83" /><rect x="604" y="410" width="26" height="36" fillOpacity="0.83" /><rect x="634" y="410" width="26" height="36" fillOpacity="0.83" />
        <rect x="454" y="452" width="26" height="36" fillOpacity="0.79" /><rect x="484" y="452" width="26" height="36" fillOpacity="0.79" /><rect x="514" y="452" width="26" height="36" fillOpacity="0.79" /><rect x="544" y="452" width="26" height="36" fillOpacity="0.79" /><rect x="574" y="452" width="26" height="36" fillOpacity="0.79" /><rect x="604" y="452" width="26" height="36" fillOpacity="0.79" />
        <rect x="424" y="494" width="26" height="36" fillOpacity="0.75" /><rect x="454" y="494" width="26" height="36" fillOpacity="0.75" /><rect x="484" y="494" width="26" height="36" fillOpacity="0.75" /><rect x="514" y="494" width="26" height="36" fillOpacity="0.75" /><rect x="544" y="494" width="26" height="36" fillOpacity="0.75" /><rect x="574" y="494" width="26" height="36" fillOpacity="0.75" />
        <rect x="394" y="536" width="26" height="36" fillOpacity="0.71" /><rect x="424" y="536" width="26" height="36" fillOpacity="0.71" /><rect x="454" y="536" width="26" height="36" fillOpacity="0.71" /><rect x="484" y="536" width="26" height="36" fillOpacity="0.71" /><rect x="514" y="536" width="26" height="36" fillOpacity="0.71" /><rect x="544" y="536" width="26" height="36" fillOpacity="0.71" />
        <rect x="364" y="578" width="26" height="36" fillOpacity="0.67" /><rect x="394" y="578" width="26" height="36" fillOpacity="0.67" /><rect x="424" y="578" width="26" height="36" fillOpacity="0.67" /><rect x="454" y="578" width="26" height="36" fillOpacity="0.67" /><rect x="484" y="578" width="26" height="36" fillOpacity="0.67" /><rect x="514" y="578" width="26" height="36" fillOpacity="0.67" />
        <rect x="334" y="620" width="26" height="36" fillOpacity="0.63" /><rect x="364" y="620" width="26" height="36" fillOpacity="0.63" /><rect x="394" y="620" width="26" height="36" fillOpacity="0.63" /><rect x="424" y="620" width="26" height="36" fillOpacity="0.63" /><rect x="454" y="620" width="26" height="36" fillOpacity="0.63" /><rect x="484" y="620" width="26" height="36" fillOpacity="0.63" />
        <rect x="304" y="662" width="26" height="36" fillOpacity="0.58" /><rect x="334" y="662" width="26" height="36" fillOpacity="0.58" /><rect x="364" y="662" width="26" height="36" fillOpacity="0.58" /><rect x="394" y="662" width="26" height="36" fillOpacity="0.58" /><rect x="424" y="662" width="26" height="36" fillOpacity="0.58" /><rect x="454" y="662" width="26" height="36" fillOpacity="0.58" />
        <rect x="274" y="704" width="26" height="36" fillOpacity="0.54" /><rect x="304" y="704" width="26" height="36" fillOpacity="0.54" /><rect x="334" y="704" width="26" height="36" fillOpacity="0.54" /><rect x="364" y="704" width="26" height="36" fillOpacity="0.54" /><rect x="394" y="704" width="26" height="36" fillOpacity="0.54" /><rect x="424" y="704" width="26" height="36" fillOpacity="0.54" /><rect x="484" y="704" width="26" height="36" fillOpacity="0.54" /><rect x="514" y="704" width="26" height="36" fillOpacity="0.54" /><rect x="544" y="704" width="26" height="36" fillOpacity="0.54" /><rect x="574" y="704" width="26" height="36" fillOpacity="0.54" /><rect x="604" y="704" width="26" height="36" fillOpacity="0.54" /><rect x="634" y="704" width="26" height="36" fillOpacity="0.54" /><rect x="664" y="704" width="26" height="36" fillOpacity="0.54" /><rect x="694" y="704" width="26" height="36" fillOpacity="0.54" /><rect x="724" y="704" width="26" height="36" fillOpacity="0.54" /><rect x="754" y="704" width="26" height="36" fillOpacity="0.54" />
        <rect x="244" y="746" width="26" height="36" fillOpacity="0.50" /><rect x="274" y="746" width="26" height="36" fillOpacity="0.50" /><rect x="304" y="746" width="26" height="36" fillOpacity="0.50" /><rect x="334" y="746" width="26" height="36" fillOpacity="0.50" /><rect x="364" y="746" width="26" height="36" fillOpacity="0.50" /><rect x="394" y="746" width="26" height="36" fillOpacity="0.50" /><rect x="454" y="746" width="26" height="36" fillOpacity="0.50" /><rect x="484" y="746" width="26" height="36" fillOpacity="0.50" /><rect x="514" y="746" width="26" height="36" fillOpacity="0.50" /><rect x="544" y="746" width="26" height="36" fillOpacity="0.50" /><rect x="574" y="746" width="26" height="36" fillOpacity="0.50" /><rect x="604" y="746" width="26" height="36" fillOpacity="0.50" /><rect x="634" y="746" width="26" height="36" fillOpacity="0.50" /><rect x="664" y="746" width="26" height="36" fillOpacity="0.50" /><rect x="694" y="746" width="26" height="36" fillOpacity="0.50" /><rect x="724" y="746" width="26" height="36" fillOpacity="0.50" /><rect x="754" y="746" width="26" height="36" fillOpacity="0.50" />
      </g>
    </svg>
  )
}
