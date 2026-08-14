package com.zcode.ideaplugin.ui

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * 多标签页状态持久化（PROJECT 级）
 *
 * 保存每个标签的显示名与绑定的 sessionId，IDE 重启后按此恢复标签并懒加载会话历史。
 * 对齐 cc-gui TabStateService，但按列表存储（删标签无索引重排问题）。
 */
@Service(Service.Level.PROJECT)
@State(name = "ZCodeTabState", storages = [Storage("zCodeTabState.xml")])
class ZCodeTabState : PersistentStateComponent<ZCodeTabState.State> {

    /** 单个标签的持久化信息（需要无参构造器供 XmlSerializer 反序列化）*/
    class TabInfo {
        var name: String = ""
        var sessionId: String? = null

        constructor()

        constructor(name: String, sessionId: String?) {
            this.name = name
            this.sessionId = sessionId
        }
    }

    class State {
        var tabs: MutableList<TabInfo> = mutableListOf()
        var activeIndex: Int = 0
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(project: Project): ZCodeTabState = project.service()
    }
}
