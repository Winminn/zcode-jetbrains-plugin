package com.zcode.ideaplugin.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * provider_config.json（新版 CLI 个人渠道注册表）的写通道——v2 双代适配的 CRUD 落盘点。
 *
 * 持久层事实（2026-09-17 zcode.cjs 逆向 + 本机实验 diag-v2-crud-validate.py）：
 * - 新版无 upsertModelProvider 等写系 RPC，跨重启持久的唯一途径是直接写
 *   `~/.zcode/v2/provider_config.json`；运行中的 app-server watch 该文件，外部写 ≤2s 热加载。
 * - rule 形态：`{providerId, templateId?, providerName, enabled?, config:{group,
 *   access:{type, apiKey}, api:{type, baseUrl}, personalModelIds, modelOrder}}`。
 *   templateId 可省（客户端"新供应商"空白模板即无 templateId，实验 A 验证 registry 接受）；
 *   api.type 取值 anthropic-messages / openai-chat-completions / openai-responses
 *   （zcode-builtin.json templateRules 实挖）。
 * - enabled:false 的渠道被 registry 整体排除（实验 B：setModel 报 Provider Registry 中
 *   不存在），启停写该字段即生效。
 * - providerId 由客户端按 name slug 化生成（冲突加 -N，hSo/mSo 逆向），本写入器同款，
 *   保证插件建的渠道与客户端建的形态一致、客户端 UI 可正常读改。
 * - 模型级 contextWindow 与输入能力位（inputFormat.supportsImage/Video/Pdf）存
 *   modelConfigRules.providerModelRules（读侧 newCliContextWindows）；插件只托管
 *   这四键，旧条目其余键合并保留。
 *
 * 写回纪律同 [ProviderConfigWriter]：进程内单锁串行、滚动备份 .bak.1~.bak.5、tmp + 原子
 * 替换、失败回滚；根节点其余键（schemaVersion/providerOrder/manualProviderModelRules…）
 * LinkedHashMap 保序原样保留。与客户端的跨进程并发靠原子替换兜底（watch 热加载天然幂等）。
 */
object ProviderConfigWriterV2 {

    private val json = Json { prettyPrint = true }
    private val WRITE_LOCK = Any()
    private const val BAK_GENERATIONS = 5

    /** 前端 kind（anthropic / openai-compatible）→ v2 api.type */
    fun apiTypeOf(kind: String): String =
        if (kind == "openai-compatible") "openai-chat-completions" else "anthropic-messages"

    /** v2 api.type → 前端 kind（openai-responses 归 openai-compatible 档）*/
    fun kindOfApiType(apiType: String?): String =
        if (apiType == null || apiType.startsWith("anthropic")) "anthropic" else "openai-compatible"

    /**
     * 插件新建渠道的 providerId：randomUUID（v1 CRUD 同款形态）。
     *
     * id 是写进 provider_config.json 的自由字符串，registry 只要求非空（不校验格式），
     * 插件侧完全可自指定。不用客户端 slug（"百度千帆"→ new-provider，多渠道变
     * new-provider-2/3 不可读）；UUID 永不重名、与渠道名无关、改名不用改 id（客户端
     * 编辑同样不动 id）。客户端对插件建的 UUID 渠道正常显示编辑（v1 迁移渠道同形态实证）。
     */
    fun newProviderId(): String = java.util.UUID.randomUUID().toString()

    // ============ CRUD 入口（err null = 成功；update 增删返回 providerId）============

