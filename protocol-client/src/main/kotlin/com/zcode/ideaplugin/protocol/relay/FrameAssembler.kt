package com.zcode.ideaplugin.protocol.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * L5 rpc-frame 分片重组（接收）与分片（发送）。
 *
 * wire 片（data 信封 payload）：
 *   {zcode_type:"rpc-frame", bridgeSessionId, bridgeGeneration?, recoveryId?, seq,
 *    messageSeq, fragmentIndex, fragmentCount, messageBytes,
 *    checksum:{algorithm:"crc32", value:"%08x"}, dataBase64}
 *
 * 上限：物理片 1MB / 消息 16MB / 64 片（探针实测与 zcode.cjs 常量一致）。
 */
class FrameAssembler(private val onWarn: (String) -> Unit = {}) {

    data class AssembledMessage(
        val bridgeSessionId: String,
        val messageSeq: Long,
        val data: ByteArray,
    )

    private class Pending(val count: Int, val messageBytes: Long) {
        val parts = ConcurrentHashMap<Int, ByteArray>()
    }

    private val pending = ConcurrentHashMap<String, Pending>()

    /** 非完整 rpc-frame 片返回 null；齐片后拼接、校验 crc32 并移除暂存 */
    fun accept(payload: JsonObject): AssembledMessage? {
        val bridge = (payload["bridgeSessionId"] as? JsonPrimitive)?.content ?: return null
        val messageSeq = (payload["messageSeq"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
        val fragmentIndex = (payload["fragmentIndex"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        val fragmentCount = (payload["fragmentCount"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1
        val dataB64 = (payload["dataBase64"] as? JsonPrimitive)?.content ?: return null
        val raw = Base64.getDecoder().decode(dataB64)

        val key = "$bridge\u0000$messageSeq"
        val entry = pending.getOrPut(key) { Pending(fragmentCount, (payload["messageBytes"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0) }
        entry.parts[fragmentIndex] = raw
        if (entry.parts.size < entry.count) return null
        pending.remove(key)

        val data = ByteArray(entry.parts.values.sumOf { it.size })
        var offset = 0
        for (i in 0 until entry.count) {
            val part = entry.parts[i] ?: run {
                onWarn("rpc-frame 分片缺片 $key index=$i")
                return null
            }
            part.copyInto(data, offset)
            offset += part.size
        }
        if (offset.toLong() != entry.messageBytes && entry.messageBytes > 0) {
            onWarn("rpc-frame 长度不符 $key: ${offset}B != 声明 ${entry.messageBytes}B")
        }
        val checksum = payload["checksum"] as? JsonObject
        val algo = (checksum?.get("algorithm") as? JsonPrimitive)?.content
        if (algo == "crc32") {
            val expected = (checksum?.get("value") as? JsonPrimitive)?.content
            val actual = crc32Hex(data)
            if (expected != null && !expected.equals(actual, ignoreCase = true)) {
                onWarn("rpc-frame crc32 不符 $key: expect=$expected actual=$actual（按内容继续）")
            }
        }
        return AssembledMessage(bridge, messageSeq, data)
    }

    companion object {
        fun crc32Hex(data: ByteArray): String {
            val crc = CRC32()
            crc.update(data)
            return String.format("%08x", crc.value)
        }
    }
}

/** 发送侧分片器：messageSeq 由调用方持有；seq 经 [fragment] 的提供器**逐片取号**——
 *  多片消息占用连续多个 seq，若整体只取一次再写 seq+index，下一条消息的 seq 会与之
 *  重叠（H5 按 seq 连续性校验入站帧，重叠即静默丢弃，2026-09-22 对照开源审查定案）*/
object FrameFragmenter {

    /**
     * 拆分为若干 rpc-frame payload（单片消息也走这里，保证字段齐全）。
     * @param nextSeq 每片调用一次取新 seq（多片消息占连续号段）
     * @param maxPhysicalBytes 物理片字节预算（base64 前的 raw 上限）
     */
    fun fragment(
        bridgeSessionId: String,
        nextSeq: () -> Long,
        messageSeq: Long,
        data: ByteArray,
        bridgeGeneration: Long? = null,
        recoveryId: String? = null,
        maxPhysicalBytes: Int = Relay.MAX_PHYSICAL_FRAME_BYTES,
    ): List<JsonObject> {
        require(data.size <= Relay.MAX_MESSAGE_BYTES) { "rpc-frame 消息超限: ${data.size}B" }
        val fragmentSize = (maxPhysicalBytes / 4 * 3).coerceAtLeast(64 * 1024) // base64 膨胀留量
        val fragmentCount = ((data.size + fragmentSize - 1) / fragmentSize).coerceAtLeast(1)
        require(fragmentCount <= Relay.MAX_FRAGMENTS) { "rpc-frame 分片数超限: $fragmentCount" }

        val checksumValue = FrameAssembler.crc32Hex(data)
        return (0 until fragmentCount).map { index ->
            val chunk = data.copyOfRange(index * fragmentSize, minOf((index + 1) * fragmentSize, data.size))
            buildJsonObject {
                put("zcode_type", Relay.PAYLOAD_RPC_FRAME)
                put("bridgeSessionId", bridgeSessionId)
                bridgeGeneration?.let { put("bridgeGeneration", it) }
                recoveryId?.let { put("recoveryId", it) }
                put("seq", nextSeq())
                put("messageSeq", messageSeq)
                put("fragmentIndex", index)
                put("fragmentCount", fragmentCount)
                put("messageBytes", data.size)
                put("checksum", buildJsonObject {
                    put("algorithm", "crc32")
                    put("value", checksumValue)
                })
                put("dataBase64", Base64.getEncoder().encodeToString(chunk))
            }
        }
    }
}
