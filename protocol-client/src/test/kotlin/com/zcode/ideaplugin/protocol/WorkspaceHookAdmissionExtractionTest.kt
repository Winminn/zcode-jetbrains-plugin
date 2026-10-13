package com.zcode.ideaplugin.protocol

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 工作区钩子准入（workspaceHookAdmission）投影提取器纯函数测试（无进程，直接喂帧 payload）。
 * 官方形态：state.updated patch 携带 {pendingCount,bundleDigest,workspaceIdentity?}；
 * 已准入/无待审时为 null（也要透传，前端据以收敛横幅）。
 */
class WorkspaceHookAdmissionExtractionTest {

    private val sid = "sess_test_1"

    private fun admission(pending: Int) = buildJsonObject {
        put("pendingCount", pending)
        put("bundleDigest", "sha256:b1")
        put("workspaceIdentity", "g:/ws")
    }

    @Test
    fun `快照帧提取非空准入`() {
        val payload = buildJsonObject {
            put("kind", "snapshot")
            put("snapshot", buildJsonObject {
                put("workspaceHookAdmission", admission(1))
            })
        }
        val ev = WorkspaceHookAdmissionExtraction.extract(sid, payload)
        assertNotNull(ev)
        assertEquals("workspaceHookAdmission", ev.type)
        assertEquals(sid, ev.sessionId)
        val a = ev.payload["admission"]!!.jsonObject
        assertEquals(1, a["pendingCount"]!!.jsonPrimitive.int)
        assertEquals("sha256:b1", a["bundleDigest"]!!.jsonPrimitive.content)
    }

    @Test
    fun `快照帧 null 准入也透传（清横幅信号）`() {
        val payload = buildJsonObject {
            put("kind", "snapshot")
            put("snapshot", buildJsonObject {
                put("workspaceHookAdmission", JsonNull)
            })
        }
        val ev = WorkspaceHookAdmissionExtraction.extract(sid, payload)!!
        assertTrue(ev.payload["admission"] is JsonNull, "null 准入应原样透传")
    }

    @Test
    fun `增量帧取最后一个带键的 state_updated`() {
        val payload = buildJsonObject {
            put("kind", "deltas")
            put("deltas", JsonArray(listOf(
                buildJsonObject { put("op", "row.appended") },
                buildJsonObject {
                    put("op", "state.updated")
                    put("patch", buildJsonObject { put("workspaceHookAdmission", admission(1)) })
                },
                buildJsonObject {
                    put("op", "state.updated")
                    put("patch", buildJsonObject { put("workspaceHookAdmission", JsonNull) })
                },
            )))
        }
        val ev = WorkspaceHookAdmissionExtraction.extract(sid, payload)!!
        assertTrue(ev.payload["admission"] is JsonNull, "末位 delta 为 null 应取 null")
    }

    @Test
    fun `增量帧无该键返回 null`() {
        val payload = buildJsonObject {
            put("kind", "deltas")
            put("deltas", JsonArray(listOf(
                buildJsonObject {
                    put("op", "state.updated")
                    put("patch", buildJsonObject { put("meta", buildJsonObject { }) })
                },
            )))
        }
        assertNull(WorkspaceHookAdmissionExtraction.extract(sid, payload))
        assertNull(WorkspaceHookAdmissionExtraction.extract(sid, null))
    }
}