    /**
     * 添加渠道（默认无 templateId 自定义形态，实验 A 验证 registry 接受）。
     * 模板渠道形态（v1 内置渠道兜底迁移用）：templateId 非空时 rule 落 templateId、
     * access.type 取模板值（如 zhipu-coding-plan-api-key）、api.type 可显式指定、
     * providerId 用模板 id（客户端同款：模板渠道 providerId == templateId）、
     * draft.models 为空时 personalModelIds/modelOrder 预填模板 builtinModelIds
     * （与客户端实拍建渠道形态一致，插件模型清单 UI 也有数据源）。
     *
     * @return (err, providerId)
     */
    fun addProvider(
        path: Path,
        draft: ProviderConfigWriter.ProviderDraft,
        templateId: String? = null,
        accessType: String = "api-key",
        apiTypeOverride: String? = null,
        providerIdOverride: String? = null,
        prefillModelIds: List<String> = emptyList(),
        orderAtTop: Boolean = false,
    ): Pair<String?, String> =
        synchronized(WRITE_LOCK) {
            val providerId = providerIdOverride ?: newProviderId()
            val err = updateLocked(path) { root ->
                val cfg = root["config"]?.jsonObject ?: throw IllegalStateException("缺少 config 节")
                val rulesArr = cfg["providerConfigRules"]?.jsonObject?.get("providerRules")?.let { it as? JsonArray }
                    ?: throw IllegalStateException("缺少 providerRules")
                if (rulesArr.any { (it as? JsonObject)?.str("providerId") == providerId }) {
                    throw IllegalStateException("渠道已存在: $providerId")
                }
                val newRule = buildJsonObject {
                    templateId?.let { put("templateId", it) }
                    put("providerId", providerId)
                    put("providerName", draft.name.trim())
                    put("enabled", true)
                    put("config", buildRuleConfig(draft, accessType, apiTypeOverride, prefillModelIds))
                }
                rewriteRoot(
                    root, JsonArray(rulesArr + newRule),
                    modelsOf(draft.models, providerId), providerId,
                    orderAdd = providerId, orderAtTop = orderAtTop,
                )
            }
            err to providerId
        }

    /**
     * 编辑渠道（就地合并，providerId 不动——客户端编辑同样不改 id）：name/kind/baseURL/
     * enabled 缺省不变；apiKey 三态（null 不变、空串清除、非空覆盖）；models 传了整表
     * 替换（personalModelIds + modelOrder + providerModelRules 同步重建）。
     */
    fun updateProvider(path: Path, providerId: String, f: ProviderConfigWriter.UpdateFields): String? =
        synchronized(WRITE_LOCK) {
            updateLocked(path) { root ->
                val rules = rulesOf(root) ?: throw IllegalStateException("缺少 providerRules")
                val idx = rules.indexOfFirst { it.str("providerId") == providerId }
                if (idx < 0) throw IllegalStateException("渠道不存在: $providerId")
                val old = rules[idx]
                val oldCfg = old["config"]?.jsonObject ?: JsonObject(emptyMap())
                val newRule = buildJsonObject {
                    old.forEach { (k, v) -> if (k != "config" && k != "providerName" && k != "enabled") put(k, v) }
                    f.name?.let { put("providerName", it) }
                    f.enabled?.let { put("enabled", it) }
                    put("config", mergeRuleConfig(oldCfg, f))
                }
                rewriteRoot(
                    root,
                    JsonArray(rules.mapIndexed { i, r -> if (i == idx) newRule else r }),
                    f.models?.let { modelsOf(it, providerId) },
                    providerId,
                )
            }
        }

    /** 删除渠道（rule + providerOrder + providerModelRules 三处同步清理）*/
    fun removeProvider(path: Path, providerId: String): String? =
        synchronized(WRITE_LOCK) {
            updateLocked(path) { root ->
                val rules = rulesOf(root) ?: throw IllegalStateException("缺少 providerRules")
                if (rules.none { it.str("providerId") == providerId }) {
                    throw IllegalStateException("渠道不存在: $providerId")
                }
                rewriteRoot(
                    root,
                    JsonArray(rules.filter { it.str("providerId") != providerId }),
                    emptyList(), providerId, orderRemove = providerId,
                )
            }
        }

    /** 启停渠道（rule.enabled；false 时 registry 整体排除该渠道，实验 B 验证）*/
    fun toggleProvider(path: Path, providerId: String, enabled: Boolean): String? =
        synchronized(WRITE_LOCK) {
            updateLocked(path) { root ->
                val rules = rulesOf(root) ?: throw IllegalStateException("缺少 providerRules")
                val idx = rules.indexOfFirst { it.str("providerId") == providerId }
                if (idx < 0) throw IllegalStateException("渠道不存在: $providerId")
                val updated = rules[idx].let { old ->
                    buildJsonObject {
                        old.forEach { (k, v) -> if (k != "enabled") put(k, v) }
                        put("enabled", enabled)
                    }
                }
                rewriteRoot(root, JsonArray(rules.mapIndexed { i, r -> if (i == idx) updated else r }), null, null)
            }
        }

