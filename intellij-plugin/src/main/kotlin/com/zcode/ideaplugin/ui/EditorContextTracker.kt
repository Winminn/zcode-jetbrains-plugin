package com.zcode.ideaplugin.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import com.intellij.util.messages.MessageBusConnection
import com.zcode.ideaplugin.action.buildLineReference

/**
 * 跟踪 IDE 当前打开的文件与选区，变化时（200ms 防抖）推给 webview。
 *
 * 监听三类事件：
 *   1. FileEditorManagerListener.selectionChanged — tab 切换 / 当前编辑器变化
 *   2. FileEditorManagerListener.fileClosed — 关闭最后一个/批量关闭 tab 时 selectionChanged
 *      不保证触发，fileClosed 每文件必触发（兜底"全部关闭后 chip 残留"偶发问题）
 *   3. SelectionListener — 选区拖动 / 单行点击 / 多行选择
 *
 * 数据格式：复用 [buildLineReference] 产出的 `@path` / `@path#L10` / `@path#L10-20`，
 * 与 SendSelectionToInputAction 同源，视觉/序列化两边一致。
 *
 * 阶段 A（docs/internal/feat/当前文件chip-前端交互重做.md）：
 * 仅产出"当前编辑器 ref 字符串"——不读文件内容、不感知 git/脏状态、不联动其他模块。
 */
class EditorContextTracker(
    private val project: Project,
    private val onUpdate: (ref: String?) -> Unit,
) : Disposable {

    private val log = Logger.getInstance("ZCodePlugin")

    @Volatile private var disposed = false
    // pushCurrent 在 EDT（Alarm SWING_THREAD），snapshot 在 handleJsMessage 池线程：@Volatile 保可见性
    @Volatile private var lastRef: String? = null
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var busConn: MessageBusConnection? = null

    init {
        // 1. 文件/编辑器切换（tab 切换 / 关闭 / 打开）
        val conn = project.messageBus.connect(this)
        busConn = conn
        conn.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    if (event.manager.project != project) return
                    scheduleUpdate()
                }

                // 关闭最后一个 / 批量关闭 tab 时 selectionChanged 不保证触发（用户实测：
                // 全部关闭后 chip 偶发残留最后一个文件 ref），fileClosed 每文件必触发，兜底重算
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (source.project != project) return
                    scheduleUpdate()
                }
            }
        )
        // 2. 选区变化（拖选 / 单行点击 / 多行选择）
        EditorFactory.getInstance().eventMulticaster.addSelectionListener(
            object : SelectionListener {
                override fun selectionChanged(e: SelectionEvent) {
                    if (e.editor.project != project) return
                    scheduleUpdate()
                }
            },
            this,
        )
    }

    private fun scheduleUpdate() {
        if (disposed) return
        // 防抖：拖选事件密集，先取消上轮挂起的 200ms 请求
        alarm.cancelAllRequests()
        alarm.addRequest({ if (!disposed) pushCurrent() }, 200)
    }

    /**
     * 立即重算当前 ref（webview init / 重连时拉取用，跳过防抖）。
     * 返回值 = 当前编辑器 ref 字符串；无打开编辑器返回 null。
     *
     * 拉取响应同样会让 webview 的显示值变成本次 ref——必须同步去重基准 lastRef。
     * 否则拉取让 webview 显示 F 而 lastRef 仍是初始 null，随后关闭唯一文件时
     * pushCurrent 算出 null 被 `ref != lastRef` 吞掉：webview 永远收不到 null，
     * chip 残留已关闭文件（2026-10-07 实测复现：chip 显示值纯靠 mount 拉取、
     * 期间无编辑器事件，开新会话后关闭唯一文件）。
     */
    fun snapshot(): String? {
        if (disposed) return null
        return computeRef().also { lastRef = it }
    }

    private fun pushCurrent() {
        val ref = computeRef()
        if (ref != lastRef) {
            // null 跳变是"全部关闭后 chip 偶发残留"问题的关键现场：有日志但 chip 仍残留
            // = webview 侧没落；无日志 = Kotlin 侧没算到 null。留证供偶发复现时定位
            if (ref == null || lastRef == null) {
                log.info("[DIAG-CURRENTFILE] ref 跳变: $lastRef -> $ref")
            }
            lastRef = ref
            onUpdate(ref)
        }
    }

    // 编辑器模型（FileEditorManager/SelectionModel）访问必须持读锁：snapshot() 会被
    // handleJsMessage 的池线程调到（无读锁，SEVERE ThreadingAssertions）；pushCurrent 的
    // EDT 路径 runReadAction 内联直执行，无额外开销
    private fun computeRef(): String? =
        ReadAction.compute<String?, RuntimeException> {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@compute null
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return@compute null
            val sel = editor.selectionModel
            // 无选区：返回 `@path`（chip 形态只有文件名，无行号后缀）
            if (!sel.hasSelection()) return@compute "@${file.path}"
            buildLineReference(file, editor.document, sel.selectionStart, sel.selectionEnd)
        }

    override fun dispose() {
        disposed = true
        // Alarm(..., this) 构造时已传 parent，dispose 会自动取消挂起请求
        // 这里只清本 tracker 持有的额外资源（busConn 在父 Disposer 链上自动释放）
        try {
            busConn?.disconnect()
        } catch (e: Exception) {
            log.warn("Editor context bus connection disconnect failed: ${e.message}")
        }
        busConn = null
    }
}
