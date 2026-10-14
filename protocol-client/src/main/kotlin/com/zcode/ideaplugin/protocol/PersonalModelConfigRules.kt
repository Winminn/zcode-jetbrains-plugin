package com.zcode.ideaplugin.protocol

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * `~/.zcode/v2/provider_config.json` 个人精确模型规则读侧（contextWindow + 输入能力位）。
 *
 * 对齐官方 ModelConfigRules.composeEffective（packages/provider/src/config/model-config.ts）：
 * 生效规则链 = 内置目录全部规则（正则链，含 `.*`=200K 兜底，见 [BuiltinModelCatalog]）
 * 在前，个人精确规则追加在后按序 overlay、后值逐叶子覆盖。个人精确规则分两组：
 * `providerModelRules`（智能/系统写入）与 `manualProviderModelRules`（手动规则，
 * 可编辑叶子齐全）；官方 schema 强制同一 (providerId, modelId) 两组互斥，
 * 条目结构同构（config.properties 路径一致），这里按官方 collect 序 provider 先、
 * manual 后依次解析，同 key 后值覆盖。
 *
 * 全程 fail-soft：文件缺失/损坏/单条畸形一律跳过，调用方按"个人层无声明"降级到
 * 内置目录链。
 */
object PersonalModelConfigRules {

    /** 单条精确规则的稀疏投影：null = 该规则未声明此叶子，overlay 时不得覆盖目录值。
     *  enabled 读条目 config 顶层（非 properties 内）：null/true=启用，仅显式 false=停用 */
    data class ModelRule(
        val providerId: String,
        val modelId: String,
        val contextWindow: Long?,
        val supportsImage: Boolean?,
        val supportsVideo: Boolean?,
        val supportsPdf: Boolean?,
        val enabled: Boolean?,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析个人配置；非 regular file / 解析失败返回空表 */
    fun load(path: Path): List<ModelRule> {
        if (!Files.isRegularFile(path)) return emptyList()
        return try {
            val root = json.parseToJsonElement(path.toFile().readText()).jsonObject
            val groups = root["config"]?.jsonObject?.get("modelConfigRules")?.jsonObject
                ?: return emptyList()
            buildList {
                (groups["providerModelRules"] as? JsonArray)?.forEach { addParsed(it) }
                (groups["manualProviderModelRules"] as? JsonArray)?.forEach { addParsed(it) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun MutableList<ModelRule>.addParsed(element: kotlinx.serialization.json.JsonElement) {
        val o = element as? JsonObject ?: return
        val pid = o["providerId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return
        val mid = o["modelId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return
        val cfg = o["config"]?.jsonObject
        val props = cfg?.get("properties")?.jsonObject
        val input = props?.get("inputFormat")?.jsonObject
        // 与内置目录读侧同口径：JSON 布尔与 "true"/"false" 串都接受
        fun inputFlag(key: String) =
            input?.get(key)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
        // contextWindow 官方落 number；容忍字符串数字，其他形态（对象/布尔）视为未声明
        val ctx = props?.get("contextWindow")?.jsonPrimitive?.longOrNull
        add(
            ModelRule(
                providerId = pid,
                modelId = mid,
                contextWindow = ctx,
                supportsImage = inputFlag("supportsImage"),
                supportsVideo = inputFlag("supportsVideo"),
                supportsPdf = inputFlag("supportsPdf"),
                enabled = cfg?.get("enabled")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
            ),
        )
    }
}
