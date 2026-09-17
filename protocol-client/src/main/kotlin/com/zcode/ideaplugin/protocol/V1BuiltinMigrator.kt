package com.zcode.ideaplugin.protocol

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * v1 内置渠道 → v2 provider_config.json 的兜底迁移（2026-09-17 用户需求）。
 *
 * 背景：v1→v2 换代后渠道源从 config.json 换成 provider_config.json，老用户在 v1 里
 * 配好的内置渠道（BigModel/Z.ai Coding Plan 订阅、BigModel/Z.ai API Key 手填型）若在
 * v2 里没重建，key 就成了死配置——模型下拉、额度查询全部无渠道可用。本迁移在插件
 * 启动时兜底：NEW 代 + provider_config.json 无对应模板渠道 + config.json 里该渠道
 * 带明文 apiKey → 按模板形态新建 rule（providerId == templateId、access/api 取模板、
 * personalModelIds 预填模板模型清单，与客户端建渠道实拍形态一致）。
 *
 * 边界（全部 fail-soft，返回空表 = 无动作）：
 * - 仅 NEW 代执行（OLD 代 config.json 就是现役渠道源，无需迁移）；
 * - v1 config.json / v2 provider_config.json 任一缺失不执行（后者缺失 = 客户端没跑过，
 *   插件不代建官方文件）；
 * - 只迁 options.apiKey 非空的渠道（订阅渠道 key 由客户端 oauth 后自动落盘；体验套餐
 *   builtin:bigmodel-start-plan 滑块门控，v2 无对应模板，不在映射表）；
 * - v1 条目 enabled:false 不迁；目标 templateId/providerId 在 v2 已存在不迁（用户已
 *   自行重建或客户端已迁移）。
 *
 * 写侧复用 [ProviderConfigWriterV2.addProvider]（单锁 + 滚动备份 + 原子替换）；
 * 「迁移过又删掉不再复迁」由调用方（插件启动钩子）用一次性标记保证——本对象按文件
 * 现状判断，保持纯函数性便于单测。
 */
object V1BuiltinMigrator {

    /** v1 内置渠道 id → v2 模板 id（模板定义挖自 zcode-builtin.json templateRules） */
    val V1_TO_V2_TEMPLATE: Map<String, String> = mapOf(
        "builtin:bigmodel-coding-plan" to "bigmodel-api",
        "builtin:zai-coding-plan" to "zai-api",
        "builtin:bigmodel" to "bigmodel-standard-api",
        "builtin:zai" to "zai-standard-api",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 执行兜底迁移。
     * @param zcodePath CLI 路径（判代主判据；null 时落配置文件兜底判代）
     * @param skipTemplateIds 调用方指定永不迁的 templateId（「迁过又被用户删除」防复活
     *   标记——必须在写入前过滤，事后过滤写回已发生）
     * @return 本次新迁入的 v2 templateId 列表（空 = 无动作；调用方据此打标记/通知）
     */
    fun migrateIfNeeded(
        zcodePath: Path?,
        configPath: Path = Credentials.defaultConfigPath(),
        providerConfigPath: Path = Credentials.personalProviderConfigPath(),
        home: String = System.getProperty("user.home") ?: ".",
        skipTemplateIds: Set<String> = emptySet(),
    ): List<String> {
        // ① 仅 NEW 代（zcode.cjs 可读走主判，否则配置兜底；OLD 代 config.json 现役无需迁）
        val generation = if (zcodePath != null) ProtocolGenerations.detect(zcodePath, home)
        else ProtocolGenerations.detectByConfig(home)
        if (generation != ProtocolGeneration.NEW) return emptyList()
        // ② v1 config.json 存在且有 provider 注册表
        if (!Files.isRegularFile(configPath)) return emptyList()
        val v1Providers = try {
            Json.parseToJsonElement(configPath.toFile().readText(Charsets.UTF_8)).jsonObject["provider"]?.jsonObject
                ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        // ③ v2 provider_config.json 存在（客户端至少跑过一次；缺失不代建官方文件）
        if (!Files.isRegularFile(providerConfigPath)) return emptyList()
        val existingRules: List<JsonObject> = try {
            val root = Json.parseToJsonElement(providerConfigPath.toFile().readText(Charsets.UTF_8)).jsonObject
            val arr = root["config"]?.jsonObject?.get("providerConfigRules")?.jsonObject
                ?.get("providerRules") as? kotlinx.serialization.json.JsonArray
                ?: return emptyList()
            arr.filterIsInstance<JsonObject>()
        } catch (_: Exception) {
            return emptyList()
        }
        val existingTemplateIds = existingRules.mapNotNull { strOf(it, "templateId") }.toSet()
        val existingProviderIds = existingRules.mapNotNull { strOf(it, "providerId") }.toSet()

        val migrated = mutableListOf<String>()
        for ((v1Id, templateId) in V1_TO_V2_TEMPLATE) {
            if (templateId in skipTemplateIds) continue
            if (templateId in existingTemplateIds || templateId in existingProviderIds) continue
            val entry = v1Providers[v1Id] as? JsonObject ?: continue
            // enabled 显式 false 不迁（用户在 v1 里停用的渠道不复活）
            if (entry["enabled"]?.jsonPrimitive?.contentOrNull == "false") continue
            val apiKey = entry["options"]?.jsonObject?.get("apiKey")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: continue
            // 模板表缺失（目录不可读/模板下架）跳过该渠道：建出的残缺 rule registry 不认
            val tpl = BuiltinModelCatalog.templateChannel(templateId, zcodePath, home) ?: continue
            val draft = ProviderConfigWriter.ProviderDraft(
                name = tpl.name,
                kind = "anthropic", // 仅占位：api.type 由模板值覆盖（apiTypeOverride）
                baseURL = tpl.baseUrl,
                apiKey = apiKey,
                models = emptyList(),
            )
            val (err, _) = ProviderConfigWriterV2.addProvider(
                providerConfigPath, draft,
                templateId = templateId,
                accessType = tpl.accessType,
                apiTypeOverride = tpl.apiType,
                providerIdOverride = templateId,
                prefillModelIds = tpl.builtinModelIds,
                orderAtTop = true, // 内置渠道默认置顶（用户可后续拖拽调整）
            )
            if (err == null) migrated.add(templateId)
        }
        return migrated
    }

    private fun strOf(obj: JsonObject, key: String): String? {
        val v = obj[key] ?: return null
        if (v is kotlinx.serialization.json.JsonNull) return null
        return (v as? JsonPrimitive)?.content?.takeIf { it != "null" }
    }
}
