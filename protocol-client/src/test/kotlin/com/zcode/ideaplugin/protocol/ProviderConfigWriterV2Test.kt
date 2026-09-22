package com.zcode.ideaplugin.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ProviderConfigWriterV2（provider_config.json 写通道）单测——v2 CRUD 落盘形态对齐
 * 客户端实拍与本机实验（diag-v2-crud-validate.py：无 templateId rule 被 registry 接受、
 * enabled:false 渠道被排除）：rule 构造、三处同步（rule/providerOrder/providerModelRules）、
 * merge 三态、未知键保序保留、providerId 客户端同款 slug 化。
 */
class ProviderConfigWriterV2Test {

    /** 造一份贴近实拍的 provider_config.json（含未知键考验保留） */
    private fun tmpProviderConfig(): java.nio.file.Path {
        val dir = Files.createTempDirectory("provider-writer-v2-test")
        val content = """
        {
          "schemaVersion": 1,
          "extraTop": {"keep": true},
          "config": {
            "providerOrder": ["bigmodel-api", "deepseek"],
            "providerConfigRules": {
              "providerRules": [
                {"providerId": "bigmodel-api", "templateId": "bigmodel-api", "providerName": "BigModel Coding Plan",
                 "config": {"group": "standard-personal", "access": {"type": "zhipu-coding-plan-api-key", "apiKey": "12345"},
                            "personalModelIds": [], "modelOrder": ["GLM-5.3"]}},
                {"providerId": "deepseek", "templateId": "deepseek", "providerName": "DeepSeek",
                 "config": {"group": "standard-personal", "access": {"type": "api-key", "apiKey": "1234567"},
                            "personalModelIds": [], "modelOrder": ["deepseek-flash"]}}
              ]
            },
            "modelConfigRules": {
              "providerModelRules": [
                {"modelId": "GLM-5.3", "providerId": "bigmodel-api", "config": {"properties": {"contextWindow": 200000}}}
              ],
              "manualProviderModelRules": []
            }
          }
        }
        """.trimIndent()
        return dir.resolve("provider_config.json").apply { Files.write(this, content.toByteArray()) }
    }

    private fun readRoot(p: java.nio.file.Path) = Json.parseToJsonElement(p.readText()).jsonObject

    private fun rulesOf(p: java.nio.file.Path) =
        readRoot(p)["config"]!!.jsonObject["providerConfigRules"]!!.jsonObject["providerRules"]!!.jsonArray
            .map { it.jsonObject }

    private fun orderOf(p: java.nio.file.Path) =
        readRoot(p)["config"]!!.jsonObject["providerOrder"]!!.jsonArray
            .map { it.jsonPrimitive.content }

    private fun modelRulesOf(p: java.nio.file.Path) =
        readRoot(p)["config"]!!.jsonObject["modelConfigRules"]!!.jsonObject["providerModelRules"]!!.jsonArray
            .map { it.jsonObject }

    private fun draft(
        name: String = "Kimi",
        kind: String = "anthropic",
        baseURL: String = "https://api.moonshot.cn/anthropic",
        apiKey: String? = "sk-moon",
        models: List<ProviderConfigWriter.ModelDraft> = listOf(
            ProviderConfigWriter.ModelDraft("kimi-k3", context = 256000),
        ),
    ) = ProviderConfigWriter.ProviderDraft(name, kind, baseURL, apiKey, models)

    // ============ providerId slug 化（客户端 hSo/mSo 同款） ============

