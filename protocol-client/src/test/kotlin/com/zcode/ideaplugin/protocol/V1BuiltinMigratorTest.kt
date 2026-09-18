package com.zcode.ideaplugin.protocol

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V1BuiltinMigrator（v1 内置渠道 → v2 provider_config.json 兜底迁移）单测：
 * fake home 全套（v1 config.json + v2 provider_config.json + 最小 zcode-builtin.json），
 * 覆盖判代门/候选过滤/模板形态落盘/模型能力位/按现状幂等（无标记）。全部 fail-soft 语义对齐实现。
 */
class V1BuiltinMigratorTest {

    private lateinit var home: Path
    private lateinit var configPath: Path
    private lateinit var providerConfigPath: Path

    /** 造 fake home：v2 provider_config.json（空规则）+ v1 config.json + 最小模板表 */
    private fun createHome() {
        home = Files.createTempDirectory("v1-migrator-test")
        val v2 = home.resolve(".zcode").resolve("v2")
        Files.createDirectories(v2)
        providerConfigPath = v2.resolve("provider_config.json")
        Files.write(providerConfigPath, """
            {"schemaVersion":1,"config":{"providerOrder":[],
              "providerConfigRules":{"providerRules":[]},
              "modelConfigRules":{"providerModelRules":[],"manualProviderModelRules":[]}}}
        """.trimIndent().toByteArray())
        // 最小模板表（catalogFile ① v2/runtime/provider 深度扫描命中）
        val runtime = v2.resolve("runtime").resolve("provider").resolve("fake")
        Files.createDirectories(runtime)
        Files.write(runtime.resolve("zcode-builtin.json"), """
            {"schemaVersion":1,"config":{
              "providerConfigRules":{"templateRules":[
                {"templateId":"bigmodel-api","templateNameMap":{"zh-CN":"BigModel Coding Plan","en-US":"BigModel Coding Plan"},
                 "config":{"access":{"type":"zhipu-coding-plan-api-key"},
                           "api":{"type":"anthropic-messages","baseUrl":"https://open.bigmodel.cn/api/anthropic"},
                           "builtinModelIds":["GLM-5.3","GLM-5.3-Flash"]}}]},
              "modelConfigRules":{"modelRules":[]}}}
        """.trimIndent().toByteArray())
        configPath = v2.resolve("config.json")
    }

    /** 写 v1 config.json：一个 builtin 订阅渠道（可覆盖 apiKey/enabled）*/
    private fun writeV1Config(vararg entries: Pair<String, Pair<String?, Boolean>>) {
        val providers = entries.joinToString(",") { (id, v) ->
            val (apiKey, enabled) = v
            buildString {
                append('"').append(id).append("\": {\"name\":\"").append(id).append("\",\"kind\":\"anthropic\",")
                append("\"options\":").append(if (apiKey != null) "{\"apiKey\":\"$apiKey\"}" else "{}")
                append(",\"enabled\":").append(enabled)
                append(",\"source\":\"custom\",\"models\":{}}")
            }
        }
        Files.write(configPath, """{"provider": {$providers}}""".toByteArray())
    }

    private fun rules(): List<kotlinx.serialization.json.JsonObject> {
        val cfg = Json.parseToJsonElement(providerConfigPath.readText()).jsonObject["config"]!!.jsonObject
        return cfg["providerConfigRules"]!!.jsonObject["providerRules"]!!.jsonArray.map { it.jsonObject }
    }

    private fun modelRules(): List<kotlinx.serialization.json.JsonObject> {
        val cfg = Json.parseToJsonElement(providerConfigPath.readText()).jsonObject["config"]!!.jsonObject
        return cfg["modelConfigRules"]!!.jsonObject["providerModelRules"]!!.jsonArray.map { it.jsonObject }
    }

