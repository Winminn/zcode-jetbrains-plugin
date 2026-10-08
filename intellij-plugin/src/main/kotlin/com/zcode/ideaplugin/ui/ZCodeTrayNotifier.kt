package com.zcode.ideaplugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.WindowManager
import java.awt.SystemTray
import java.awt.TrayIcon

/**
 * Windows 系统级通知（OS toast，设计稿 docs/internal/feat/系统级通知-windows-设计.md）：
 * AWT SystemTray + TrayIcon.displayMessage——Win10/11 上映射为系统 toast 并落通知中心，
 * 切走 IDE 窗口也可见（IDE 内 BALLOON 气泡做不到的那一档）。
 *
 * 形态与纪律：
 * - 仅 Windows 且 SystemTray.isSupported() 时启用，其余平台/环境静默 no-op；
 * - 托盘图标懒添加（首次发 toast 时），IDE 退出经 Disposer 摘除，防残留；
 * - toast/托盘图标点击回调路由到「最近一次」通知的 onClick（AWT 回调在 EDT，
 *   openConversationTab 等 UI 操作可直接执行）；
 * - 已发 toast 不可撤回、不可按条取消——审批被应答后点击旧 toast 只是打开 IDE（接受）。
 */
object ZCodeTrayNotifier {

    private val log = Logger.getInstance("ZCodePlugin")

    @Volatile
    private var trayIcon: TrayIcon? = null

    /** 最近一次通知的点击行为（托盘图标/toast 点击共用，ActionListener 触发）*/
    @Volatile
    private var pendingClick: (() -> Unit)? = null

    /** 平台支持性：仅 Windows（v1 范围）；SystemTray 探测失败按不支持降级 */
    fun isSupported(): Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("windows") == true &&
            runCatching { SystemTray.isSupported() }.getOrDefault(false)

    /** 目标 project 的 IDE 主窗口非激活（用户切走/最小化）——toast 的发送条件 */
    internal fun isIdeFrameInactive(project: Project): Boolean =
        WindowManager.getInstance().getFrame(project)?.isActive != true

    /**
     * 发系统 toast（任意线程可调；仅当 IDE 主窗口非激活时真正发出，窗口激活时
     * 用户本就能看到 webview 对话框/IDE 气泡，toast 是重复打扰）。
     * [onClick] 在用户点击 toast/托盘图标时于 EDT 执行。
     */
    fun notifyOsToastIfUnfocused(
        project: Project,
        title: String,
        body: String,
        warning: Boolean,
        onClick: () -> Unit,
    ) {
        if (!isSupported()) return
        if (!isIdeFrameInactive(project)) return
        ApplicationManager.getApplication().invokeLater {
            try {
                val icon = ensureTrayIcon() ?: return@invokeLater
                pendingClick = onClick
                icon.displayMessage(
                    title, body,
                    if (warning) TrayIcon.MessageType.WARNING else TrayIcon.MessageType.INFO,
                )
            } catch (e: Exception) {
                log.warn("OS toast failed: ${e.message}")
            }
        }
    }

    /** 把 IDE 主窗口拉回前台（最小化先还原）；toast 点击时与定位会话配套使用 */
    internal fun bringIdeToFront(project: Project) {
        val frame = WindowManager.getInstance().getFrame(project) ?: return
        if (frame.extendedState and java.awt.Frame.ICONIFIED != 0) {
            frame.extendedState = frame.extendedState and java.awt.Frame.ICONIFIED.inv()
        }
        frame.isVisible = true
        forceForeground(frame)
    }

    /**
     * Windows 前台锁规避（2026-10-08 [DIAG-OSNOTIFY] 沙箱实测收敛，Win11 23H2）：
     * 前台锁下一切"激活"手段均被系统拒绝——纯 AWT toFront()、平台
     * AppIcon.requestFocus（内部同样是裸 SetForegroundWindow）、AttachThreadInput
     * （attach 本身被拒）、SendInput 注入输入（不再授予前台权）。
     * 唯一有效 = TOPMOST 切换：SetWindowPos 不需要前台权，先抬到最上层再取消置顶，
     * 视觉上 IDE 回到最前；随后补一次 SetForegroundWindow 尝试真激活
     * （有前台权的环境生效，被拒无害——窗口已可见）。
     */
    private fun forceForeground(window: java.awt.Window) {
        try {
            val id = com.sun.jna.Native.getComponentID(window)
            if (id == 0L) return
            val hwnd = com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Pointer(id))
            val u = com.sun.jna.platform.win32.User32.INSTANCE
            val topmost = com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Pointer(-1))
            val notopmost = com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Pointer(-2))
            u.SetWindowPos(hwnd, topmost, 0, 0, 0, 0, 0x13) // SWP_NOMOVE|SWP_NOSIZE|SWP_NOACTIVATE
            u.SetWindowPos(hwnd, notopmost, 0, 0, 0, 0, 0x13)
            u.SetForegroundWindow(hwnd)
        } catch (e: Throwable) {
            log.warn("Force foreground failed: ${e.message}")
            runCatching { com.intellij.ui.AppIcon.getInstance().requestFocus(window) }
        }
    }

    /** 懒添加托盘图标（EDT 调用）；添加失败返回 null 本/次通知放弃，不抛异常 */
    private fun ensureTrayIcon(): TrayIcon? {
        trayIcon?.let { return it }
        val tray = try {
            SystemTray.getSystemTray()
        } catch (e: Exception) {
            log.warn("SystemTray unavailable: ${e.message}")
            return null
        }
        val image = loadTrayImage() ?: return null
        val icon = TrayIcon(image, "ZCode")
        icon.isImageAutoSize = true
        icon.addActionListener {
            // toast 点击（Win10/11 路由回托盘图标时）与双击托盘图标共用：
            // 执行最近一次通知的点击行为
            pendingClick?.invoke()
        }
        return try {
            tray.add(icon)
            trayIcon = icon
            Disposer.register(ApplicationManager.getApplication(), com.intellij.openapi.Disposable {
                removeTrayIcon()
            })
            icon
        } catch (e: Exception) {
            log.warn("Add tray icon failed: ${e.message}")
            null
        }
    }

    private fun removeTrayIcon() {
        trayIcon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        trayIcon = null
        pendingClick = null
    }

    /** 托盘图标取插件 logo（SVG 经 IconLoader 栅格化；失败返回 null 放弃本次通知）*/
    private fun loadTrayImage(): java.awt.Image? = try {
        val icon = IconLoader.getIcon("/META-INF/pluginIcon.svg", ZCodeTrayNotifier::class.java)
        com.intellij.util.IconUtil.toImage(icon)
    } catch (e: Exception) {
        log.warn("Load tray image failed: ${e.message}")
        null
    }
}
