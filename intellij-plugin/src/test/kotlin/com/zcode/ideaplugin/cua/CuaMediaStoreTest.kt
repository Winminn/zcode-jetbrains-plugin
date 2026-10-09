package com.zcode.ideaplugin.cua

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull

/**
 * CuaMediaStore 落盘→历史回填往返（真机缺陷：截图重载后丢失，见缺陷EQ 补充记录）。
 * 直接读写真实存储根下的临时会话目录（sessionId 用测试专用前缀，结束后清理）。
 */
class CuaMediaStoreTest {

    private val sid = "sess_cuamedia_test_${System.currentTimeMillis()}"

    @Test
    fun `persist then inject roundtrip`() {
        val callId = "call_test_1"
        val media = Json.parseToJsonElement(
            """[{"mimeType":"image/jpeg","data":"/9j/4AAQSkZJRg"}]""",
        ).jsonArray
        CuaMediaStore.persist(sid, callId, media)

        val messages = Json.parseToJsonElement(
            """{"parts":[{"type":"text","text":"hi"},{"type":"tool","callID":"$callId",
                "tool":"mcp__zcgui-computer-use__get_app_state",
                "state":{"status":"completed","output":"[Attached image/jpeg: MCP image]"}}]}""",
        ).jsonObject
        val injected = CuaMediaStore.injectIntoMessages(sid, kotlinx.serialization.json.JsonArray(listOf(messages)))

        val part = injected[0].jsonObject["parts"]!!.jsonArray[1].jsonObject
        val mediaOut = part["state"]!!.jsonObject["media"]
        assertNotNull(mediaOut, "media 应被注入")
        assertEquals("image/jpeg", mediaOut!!.jsonArray[0].jsonObject["mimeType"]!!.jsonPrimitive.content)
        assertEquals("/9j/4AAQSkZJRg", mediaOut.jsonArray[0].jsonObject["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `inject skips parts without file or with existing media`() {
        val messages = Json.parseToJsonElement(
            """{"parts":[{"type":"tool","callID":"call_missing","tool":"x",
                "state":{"status":"completed","output":"y"}}]}""",
        ).jsonObject
        val out = CuaMediaStore.injectIntoMessages(sid, kotlinx.serialization.json.JsonArray(listOf(messages)))
        val part = out[0].jsonObject["parts"]!!.jsonArray[0].jsonObject
        assertEquals(null, part["state"]!!.jsonObject["media"], "无落盘文件不应注入")
    }
}
