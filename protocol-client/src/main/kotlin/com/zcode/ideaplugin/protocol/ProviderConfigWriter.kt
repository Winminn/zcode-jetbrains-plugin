package com.zcode.ideaplugin.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * config.json provider 注册表的写通道（自定义渠道增删改与启停切换共用）。
 *
 * 持久层事实（2026-09-16 zcode.cjs 逆向，design-research/自定义模型渠道CRUD实现方案）：
 * workspace/upsertModelProvider 等 RPC 写系方法只改 app-server workspace 内存态不落盘，
 * 跨重启持久的唯一途径是写 `~/.zcode/v2/config.json` 的 provider 节点。文件端命名与
 * RPC schema 是两套（文件 name/options.baseURL/limit.context/modalities.input ↔ RPC
 * label/baseURL/contextWindow/supportsImages），本对象统一按**文件端命名**构造——与
 * Zcode 客户端写的自定义渠道同形态（实拍 DeepSeek/千帆/千问条目），客户端可直接读改。
 *
 * 内置渠道（`builtin:` 前缀）的启停与本体以客户端配置为唯一写者，本对象调用方须
 * 自行拒绝；写回纪律：进程内单锁串行（多标签 Panel 并发处理 op，锁住「读文件→
 * 内存改→原子替换」整段防后写覆盖前写）、备份 .bak → 写 tmp → 原子替换、失败从
 * 备份回滚、根节点其余键与 provider 兄弟节点 LinkedHashMap 保序原样保留。与
 * Zcode 官方客户端的跨进程并发无法加锁，靠 tmp+原子替换把窗口压到毫秒级兜底。
 */
object ProviderConfigWriter {

    private val json = Json { prettyPrint = true }
    private val WRITE_LOCK = Any()

    /** 滚动备份代数（<name>.bak.1 最新 ~ <name>.bak.N 最老）*/
    private const val BAK_GENERATIONS = 5

    /** 渠道支持的协议形态（表单二选一；客户端添加第三方一律 anthropic 兼容端点）*/
    val KINDS = setOf("anthropic", "openai-compatible")

    /**
     * 模型草稿（context 必填：autocompact 阈值依赖 limit.context，虚报会误触发压缩）。
     * 输入类型位对齐 Zcode 客户端编辑弹窗（文本恒选不落草稿；modalities.input 顺序
     * text→image→video→pdf，与客户端写入形态一致）；输出恒为文本（客户端锁定）。
     */
    data class ModelDraft(
        val modelId: String,
        val name: String? = null,
        val context: Long,
        val output: Long? = null,
        val supportsImages: Boolean = false,
        val supportsVideo: Boolean = false,
        val supportsPdf: Boolean = false,
    )

    /**
     * 渠道草稿。apiKey 三态：null=编辑时保持不变；""=清除；非空=写入该值
     * （添加时由调用方保证非 null）。
     */
    data class ProviderDraft(
        val name: String,
        val kind: String,
        val baseURL: String,
        val apiKey: String?,
        val models: List<ModelDraft>,
    )

    /** 草稿校验，返回首个错误文案（null=通过） */
    fun validateDraft(draft: ProviderDraft): String? {
        if (draft.name.isBlank()) return "渠道名称不能为空"
        if (draft.kind !in KINDS) return "不支持的协议类型: ${draft.kind}"
        val url = draft.baseURL.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "baseURL 须以 http(s):// 开头"
        if (draft.models.isEmpty()) return "至少配置一个模型"
        if (draft.models.any { it.modelId.isBlank() }) return "模型 ID 不能为空"
        if (draft.models.map { it.modelId.trim() }.distinct().size != draft.models.size) return "模型 ID 重复"
        if (draft.models.any { it.context <= 0 }) return "上下文窗口须为正整数"
        draft.models.firstOrNull { it.output != null && it.output!! <= 0 }?.let { return "最大输出须为正整数" }
        return null
    }

    /** 构造整 provider 节点（添加用；文件端命名，客户端同款形态） */
    fun buildProviderNode(draft: ProviderDraft): JsonObject = buildJsonObject {
        put("name", draft.name.trim())
        put("kind", draft.kind)
        put("source", "custom")
        put("enabled", true)
        put("options", buildJsonObject {
            // 空 key 也保留 apiKeyRequired（客户端同款）：第三方渠道凭证必填，明文与客户端一致
            draft.apiKey?.takeIf { it.isNotBlank() }?.let { put("apiKey", it) }
            put("apiKeyRequired", true)
            put("baseURL", draft.baseURL.trim())
        })
        put("models", buildModelsNode(draft.models))
    }

