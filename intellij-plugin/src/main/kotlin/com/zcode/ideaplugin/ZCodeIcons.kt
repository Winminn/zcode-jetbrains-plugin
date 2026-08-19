package com.zcode.ideaplugin

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * ZCode 插件图标（ZC GUI 品牌标识）
 *
 * 用于 ToolWindow 图标 + 右键菜单 action 图标。
 * 深色渐变底 + 白色斜杠像素图案，亮暗主题通用。
 */
object ZCodeIcons {
    @JvmField
    val ZcGui: Icon = IconLoader.getIcon("/icons/zcgui.svg", ZCodeIcons::class.java)
}
