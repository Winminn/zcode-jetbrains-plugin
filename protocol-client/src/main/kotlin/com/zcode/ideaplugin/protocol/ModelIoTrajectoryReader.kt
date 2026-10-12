package com.zcode.ideaplugin.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.RandomAccessFile

/**
 * 模型调用轨迹读取器（会话「调用轨迹」面板数据源）
 *
 * ZCode CLI 在每次模型调用完成时向 ~/.zcode/cli/{debug,rollout}/ 写一条
 * model-io-<sanitize(sessionId)>.jsonl 记录（type="model_io"：一次调用 = 一条，
 * 含完整请求上下文 + 响应 + 错误）。与官方桌面端同源同构：
 * packages/services/src/zcode-agent/modelTrajectory.ts。
 *
 * 读取策略：双目录扫文件 → 尾部 chunk（大会话单文件可达几十 MB，全量 parse
 * 慢且内存峰值高）→ 逐行宽松解析 → 按 sessionId 精确过滤 → 按 startedAt 排序 →
 * delta 展开 → 截尾最近 limit 条。
 *
 * delta 展开：新版 model-io 为避免完整上下文梯度重复落盘，request.messages /
 * sdkMessages / body.messages 只存增量（kind="delta" + offset），读取时按前一条
 * 记录拼接还原；kind="tail" 是进程内缓存丢失后写的裁剪基线，不向前拼。
 * 未做展开时轨迹中段请求的消息列表残缺（功能「时好时坏」的同型坑，参见缺陷 EK）。
 *
 * 保留期：CLI 生产态对 rollout 目录按「最近 3 个会话」轮转淘汰（runner-debug.ts
 * MAX_ROLLOUT_FILES=3，新会话首写时按 mtime 删最旧；单文件上限 64MiB 超限重置）。
 * 更早会话的落盘文件已被 CLI 删除——本读取器查不到 ≠ 该会话没有调用过。
 */
object ModelIoTrajectoryReader {

    // ---------- DTO（对齐官方 ZCodeModelTrajectory*，kotlinx 直序列化给 webview）----------

    @Serializable
    data class ModelTrajectory(
        val taskId: String,
        val records: List<TrajectoryRecord>,
        /** 命中的源文件绝对路径（排查用） */
        val sourceFiles: List<String>,
        /** 文件尾截断 或 超出 limit 截尾 */
        val truncated: Boolean,
        /** 源文件全部行数（含未进展示窗口的更早记录）；轮转删除的历史无法计入 */
        val totalCount: Int = 0,
    )

    @Serializable
    data class TrajectoryRecord(
        val requestId: String,
        val attempt: Int = 1,
        val startedAt: String,
        val completedAt: String? = null,
        val durationMs: Long? = null,
        val turnId: String? = null,
        val traceId: String? = null,
        val callSource: TrajectoryCallSource? = null,
        val model: TrajectoryModel,
        val request: TrajectoryRequest,
        val response: TrajectoryResponse? = null,
        val error: TrajectoryError? = null,
    )

    /** main=主会话 subagent=子智能体 compact=上下文压缩 sidecar=辅助请求（标题生成/提示优化/目标验证等） */
    @Serializable
    data class TrajectoryCallSource(val kind: String, val querySource: String? = null)

    @Serializable
    data class TrajectoryModel(
        val modelId: String? = null,
        val providerId: String? = null,
        val role: String? = null,
        val source: String? = null,
    )

    @Serializable
    data class TrajectoryRequest(
        val messages: List<TrajectoryMessage> = emptyList(),
        val toolNames: List<String> = emptyList(),
    )

    @Serializable
    data class TrajectoryMessage(val role: String, val parts: List<TrajectoryPart> = emptyList())

    /** kind: text | reasoning | tool-call | tool-result | image | unknown */
    @Serializable
    data class TrajectoryPart(
        val kind: String,
        val text: String? = null,
        val toolCallId: String? = null,
        val toolName: String? = null,
        val input: JsonElement? = null,
        val output: JsonElement? = null,
        val mediaType: String? = null,
        val raw: JsonElement? = null,
    )

    @Serializable
    data class TrajectoryUsage(
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        val totalTokens: Long? = null,
        val cacheReadTokens: Long? = null,
        val reasoningTokens: Long? = null,
    )

    @Serializable
    data class TrajectoryResponse(
        val finishReason: String? = null,
        val text: String? = null,
        val reasoningText: String? = null,
        val toolCalls: List<TrajectoryPart> = emptyList(),
        val usage: TrajectoryUsage? = null,
        val responseId: String? = null,
        val modelId: String? = null,
    )

    @Serializable
    data class TrajectoryError(
        val name: String,
        val message: String,
        val stack: String? = null,
    )

