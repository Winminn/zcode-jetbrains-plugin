package com.zcode.ideaplugin.protocol

import com.zcode.ideaplugin.protocol.model.SessionEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 工作区钩子信任评审的 v4 投影提取（见设计文档《工作区钩子信任评审》）。
 *
 * v4 帧 payload 的 pendingInteractions 投影为**全量替换语义**：快照帧给全量
 * 集合，增量帧 state.updated delta 的 patch.pendingInteractions 给最新全集。
 * 评审 settled/superseded 后服务端从数组移除该项——前端拿到空集合即收敛卡片，
 * 无需逐条 diff。
 */
object WorkspaceHookReviewExtraction {

    /** 从帧 payload 提取评审合成事件；帧内无 pendingInteractions 字段返回 null */
    fun extract(sid: String, payload: JsonObject?): SessionEvent? {
        payload ?: return null
        val interactions = when (payload["kind"]?.jsonPrimitive?.jsonStringOrNull) {
            "snapshot" -> payload["snapshot"]?.jsonObject?.get("pendingInteractions") as? JsonArray
            "deltas" -> {
                // 逐 delta 扫，取最后一个带 pendingInteractions 的 state.updated
                // （一帧内多 delta 均为同代投影，末位即最新）
                var latest: JsonArray? = null
                for (d in payload["deltas"]?.jsonArray ?: JsonArray(emptyList())) {
                    val delta = d as? JsonObject ?: continue
                    if (delta["op"]?.jsonPrimitive?.jsonStringOrNull != "state.updated") continue
                    val arr = delta["patch"]?.jsonObject?.get("pendingInteractions") as? JsonArray ?: continue
                    latest = arr
                }
                latest
            }
            else -> null
        } ?: return null

        val reviews = interactions.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val p = o["payload"] as? JsonObject
            val kind = p?.get("kind")?.jsonPrimitive?.jsonStringOrNull
                ?: o["kind"]?.jsonPrimitive?.jsonStringOrNull
            // 评审标识实证为元素内 payload.kind；元素顶层 kind 作漂移兜底，此时回传元素本身
            if (kind == "workspaceHookReview") p ?: o else null
        }
        return SessionEvent(
            type = "workspaceHookReview",
            seq = 0L,
            sessionId = sid,
            timestamp = System.currentTimeMillis(),
            traceId = null,
            turnId = null,
            deliveryKind = null,
            payload = buildJsonObject { put("reviews", JsonArray(reviews)) },
        )
    }

    /**
     * 构造 respondWorkspaceHookReview 命令 payload：评审标识字段原样回传
     * （评审 payload 全字段自带），叠加 trust_selected 决策。
     */
    fun buildRespondPayload(sessionId: String, identity: JsonObject, reviewItemIds: List<String>): JsonObject =
        buildJsonObject {
            put("sessionId", sessionId)
            for (key in IDENTITY_KEYS) {
                val v = identity[key] ?: continue
                // remoteSessionId 缺省为 null，不入键（服务端按可选处理）
                if (key == "remoteSessionId" && v is JsonNull) continue
                put(key, v)
            }
            put("decision", buildJsonObject {
                put("action", "trust_selected")
                put("reviewItemIds", JsonArray(reviewItemIds.map { JsonPrimitive(it) }))
            })
        }

    /** 应答需回传的评审标识字段（逐字段实证，见设计 §2.3/§2.4） */
    private val IDENTITY_KEYS = listOf(
        "taskId", "runId", "remoteSessionId", "workspaceIdentity",
        "bundleDigest", "reviewFlowId", "generation", "interactionId",
    )
}
