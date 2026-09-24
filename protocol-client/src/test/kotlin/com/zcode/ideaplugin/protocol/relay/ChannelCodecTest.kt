package com.zcode.ideaplugin.protocol.relay

import com.zcode.ideaplugin.protocol.relay.ChannelCodec.ChValue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** VLQ / 值编解码 / 帧构造，hex 向量与探针实测对齐 */
class ChannelCodecTest {

    @Test
    fun `VLQ 基础`() {
        assertEquals("00", ChannelCodec.vlq(0).toHexString())
        assertEquals("01", ChannelCodec.vlq(1).toHexString())
        assertEquals("7f", ChannelCodec.vlq(127).toHexString())
        assertEquals("8001", ChannelCodec.vlq(128).toHexString())
        assertEquals("c801", ChannelCodec.vlq(200).toHexString())
        for (v in listOf(0L, 1L, 127L, 128L, 300L, 65535L, 1_000_000L, Int.MAX_VALUE.toLong())) {
            val (decoded, _) = ChannelCodec.vlqRead(ChannelCodec.vlq(v), 0)
            assertEquals(v, decoded, "vlq($v) roundtrip")
        }
    }

    @Test
    fun `Initialize 帧 hex 与探针实测一致`() {
        // 探针 18:54 实测推送的握手帧：Array[200] + Undefined = 040106c80100
        assertEquals("040106c80100", ChannelCodec.encodeInitialize().toHexString())
    }

    @Test
    fun `String 与 Object 编解码 roundtrip`() {
        val frame = ChannelCodec.encodeMessage(
            listOf(ChValue.Str("zcode-task"), ChValue.Obj(buildJsonObject { put("ok", true) }))
        )
        val values = ChannelCodec.decodeAll(frame)
        assertEquals(ChValue.Str("zcode-task"), values[0])
        assertEquals(buildJsonObject { put("ok", true) }, (values[1] as ChValue.Obj).json)
    }

    @Test
    fun `Array 嵌套 roundtrip`() {
        val value = ChValue.Arr(
            listOf(ChValue.IntVal(100), ChValue.Str("id"), ChValue.Arr(listOf(ChValue.IntVal(1), ChValue.IntVal(2))))
        )
        val (decoded, _) = ChannelCodec.decode(ChannelCodec.encode(value))
        assertEquals(value, decoded)
    }

    @Test
    fun `Buffer roundtrip`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val (decoded, _) = ChannelCodec.decode(ChannelCodec.encode(ChValue.Buf(bytes)))
        assertTrue((decoded as ChValue.Buf).bytes.contentEquals(bytes))
    }

    @Test
    fun `Undefined 与负数走 JSON`() {
        val (u, p) = ChannelCodec.decode(byteArrayOf(0), 0)
        assertEquals(ChValue.Undefined, u)
        assertEquals(1, p)
        // 负数不允许 IntVal 编码
        assertFailsWith<IllegalArgumentException> { ChannelCodec.encode(ChValue.IntVal(-1)) }
        // fromJson 把负数落到 Obj
        assertEquals(
            buildJsonObject { put("n", -1) },
            ((ChannelCodec.fromJson(buildJsonObject { put("n", -1) })) as ChValue.Obj).json,
        )
    }

    @Test
    fun `请求帧构造与解析`() {
        val frame = ChannelCodec.encodeRequest(
            id = 7, channel = "zcode-task", method = "sendPrompt",
            args = listOf(ChValue.Obj(buildJsonObject { put("taskId", "t1"); put("content", "hi") })),
        )
        val parsed = ChannelCodec.parseRequest(frame)
        assertEquals(ChannelCodec.REQ_PROMISE, parsed.type)
        assertEquals(7, parsed.id)
        assertEquals("zcode-task", parsed.channel)
        assertEquals("sendPrompt", parsed.method)
        assertEquals(1, parsed.args.size)
    }

    @Test
    fun `应答帧 EventFire 帧 roundtrip`() {
        val success = ChannelCodec.encodeSuccess(9, ChValue.Undefined)
        val values = ChannelCodec.decodeAll(success)
        // wire 形态：头为单元素数组 [201, id] + data（对齐 H5 Nm 序列化，真机实测）
        val head = (values[0] as ChValue.Arr).items
        assertEquals(ChannelCodec.RES_PROMISE_SUCCESS, (head[0] as ChValue.IntVal).value)
        assertEquals(9, (head[1] as ChValue.IntVal).value)
        assertEquals(ChValue.Undefined, values[1])

        val event = ChannelCodec.encodeEvent(33, ChValue.Obj(buildJsonObject { put("kind", "delta") }))
        val ev = ChannelCodec.decodeAll(event)
        val evHead = (ev[0] as ChValue.Arr).items
        assertEquals(ChannelCodec.RES_EVENT_FIRE, (evHead[0] as ChValue.IntVal).value)
        assertEquals(33, (evHead[1] as ChValue.IntVal).value)
    }

    @Test
    fun `真机请求帧形态解析（数组头+尾值 args）`() {
        // H5 实测 wire：Array[100, id, channel, method] + args(Object)
        val frame = ChannelCodec.encodeMessage(
            listOf(
                ChValue.Arr(
                    listOf(
                        ChValue.IntVal(ChannelCodec.REQ_PROMISE),
                        ChValue.IntVal(7),
                        ChValue.Str("zcode-task"),
                        ChValue.Str("listTaskList"),
                    )
                ),
                ChValue.Obj(buildJsonObject { put("workspacePath", "G:\\w") }),
            )
        )
        val parsed = ChannelCodec.parseRequest(frame)
        assertEquals(ChannelCodec.REQ_PROMISE, parsed.type)
        assertEquals(7, parsed.id)
        assertEquals("zcode-task", parsed.channel)
        assertEquals("listTaskList", parsed.method)
        assertEquals(1, parsed.args.size)
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}