    /**
     * 就地合并（编辑用）：null/缺省字段保留原值，models 传了整表替换，enabled 独立可切。
     * 保留原节点中插件表单不覆盖的字段（如客户端写入的 reasoning/zcode 管理元数据——
     * models 整表替换时草稿行不含这些字段，等价于重建，客户端模型条目由此回归默认）。
     */
    fun mergeProviderNode(
        existing: JsonObject,
        name: String?,
        kind: String?,
        baseURL: String?,
        apiKey: String?,
        models: List<ModelDraft>?,
        enabled: Boolean?,
    ): JsonObject {
        val oldOptions = existing["options"]?.let { it as? JsonObject } ?: JsonObject(emptyMap())
        return buildJsonObject {
            existing.forEach { (k, v) -> if (k != "options" && k != "models") put(k, v) }
            name?.takeIf { it.isNotBlank() }?.let { put("name", it.trim()) }
            kind?.takeIf { it in KINDS }?.let { put("kind", it) }
            enabled?.let { put("enabled", it) }
            put("options", buildJsonObject {
                oldOptions.forEach { (k, v) -> if (k != "apiKey" && k != "baseURL") put(k, v) }
                when {
                    apiKey == null -> oldOptions["apiKey"]?.let { put("apiKey", it) }
                    apiKey.isBlank() -> Unit // 清除：不写该字段
                    else -> put("apiKey", apiKey)
                }
                val oldBaseURL = (oldOptions["baseURL"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                (baseURL?.trim()?.takeIf { it.isNotEmpty() } ?: oldBaseURL)?.let { put("baseURL", it) }
            })
            put("models", models?.let { buildModelsNode(it) } ?: (existing["models"] ?: JsonObject(emptyMap())))
        }
    }

    /** 模型草稿列表 → 文件端 models map（数字 limit，与客户端写入形态一致） */
    fun buildModelsNode(models: List<ModelDraft>): JsonObject = JsonObject(
        linkedMapOf<String, JsonElement>().apply {
            models.forEach { m ->
                put(m.modelId.trim(), buildJsonObject {
                    m.name?.takeIf { it.isNotBlank() }?.let { put("name", it.trim()) }
                    put("limit", buildJsonObject {
                        put("context", m.context)
                        m.output?.let { put("output", it) }
                    })
                    put("modalities", buildJsonObject {
                        put("input", JsonArray(
                            buildList {
                                add(JsonPrimitive("text"))
                                if (m.supportsImages) add(JsonPrimitive("image"))
                                if (m.supportsVideo) add(JsonPrimitive("video"))
                                if (m.supportsPdf) add(JsonPrimitive("pdf"))
                            }
                        ))
                        put("output", JsonArray(listOf(JsonPrimitive("text"))))
                    })
                })
            }
        }
    )

    /**
     * 读-改-写 provider 注册表（唯一写通道）。mutator 在锁内拿到刚读的 provider 根节点，
     * 返回新根节点（业务拒绝抛 IllegalStateException，message 作错误文案）。
     *
     * 备份策略：写回前快照滚动保存 `<name>.bak.1`~`<name>.bak.5`（bak.1 最新，logrotate
     * 式移位）——单代备份会被后续写覆盖，2026-09-17 DeepSeek key 污染事件里完好 key
     * 就这样丢的；5 代把可回溯窗口拉开。旧单代 `<name>.bak` 首次自动迁移为 bak.1。
     *
     * @return null=成功；非空=错误文案（含解析失败/写回失败回滚）
     */
    fun update(configPath: Path, mutator: (providers: JsonObject) -> JsonObject): String? =
        synchronized(WRITE_LOCK) {
            if (!Files.isRegularFile(configPath)) return@synchronized "config.json 不存在: $configPath"
            val root = try {
                Json.parseToJsonElement(configPath.toFile().readText(Charsets.UTF_8)).jsonObject
            } catch (e: Exception) {
                return@synchronized "解析 config.json 失败: ${e.message}"
            }
            val providers = root["provider"] as? JsonObject
                ?: return@synchronized "config.json 缺少 provider 注册表"
            val newProviders = try {
                mutator(providers)
            } catch (e: IllegalStateException) {
                return@synchronized (e.message ?: "操作失败")
            }
            if (newProviders === providers) return@synchronized null
            val newRoot = buildJsonObject {
                root.forEach { (k, v) -> put(k, if (k == "provider") newProviders else v) }
            }
            try {
                val bak1 = rotateBackups(configPath)
                Files.copy(configPath, bak1, StandardCopyOption.REPLACE_EXISTING)
                val tmp = configPath.resolveSibling(configPath.fileName.toString() + ".tmp")
                tmp.toFile().writeText(json.encodeToString(JsonObject.serializer(), newRoot), Charsets.UTF_8)
                try {
                    Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) {
                    // 回滚用 copy 保留 bak.1（move 会把最新一代消耗掉）
                    Files.copy(bak1, configPath, StandardCopyOption.REPLACE_EXISTING)
                    throw e
                }
                null
            } catch (e: Exception) {
                "写回失败: ${e.message}"
            }
        }

    /** 滚动备份链：删最老一代 → 逐代后移 → 返回新的 bak.1 路径（调用方随后写入快照）。
     *  移位失败尽力而为（旧代有洞不阻断写回）；旧单代 .bak 存在时迁移为 bak.1。 */
    private fun rotateBackups(configPath: Path): Path {
        val name = configPath.fileName.toString()
        val bak1 = configPath.resolveSibling("$name.bak.1")
        // 旧单代备份迁移（单代时代遗物，保住既有快照不静默丢弃）
        val legacyBak = configPath.resolveSibling("$name.bak")
        if (Files.isRegularFile(legacyBak) && !Files.isRegularFile(bak1)) {
            runCatching { Files.move(legacyBak, bak1) }
        }
        return runCatching {
            Files.deleteIfExists(configPath.resolveSibling("$name.bak.$BAK_GENERATIONS"))
            for (i in BAK_GENERATIONS - 1 downTo 1) {
                val from = configPath.resolveSibling("$name.bak.$i")
                if (Files.isRegularFile(from)) {
                    Files.move(from, configPath.resolveSibling("$name.bak.${i + 1}"), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            bak1
        }.getOrDefault(bak1)
    }

    // ============ 前端消息解析（webview 请求形状 {op, providerId?, draft{...}}，
    // 字段全在 draft 内——2026-09-16 缺陷：首版 handler 从消息顶层读全为 null，
    // 编辑保存"成功"实则零变更。抽到本对象以便单测消息形状）============

    /** modelAddProvider 消息 → 添加草稿（字段缺失给非法值，[validateDraft] 兜底报错）*/
    fun addDraftFromMessage(msg: JsonObject): ProviderDraft {
        val d = msg["draft"] as? JsonObject ?: JsonObject(emptyMap())
        return ProviderDraft(
            name = d.str("name") ?: "",
            kind = d.str("kind") ?: "anthropic",
            baseURL = d.str("baseURL") ?: "",
            apiKey = d.str("apiKey") ?: "",
            models = modelDraftsOf(d),
        )
    }

    /** modelUpdateProvider 消息 → 编辑字段（null=该字段不变；apiKey 三态同 merge）*/
    data class UpdateFields(
        val name: String?,
        val kind: String?,
        val baseURL: String?,
        val apiKey: String?,
        val models: List<ModelDraft>?,
        val enabled: Boolean?,
    )

    fun updateFieldsFromMessage(msg: JsonObject): UpdateFields {
        val d = msg["draft"] as? JsonObject
        return UpdateFields(
            name = d?.str("name")?.takeIf { it.isNotBlank() }?.trim(),
            kind = d?.str("kind"),
            baseURL = d?.str("baseURL")?.takeIf { it.isNotBlank() }?.trim(),
            // 无键 / JSON null（前端显式传 null 表示"不动该字段"）/ 字面量 "null"（JsonNull
            // .content 即该串，历史污染值回传）= 不变；空串 = 清除；其余 = 新值
            apiKey = d?.let { dg ->
                val raw = dg["apiKey"]
                val s = if (raw is JsonNull) null else (raw as? JsonPrimitive)?.content
                when {
                    s == null -> null
                    s.isBlank() -> ""
                    s == "null" -> null
                    else -> s
                }
            },
            models = d?.takeIf { it.containsKey("models") }?.let { modelDraftsOf(it) },
            enabled = msg.str("enabled")?.toBooleanStrictOrNull(),
        )
    }

    /** draft.models 数组条目 {modelId, label?, context, output?, images?} → 草稿行（空/畸形条目丢弃）*/
    private fun modelDraftsOf(draft: JsonObject): List<ModelDraft> {
        val arr = draft["models"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val mid = o.str("modelId")?.trim().takeIf { !it.isNullOrEmpty() } ?: return@mapNotNull null
            ModelDraft(
                modelId = mid,
                name = o.str("label")?.takeIf { it.isNotBlank() },
                context = o.str("context")?.toLongOrNull() ?: 0L,
                output = o.str("output")?.toLongOrNull(),
                supportsImages = o.str("images") == "true",
                supportsVideo = o.str("video") == "true",
                supportsPdf = o.str("pdf") == "true",
            )
        }
    }

    /** 取字符串字段：JsonNull / 字面量 "null" 统一按缺失处理（CLI 生态历史陷阱，
     *  同 RuntimeModels.jsonStringOrNull 口径——JsonNull.content 就是字符串 "null"）*/
    private fun JsonObject.str(key: String): String? {
        val v = this[key] ?: return null
        if (v is JsonNull) return null
        return (v as? JsonPrimitive)?.content?.takeIf { it != "null" }
    }
}
