/**
 * 设置页通用开关卡片（结构对齐 MemoryToggle：图标+标题+说明+胶囊开关）
 *
 * 供「基础设置→行为」（对话结束通知）与「浏览器」设置页复用；
 * 状态与持久化由调用方注入（on/off/onToggle/busy），本组件只管展示。
 */
import '../styles/setting-toggle.less'

const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(' ')

interface Props {
  /** codicon 图标类名 */
  icon: string
  title: string
  desc?: string
  on: boolean
  busy?: boolean
  /** null/undefined = 加载中禁用态（等后端快照）*/
  disabled?: boolean
  onHint?: string
  offHint?: string
  onToggle: () => void
}

export function SettingToggle({ icon, title, desc, on, busy, disabled, onHint, offHint, onToggle }: Props) {
  return (
    <div className={cx('setting-toggle', on && 'on')}>
      <div className="setting-toggle__body">
        <div className="setting-toggle__name-row">
          <span className={cx('codicon', icon, 'setting-toggle__icon')} />
          <span className="setting-toggle__name">{title}</span>
        </div>
        {desc ? <div className="setting-toggle__desc">{desc}</div> : null}
      </div>
      <button
        className={cx('setting-toggle__switch', on && 'on')}
        onClick={() => !disabled && !busy && onToggle()}
        disabled={busy || disabled}
        title={on ? onHint : offHint}
      >
        <span className={cx('codicon', busy ? 'codicon-loading spin' : 'setting-toggle__knob')} />
      </button>
    </div>
  )
}
