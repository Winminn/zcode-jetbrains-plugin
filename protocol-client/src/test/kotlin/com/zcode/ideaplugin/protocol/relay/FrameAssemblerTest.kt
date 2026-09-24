package com.zcode.ideaplugin.protocol.relay

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** rpc-frame 分片重组与发送侧分片对拍 */
class FrameAssemblerTest {

    private fun rpcFrame(
        bridge: String, seq: Long, messageSeq: Long, index: Int, count: Int,
        total: Int, chunk: ByteArray, crc: String,
    ) = buildJsonObject {
        put("zcode_type", "rpc-frame")
        put("bridgeSessionId", bridge)
        put("seq", seq)
        put("messageSeq", messageSeq)
        put("fragmentIndex", index)
        put("fragmentCount", count)
        put("messageBytes", total)
        put("checksum", buildJsonObject { put("algorithm", "crc32"); put("value", crc) })
        put("dataBase64", Base64.getEncoder().encodeToString(chunk))
    }

    @Test
    fun `单片消息直接组装`() {
        val inner = ChannelCodec.encodeInitialize()
        val frame = rpcFrame("b1", 1, 1, 0, 1, inner.size, inner, FrameAssembler.crc32Hex(inner))
        val assembled = FrameAssembler().accept(frame)!!
        assertTrue(assembled.data.contentEquals(inner))
        assertEquals("b1", assembled.bridgeSessionId)
        assertEquals(1, assembled.messageSeq)
    }

    @Test
    fun `多片消息分片重组 roundtrip`() {
        val inner = ByteArray(300_000) { (it % 251).toByte() } // 300KB，分片后多片
        val fragments = FrameFragmenter.fragment("b2", nextSeq = { 5L }, messageSeq = 9, data = inner, maxPhysicalBytes = 64 * 1024)
        assertTrue(fragments.size > 1, "应产生多片: ${fragments.size}")

        val assembler = FrameAssembler()
        var assembled: FrameAssembler.AssembledMessage? = null
        for (f in fragments) {
            val result = assembler.accept(f)
            if (result != null) assembled = result
        }
        assertTrue(assembled!!.data.contentEquals(inner), "重组后应与原消息一致")
        assertEquals(9, assembled.messageSeq)
    }

    @Test
    fun `多片消息 seq 逐片取号不重叠`() {
        // 2026-09-22 审查定案：seq 若整体取一次再写 seq+index，下一条消息会与之重叠，
        // H5 按 seq 连续性校验入站帧，重叠即静默丢弃
        val counter = java.util.concurrent.atomic.AtomicLong(10)
        val frag1 = FrameFragmenter.fragment("bx", nextSeq = { counter.incrementAndGet() }, messageSeq = 1, data = ByteArray(150_000), maxPhysicalBytes = 64 * 1024)
        val frag2 = FrameFragmenter.fragment("bx", nextSeq = { counter.incrementAndGet() }, messageSeq = 2, data = ByteArray(150_000), maxPhysicalBytes = 64 * 1024)
        val seqs = (frag1 + frag2).map { (it["seq"] as kotlinx.serialization.json.JsonPrimitive).content.toLong() }
        assertEquals(seqs.size, seqs.toSet().size, "seq 不得重复: $seqs")
        assertEquals(seqs, seqs.sorted(), "seq 应连续递增")
    }

    @Test
    fun `乱序分片也可组装`() {
        val inner = ByteArray(150_000) { (it % 7).toByte() }
        val fragments = FrameFragmenter.fragment("b3", { 1L }, 1, inner, maxPhysicalBytes = 64 * 1024)
        val assembler = FrameAssembler()
        var assembled: FrameAssembler.AssembledMessage? = null
        for (f in fragments.reversed()) {
            assembler.accept(f)?.let { assembled = it }
        }
        assertTrue(assembled!!.data.contentEquals(inner))
    }

    @Test
    fun `缺片返回 null`() {
        val inner = ByteArray(150_000) { 1 }
        val fragments = FrameFragmenter.fragment("b4", { 1L }, 1, inner, maxPhysicalBytes = 64 * 1024)
        val assembler = FrameAssembler()
        // 只喂第一片
        assertNull(assembler.accept(fragments.first()))
    }

    @Test
    fun `crc32 与 Python zlib 对拍`() {
        // python: zlib.crc32(b"zcode") = 0x2311e0f7
        assertEquals("2311e0f7", FrameAssembler.crc32Hex("zcode".toByteArray()))
    }
}