    // ---------- 读取 ----------

    private const val TAIL_BYTES = 16L * 1024 * 1024 // 每文件读尾部 16MB（官方 32MB，插件取半减内存峰值）
    const val DEFAULT_LIMIT = 200
    private val json = Json { ignoreUnknownKeys = true }

    /** 官方 generateText 标题生成的 system prompt 前缀（querySource 缺失时的来源兜底推断） */
    private const val SESSION_TITLE_PROMPT_PREFIX = "Generate a concise title for this coding session."

    /** 读取某会话的模型调用轨迹（debug+rollout 双目录，时间序，最近 limit 条） */
    fun read(sessionId: String, limit: Int = DEFAULT_LIMIT): ModelTrajectory =
        readFrom(resolveCliRoots(), sessionId, limit)

    private fun resolveCliRoots(): List<File> = listOfNotNull(
        File(System.getProperty("user.home"), ".zcode/cli"),
        System.getenv("ZCODE_DATA_BASE_DIR")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { File(it, ".zcode/cli") },
    )

    internal fun readFrom(cliRoots: List<File>, sessionId: String, limit: Int = DEFAULT_LIMIT): ModelTrajectory {
        val safeLimit = if (limit > 0) limit else DEFAULT_LIMIT
        val sanitized = sanitizeSessionSegment(sessionId)
        val fileName = "model-io-${sanitized.ifEmpty { "no-session" }}.jsonl"
        val rawRecords = ArrayList<JsonObject>()
        val sourceFiles = ArrayList<String>()
        var inputTruncated = false
        var totalCount = 0

        for (root in cliRoots) {
            for (mode in listOf("debug", "rollout")) {
                val f = File(File(root, mode), fileName)
                if (!f.isFile) continue
                totalCount += countLines(f)
                val tail = readTailLines(f) ?: continue
                inputTruncated = inputTruncated || tail.second
                var matched = false
                for (line in tail.first) {
                    val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                    if (str(obj["type"]) != "model_io") continue
                    if (str(obj["sessionId"]) != sessionId) continue
                    rawRecords.add(obj)
                    matched = true
                }
                if (matched) sourceFiles.add(f.absolutePath)
            }
        }

        // 时间序；同毫秒/缺失时间按 requestId 兜底保证稳定（与官方一致）
        rawRecords.sortWith(
            compareBy({ parseTime(str(it["startedAt"])) }, { str(it["requestId"]) ?: "" })
        )

        // delta 展开依赖顺序（前一条 = 展开后），必须先排序再展开
        var previous: JsonObject? = null
        val records = ArrayList<TrajectoryRecord>(rawRecords.size)
        for (raw in rawRecords) {
            val expanded = expandDeltas(raw, previous)
            records.add(mapRecord(expanded))
            previous = expanded
        }

        val truncated = inputTruncated || records.size > safeLimit
        val trimmed = if (truncated) records.takeLast(safeLimit) else records
        return ModelTrajectory(
            taskId = sessionId,
            records = trimmed,
            sourceFiles = sourceFiles,
            truncated = truncated,
            totalCount = totalCount,
        )
    }

    /** 与 runner-debug.ts 的 sanitizeFileSegment 一致：仅保留文件名安全字符，截 80 */
    fun sanitizeSessionSegment(value: String): String =
        value.replace(Regex("[^a-zA-Z0-9_-]+"), "-").trim('-').take(80)

