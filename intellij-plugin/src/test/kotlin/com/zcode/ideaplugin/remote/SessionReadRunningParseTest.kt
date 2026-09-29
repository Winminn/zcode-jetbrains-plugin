package com.zcode.ideaplugin.remote

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 缺陷EB兜底：session/read 活性复核的解析纯函数（diag-eb 实证 shape） */
class SessionReadRunningParseTest {

    @Test
    fun `running status parses true`() {
        val read = buildJsonObject {
            put("projection", buildJsonObject { put("status", "running") })
        }
        assertEquals(true, parseSessionReadRunning(read))
    }

    @Test
    fun `idle status parses false`() {
        val read = buildJsonObject {
            put("projection", buildJsonObject { put("status", "idle") })
        }
        assertEquals(false, parseSessionReadRunning(read))
    }

    @Test
    fun `missing projection node returns null for fail-soft`() {
        val read = buildJsonObject { put("messages", buildJsonObject {}) }
        assertNull(parseSessionReadRunning(read))
    }

    @Test
    fun `empty object returns null`() {
        assertNull(parseSessionReadRunning(buildJsonObject { }))
    }
}