    /** 重写 fake 目录的 modelRules（模型能力位用例：给 GLM-5.3 系配上下文/输入能力） */
    private fun writeCatalogModelRules(vararg rules: String) {
        val runtime = home.resolve(".zcode").resolve("v2").resolve("runtime").resolve("provider").resolve("fake")
        Files.write(runtime.resolve("zcode-builtin.json"), """
            {"schemaVersion":1,"config":{
              "providerConfigRules":{"templateRules":[
                {"templateId":"bigmodel-api","templateNameMap":{"zh-CN":"BigModel Coding Plan","en-US":"BigModel Coding Plan"},
                 "config":{"access":{"type":"zhipu-coding-plan-api-key"},
                           "api":{"type":"anthropic-messages","baseUrl":"https://open.bigmodel.cn/api/anthropic"},
                           "builtinModelIds":["GLM-5.3","GLM-5.3-Flash"]}}]},
              "modelConfigRules":{"modelRules":[${rules.joinToString(",")}]}}}
        """.trimIndent().toByteArray())
    }

    // ============ 主链路 ============

    @Test
    fun `NEW代迁移带key的v1内置渠道为模板形态`() {
        createHome()
        writeV1Config("builtin:bigmodel-coding-plan" to ("sk-old-key" to true))
        val migrated = V1BuiltinMigrator.migrateIfNeeded(
            zcodePath = null, configPath = configPath, providerConfigPath = providerConfigPath, home = home.toString(),
        )
        assertEquals(listOf("bigmodel-api"), migrated)
        val rule = rules().single()
        assertEquals("bigmodel-api", rule["providerId"]!!.jsonPrimitive.content)
        assertEquals("bigmodel-api", rule["templateId"]!!.jsonPrimitive.content)
        assertEquals("BigModel Coding Plan", rule["providerName"]!!.jsonPrimitive.content)
        val cfg = rule["config"]!!.jsonObject
        assertEquals("zhipu-coding-plan-api-key", cfg["access"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("sk-old-key", cfg["access"]!!.jsonObject["apiKey"]!!.jsonPrimitive.content)
        assertEquals("anthropic-messages", cfg["api"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("https://open.bigmodel.cn/api/anthropic", cfg["api"]!!.jsonObject["baseUrl"]!!.jsonPrimitive.content)
        val mids = cfg["personalModelIds"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("GLM-5.3", "GLM-5.3-Flash"), mids, "模板模型清单预填")
        // 目录无能力数据 → 退回纯 prefill：不写半截 providerModelRules
        assertTrue(modelRules().isEmpty(), "能力解析不出时不写模型级规则")
        val orderCfg = Json.parseToJsonElement(providerConfigPath.readText()).jsonObject["config"]!!.jsonObject
        val order = orderCfg["providerOrder"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("bigmodel-api"), order)
    }

    @Test
    fun `迁移写入模型上下文与输入能力位`() {
        createHome()
        writeCatalogModelRules(
            """{"modelMatch":"glm-5\\.3.*","config":{"properties":{"contextWindow":1000000}}}""",
            """{"modelMatch":"glm-5\\.3-flash.*","config":{"properties":{"inputFormat":{"supportsImage":true,"supportsVideo":true,"supportsPdf":true}}}}""",
        )
        writeV1Config("builtin:bigmodel-coding-plan" to ("sk-old-key" to true))
        val migrated = V1BuiltinMigrator.migrateIfNeeded(
            zcodePath = null, configPath = configPath, providerConfigPath = providerConfigPath, home = home.toString(),
        )
        assertEquals(listOf("bigmodel-api"), migrated)
        val modelRules = modelRules()
        assertEquals(2, modelRules.size, "预填模型逐个落 providerModelRules")
        val flash = modelRules.single { it["modelId"]!!.jsonPrimitive.content == "GLM-5.3-Flash" }
        assertEquals("bigmodel-api", flash["providerId"]!!.jsonPrimitive.content)
        val flashProps = flash["config"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(1000000L, flashProps["contextWindow"]!!.jsonPrimitive.content.toLong())
        val flashInput = flashProps["inputFormat"]!!.jsonObject
        assertEquals("true", flashInput["supportsImage"]!!.jsonPrimitive.content)
        assertEquals("true", flashInput["supportsVideo"]!!.jsonPrimitive.content)
        assertEquals("true", flashInput["supportsPdf"]!!.jsonPrimitive.content)
        // 链上未声明能力位 = false 显式落盘（sparse 缺省语义与读侧一致）
        val glm53 = modelRules.single { it["modelId"]!!.jsonPrimitive.content == "GLM-5.3" }
        val glm53Props = glm53["config"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(1000000L, glm53Props["contextWindow"]!!.jsonPrimitive.content.toLong())
        assertEquals("false", glm53Props["inputFormat"]!!.jsonObject["supportsImage"]!!.jsonPrimitive.content)
    }

    @Test
    fun `迁移渠道默认置顶`() {
        createHome()
        writeV1Config("builtin:bigmodel-coding-plan" to ("sk-old-key" to true))
        // v2 已有两个自定义渠道（无模板规则），迁移后内置渠道应插到 providerOrder 首位
        Files.write(providerConfigPath, """
            {"schemaVersion":1,"config":{"providerOrder":["a","b"],
              "providerConfigRules":{"providerRules":[
                {"providerId":"a","providerName":"A","config":{"group":"standard-personal"}},
                {"providerId":"b","providerName":"B","config":{"group":"standard-personal"}}]},
              "modelConfigRules":{"providerModelRules":[],"manualProviderModelRules":[]}}}
        """.trimIndent().toByteArray())
        val migrated = V1BuiltinMigrator.migrateIfNeeded(
            zcodePath = null, configPath = configPath, providerConfigPath = providerConfigPath, home = home.toString(),
        )
        assertEquals(listOf("bigmodel-api"), migrated)
        val orderCfg = Json.parseToJsonElement(providerConfigPath.readText()).jsonObject["config"]!!.jsonObject
        assertEquals(
            listOf("bigmodel-api", "a", "b"),
            orderCfg["providerOrder"]!!.jsonArray.map { it.jsonPrimitive.content },
            "迁移内置渠道置顶",
        )
    }

    @Test
    fun `v2已有同模板或同id渠道跳过`() {
        createHome()
        writeV1Config("builtin:bigmodel-coding-plan" to ("sk-old-key" to true))
        // 用户已在 v2 重建（templateId 命中）
        Files.write(providerConfigPath, """
            {"schemaVersion":1,"config":{"providerOrder":["bigmodel-api"],
              "providerConfigRules":{"providerRules":[
                {"providerId":"bigmodel-api","templateId":"bigmodel-api","providerName":"BigModel Coding Plan",
                 "config":{"group":"standard-personal","access":{"type":"zhipu-coding-plan-api-key","apiKey":"own"}}}]},
              "modelConfigRules":{"providerModelRules":[],"manualProviderModelRules":[]}}}
        """.trimIndent().toByteArray())
        val migrated = V1BuiltinMigrator.migrateIfNeeded(
            zcodePath = null, configPath = configPath, providerConfigPath = providerConfigPath, home = home.toString(),
        )
        assertTrue(migrated.isEmpty(), "已配置的模板渠道不迁")
        assertEquals("own", rules().single()["config"]!!.jsonObject["access"]!!.jsonObject["apiKey"]!!.jsonPrimitive.content)
    }

    // ============ 候选过滤 ============

    @Test
    fun `空key与停用渠道不迁`() {
        createHome()
        writeV1Config(
            "builtin:bigmodel-coding-plan" to (null to true),
            "builtin:zai-coding-plan" to ("sk-zai" to false),
        )
        val migrated = V1BuiltinMigrator.migrateIfNeeded(
            zcodePath = null, configPath = configPath, providerConfigPath = providerConfigPath, home = home.toString(),
        )
        assertTrue(migrated.isEmpty())
        assertTrue(rules().isEmpty())
    }

    @Test
    fun `映射表外渠道不迁`() {
        createHome()
        writeV1Config("builtin:bigmodel-start-plan" to ("sk-trial" to true))
        val migrated = V1BuiltinMigrator.migrateIfNeeded(
            zcodePath = null, configPath = configPath, providerConfigPath = providerConfigPath, home = home.toString(),
        )
        assertTrue(migrated.isEmpty(), "体验套餐（滑块门控）不在映射表")
    }

    // ============ 判代门与防复活 ============

    @Test
    fun `OLD代不迁`() {
        createHome()
        // setting.json 键形态兜底判为 OLD（modelProviderFamilySelectedKeys）
        Files.write(home.resolve(".zcode").resolve("v2").resolve("setting.json"),
            """{"modelProviderFamilySelectedKeys":{}}""".toByteArray())
        writeV1Config("builtin:bigmodel-coding-plan" to ("sk-old-key" to true))
        val migrated = V1BuiltinMigrator.migrateIfNeeded(
            zcodePath = null, configPath = configPath, providerConfigPath = providerConfigPath, home = home.toString(),
        )
        assertTrue(migrated.isEmpty(), "OLD 代 config.json 是现役渠道源，无需迁移")
        assertTrue(rules().isEmpty())
    }

    @Test
    fun `v2删除后再次调用重新迁入（无标记幂等按现状判定）`() {
        createHome()
        writeV1Config("builtin:bigmodel-coding-plan" to ("sk-old-key" to true))
        assertEquals(listOf("bigmodel-api"), V1BuiltinMigrator.migrateIfNeeded(
            zcodePath = null, configPath = configPath, providerConfigPath = providerConfigPath, home = home.toString(),
        ))
        // 用户在 v2 删除了该渠道（rules 清空）→ 下次调用按现状补回（2026-09-18 用户决策：
        // 移除「防复活」标记，启动即按 config.json 现状补齐）
        Files.write(providerConfigPath, """
            {"schemaVersion":1,"config":{"providerOrder":[],
              "providerConfigRules":{"providerRules":[]},
              "modelConfigRules":{"providerModelRules":[],"manualProviderModelRules":[]}}}
        """.trimIndent().toByteArray())
        assertEquals(listOf("bigmodel-api"), V1BuiltinMigrator.migrateIfNeeded(
            zcodePath = null, configPath = configPath, providerConfigPath = providerConfigPath, home = home.toString(),
        ), "删掉的渠道下次启动补回")
        assertEquals("bigmodel-api", rules().single()["providerId"]!!.jsonPrimitive.content)
        // 幂等：已在 v2 则不再重复迁
        assertTrue(V1BuiltinMigrator.migrateIfNeeded(
            zcodePath = null, configPath = configPath, providerConfigPath = providerConfigPath, home = home.toString(),
        ).isEmpty(), "已存在的渠道不重复迁")
        assertEquals(1, rules().size)
    }

    @Test
    fun `v1侧enabled为false或清空key即停用迁移`() {
        createHome()
        writeV1Config("builtin:bigmodel-coding-plan" to ("sk-old-key" to false))
        assertTrue(V1BuiltinMigrator.migrateIfNeeded(
            zcodePath = null, configPath = configPath, providerConfigPath = providerConfigPath, home = home.toString(),
        ).isEmpty(), "v1 停用 = 迁移 opt-out")
        writeV1Config("builtin:bigmodel-coding-plan" to (null to true))
        assertTrue(V1BuiltinMigrator.migrateIfNeeded(
            zcodePath = null, configPath = configPath, providerConfigPath = providerConfigPath, home = home.toString(),
        ).isEmpty(), "v1 清空 key = 迁移 opt-out")
        assertTrue(rules().isEmpty())
    }

    @Test
    fun `v1或v2配置文件缺失不迁`() {
        createHome()
        // config.json 缺失
        assertTrue(V1BuiltinMigrator.migrateIfNeeded(
            zcodePath = null, configPath = configPath, providerConfigPath = providerConfigPath, home = home.toString(),
        ).isEmpty())
        // provider_config.json 缺失（客户端没跑过，不代建）
        writeV1Config("builtin:bigmodel-coding-plan" to ("sk-old-key" to true))
        Files.delete(providerConfigPath)
        assertTrue(V1BuiltinMigrator.migrateIfNeeded(
            zcodePath = null, configPath = configPath, providerConfigPath = providerConfigPath, home = home.toString(),
        ).isEmpty())
    }
}