    /**
     * 渠道排序（客户端展示序语义）：ids = 完整目标顺序。写 providerOrder 单键，rules
     * 本体不动（排序只影响客户端 UI 与插件展示序，registry 解析无关顺序）。未列出的
     * 渠道保持原相对次序排在 ids 之后（拖拽保存时前端应传全集，此处是防御）。
     */
    fun reorderProviders(path: Path, ids: List<String>): String? =
        synchronized(WRITE_LOCK) {
            updateLocked(path) { root ->
                val existing = rulesOf(root)?.mapNotNull { it.str("providerId") } ?: emptyList()
                val unknown = ids.filter { it !in existing.toSet() }
                if (unknown.isNotEmpty()) throw IllegalStateException("未知渠道: ${unknown.first()}")
                val tail = existing.filter { it !in ids.toSet() }
                rewriteRoot(root, null, null, null, newOrder = ids + tail)
            }
        }

    // ============ 节点构造 ============

    /** 添加用的完整 config 节（access + api + personalModelIds + modelOrder）。
     *  accessType/apiTypeOverride 供模板渠道用（订阅套餐 access.type 非 api-key）；
     *  prefillModelIds 在 draft.models 为空时预填 personalModelIds/modelOrder（模板渠道）。 */
    private fun buildRuleConfig(
        draft: ProviderConfigWriter.ProviderDraft,
        accessType: String = "api-key",
        apiTypeOverride: String? = null,
        prefillModelIds: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("group", "standard-personal")
        put("access", buildJsonObject {
            put("type", accessType)
            put("apiKey", draft.apiKey.orEmpty())
        })
        put("api", buildJsonObject {
            put("type", apiTypeOverride ?: apiTypeOf(draft.kind))
            put("baseUrl", draft.baseURL.trim())
        })
        val mids = draft.models.map { it.modelId.trim() }.ifEmpty { prefillModelIds.map { it.trim() } }
        put("personalModelIds", JsonArray(mids.map { JsonPrimitive(it) }))
        put("modelOrder", JsonArray(mids.map { JsonPrimitive(it) }))
    }

    /** 编辑的就地合并：access.apiKey 三态、api.type/baseUrl 覆盖（保留 headers 等既有键）、
     *  personalModelIds/modelOrder 仅在 models 传入时整表替换 */
    private fun mergeRuleConfig(old: JsonObject, f: ProviderConfigWriter.UpdateFields): JsonObject = buildJsonObject {
        old.forEach { (k, v) -> if (k != "access" && k != "api" && k != "personalModelIds" && k != "modelOrder") put(k, v) }
        val oldAccess = old["access"]?.jsonObject ?: JsonObject(emptyMap())
        put("access", buildJsonObject {
            oldAccess.forEach { (k, v) -> if (k != "apiKey") put(k, v) }
            when {
                f.apiKey == null -> oldAccess["apiKey"]?.let { put("apiKey", it) }
                f.apiKey.isBlank() -> put("apiKey", "")
                else -> put("apiKey", f.apiKey)
            }
        })
        val oldApi = old["api"]?.jsonObject
        val newType = f.kind?.let { apiTypeOf(it) }
        val newUrl = f.baseURL?.trim()?.takeIf { it.isNotEmpty() }
        if (oldApi != null || newType != null || newUrl != null) {
            put("api", buildJsonObject {
                oldApi?.forEach { (k, v) -> if (k != "type" && k != "baseUrl") put(k, v) }
                put("type", newType ?: (oldApi?.get("type") as? JsonPrimitive)?.content ?: "anthropic-messages")
                put("baseUrl", newUrl ?: (oldApi?.get("baseUrl") as? JsonPrimitive)?.content ?: "")
            })
        }
        if (f.models != null) {
            val mids = f.models.map { it.modelId.trim() }
            put("personalModelIds", JsonArray(mids.map { JsonPrimitive(it) }))
            put("modelOrder", JsonArray(mids.map { JsonPrimitive(it) }))
        }
    }

