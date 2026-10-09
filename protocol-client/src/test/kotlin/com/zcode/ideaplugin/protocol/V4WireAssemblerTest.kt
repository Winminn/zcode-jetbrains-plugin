package com.zcode.ideaplugin.protocol

import kotlinx.serialization.json.*
import java.util.Base64
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * V4WireAssembler 单测（缺陷EK：wireVersion 3 分片重组——大会话快照此前全被丢弃）
 *
 * 覆盖：非 fragment 原样放行 / 两片重组（含乱序）/ 未收齐等待 / CRC32 与长度校验失败丢弃 /
 * 缺片超时前保持 pending
 */
class V4WireAssemblerTest {

    private val assembler = V4WireAssembler()

    /** 构造真实形状的两片 fragment 帧通知（逻辑帧 JSON → bytes → CRC32 → 对半切 → base64） */
    private fun fragmentPair(
        logicalJson: String,
        crcOverride: String? = null,
        logicalBytesOverride: Int? = null,
    ): Pair<JsonObject, JsonObject> {
        val bytes = logicalJson.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(bytes) }
        val crcHex = crcOverride ?: java.lang.Long.toHexString(crc.value).padStart(8, '0')
        val total = logicalBytesOverride ?: bytes.size
        val half = bytes.size / 2
        val p0 = Base64.getEncoder().encodeToString(bytes.copyOfRange(0, half))
        val p1 = Base64.getEncoder().encodeToString(bytes.copyOfRange(half, bytes.size))
        fun frag(index: Int, data: String): JsonObject = buildJsonObject {
            put("wireVersion", 3)
            put("kind", "fragment")
            put("deliveryKind", "initial")
            put("logicalFrameId", "sub-x-1-lf-1")
            put("logicalFrameOrdinal", 1)
            put("topic", "conversation/sess_test")
            put("subscriptionId", "sub-x-1")
            put("fragmentIndex", index)
            put("fragmentCount", 2)
            put("logicalBytes", total)
            put("checksum", buildJsonObject {
                put("algorithm", "crc32")
                put("value", crcHex)
            })
            put("dataBase64", data)
        }
        return frag(0, p0) to frag(1, p1)
    }

    private fun logicalJson(): String = buildJsonObject {
        put("topic", "conversation/sess_test")
        put("subscriptionId", "sub-x-1")
        put("fromSeq", 0)
        put("toSeq", 12)
        put("payload", buildJsonObject {
            put("kind", "snapshot")
            put("snapshot", buildJsonObject {
                put("revision", 1569)
                put("sessionId", "sess_test")
            })
        })
    }.toString()

    @Test
    fun `non-fragment frame passes through unchanged`() {
        val plain = buildJsonObject {
            put("topic", "conversation/sess_test")
            put("frame", buildJsonObject { put("payload", buildJsonObject { put("kind", "deltas") }) })
        }
        assertEquals(plain, assembler.accept(plain))
    }

    @Test
    fun `two fragments assemble into logical frame with topic wrapper`() {
        val (f0, f1) = fragmentPair(logicalJson())
        val first = assembler.accept(f0)
        // 未收齐：返回原 fragment params（调用方按缺 frame 键早退），不发不丢
        assertEquals(f0, first)
        val assembled = assembler.accept(f1)
        assertNotEquals(f1, assembled, "收齐应返回重组产物而非原分片")
        assertEquals("conversation/sess_test", assembled["topic"]!!.jsonPrimitive.content)
        val frame = assembled["frame"]!!.jsonObject
        assertEquals("snapshot", frame["payload"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals(1569, frame["payload"]!!.jsonObject["snapshot"]!!.jsonObject["revision"]!!.jsonPrimitive.int)
    }

    @Test
    fun `out-of-order fragments still assemble`() {
        val (f0, f1) = fragmentPair(logicalJson())
        assertEquals(f1, assembler.accept(f1))
        val assembled = assembler.accept(f0)
        assertTrue(assembled["frame"]!!.jsonObject["payload"]!!.jsonObject["kind"]!!.jsonPrimitive.content == "snapshot")
    }

    @Test
    fun `checksum mismatch drops the frame`() {
        val (f0, f1) = fragmentPair(logicalJson(), crcOverride = "deadbeef")
        assembler.accept(f0)
        val assembled = assembler.accept(f1)
        assertEquals(f1, assembled, "CRC 不符应整帧丢弃（返回原 fragment params）")
    }

    @Test
    fun `logicalBytes mismatch drops the frame`() {
        val (f0, f1) = fragmentPair(logicalJson(), logicalBytesOverride = bytesCount(logicalJson()) + 10)
        assembler.accept(f0)
        val assembled = assembler.accept(f1)
        assertEquals(f1, assembled, "长度不符应整帧丢弃")
    }

    /** 与 fragmentPair 内切的 bytes 对齐（构造用同一份） */
    private fun bytesCount(json: String): Int = json.toByteArray(Charsets.UTF_8).size

    @Test
    fun `missing middle fragment keeps pending without assembling`() {
        // 三片缺中间片：收 0 与 2 不出帧
        val json = buildJsonObject {
            put("topic", "conversation/sess_test")
            put("payload", buildJsonObject { put("kind", "snapshot") })
        }.toString()
        val bytes = json.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(bytes) }
        val crcHex = java.lang.Long.toHexString(crc.value).padStart(8, '0')
        val third = bytes.size / 3
        val parts = listOf(
            bytes.copyOfRange(0, third),
            bytes.copyOfRange(third, third * 2),
            bytes.copyOfRange(third * 2, bytes.size),
        )
        fun frag(index: Int): JsonObject = buildJsonObject {
            put("kind", "fragment")
            put("logicalFrameId", "sub-y-1-lf-1")
            put("topic", "conversation/sess_test")
            put("fragmentIndex", index)
            put("fragmentCount", 3)
            put("logicalBytes", bytes.size)
            put("checksum", buildJsonObject {
                put("algorithm", "crc32")
                put("value", crcHex)
            })
            put("dataBase64", Base64.getEncoder().encodeToString(parts[index]))
        }
        assertEquals(frag(0), assembler.accept(frag(0)))
        assertEquals(frag(2), assembler.accept(frag(2)))
        val assembled = assembler.accept(frag(1))
        assertTrue(assembled["frame"] != null, "补齐缺片后应重组成功")
    }
}
