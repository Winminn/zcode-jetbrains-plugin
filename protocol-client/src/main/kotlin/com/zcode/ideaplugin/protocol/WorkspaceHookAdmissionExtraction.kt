package com.zcode.ideaplugin.protocol

import com.zcode.ideaplugin.protocol.model.SessionEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 工作区钩子准入（admission）的 v4 投影提取——官方客户端会话内
 * 「N 个工作区 Hook 待审核，本会话暂未启用」横幅的数据源（computer-use 实测）。
 *
 * 官方不把待审钩子放进 pendingInteractions 交互投影（2026-10-13 探针 + 客户端
 * 实测），而是经 state.updated patch 的 workspaceHookAdmission 键下发
 * {pendingCount, bundleDigest, workspaceIdentity?}；信任完成后 patch 回 null。
 * 快照帧该键恒存在（schema 默认 null）。
 *
 * 提取语义：快照帧键存在即合成事件（null 也发，前端据以清横幅）；增量帧取最后
 * 一个带该键的 state.updated。与 [[WorkspaceHookReviewExtraction]] 的评审交互
 * 路线不同——后者在真实环境无数据源，已随评审卡一并移除。
 */
object WorkspaceHookAdmissionExtraction {

    /** 从帧 payload 提取准入合成事件；增量帧无该键返回 null（快照帧键恒存在） */
    fun extract(sid: String, payload: JsonObject?): SessionEvent? {
        payload ?: return null
        val value = when (payload["kind"]?.jsonPrimitive?.jsonStringOrNull) {
            "snapshot" -> payload["snapshot"]?.jsonObject?.get("workspaceHookAdmission")
            "deltas" -> {
                var latest: kotlinx.serialization.json.JsonElement? = null
                var found = false
                for (d in payload["deltas"]?.jsonArray ?: JsonArray(emptyList())) {
                    val delta = d as? JsonObject ?: continue
                    if (delta["op"]?.jsonPrimitive?.jsonStringOrNull != "state.updated") continue
                    val v = delta["patch"]?.jsonObject?.get("workspaceHookAdmission") ?: continue
                    latest = v
                    found = true
                }
                if (found) latest else null
            }
            else -> null
        } ?: return null

        return SessionEvent(
            type = "workspaceHookAdmission",
            seq = 0L,
            sessionId = sid,
            timestamp = System.currentTimeMillis(),
            traceId = null,
            turnId = null,
            deliveryKind = null,
            payload = buildJsonObject {
                // null（已准入/无待审）以 JsonNull 形态原样透传，前端据以收敛横幅
                put("admission", value)
            },
        )
    }
}