    /**
     * 模型级 rules（providerModelRules 条目；读侧 newCliContextWindows 同源）。
     * 能力位落 properties.inputFormat（2026-09-17 zcode.cjs 逆向：providerModelRules
     * config schema JJn 的 inputFormat = 完整五键形状 partial 化 strict，接受
     * supportsImage/supportsVideo/supportsPdf 子集；registry 构建模型时对命中条目
     * .overlay(o.config)，模板渠道模型同样生效——图片/视频/PDF 附件校验即读此值）。
     */
    private fun modelsOf(
        models: List<ProviderConfigWriter.ModelDraft>,
        providerId: String,
    ): List<JsonObject> = models.map { m ->
        buildJsonObject {
            put("modelId", m.modelId.trim())
            put("providerId", providerId)
            put("config", buildJsonObject {
                put("properties", buildJsonObject {
                    put("contextWindow", m.context)
                    put("inputFormat", buildJsonObject {
                        put("supportsImage", m.supportsImages)
                        put("supportsVideo", m.supportsVideo)
                        put("supportsPdf", m.supportsPdf)
                    })
                })
            })
        }
    }

    /** 插件托管的 properties 键（其余键合并时从旧条目原样保留） */
    private val MANAGED_PROPS = setOf("contextWindow", "inputFormat")
    /** 插件托管的 inputFormat 键（supportsText/supportsAudio 等不托管，保留旧值） */
    private val MANAGED_INPUT = setOf("supportsImage", "supportsVideo", "supportsPdf")

    /**
     * 新条目合并旧条目中插件不托管的键：properties 层保留 inputFormat 之外的自定义键
     * （如 supportsJsonSchemaOutput），inputFormat 层保留三能力位之外的键
     * （如 supportsText/supportsAudio——手写或客户端写入的不被插件编辑洗掉）。
     */
    private fun mergeUnmanaged(newRule: JsonObject, oldRule: JsonObject?): JsonObject {
        val oldProps = oldRule?.get("config")?.jsonObject?.get("properties")?.jsonObject ?: return newRule
        val newCfg = newRule["config"]?.jsonObject ?: return newRule
        val newProps = newCfg["properties"]?.jsonObject ?: return newRule
        val mergedProps = LinkedHashMap<String, JsonElement>()
        newProps.forEach { (k, v) -> mergedProps[k] = v }
        oldProps.forEach { (k, v) -> if (k !in MANAGED_PROPS && k !in mergedProps) mergedProps[k] = v }
        val oldInput = oldProps["inputFormat"]?.jsonObject
        val mergedRule: JsonObject
        if (oldInput != null) {
            val newInput = mergedProps["inputFormat"]?.jsonObject ?: JsonObject(emptyMap())
            val mergedInput = LinkedHashMap<String, JsonElement>()
            newInput.forEach { (k, v) -> mergedInput[k] = v }
            oldInput.forEach { (k, v) -> if (k !in MANAGED_INPUT && k !in mergedInput) mergedInput[k] = v }
            mergedProps["inputFormat"] = JsonObject(mergedInput)
        }
        mergedRule = buildJsonObject {
            newRule.forEach { (k, v) -> if (k != "config") put(k, v) }
            put("config", buildJsonObject {
                newCfg.forEach { (k, v) -> if (k != "properties") put(k, v) }
                put("properties", JsonObject(mergedProps))
            })
        }
        return mergedRule
    }

    // ============ 读改写骨架 ============

    private fun rulesOf(root: JsonObject): List<JsonObject>? =
        (root["config"]?.jsonObject?.get("providerConfigRules")?.jsonObject?.get("providerRules")
            as? JsonArray)?.filterIsInstance<JsonObject>()

