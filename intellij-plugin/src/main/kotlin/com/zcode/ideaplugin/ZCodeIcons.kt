package com.zcode.ideaplugin

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * ZCode 插件图标（Zai 品牌标识）
 *
 * 用于 ToolWindow 图标 + 右键菜单 action 图标。
 * 黑底白 Z（#2D2D2D + #FFFFFF），亮暗主题通用。
 */
object ZCodeIcons {
    @JvmField
    val Zai: Icon = IconLoader.getIcon("/icons/zai.svg", ZCodeIcons::class.java)
}
