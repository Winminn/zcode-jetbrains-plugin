package com.zcode.ideaplugin.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ProviderConfigWriter（自定义渠道 CRUD 写通道）单测——节点构造形态对齐客户端实拍
 * （design-research/自定义模型渠道CRUD实现方案：文件端命名 name/options/limit/modalities）、
 * merge 三态语义、写回保序与原子性、builtin 守卫由调用方负责（此处不测）。
 */
class ProviderConfigWriterTest {

    private fun tmpConfig(content: String = """{"provider":{}}"""): java.nio.file.Path {
        val dir = Files.createTempDirectory("provider-writer-test")
        return dir.resolve("config.json").apply { Files.write(this, content.toByteArray()) }
    }

    private fun draft(
        name: String = "DeepSeek",
        kind: String = "anthropic",
        baseURL: String = "https://api.deepseek.com/anthropic",
        apiKey: String? = "sk-test",
        models: List<ProviderConfigWriter.ModelDraft> = listOf(
            ProviderConfigWriter.ModelDraft("deepseek-chat", context = 128000, output = 8192),
        ),
    ) = ProviderConfigWriter.ProviderDraft(name, kind, baseURL, apiKey, models)

    @Test
    fun `validateDraft rejects blank name bad url empty models dup ids`() {
        assertTrue(validate(draft(name = " "))!!.contains("名称"))
        assertTrue(validate(draft(kind = "openai"))!!.contains("协议类型"))
        assertTrue(validate(draft(baseURL = "ftp://x"))!!.contains("http"))
        assertTrue(validate(draft(models = emptyList()))!!.contains("至少"))
        val dup = draft(models = listOf(
            ProviderConfigWriter.ModelDraft("m", context = 1),
            ProviderConfigWriter.ModelDraft("m", context = 2),
        ))
        assertTrue(validate(dup)!!.contains("重复"))
        assertTrue(validate(draft(models = listOf(ProviderConfigWriter.ModelDraft("m", context = 0))))!!.contains("上下文"))
        assertNull(validate(draft()))
    }

    private fun validate(d: ProviderConfigWriter.ProviderDraft) = ProviderConfigWriter.validateDraft(d)

