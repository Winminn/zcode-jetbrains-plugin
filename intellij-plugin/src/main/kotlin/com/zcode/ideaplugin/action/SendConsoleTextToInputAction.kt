package com.zcode.ideaplugin.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.zcode.ideaplugin.ZCodeBundle.message
import com.zcode.ideaplugin.ZCodeIcons
import com.zcode.ideaplugin.zCodeService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 右键菜单：把控制台选中的日志文本发送到 ZC GUI 输入框（issue #14：
 * 控制台输出的日志希望右键加入输入框）
 *
 * - 注册位置：控制台右键（ConsoleEditorPopupMenu，Run/Debug 等工具窗口控制台）
 * - 控制台文本没有对应的行号引用语义（不同于编辑器场景），发纯文本正文；
 *   编辑器场景行号引用走 SendSelectionToInputAction，两个动作互不替代
 * - 菜单上下文里恒显示（无选区置灰，保右键入口可发现），有选区才可点
 * - 终端（Terminal 工具窗口）不支持：右键菜单组按引擎分化且经真机验证注入
 *   不生效（2026-09-15 尝试 ReworkedTerminalContextMenu/OutputContextMenu 均
 *   未出现，已放弃该场景，详见 docs/internal/bugs-regressions 留档）
 */
class SendConsoleTextToInputAction : AnAction(
    message("action.sendConsoleTextToInput.text"),
    message("action.sendConsoleTextToInput.description"),
    ZCodeIcons.ZcGui,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null
        e.presentation.isEnabled = editor?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val text = editor.selectionModel.selectedText?.trim() ?: return
        if (text.isEmpty()) return
        pushTextToInput(project, text)
    }
}

/** 公共推送入口：确保 ZCode 工具窗口打开后推送 textToInput 到前端输入框（走粘贴折叠逻辑）*/
internal fun pushTextToInput(project: Project, text: String) {
    ToolWindowManager.getInstance(project).getToolWindow("ZCode")?.show()
    project.zCodeService().pushToWebview(
        buildJsonObject {
            put("op", "textToInput")
            put("text", text)
        }
    )
}
