package com.zcode.ideaplugin.protocol.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject as KJsonObject

/**
 * L6 channel RPC 二进制编解码（H5 Zbe/Nm/Pm 类逆向 = VSCode IPC 协议变体，探针实测对齐）。
 *
 * 值序列化：类型标记 1 字节 + VLQ 7bit 变长长度 + payload。
 *   Undefined=0 / String=1 / Buffer=2 / VSBuffer=3 / Array=4 / Object(JSON)=5 / Int=6
 *   负数与浮点走 Object(JSON)（VLQ 只编码非负整数）。
 *
 * 帧结构 = 若干值顺序拼接：
 *   请求  [Promise=100, id, channel, method, ...args]
 *   应答  [PromiseSuccess=201, id, result] / [PromiseError=202, id, message]
 *   事件  [EventListen=102, id, channel, event, filter?] / [EventFire=204, listenerId, data]
 *   握手  [Initialize=200, Undefined]
 */
object ChannelCodec {

    const val REQ_PROMISE = 100L
    const val REQ_PROMISE_CANCEL = 101L
    const val REQ_EVENT_LISTEN = 102L
    const val REQ_EVENT_DISPOSE = 103L

    const val RES_INITIALIZE = 200L
    const val RES_PROMISE_SUCCESS = 201L
    const val RES_PROMISE_ERROR = 202L
    const val RES_PROMISE_ERROR_OBJ = 203L
    const val RES_EVENT_FIRE = 204L

    private const val TAG_UNDEFINED = 0
    private const val TAG_STRING = 1
    private const val TAG_BUFFER = 2
    private const val TAG_VSBUFFER = 3
    private const val TAG_ARRAY = 4
    private const val TAG_OBJECT = 5
    private const val TAG_INT = 6

    /** channel wire 值模型（业务 JSON 统一走 Obj 携带 JsonElement） */
    sealed class ChValue {
        object Undefined : ChValue()
        data class Str(val value: String) : ChValue()
        data class IntVal(val value: Long) : ChValue()
        data class Buf(val bytes: ByteArray) : ChValue()
        data class Arr(val items: List<ChValue>) : ChValue()
        data class Obj(val json: JsonElement) : ChValue()
    }

    private val json = Json { encodeDefaults = true }

    // ---- 值编解码 ----

    fun encode(value: ChValue): ByteArray = when (value) {
        is ChValue.Undefined -> byteArrayOf(TAG_UNDEFINED.toByte())
        is ChValue.Str -> {
            val raw = value.value.toByteArray(Charsets.UTF_8)
            byteArrayOf(TAG_STRING.toByte()) + vlq(raw.size.toLong()) + raw
        }
        is ChValue.IntVal -> {
            require(value.value >= 0) { "负整数须转 Obj 走 JSON（VLQ 仅非负）" }
            byteArrayOf(TAG_INT.toByte()) + vlq(value.value)
        }
        is ChValue.Buf -> {
            val raw = value.bytes
            byteArrayOf(TAG_BUFFER.toByte()) + vlq(raw.size.toLong()) + raw
        }
        is ChValue.Arr -> {
            var out = byteArrayOf(TAG_ARRAY.toByte()) + vlq(value.items.size.toLong())
            for (item in value.items) out += encode(item)
            out
        }
        is ChValue.Obj -> {
            val text = json.encodeToString(JsonElement.serializer(), value.json)
            val raw = text.toByteArray(Charsets.UTF_8)
            byteArrayOf(TAG_OBJECT.toByte()) + vlq(raw.size.toLong()) + raw
        }
    }

    /** 解码单个值，返回值与新偏移（buf 越界/未知标记抛 [ChannelCodecException]） */
    fun decode(buf: ByteArray, pos: Int = 0): Pair<ChValue, Int> {
        require(pos < buf.size) { "channel 帧越界 pos=$pos size=${buf.size}" }
        return when (val tag = buf[pos].toInt() and 0xFF) {
            TAG_UNDEFINED -> ChValue.Undefined to pos + 1
            TAG_STRING, TAG_OBJECT -> {
                val (len, p) = vlqRead(buf, pos + 1)
                val end = p + len.toInt()
                val raw = buf.copyOfRange(p, end)
                if (tag == TAG_STRING) ChValue.Str(String(raw, Charsets.UTF_8)) to end
                else ChValue.Obj(json.parseToJsonElement(String(raw, Charsets.UTF_8))) to end
            }
            TAG_BUFFER, TAG_VSBUFFER -> {
                val (len, p) = vlqRead(buf, pos + 1)
                val end = p + len.toInt()
                ChValue.Buf(buf.copyOfRange(p, end)) to end
            }
            TAG_ARRAY -> {
                val (count, p) = vlqRead(buf, pos + 1)
                val items = ArrayList<ChValue>(count.toInt().coerceAtMost(1024))
                var cursor = p
                repeat(count.toInt()) {
                    val (v, np) = decode(buf, cursor)
                    items.add(v)
                    cursor = np
                }
                ChValue.Arr(items) to cursor
            }
            TAG_INT -> {
                val (v, p) = vlqRead(buf, pos + 1)
                ChValue.IntVal(v) to p
            }
            else -> throw ChannelCodecException("未知 channel 类型标记 $tag @ $pos")
        }
    }

    /** 解码整帧全部尾随值（请求 = head + 变长 args） */
    fun decodeAll(buf: ByteArray): List<ChValue> {
        val out = ArrayList<ChValue>()
        var pos = 0
        while (pos < buf.size) {
            val (v, np) = decode(buf, pos)
            out.add(v)
            pos = np
        }
        return out
    }

    fun encodeMessage(values: List<ChValue>): ByteArray {
        var out = ByteArray(0)
        for (v in values) out += encode(v)
        return out
    }

