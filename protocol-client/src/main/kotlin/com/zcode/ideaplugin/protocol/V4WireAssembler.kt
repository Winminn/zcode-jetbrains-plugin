package com.zcode.ideaplugin.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.*

/**
 * v4 wire 分片重组器（wireVersion 3 fragment 帧，2026-10-06 缺陷EK 根因修复）。
 *
 * 大快照（会话行多时数百 KB 级）被服务端拆成多个 kind:"fragment" 的帧通知后逐片下发：
 * `{wireVersion, kind:"fragment", deliveryKind, logicalFrameId, logicalFrameOrdinal, topic,
 *   subscriptionId, fragmentIndex, fragmentCount, logicalBytes, checksum{algorithm,value},
 *   dataBase64}`——插件此前只认整帧（params={frame:{payload:{kind:"snapshot"/"deltas"...}}}），
 * 分片全部静默丢弃，大会话的快照/标题/逐轮更改投影全灭（小会话不分片所以「时好时坏」）。
 *
 * 重组语义对齐官方 wire-reassembly.ts：
 * - 按 logicalFrameId 收集，每片独立 base64 解码（缺片超时整帧丢弃，防 pending 泄漏）
 * - 拼接后字节数 == logicalBytes、CRC32（java.util.zip 与官方 crc32WireBytes 同多项式）
 *   校验 checksum.value（8 位 hex）
 * - UTF-8 → JSON 解析出逻辑帧 {topic, subscriptionId, fromSeq, toSeq, payload}
 *
 * 输出包装为既有分支消费的形状 `{topic, frame: <逻辑帧>}`（与不分片整帧的 params 同构），
 * 下游（track/标题合成/turnFileChanges/V4FrameMapper/投影提取）零改动。
 */
internal class V4WireAssembler {

    private class Pending(
        val count: Int,
        val logicalBytes: Int,
        val crc32Hex: String?,
        val createdAt: Long,
    ) {
        val parts = HashMap<Int, ByteArray>(count)
    }

    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pending>()

    /**
     * 帧入口。fragment 帧返回 null（未收齐）或重组后的 `{topic, frame}` params；
     * 非 fragment（旧 CLI 整帧 params={topic, frame:{...}}）原样返回。
     */
    fun accept(params: JsonObject): JsonObject {
        if (params["kind"]?.jsonPrimitive?.jsonStringOrNull != "fragment") return params
        val logicalId = params["logicalFrameId"]?.jsonPrimitive?.jsonStringOrNull ?: return params
        val count = params["fragmentCount"]?.jsonPrimitive?.intOrNull ?: return params
        val index = params["fragmentIndex"]?.jsonPrimitive?.intOrNull ?: return params
        val logicalBytes = params["logicalBytes"]?.jsonPrimitive?.intOrNull ?: return params
        val data = params["dataBase64"]?.jsonPrimitive?.jsonStringOrNull ?: return params
        // 防御对齐官方：分片数上限 + 索引界内
        if (count <= 0 || count > MAX_FRAGMENTS || index !in 0 until count) return params
        val now = System.currentTimeMillis()
        if (pending.isNotEmpty()) {
            pending.values.removeIf { now - it.createdAt > PENDING_TTL_MS }
        }
        val crc32Hex = params["checksum"]?.jsonObject
            ?.takeIf { it["algorithm"]?.jsonPrimitive?.jsonStringOrNull == "crc32" }
            ?.get("value")?.jsonPrimitive?.jsonStringOrNull
        val p = pending.computeIfAbsent(logicalId) { Pending(count, logicalBytes, crc32Hex, now) }
        if (p.count != count || p.logicalBytes != logicalBytes) {
            pending.remove(logicalId)
            return params
        }
        val bytes = try {
            java.util.Base64.getDecoder().decode(data)
        } catch (_: IllegalArgumentException) {
            pending.remove(logicalId)
            return params
        }
        p.parts[index] = bytes
        if (p.parts.size < count) return params
        pending.remove(logicalId) // 收齐即摘出：拼装成败都不再收该片系
        return assemble(p) ?: params
    }

    /** 拼装：长度/CRC32/UTF-8/JSON 任一校验失败返回 null（整帧丢弃） */
    private fun assemble(p: Pending): JsonObject? {
        val parts = (0 until p.count).map { p.parts[it] ?: return null }
        val total = parts.sumOf { it.size }
        if (total != p.logicalBytes) return null
        val buf = ByteArray(total)
        var off = 0
        for (part in parts) {
            part.copyInto(buf, off)
            off += part.size
        }
        if (p.crc32Hex != null) {
            val crc = java.util.zip.CRC32()
            crc.update(buf)
            val expected = p.crc32Hex.toLongOrNull(16) ?: return null
            if (crc.value != expected) return null
        }
        val logical = try {
            Json.parseToJsonElement(buf.toString(Charsets.UTF_8)).jsonObject
        } catch (_: Exception) {
            return null
        }
        // 逻辑帧自带的 topic（信封与载荷一致性由服务端保证，这里取载荷值为准）
        val topic = logical["topic"]?.jsonPrimitive?.jsonStringOrNull ?: return null
        return buildJsonObject {
            put("topic", topic)
            put("frame", logical)
        }
    }

    companion object {
        private const val MAX_FRAGMENTS = 64
        private const val PENDING_TTL_MS = 60_000L
    }
}
