package com.zcode.ideaplugin.protocol

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 工作区钩子信任评审提取器纯函数测试（无进程，直接喂帧 payload）。
 */
class WorkspaceHookReviewExtractionTest {

    private val sid = "sess_test_1"

    private fun reviewInteraction(id: String, vararg items: JsonObject): JsonObject =
        buildJsonObject {
            put("payload", buildJsonObject {
                put("kind", "workspaceHookReview")
                put("interactionId", id)
                put("items", JsonArray(items.toList()))
            })
        }

    private val otherInteraction = buildJsonObject {
        put("payload", buildJsonObject { put("kind", "somethingElse") })
    }

    private fun hookItem(itemId: String) = buildJsonObject { put("reviewItemId", itemId) }

    @Test
    fun `快照帧提取评审且过滤其他交互类型`() {
        val payload = buildJsonObject {
            put("kind", "snapshot")
            put("snapshot", buildJsonObject {
                put("pendingInteractions", JsonArray(listOf(
                    reviewInteraction("ia1", hookItem("i1")),
                    otherInteraction,
                    reviewInteraction("ia2"),
                )))
            })
        }
        val ev = WorkspaceHookReviewExtraction.extract(sid, payload)
        assertNotNull(ev)
        assertEquals("workspaceHookReview", ev.type)
        assertEquals(sid, ev.sessionId)
        val reviews = ev.payload["reviews"]!!.jsonArray
        assertEquals(2, reviews.size)
        assertEquals("ia1", reviews[0].jsonObject["interactionId"]!!.jsonPrimitive.content)
        assertEquals("ia2", reviews[1].jsonObject["interactionId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `增量帧取最后一个带 pendingInteractions 的 state_updated`() {
        val payload = buildJsonObject {
            put("kind", "deltas")
            put("deltas", JsonArray(listOf(
                buildJsonObject { put("op", "row.appended") },
                buildJsonObject {
                    put("op", "state.updated")
                    put("patch", buildJsonObject {
                        put("pendingInteractions", JsonArray(listOf(reviewInteraction("old"))))
                    })
                },
                buildJsonObject {
                    put("op", "state.updated")
                    put("patch", buildJsonObject {
                        put("pendingInteractions", JsonArray(listOf(reviewInteraction("new"))))
                    })
                },
            )))
        }
        val ev = WorkspaceHookReviewExtraction.extract(sid, payload)!!
        val reviews = ev.payload["reviews"]!!.jsonArray
        assertEquals(1, reviews.size)
        assertEquals("new", reviews[0].jsonObject["interactionId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `帧无 pendingInteractions 字段返回 null`() {
        val snapshot = buildJsonObject {
            put("kind", "snapshot")
            put("snapshot", buildJsonObject { put("meta", JsonObject(emptyMap())) })
        }
        assertNull(WorkspaceHookReviewExtraction.extract(sid, snapshot))

        val deltas = buildJsonObject {
            put("kind", "deltas")
            put("deltas", JsonArray(listOf(
                buildJsonObject { put("op", "state.updated"); put("patch", buildJsonObject { }) },
            )))
        }
        assertNull(WorkspaceHookReviewExtraction.extract(sid, deltas))
        assertNull(WorkspaceHookReviewExtraction.extract(sid, null))
    }

    @Test
    fun `空集合照常合成事件供前端收敛`() {
        val payload = buildJsonObject {
            put("kind", "snapshot")
            put("snapshot", buildJsonObject {
                put("pendingInteractions", JsonArray(emptyList()))
            })
        }
        val ev = WorkspaceHookReviewExtraction.extract(sid, payload)!!
        assertEquals(0, ev.payload["reviews"]!!.jsonArray.size)
    }

    @Test
    fun `元素顶层 kind 作漂移兜底`() {
        val payload = buildJsonObject {
            put("kind", "snapshot")
            put("snapshot", buildJsonObject {
                put("pendingInteractions", JsonArray(listOf(
                    buildJsonObject {
                        put("kind", "workspaceHookReview")
                        put("interactionId", "flat")
                    },
                )))
            })
        }
        val ev = WorkspaceHookReviewExtraction.extract(sid, payload)!!
        val reviews = ev.payload["reviews"]!!.jsonArray
        assertEquals(1, reviews.size)
        assertEquals("flat", reviews[0].jsonObject["interactionId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `应答 payload 回传标识并叠加信任决策，null remoteSessionId 不入键`() {
        val identity = buildJsonObject {
            put("taskId", "task_1")
            put("runId", "run_1")
            put("remoteSessionId", JsonNull)
            put("workspaceIdentity", "ws_key")
            put("bundleDigest", "sha256:b1")
            put("reviewFlowId", "flow_1")
            put("generation", 3)
            put("interactionId", "ia1")
        }
        val p = WorkspaceHookReviewExtraction.buildRespondPayload(sid, identity, listOf("item_1", "item_2"))
        assertEquals(sid, p["sessionId"]!!.jsonPrimitive.content)
        assertEquals("task_1", p["taskId"]!!.jsonPrimitive.content)
        assertEquals(3, p["generation"]!!.jsonPrimitive.int)
        assertTrue("remoteSessionId" !in p, "null remoteSessionId 不应入键")
        val decision = p["decision"]!!.jsonObject
        assertEquals("trust_selected", decision["action"]!!.jsonPrimitive.content)
        assertEquals(listOf("item_1", "item_2"),
            decision["reviewItemIds"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