    class ChannelCodecException(message: String) : Exception(message)

    // ---- 帧构造（wire 形态：头为单元素数组 + 变长尾值，对齐 H5 Nm 序列化） ----

    fun encodeRequest(id: Long, channel: String, method: String, args: List<ChValue> = emptyList()): ByteArray =
        encodeMessage(
            listOf(
                ChValue.Arr(listOf(ChValue.IntVal(REQ_PROMISE), ChValue.IntVal(id), ChValue.Str(channel), ChValue.Str(method))),
            ) + args,
        )

    fun encodeSuccess(id: Long, result: ChValue): ByteArray =
        encodeMessage(listOf(ChValue.Arr(listOf(ChValue.IntVal(RES_PROMISE_SUCCESS), ChValue.IntVal(id))), result))

    /** PromiseError：[202, id] + message（H5 对 "Method not found" 文案有容错） */
    fun encodeError(id: Long, message: String): ByteArray =
        encodeMessage(listOf(ChValue.Arr(listOf(ChValue.IntVal(RES_PROMISE_ERROR), ChValue.IntVal(id))), ChValue.Str(message)))

    fun encodeEvent(listenerId: Long, data: ChValue): ByteArray =
        encodeMessage(listOf(ChValue.Arr(listOf(ChValue.IntVal(RES_EVENT_FIRE), ChValue.IntVal(listenerId))), data))

    /** bridge ready 后必须立即推送，否则手机端所有服务调用挂起（探针实测）；[200] 为单元素数组 */
    fun encodeInitialize(): ByteArray =
        encodeMessage(listOf(ChValue.Arr(listOf(ChValue.IntVal(RES_INITIALIZE))), ChValue.Undefined))

    // ---- 帧解析 ----

    data class ChannelRequest(
        val type: Long,
        val id: Long,
        val channel: String?,
        val method: String?,
        val args: List<ChValue>,
    )

    /**
     * 解析请求/订阅帧。wire 形态（H5 sendRequest 的 Nm 序列化实测）：
     * `Array[type, id, channel, method] + Array[arg1, arg2…]`——头是单元素数组值，
     * **参数整体再包一层单元素数组尾值**（无参请求也带空数组尾值；2026-08-26 装机
     * HAR 定案——此前按平铺解析导致所有带参方法参数读不到：topic required /
     * helloRequired / entitlement 恒 no_plan 等连锁故障）。平铺形态一并兼容。
     */
    fun parseRequest(buf: ByteArray): ChannelRequest {
        val values = decodeAll(buf)
        if (values.isEmpty()) throw ChannelCodecException("channel 帧为空")
        val head = values[0]
        val items = (head as? ChValue.Arr)?.items
        if (items != null && items.size >= 2) {
            val type = (items[0] as? ChValue.IntVal)?.value
                ?: throw ChannelCodecException("channel 帧 head[0] 非整数")
            val id = (items[1] as? ChValue.IntVal)?.value
                ?: throw ChannelCodecException("channel 帧 id 非整数")
            // EventDispose(103) 头仅 [type,id] 两元素（2026-09-22 真机抓帧）；Promise/EventListen
            // 为 [type,id,channel,method] 四元素——channel/method 统一按位置可缺失解析
            val channel = items.getOrNull(2) as? ChValue.Str
            val method = items.getOrNull(3) as? ChValue.Str
            val tail = values.drop(1)
            val args = (tail.singleOrNull() as? ChValue.Arr)?.items ?: tail
            return ChannelRequest(type, id, channel?.value, method?.value, args)
        }
        if (values.size >= 2 && values[0] is ChValue.IntVal && values[1] is ChValue.IntVal) {
            val type = (values[0] as ChValue.IntVal).value
            val id = (values[1] as ChValue.IntVal).value
            val channel = values.getOrNull(2) as? ChValue.Str
            val method = values.getOrNull(3) as? ChValue.Str
            return ChannelRequest(type, id, channel?.value, method?.value, values.drop(4))
        }
        throw ChannelCodecException("channel 帧头不可识别: ${values.size} values")
    }

    // ---- VLQ（7bit 变长，小端序组） ----

    fun vlq(value: Long): ByteArray {
        var v = value
        val out = ArrayList<Byte>(4)
        while (true) {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out.add(b.toByte())
            if (v == 0L) return out.toByteArray()
        }
    }

    fun vlqRead(buf: ByteArray, pos: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var p = pos
        while (true) {
            require(p < buf.size) { "VLQ 越界" }
            val b = buf[p].toInt() and 0xFF
            p++
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result to p
            shift += 7
            require(shift < 64) { "VLQ 过长" }
        }
    }

    // ---- JsonElement 互转（业务层便捷） ----

    fun ChValue.toJson(): JsonElement = when (this) {
        is ChValue.Undefined -> JsonNull
        is ChValue.Str -> JsonPrimitive(value)
        is ChValue.IntVal -> JsonPrimitive(value)
        is ChValue.Buf -> JsonPrimitive("<binary ${bytes.size} bytes>")
        is ChValue.Arr -> JsonArray(items.map { it.toJson() })
        is ChValue.Obj -> json
    }

    fun fromJson(element: JsonElement): ChValue = when {
        element is JsonNull -> ChValue.Undefined
        element is JsonPrimitive && element.isString -> ChValue.Str(element.content)
        element is JsonPrimitive && (element.content == "true" || element.content == "false") ->
            ChValue.Obj(element)
        element is JsonPrimitive && element.content.toLongOrNull() != null &&
            !element.content.startsWith("-") -> ChValue.IntVal(element.content.toLong())
        else -> ChValue.Obj(element)
    }

    fun objOf(vararg pairs: Pair<String, JsonElement>): KJsonObject = KJsonObject(pairs.toMap())
}