    @Test
    fun `newProviderId 生成 UUID 形态且不重复`() {
        val a = ProviderConfigWriterV2.newProviderId()
        val b = ProviderConfigWriterV2.newProviderId()
        assertTrue(a != b, "两次生成应不同")
        assertTrue(a.matches(Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")), "UUID 形态: $a")
    }

    // ============ api type 映射 ============

    @Test
    fun `apiTypeOf 与 kindOfApiType 互转`() {
        assertEquals("anthropic-messages", ProviderConfigWriterV2.apiTypeOf("anthropic"))
        assertEquals("openai-chat-completions", ProviderConfigWriterV2.apiTypeOf("openai-compatible"))
        assertEquals("anthropic", ProviderConfigWriterV2.kindOfApiType("anthropic-messages"))
        assertEquals("openai-compatible", ProviderConfigWriterV2.kindOfApiType("openai-chat-completions"))
        assertEquals("openai-compatible", ProviderConfigWriterV2.kindOfApiType("openai-responses"))
        assertEquals("anthropic", ProviderConfigWriterV2.kindOfApiType(null))
    }

    // ============ add ============

    @Test
    fun `addProvider 写入无 templateId 自定义 rule 并三处同步`() {
        val p = tmpProviderConfig()
        val (err, id) = ProviderConfigWriterV2.addProvider(p, draft())
        assertEquals(null, err)
        assertTrue(id.matches(Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")), "插件新建渠道 id = UUID: $id")

        val rule = rulesOf(p).last()
        assertEquals(id, rule["providerId"]!!.jsonPrimitive.content)
        assertEquals("Kimi", rule["providerName"]!!.jsonPrimitive.content)
        assertTrue("templateId" !in rule, "自定义形态不带 templateId（实验 A）")
        val cfg = rule["config"]!!.jsonObject
        assertEquals("standard-personal", cfg["group"]!!.jsonPrimitive.content)
        assertEquals("api-key", cfg["access"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("sk-moon", cfg["access"]!!.jsonObject["apiKey"]!!.jsonPrimitive.content)
        assertEquals("anthropic-messages", cfg["api"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("https://api.moonshot.cn/anthropic", cfg["api"]!!.jsonObject["baseUrl"]!!.jsonPrimitive.content)
        assertEquals("kimi-k3", cfg["modelOrder"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("kimi-k3", cfg["personalModelIds"]!!.jsonArray[0].jsonPrimitive.content)
        // providerOrder append + 尾部
        assertEquals(listOf("bigmodel-api", "deepseek", id), orderOf(p))
        // 模型级 contextWindow 落 providerModelRules（既有条目保留在前）
        val mr = modelRulesOf(p)
        assertEquals(2, mr.size)
        assertEquals(id to "kimi-k3", mr[1]["providerId"]!!.jsonPrimitive.content to mr[1]["modelId"]!!.jsonPrimitive.content)
        assertEquals(256000, mr[1]["config"]!!.jsonObject["properties"]!!.jsonObject["contextWindow"]!!.jsonPrimitive.content.toLong())
        // 未知键与既有 rule 原样保留
        val root = readRoot(p)
        assertNotNull(root["extraTop"])
        assertEquals(1, root["schemaVersion"]!!.jsonPrimitive.content.toInt())
        assertEquals(3, rulesOf(p).size)
    }

    @Test
    fun `addProvider 中文名渠道拿 UUID id`() {
        val p = tmpProviderConfig()
        val (err, id) = ProviderConfigWriterV2.addProvider(p, draft(name = "测试渠道"))
        assertEquals(null, err)
        assertTrue(id.matches(Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")), "UUID 形态: $id")
        assertEquals("测试渠道", rulesOf(p).last()["providerName"]!!.jsonPrimitive.content)
    }

    // ============ update ============

    @Test
    fun `updateProvider 就地合并与模型整表替换`() {
        val p = tmpProviderConfig()
        val err = ProviderConfigWriterV2.updateProvider(p, "deepseek", ProviderConfigWriter.UpdateFields(
            name = "DeepSeek V2",
            kind = "openai-compatible",
            baseURL = "https://api.deepseek.com/v1",
            apiKey = "sk-new",
            models = listOf(
                ProviderConfigWriter.ModelDraft("deepseek-v4-pro", context = 128000),
                ProviderConfigWriter.ModelDraft("deepseek-flash", context = 64000),
            ),
            enabled = null,
        ))
        assertEquals(null, err)
        val rule = rulesOf(p).first { it["providerId"]!!.jsonPrimitive.content == "deepseek" }
        assertEquals("DeepSeek V2", rule["providerName"]!!.jsonPrimitive.content)
        assertEquals("deepseek", rule["templateId"]!!.jsonPrimitive.content, "templateId 等原键保留")
        val cfg = rule["config"]!!.jsonObject
        assertEquals("sk-new", cfg["access"]!!.jsonObject["apiKey"]!!.jsonPrimitive.content)
        assertEquals("openai-chat-completions", cfg["api"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("https://api.deepseek.com/v1", cfg["api"]!!.jsonObject["baseUrl"]!!.jsonPrimitive.content)
        assertEquals(2, cfg["modelOrder"]!!.jsonArray.size)
        assertEquals(2, modelRulesOf(p).count { it["providerId"]!!.jsonPrimitive.content == "deepseek" })
        assertEquals(listOf("bigmodel-api", "deepseek"), orderOf(p), "编辑不动 providerOrder")
    }

    @Test
    fun `updateProvider apiKey 三态`() {
        val p = tmpProviderConfig()
        // null = 不变
        ProviderConfigWriterV2.updateProvider(p, "deepseek", ProviderConfigWriter.UpdateFields(
            name = null, kind = null, baseURL = null, apiKey = null, models = null, enabled = null))
        assertEquals("1234567", rulesOf(p)[1]["config"]!!.jsonObject["access"]!!.jsonObject["apiKey"]!!.jsonPrimitive.content)
        // 空串 = 清除
        ProviderConfigWriterV2.updateProvider(p, "deepseek", ProviderConfigWriter.UpdateFields(
            name = null, kind = null, baseURL = null, apiKey = "", models = null, enabled = null))
        assertEquals("", rulesOf(p)[1]["config"]!!.jsonObject["access"]!!.jsonObject["apiKey"]!!.jsonPrimitive.content)
        // 不存在的渠道
        val err = ProviderConfigWriterV2.updateProvider(p, "nope", ProviderConfigWriter.UpdateFields(
            name = null, kind = null, baseURL = null, apiKey = null, models = null, enabled = null))
        assertTrue(err!!.contains("不存在"))
    }

    @Test
    fun `模型能力位落 inputFormat 且非托管键合并保留`() {
        // 旧条目带手写/客户端写入的非托管键（supportsText/supportsAudio/supportsNativeWebSearch），
        // 插件编辑（contextWindow + 三能力位）不得洗掉
        val dir = Files.createTempDirectory("provider-writer-v2-test")
        val p = dir.resolve("provider_config.json")
        Files.write(p, """
            {"schemaVersion":1,"config":{"providerOrder":["bigmodel-api"],
              "providerConfigRules":{"providerRules":[
                {"providerId":"bigmodel-api","templateId":"bigmodel-api","providerName":"BigModel Coding Plan",
                 "config":{"group":"standard-personal","access":{"type":"zhipu-coding-plan-api-key","apiKey":"k"},
                           "personalModelIds":["GLM-5.3-Flash"],"modelOrder":["GLM-5.3-Flash"]}}]},
              "modelConfigRules":{"providerModelRules":[
                {"modelId":"GLM-5.3-Flash","providerId":"bigmodel-api",
                 "config":{"properties":{"contextWindow":200000,
                   "inputFormat":{"supportsText":true,"supportsAudio":true,"supportsImage":false},
                   "supportsNativeWebSearch":true}}}],
              "manualProviderModelRules":[]}}}
        """.trimIndent().toByteArray())
        val err = ProviderConfigWriterV2.updateProvider(p, "bigmodel-api", ProviderConfigWriter.UpdateFields(
            name = null, kind = null, baseURL = null, apiKey = null,
            models = listOf(ProviderConfigWriter.ModelDraft("GLM-5.3-Flash", context = 1000000, supportsImages = true)),
            enabled = null))
        assertEquals(null, err)
        val props = modelRulesOf(p)[0]["config"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(1000000L, props["contextWindow"]!!.jsonPrimitive.content.toLong())
        val input = props["inputFormat"]!!.jsonObject
        assertEquals("true", input["supportsImage"]!!.jsonPrimitive.content, "勾选图片 → supportsImage:true")
        assertEquals("false", input["supportsVideo"]!!.jsonPrimitive.content)
        assertEquals("false", input["supportsPdf"]!!.jsonPrimitive.content)
        assertEquals("true", input["supportsText"]!!.jsonPrimitive.content, "非托管键保留")
        assertEquals("true", input["supportsAudio"]!!.jsonPrimitive.content, "非托管键保留")
        assertEquals("true", props["supportsNativeWebSearch"]!!.jsonPrimitive.content, "properties 非托管键保留")
    }

    @Test
    fun `updateProvider SSO 渠道补 access 与 api 节`() {
        // 无 access/api 的 SSO 型渠道（zai-api 实拍形态），编辑后节点被补齐
        val dir = Files.createTempDirectory("provider-writer-v2-test")
        val p = dir.resolve("provider_config.json")
        Files.write(p, """
            {"schemaVersion":1,"config":{"providerOrder":["zai-api"],
              "providerConfigRules":{"providerRules":[
                {"providerId":"zai-api","templateId":"zai-api","providerName":"Z.ai Coding Plan",
                 "config":{"group":"standard-personal","personalModelIds":[],"modelOrder":[]}}]},
              "modelConfigRules":{"providerModelRules":[]}}}
        """.trimIndent().toByteArray())
        val err = ProviderConfigWriterV2.updateProvider(p, "zai-api", ProviderConfigWriter.UpdateFields(
            name = null, kind = "anthropic", baseURL = "https://api.z.ai/api/anthropic",
            apiKey = "sk-zai", models = listOf(ProviderConfigWriter.ModelDraft("GLM-5.3", context = 200000)),
            enabled = null))
        assertEquals(null, err)
        val cfg = rulesOf(p)[0]["config"]!!.jsonObject
        assertEquals("sk-zai", cfg["access"]!!.jsonObject["apiKey"]!!.jsonPrimitive.content)
        assertEquals("https://api.z.ai/api/anthropic", cfg["api"]!!.jsonObject["baseUrl"]!!.jsonPrimitive.content)
        assertEquals("GLM-5.3", cfg["modelOrder"]!!.jsonArray[0].jsonPrimitive.content)
    }

    // ============ remove / toggle ============

    @Test
    fun `removeProvider 三处同步清理`() {
        val p = tmpProviderConfig()
        // 先给 deepseek 加模型级 rule 再删，验证清理
        ProviderConfigWriterV2.updateProvider(p, "deepseek", ProviderConfigWriter.UpdateFields(
            name = null, kind = null, baseURL = null, apiKey = null,
            models = listOf(ProviderConfigWriter.ModelDraft("deepseek-flash", context = 64000)), enabled = null))
        val err = ProviderConfigWriterV2.removeProvider(p, "deepseek")
        assertEquals(null, err)
        assertEquals(listOf("bigmodel-api"), rulesOf(p).map { it["providerId"]!!.jsonPrimitive.content })
        assertEquals(listOf("bigmodel-api"), orderOf(p))
        assertTrue(modelRulesOf(p).none { it["providerId"]!!.jsonPrimitive.content == "deepseek" })
        // 不存在的渠道
        assertTrue(ProviderConfigWriterV2.removeProvider(p, "ghost")!!.contains("不存在"))
    }

    @Test
    fun `toggleProvider 写 enabled 不动其他`() {
        val p = tmpProviderConfig()
        assertEquals(null, ProviderConfigWriterV2.toggleProvider(p, "deepseek", false))
        val rule = rulesOf(p).first { it["providerId"]!!.jsonPrimitive.content == "deepseek" }
        assertEquals(false, rule["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("1234567", rule["config"]!!.jsonObject["access"]!!.jsonObject["apiKey"]!!.jsonPrimitive.content)
        assertEquals(2, rulesOf(p).size, "不增删渠道")
        assertEquals(1, modelRulesOf(p).size, "不动模型级 rules")
        // 再切回 true
        ProviderConfigWriterV2.toggleProvider(p, "deepseek", true)
        assertEquals(true, rulesOf(p)[1]["enabled"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `reorderProviders 写 providerOrder 不动 rules 与未知渠道拒绝`() {
        val p = tmpProviderConfig()
        // 把 deepseek 挪到首位（客户端拖拽语义），rules/modelRules/未知键全部原样
        val err = ProviderConfigWriterV2.reorderProviders(p, listOf("deepseek", "bigmodel-api"))
        assertEquals(null, err)
        assertEquals(listOf("deepseek", "bigmodel-api"), orderOf(p))
        assertEquals(2, rulesOf(p).size)
        assertEquals(
            "1234567",
            rulesOf(p)[1]["config"]!!.jsonObject["access"]!!.jsonObject["apiKey"]!!.jsonPrimitive.content,
        )
        assertEquals(1, modelRulesOf(p).size)
        assertEquals(
            0,
            readRoot(p)["config"]!!.jsonObject["modelConfigRules"]!!.jsonObject
                ["manualProviderModelRules"]!!.jsonArray.size,
            "未知键保留（manualProviderModelRules 是空数组本身）",
        )
        assertNotNull(readRoot(p)["extraTop"], "顶层未知键保留")
        // 未列出的渠道保持原相对次序排尾部（防御）
        val err2 = ProviderConfigWriterV2.reorderProviders(p, listOf("bigmodel-api"))
        assertEquals(null, err2)
        assertEquals(listOf("bigmodel-api", "deepseek"), orderOf(p))
        // 未知渠道拒绝
        assertTrue(ProviderConfigWriterV2.reorderProviders(p, listOf("ghost"))!!.contains("未知渠道"))
    }

    @Test
    fun `备份滚动生成 bak 链`() {
        val p = tmpProviderConfig()
        repeat(3) {
            ProviderConfigWriterV2.toggleProvider(p, "bigmodel-api", it % 2 == 0)
        }
        assertTrue(Files.isRegularFile(p.resolveSibling("provider_config.json.bak.1")))
        assertTrue(Files.isRegularFile(p.resolveSibling("provider_config.json.bak.2")))
    }
}