    /**
     * 重建根节点：替换 providerRules / providerOrder，并同步目标 provider 的
     * providerModelRules。newRules=null 不动 rules（reorder）；models=null 不动模型级
     * rules（toggle/reorder）；否则整表替换该 provider 的条目（其他 provider 保序保留）。
     * newOrder 非空时直接采用（reorder），否则按 orderAdd/orderRemove 推导。
     */
    private fun rewriteRoot(
        root: JsonObject,
        newRules: JsonArray?,
        models: List<JsonObject>?,
        targetProviderId: String?,
        orderAdd: String? = null,
        orderRemove: String? = null,
        newOrder: List<String>? = null,
        orderAtTop: Boolean = false,
    ): JsonObject {
        val cfg = root["config"]?.jsonObject ?: JsonObject(emptyMap())
        val oldOrder = (cfg["providerOrder"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        val newOrderList = newOrder ?: buildList {
            when {
                // orderAdd 已在 order 里（rule 删了 order 残留的边缘）不重复追加
                orderAdd != null && orderAtTop -> { add(orderAdd); addAll(oldOrder.filter { it != orderAdd }) }
                orderAdd != null -> { addAll(oldOrder.filter { it != orderAdd }); add(orderAdd) }
                orderRemove != null -> addAll(oldOrder.filter { it != orderRemove })
                else -> addAll(oldOrder)
            }
        }
        val oldModelRules = (cfg["modelConfigRules"]?.jsonObject?.get("providerModelRules") as? JsonArray)
            ?.filterIsInstance<JsonObject>() ?: emptyList()
        val newModelRules = if (models == null || targetProviderId == null) {
            oldModelRules
        } else {
            val oldByModel = oldModelRules
                .filter { it.str("providerId") == targetProviderId }
                .associateBy { it.str("modelId") }
            oldModelRules.filterNot { it.str("providerId") == targetProviderId } +
                models.map { m -> mergeUnmanaged(m, oldByModel[m.str("modelId")]) }
        }
        return buildJsonObject {
            root.forEach { (k, v) -> if (k != "config") put(k, v) }
            put("config", buildJsonObject {
                cfg.forEach { (k, v) ->
                    when (k) {
                        "providerOrder" -> put("providerOrder", JsonArray(newOrderList.map { JsonPrimitive(it) }))
                        "providerConfigRules" -> put(k, buildJsonObject {
                            v.jsonObject.forEach { (pk, pv) ->
                                if (pk == "providerRules" && newRules != null) put("providerRules", newRules) else put(pk, pv)
                            }
                        })
                        "modelConfigRules" -> put(k, buildJsonObject {
                            v.jsonObject.forEach { (pk, pv) ->
                                if (pk == "providerModelRules") {
                                    put("providerModelRules", JsonArray(newModelRules))
                                } else put(pk, pv)
                            }
                        })
                        else -> put(k, v)
                    }
                }
                // 原文件缺 providerOrder（老版本迁移态）时补齐
                if (!cfg.containsKey("providerOrder")) put("providerOrder", JsonArray(newOrderList.map { JsonPrimitive(it) }))
            })
        }
    }

    /** 读-改-写（调用方已持锁）：mutator 返回新根；IllegalStateException.message 作错误文案 */
    private fun updateLocked(path: Path, mutator: (JsonObject) -> JsonObject): String? {
        if (!Files.isRegularFile(path)) return "provider_config.json 不存在: $path"
        val root = try {
            Json.parseToJsonElement(path.toFile().readText(Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            return "解析 provider_config.json 失败: ${e.message}"
        }
        val newRoot = try {
            mutator(root)
        } catch (e: IllegalStateException) {
            return e.message ?: "操作失败"
        }
        if (newRoot === root) return null
        return try {
            val bak1 = rotateBackups(path)
            Files.copy(path, bak1, StandardCopyOption.REPLACE_EXISTING)
            val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
            tmp.toFile().writeText(json.encodeToString(JsonObject.serializer(), newRoot), Charsets.UTF_8)
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                Files.copy(bak1, path, StandardCopyOption.REPLACE_EXISTING)
                throw e
            }
            null
        } catch (e: Exception) {
            "写回失败: ${e.message}"
        }
    }

    /**
     * 滚动备份链（v1 [ProviderConfigWriter.rotateBackups] 同款滚动策略）。
     * 不含 v1 的旧单代 `.bak`→`.bak.1` 迁移分支：provider_config.json 是 v2 新文件，
     * 从未有单代备份遗产，该分支在此恒空转——2026-09-17 review 澄清，非遗漏。
     */
    private fun rotateBackups(path: Path): Path {
        val name = path.fileName.toString()
        val bak1 = path.resolveSibling("$name.bak.1")
        return runCatching {
            Files.deleteIfExists(path.resolveSibling("$name.bak.$BAK_GENERATIONS"))
            for (i in BAK_GENERATIONS - 1 downTo 1) {
                val from = path.resolveSibling("$name.bak.$i")
                if (Files.isRegularFile(from)) {
                    Files.move(from, path.resolveSibling("$name.bak.${i + 1}"), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            bak1
        }.getOrDefault(bak1)
    }

    /** 取字符串字段（JsonNull / "null" 按缺失，同 v1 口径）*/
    private fun JsonObject.str(key: String): String? {
        val v = this[key] ?: return null
        if (v is kotlinx.serialization.json.JsonNull) return null
        return (v as? JsonPrimitive)?.content?.takeIf { it != "null" }
    }
}