    /** 读文件尾部 chunk 按行切；起始位置在文件中段时首行可能残缺，丢弃。IO 失败返回 null */
    private fun readTailLines(file: File): Pair<List<String>, Boolean>? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val start = maxOf(0L, len - TAIL_BYTES)
                raf.seek(start)
                val buf = ByteArray((len - start).toInt())
                raf.readFully(buf)
                val lines = String(buf, Charsets.UTF_8).split('\n').filter { it.isNotBlank() }
                val truncated = start > 0
                Pair(if (truncated && lines.isNotEmpty()) lines.drop(1) else lines, truncated)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 全文件行数（数换行 + 末行无换行补一；单文件上限 64MiB 级顺序读可接受）。IO 失败返回 0 */
    private fun countLines(file: File): Int {
        return try {
            var count = 0L
            var last = -1
            val nl = '\n'.code
            java.io.FileInputStream(file).use { fin ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = fin.read(buf)
                    if (n < 0) break
                    for (i in 0 until n) {
                        if (buf[i].toInt() == nl) count++
                    }
                    last = buf[n - 1].toInt()
                }
            }
            (count + if (last != -1 && last != nl) 1 else 0).toInt()
        } catch (e: Exception) {
            0
        }
    }

    // ---------- delta 展开（照官方 expandModelIORequest 三处同构） ----------

    private fun expandDeltas(record: JsonObject, previous: JsonObject?): JsonObject {
        val request = obj(record["request"]) ?: return record
        val prevRequest = previous?.let { obj(it["request"]) }
        val reqMutable = HashMap(request)
        expandCollection(reqMutable, prevRequest, "messages", "messagesKind", "messageOffset")
        expandCollection(reqMutable, prevRequest, "sdkMessages", "sdkMessagesKind", "sdkMessageOffset")

        val body = obj(request["body"])
        if (body != null) {
            val bodyMutable = HashMap(body)
            expandCollection(bodyMutable, prevRequest?.let { obj(it["body"]) }, "messages", "bodyMessagesKind", "bodyMessageOffset")
            reqMutable["body"] = JsonObject(bodyMutable)
        }
        val result = HashMap(record)
        result["request"] = JsonObject(reqMutable)
        return JsonObject(result)
    }

    private fun expandCollection(
        target: MutableMap<String, JsonElement>,
        previous: JsonObject?,
        collectionKey: String,
        kindKey: String,
        offsetKey: String,
    ) {
        when (str(target[kindKey])) {
            "tail" -> return // 裁剪基线：新的展开起点，不拼更早历史
            "delta" -> Unit
            else -> return
        }
        val delta = arr(target[collectionKey]) ?: return
        val prevArr = previous?.get(collectionKey)?.let { arr(it) } ?: return
        val offset = (target[offsetKey] as? JsonPrimitive)?.content?.toIntOrNull() ?: return
        if (offset < 0 || offset > prevArr.size) return
        val merged = ArrayList<JsonElement>(offset + delta.size)
        merged.addAll(prevArr.subList(0, offset))
        merged.addAll(delta)
        target[collectionKey] = JsonArray(merged)
    }

    // ---------- 记录映射（照官方 mapRecord / mapMessages / mapPart） ----------

    private fun mapRecord(record: JsonObject): TrajectoryRecord {
        val request = obj(record["request"])
        val response = obj(record["response"])
        val model = obj(record["model"])
        val error = obj(record["error"])
        val querySource = str(record["querySource"]) ?: inferQuerySourceFromRequest(request)
        val modelRole = str(model?.get("role"))

        return TrajectoryRecord(
            requestId = str(record["requestId"]) ?: "",
            attempt = num(record["attempt"])?.toInt() ?: 1,
            startedAt = str(record["startedAt"]) ?: "",
            completedAt = str(record["completedAt"]),
            durationMs = num(record["durationMs"]),
            turnId = str(record["turnId"]),
            traceId = str(record["traceId"]),
            callSource = classifyCallSource(querySource, modelRole),
            model = TrajectoryModel(
                modelId = str(model?.get("modelId")),
                providerId = str(model?.get("providerId")),
                role = modelRole,
                source = str(model?.get("source")),
            ),
            request = TrajectoryRequest(
                messages = mapMessages(request?.get("messages")),
                toolNames = request?.get("toolNames")?.let { arr(it) }
                    ?.mapNotNull { str2(it) } ?: emptyList(),
            ),
            response = response?.let {
                TrajectoryResponse(
                    finishReason = str(it["finishReason"]),
                    text = str(it["text"]),
                    reasoningText = str(it["reasoningText"]),
                    toolCalls = it["toolCalls"]?.let { a -> arr(a) }?.map { c -> mapPart(c) } ?: emptyList(),
                    usage = mapUsage(obj(it["usage"])),
                    responseId = str(it["responseId"]),
                    modelId = str(it["modelId"]),
                )
            },
            error = error?.let {
                val name = str(it["name"])
                val message = str(it["message"])
                if (name != null || message != null) {
                    TrajectoryError(name = name ?: "Error", message = message ?: "", stack = str(it["stack"]))
                } else null
            },
        )
    }

    /** querySource 缺失时按首条 system 消息推断标题生成（与官方 inferQuerySourceFromRequest 一致） */
    private fun inferQuerySourceFromRequest(request: JsonObject?): String? {
        val messages = request?.get("messages")?.let { arr(it) } ?: return null
        val first = messages.firstOrNull()?.let { obj(it) } ?: return null
        if (str(first["role"]) != "system") return null
        val content = str(first["content"]) ?: return null
        return if (content.startsWith(SESSION_TITLE_PROMPT_PREFIX)) "session_title" else null
    }

    private fun classifyCallSource(querySource: String?, modelRole: String?): TrajectoryCallSource = when {
        querySource == "main_turn" -> TrajectoryCallSource("main", querySource)
        querySource == "subagent" -> TrajectoryCallSource("subagent", querySource)
        querySource == "compact" || modelRole == "compact" -> TrajectoryCallSource("compact", querySource)
        querySource != null -> TrajectoryCallSource("sidecar", querySource)
        modelRole == "subagent" -> TrajectoryCallSource("subagent")
        else -> TrajectoryCallSource("main")
    }

    private fun mapMessages(value: JsonElement?): List<TrajectoryMessage> {
        val array = arr(value) ?: return emptyList()
        return array.map { entry ->
            val message = obj(entry) ?: JsonObject(emptyMap())
            val role = str(message["role"]) ?: "unknown"
            TrajectoryMessage(
                role = role,
                parts = mapContent(
                    message["content"],
                    role,
                    ToolMeta(
                        toolCallId = str(message["toolCallId"]) ?: str(message["tool_call_id"])
                            ?: str(message["tool_use_id"]),
                        toolName = str(message["toolName"]) ?: str(message["name"]),
                        isError = str2(message["isError"]) == "true" || str2(message["is_error"]) == "true",
                    ),
                ),
            )
        }
    }

    private data class ToolMeta(val toolCallId: String?, val toolName: String?, val isError: Boolean)

    private fun mapContent(content: JsonElement?, role: String?, tool: ToolMeta?): List<TrajectoryPart> {
        if (content is JsonPrimitive) {
            if (content.content.isEmpty()) return emptyList()
            if (role == "tool") {
                // tool 角色消息的关联字段在消息顶层，content 只有字符串结果；
                // 不还原顶层字段 UI 就无法把返回关联到对应 tool call
                return listOf(
                    TrajectoryPart(
                        kind = "tool-result",
                        toolCallId = tool?.toolCallId,
                        toolName = tool?.toolName,
                        output = tryParseJson(content.content, tool?.isError ?: false),
                    )
                )
            }
            return listOf(TrajectoryPart(kind = "text", text = content.content))
        }
        val array = arr(content) ?: return emptyList()
        return array.map { mapPart(it, if (role == "tool") tool else null) }
    }

    private fun mapPart(rawPart: JsonElement, messageTool: ToolMeta? = null): TrajectoryPart {
        val part = obj(rawPart) ?: return TrajectoryPart(kind = "unknown", raw = rawPart)
        return when (str(part["type"])) {
            "text" -> TrajectoryPart(kind = "text", text = str(part["text"]) ?: "")
            "reasoning" -> TrajectoryPart(kind = "reasoning", text = str(part["text"]) ?: "")
            "tool-call" -> TrajectoryPart(
                kind = "tool-call",
                toolCallId = str(part["toolCallId"]),
                toolName = str(part["toolName"]) ?: "tool",
                input = part["input"] ?: part["args"],
            )
            "tool-result" -> TrajectoryPart(
                kind = "tool-result",
                toolCallId = str(part["toolCallId"]) ?: messageTool?.toolCallId,
                toolName = str(part["toolName"]) ?: messageTool?.toolName,
                output = part["output"] ?: part["result"],
            )
            "image", "file" -> TrajectoryPart(kind = "image", mediaType = str(part["mediaType"]))
            else -> TrajectoryPart(kind = "unknown", raw = rawPart)
        }
    }

    private fun mapUsage(usage: JsonObject?): TrajectoryUsage? {
        usage ?: return null
        return TrajectoryUsage(
            inputTokens = num(usage["inputTokens"]),
            outputTokens = num(usage["outputTokens"]),
            totalTokens = num(usage["totalTokens"]),
            cacheReadTokens = num(usage["cacheReadTokens"]),
            reasoningTokens = num(usage["reasoningTokens"]),
        )
    }

    /** 错误文本包成 {type:"error-text", value}（与官方 tryParseJson 一致），其余尝试 JSON.parse */
    private fun tryParseJson(value: String, isError: Boolean): JsonElement {
        if (isError) {
            return JsonObject(mapOf("type" to JsonPrimitive("error-text"), "value" to JsonPrimitive(value)))
        }
        return runCatching { json.parseToJsonElement(value) }.getOrDefault(JsonPrimitive(value))
    }

    private fun parseTime(value: String?): Long {
        if (value.isNullOrEmpty()) return 0L
        return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
    }

    // ---------- JSON 元素安全访问 ----------

    private fun str(e: JsonElement?): String? =
        (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** 含数字等非字符串原始值的字符串化（isError 布尔等） */
    private fun str2(e: JsonElement?): String? =
        (e as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun obj(e: JsonElement?): JsonObject? = e as? JsonObject

    private fun arr(e: JsonElement?): JsonArray? = e as? JsonArray

    private fun num(e: JsonElement?): Long? =
        (e as? JsonPrimitive)?.content?.toLongOrNull()
            ?: (e as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
}