    @Test
    fun `buildProviderNode produces client-compatible file-side shape`() {
        val node = ProviderConfigWriter.buildProviderNode(
            draft(models = listOf(
                ProviderConfigWriter.ModelDraft("deepseek-chat", name = "DeepSeek Chat", context = 128000, supportsImages = true),
            ))
        )
        assertEquals("DeepSeek", node["name"]!!.jsonPrimitive.content)
        assertEquals("anthropic", node["kind"]!!.jsonPrimitive.content)
        assertEquals("custom", node["source"]!!.jsonPrimitive.content)
        assertEquals("true", node["enabled"]!!.jsonPrimitive.content)
        val options = node["options"]!!.jsonObject
        assertEquals("sk-test", options["apiKey"]!!.jsonPrimitive.content)
        assertEquals("true", options["apiKeyRequired"]!!.jsonPrimitive.content)
        assertEquals("https://api.deepseek.com/anthropic", options["baseURL"]!!.jsonPrimitive.content)
        // 数字 limit（客户端实拍形态，非字符串）
        val model = node["models"]!!.jsonObject["deepseek-chat"]!!.jsonObject
        assertEquals(128000, model["limit"]!!.jsonObject["context"]!!.jsonPrimitive.int)
        val input = model["modalities"]!!.jsonObject["input"]!!.jsonArray
        assertEquals(listOf("text", "image"), input.map { it.jsonPrimitive.content })
        assertEquals("DeepSeek Chat", model["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `merge keeps unspecified fields and handles apiKey tri-state`() {
        val existing = Json.parseToJsonElement("""
            {"name":"DeepSeek","kind":"anthropic","source":"custom","enabled":false,
             "options":{"apiKey":"sk-old","apiKeyRequired":true,"baseURL":"https://old"},
             "models":{"deepseek-chat":{"limit":{"context":1000}}},
             "systemDisabledReason":"x"}
        """).jsonObject

        // apiKey 缺省=不变
        val keep = ProviderConfigWriter.mergeProviderNode(existing, null, null, null, null, null, null)
        assertEquals("sk-old", keep["options"]!!.jsonObject["apiKey"]!!.jsonPrimitive.content)
        assertEquals(false, keep["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("x", keep["systemDisabledReason"]!!.jsonPrimitive.content)

        // apiKey 空串=清除；enabled/baseURL 同步更新
        val cleared = ProviderConfigWriter.mergeProviderNode(existing, "新名字", null, "https://new", "", null, true)
        assertEquals("新名字", cleared["name"]!!.jsonPrimitive.content)
        assertFalse("apiKey" in cleared["options"]!!.jsonObject)
        assertEquals("https://new", cleared["options"]!!.jsonObject["baseURL"]!!.jsonPrimitive.content)
        assertTrue(cleared["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("apiKeyRequired", cleared["options"]!!.jsonObject.keys.first { it == "apiKeyRequired" })

        // apiKey 非空=新值；models 整表替换
        val replaced = ProviderConfigWriter.mergeProviderNode(
            existing, null, null, null, "sk-new",
            listOf(ProviderConfigWriter.ModelDraft("m2", context = 2000)), null,
        )
        assertEquals("sk-new", replaced["options"]!!.jsonObject["apiKey"]!!.jsonPrimitive.content)
        assertEquals(setOf("m2"), replaced["models"]!!.jsonObject.keys)
    }

    @Test
    fun `update writes new provider and preserves sibling order and root keys`() {
        val path = tmpConfig("""
            {"model":"anthropic/GLM-5.3","provider":{"builtin:bigmodel":{"name":"B","kind":"anthropic"}},"mcp":{"servers":{}}}
        """)
        val err = ProviderConfigWriter.update(path) { providers ->
            JsonObject(linkedMapOf<String, kotlinx.serialization.json.JsonElement>("uuid-1" to ProviderConfigWriter.buildProviderNode(draft())).apply { putAll(providers) })
        }
        assertNull(err)
        val root = Json.parseToJsonElement(path.readText()).jsonObject
        // 根节点其余键保留
        assertEquals("anthropic/GLM-5.3", root["model"]!!.jsonPrimitive.content)
        assertTrue("mcp" in root)
        // 新渠道追加在前、内置节点原样
        val ids = root["provider"]!!.jsonObject.keys.toList()
        assertEquals(listOf("uuid-1", "builtin:bigmodel"), ids)
        assertTrue(Files.exists(path.resolveSibling("config.json.bak.1")))
    }

    @Test
    fun `update keeps rolling 5 generations of backups newest first`() {
        val path = tmpConfig()
        // 连续 7 次变更：每代内容以 name-v<i> 标记
        for (i in 1..7) {
            val err = ProviderConfigWriter.update(path) { providers ->
                JsonObject(LinkedHashMap<String, kotlinx.serialization.json.JsonElement>().apply {
                    put("p", buildJsonObject { put("name", "v$i") })
                })
            }
            assertNull(err)
        }
        // 只剩 5 代；bak.1 最新（v6 的写前快照）、bak.5 最老（v2）
        for (i in 1..5) {
            val bak = path.resolveSibling("config.json.bak.$i")
            assertTrue(Files.exists(bak), "missing bak.$i")
            val snap = Json.parseToJsonElement(bak.readText()).jsonObject
            // 写回前快照：第 7 次写后，bak.k = 第 (7-k) 次写入的结果 → v6..v2
            assertEquals("v${7 - i}", snap["provider"]!!.jsonObject["p"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        }
        assertFalse(Files.exists(path.resolveSibling("config.json.bak.6")))
        // 当前文件 = 最后一次写入 v7
        assertEquals("v7", Json.parseToJsonElement(path.readText()).jsonObject["provider"]!!.jsonObject["p"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `update migrates legacy single bak to bak1 on first rotation`() {
        val path = tmpConfig()
        Files.write(path.resolveSibling("config.json.bak"), "{\"provider\":{\"legacy\":{}}}".toByteArray())
        val err = ProviderConfigWriter.update(path) { providers ->
            JsonObject(LinkedHashMap<String, kotlinx.serialization.json.JsonElement>().apply { put("new", buildJsonObject {}) })
        }
        assertNull(err)
        assertTrue(Files.exists(path.resolveSibling("config.json.bak.1")))
        assertFalse(Files.exists(path.resolveSibling("config.json.bak")))
        // 迁移发生在滚动前：旧单代内容后移到 bak.2，bak.1 = 本次写前快照（初始空 provider）
        val bak1 = Json.parseToJsonElement(path.resolveSibling("config.json.bak.1").readText()).jsonObject
        val bak2 = Json.parseToJsonElement(path.resolveSibling("config.json.bak.2").readText()).jsonObject
        assertTrue(bak1["provider"]!!.jsonObject.isEmpty())
        assertTrue("legacy" in bak2["provider"]!!.jsonObject)
    }

    @Test
    fun `update returns business error message from IllegalStateException`() {
        val path = tmpConfig()
        val err = ProviderConfigWriter.update(path) { throw IllegalStateException("渠道不存在: x") }
        assertEquals("渠道不存在: x", err)
        // 未变更：文件内容不变
        assertEquals("""{"provider":{}}""", path.readText().replace("\\s".toRegex(), ""))
    }

    @Test
    fun `update fails cleanly on missing file`() {
        val dir = Files.createTempDirectory("provider-writer-test")
        assertEquals(true, ProviderConfigWriter.update(dir.resolve("none.json")) { it }!!.contains("不存在"))
    }

    @Test
    fun `merge keeps non-form-managed model metadata absent on rebuild`() {
        // models 整表替换 = 客户端模型条目上的 reasoning/zcode 管理元数据回归默认（草稿不含）
        val existing = Json.parseToJsonElement("""
            {"name":"x","kind":"anthropic","options":{"apiKey":"k","baseURL":"https://x"},
             "models":{"m1":{"limit":{"context":1},"reasoning":{"enabled":true},"zcode":{"modified":true}}}}
        """).jsonObject
        val merged = ProviderConfigWriter.mergeProviderNode(
            existing, null, null, null, null,
            listOf(ProviderConfigWriter.ModelDraft("m1", context = 1)), null,
        )
        val m = merged["models"]!!.jsonObject["m1"]!!.jsonObject
        assertFalse("reasoning" in m)
        assertFalse("zcode" in m)
    }

    // ============ 前端消息形状解析（2026-09-16 缺陷回归：首版 handler 从消息顶层读
    // 字段全为 null，编辑保存"成功"实则零变更——字段实际嵌在 draft 里）============

    /** 前端真实消息形状：字段嵌在 draft（webview store addModelProvider 发送形状）*/
    private fun addMsg(draftJson: String): JsonObject =
        Json.parseToJsonElement("""{"op":"modelAddProvider","draft":$draftJson}""").jsonObject

    @Test
    fun `addDraftFromMessage unwraps nested draft`() {
        val d = ProviderConfigWriter.addDraftFromMessage(
            addMsg("""{"name":"DeepSeek","kind":"anthropic","baseURL":"https://x/anthropic",
                      "apiKey":"sk-1","models":[{"modelId":"deepseek-v4pro","context":128000,"output":8192,"images":true,"video":true,"pdf":true}]}""")
        )
        assertEquals("DeepSeek", d.name)
        assertEquals("sk-1", d.apiKey)
        assertEquals(1, d.models.size)
        assertEquals("deepseek-v4pro", d.models[0].modelId)
        assertEquals(8192L, d.models[0].output)
        assertTrue(d.models[0].supportsImages)
        assertTrue(d.models[0].supportsVideo)
        assertTrue(d.models[0].supportsPdf)
        assertNull(ProviderConfigWriter.validateDraft(d))
    }

    @Test
    fun `buildProviderNode writes input modalities in client order text image video pdf`() {
        // 对齐客户端实拍（GLM-5.3-Flash input=["text","image","video"]）：恒 text 打头，能力位按序追加
        fun node(vararg bits: Boolean) = ProviderConfigWriter.buildProviderNode(
            draft(models = listOf(
                ProviderConfigWriter.ModelDraft(
                    "m", context = 1,
                    supportsImages = bits.isNotEmpty() && bits[0],
                    supportsVideo = bits.size > 1 && bits[1],
                    supportsPdf = bits.size > 2 && bits[2],
                )
            ))
        )["models"]!!.jsonObject["m"]!!.jsonObject

        assertEquals(
            listOf("text", "image", "video", "pdf"),
            node(true, true, true)["modalities"]!!.jsonObject["input"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("text"),
            node()["modalities"]!!.jsonObject["input"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        // 输出恒为文本（客户端锁定语义）
        assertEquals(
            listOf("text"),
            node(true, true, true)["modalities"]!!.jsonObject["output"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `addDraftFromMessage tolerates missing draft`() {
        val d = ProviderConfigWriter.addDraftFromMessage(Json.parseToJsonElement("""{"op":"modelAddProvider"}""").jsonObject)
        assertEquals("", d.name)
        assertEquals("", d.apiKey)
        assertEquals(0, d.models.size)
        assertTrue(ProviderConfigWriter.validateDraft(d)!!.isNotBlank())
    }

    @Test
    fun `updateFieldsFromMessage unwraps nested draft with tri-state apiKey`() {
        fun updateMsg(draftJson: String?): JsonObject = if (draftJson == null)
            Json.parseToJsonElement("""{"op":"modelUpdateProvider","providerId":"u1"}""").jsonObject
        else
            Json.parseToJsonElement("""{"op":"modelUpdateProvider","providerId":"u1","draft":$draftJson}""").jsonObject

        // 全量：apiKey 非空=新值；models 数组=整表替换
        val f = ProviderConfigWriter.updateFieldsFromMessage(
            updateMsg("""{"name":"新名","kind":"anthropic","baseURL":"https://y","apiKey":"sk-2",
                         "models":[{"modelId":"m1","label":"显示名","context":1000}]}""")
        )
        assertEquals("新名", f.name)
        assertEquals("https://y", f.baseURL)
        assertEquals("sk-2", f.apiKey)
        val rows = f.models!!
        assertEquals(1, rows.size)
        assertEquals("显示名", rows[0].name)
        assertEquals(false, rows[0].supportsImages)

        // draft 无 apiKey 键 = 不变（null）
        val keep = ProviderConfigWriter.updateFieldsFromMessage(updateMsg("""{"name":"n"}"""))
        assertNull(keep.apiKey)
        assertNull(keep.models)

        // draft 有 apiKey 空串 = 清除
        val cleared = ProviderConfigWriter.updateFieldsFromMessage(updateMsg("""{"apiKey":""}"""))
        assertEquals("", cleared.apiKey)

        // 无 draft = 全部不变
        val none = ProviderConfigWriter.updateFieldsFromMessage(updateMsg(null))
        assertNull(none.name)
        assertNull(none.apiKey)
        assertNull(none.models)
    }

    @Test
    fun `updateFieldsFromMessage treats json null and literal null string as unchanged`() {
        // 2026-09-17 缺陷回归：模型行删除（commitDeleteModel）发 apiKey:null（JSON null），
        // JsonNull.content 返回字符串 "null" 被当新值写入 config，渠道 key 被污染成 'null'
        fun updateMsg(draftJson: String) =
            Json.parseToJsonElement("""{"op":"modelUpdateProvider","providerId":"u1","draft":$draftJson}""").jsonObject

        // JSON null = 不变（不变语义），绝不能落成 "null" 字符串
        val jsonNull = ProviderConfigWriter.updateFieldsFromMessage(
            updateMsg("""{"name":"n","baseURL":"https://x","apiKey":null,"models":[]}""")
        )
        assertNull(jsonNull.apiKey)

        // 字面量 "null" 字符串（污染值回传）同样按不变处理，不再写回
        val literalNull = ProviderConfigWriter.updateFieldsFromMessage(
            updateMsg("""{"apiKey":"null"}""")
        )
        assertNull(literalNull.apiKey)

        // 正常值不受影响
        val normal = ProviderConfigWriter.updateFieldsFromMessage(updateMsg("""{"apiKey":"sk-ok"}"""))
        assertEquals("sk-ok", normal.apiKey)
    }
}

private val JsonPrimitive.int: Int get() = content.toInt()
