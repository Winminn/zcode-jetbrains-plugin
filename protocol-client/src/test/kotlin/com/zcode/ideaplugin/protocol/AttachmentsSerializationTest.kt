package com.zcode.ideaplugin.protocol

import com.zcode.ideaplugin.protocol.model.AttachmentInput
import com.zcode.ideaplugin.protocol.model.SendParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * session/send 图片附件序列化测试（纯单元，无需 ZCode 环境）
 *
 * 对齐 zcode.cjs 的 ZCode Protocol 通道附件形态（2026-08-26 源码确认）：
 * {kind:"image", filename, mimeType, sizeBytes?, dataBase64?, localPath?}。
 * 字段名写错会被服务端静默丢弃（v4i 只认这 6 个键），此处锁形状防回归。
 */
class AttachmentsSerializationTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `AttachmentInput 序列化字段名与协议一致`() {
        val att = AttachmentInput(
            kind = "image",
            filename = "pasted-image-123.png",
            mimeType = "image/png",
            sizeBytes = 12345,
            dataBase64 = "iVBORw0KGgoAAAANSUhEUg==",
        )
        val obj = json.parseToJsonElement(json.encodeToString(AttachmentInput.serializer(), att)).jsonObject
        assertEquals("image", obj["kind"]?.jsonPrimitive?.content)
        assertEquals("pasted-image-123.png", obj["filename"]?.jsonPrimitive?.content)
        assertEquals("image/png", obj["mimeType"]?.jsonPrimitive?.content)
        assertEquals(12345L, obj["sizeBytes"]?.jsonPrimitive?.content?.toLong())
        assertEquals("iVBORw0KGgoAAAANSUhEUg==", obj["dataBase64"]?.jsonPrimitive?.content)
        // 未提供的字段缺失或为 null（协议端 v4i 按缺省处理；生产链路手工 buildJsonObject 恒省略）
        assertNull(obj["localPath"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `SendParams 可选字段缺省不序列化`() {
        val params = SendParams(sessionId = "sess_1", content = "看这张图")
        val obj = json.parseToJsonElement(json.encodeToString(SendParams.serializer(), params)).jsonObject
        assertNull(obj["attachments"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `SendParams 带附件时序列化完整`() {
        val params = SendParams(
            sessionId = "sess_1",
            content = "看这张图",
            attachments = listOf(
                AttachmentInput(filename = "a.png", mimeType = "image/png", sizeBytes = 1, dataBase64 = "QQ=="),
            ),
        )
        val obj = json.parseToJsonElement(json.encodeToString(SendParams.serializer(), params)).jsonObject
        val atts = obj["attachments"]?.jsonArray
        assertTrue(atts != null && atts.size == 1, "attachments 应为 1 项数组")
        val first = atts!![0].jsonObject
        assertEquals("image", first["kind"]?.jsonPrimitive?.content)
        assertEquals("image/png", first["mimeType"]?.jsonPrimitive?.content)
        assertEquals("QQ==", first["dataBase64"]?.jsonPrimitive?.content)
    }

    @Test
    fun `dataBase64 与 localPath 可共存（localPath 优先的服务端语义不变）`() {
        val att = AttachmentInput(
            filename = "x.png",
            mimeType = "image/png",
            dataBase64 = "QQ==",
            localPath = "C:/tmp/x.png",
        )
        val obj = json.parseToJsonElement(json.encodeToString(AttachmentInput.serializer(), att)).jsonObject
        assertEquals("C:/tmp/x.png", obj["localPath"]?.jsonPrimitive?.content)
        assertEquals("QQ==", obj["dataBase64"]?.jsonPrimitive?.content)
    }
}
