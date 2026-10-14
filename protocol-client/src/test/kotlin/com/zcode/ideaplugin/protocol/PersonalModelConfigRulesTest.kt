package com.zcode.ideaplugin.protocol

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 个人精确模型规则解析（~/.zcode/v2/provider_config.json）。
 * 对齐官方 ModelConfigRules.composeEffective：两组精确规则（providerModelRules +
 * manualProviderModelRules）结构同构，个人层覆盖内置目录正则链。
 */
class PersonalModelConfigRulesTest {

    @TempDir
    lateinit var tempDir: Path

    private fun config(body: String): Path =
        tempDir.resolve("provider_config.json").also { it.toFile().writeText(body) }

    private val fullJson = """
        {
          "config": {
            "modelConfigRules": {
              "providerModelRules": [
                {
                  "providerId": "p1", "modelId": "m1",
                  "config": { "properties": {
                    "contextWindow": 256000,
                    "inputFormat": { "supportsImage": true, "supportsVideo": false, "supportsPdf": true }
                  }}
                },
                { "providerId": "p1", "modelId": "sparse", "config": { "enabled": true } },
                { "providerId": "p1", "modelId": "off", "config": { "enabled": false } },
                { "providerId": "  ", "modelId": "skip-blank", "config": {} },
                { "modelId": "skip-no-pid", "config": {} },
                {
                  "providerId": "p1", "modelId": "weird",
                  "config": { "properties": {
                    "contextWindow": "not-a-number",
                    "inputFormat": { "supportsImage": "true" }
                  }}
                }
              ],
              "manualProviderModelRules": [
                {
                  "providerId": "p2", "modelId": "m2",
                  "config": { "properties": { "contextWindow": 1024000 } }
                }
              ]
            }
          }
        }
    """.trimIndent()

    @Test
    fun `两组精确规则都解析，字段按叶子提取`() {
        val rules = PersonalModelConfigRules.load(config(fullJson))
            .associate { (it.providerId to it.modelId) to it }

        val m1 = rules.getValue("p1" to "m1")
        assertEquals(256_000L, m1.contextWindow)
        assertEquals(true, m1.supportsImage)
        assertEquals(false, m1.supportsVideo)
        assertEquals(true, m1.supportsImage)
        assertEquals(false, m1.supportsVideo)
        assertEquals(true, m1.supportsPdf)
        // m1 未声明 enabled（缺省=启用，解析为 null 不伪造值）
        assertNull(m1.enabled)

        // config.enabled 顶层键：true 启用 / 显式 false 停用（0.4.0 模型停用开关同源）
        assertEquals(true, rules.getValue("p1" to "sparse").enabled)
        assertEquals(false, rules.getValue("p1" to "off").enabled)

        // manualProviderModelRules 同构解析（官方 schema 两组同 key 互斥）
        val m2 = rules.getValue("p2" to "m2")
        assertEquals(1_024_000L, m2.contextWindow)
        assertNull(m2.supportsImage)
    }

    @Test
    fun `未声明叶子为 null，不伪造覆盖值`() {
        val rules = PersonalModelConfigRules.load(config(fullJson))
            .associate { (it.providerId to it.modelId) to it }
        val sparse = rules.getValue("p1" to "sparse")
        assertNull(sparse.contextWindow)
        assertNull(sparse.supportsImage)
        assertNull(sparse.supportsVideo)
        assertNull(sparse.supportsPdf)
    }

    @Test
    fun `畸形条目跳过但不殃及同文件其他规则`() {
        val keys = PersonalModelConfigRules.load(config(fullJson)).map { it.providerId to it.modelId }
        assert("p1" to "m1" in keys)
        assert("p1" to "sparse" in keys)
        assert("p1" to "weird" in keys) // 条目本身有效，只是 contextWindow 未声明
        assertEquals(5, keys.size) // 空 providerId 与缺 providerId 两条被丢弃
    }

    @Test
    fun `非数字 contextWindow 视为未声明，字符串布尔容忍`() {
        val weird = PersonalModelConfigRules.load(config(fullJson))
            .first { it.modelId == "weird" }
        assertNull(weird.contextWindow)
        assertEquals(true, weird.supportsImage)
    }

    @Test
    fun `同 key 重复时后者覆盖（manual 在 provider 之后，对齐官方 collect 序）`() {
        val json = """
          { "config": { "modelConfigRules": {
            "providerModelRules": [
              { "providerId": "p", "modelId": "m",
                "config": { "properties": { "contextWindow": 256000 } } }
            ],
            "manualProviderModelRules": [
              { "providerId": "p", "modelId": "m",
                "config": { "properties": { "contextWindow": 1024000 } } }
            ]
          }}}
        """.trimIndent()
        // load 保留两条有序记录，associate 成表时后者覆盖（与 panel newCliModelRules 同口径）
        val raw = PersonalModelConfigRules.load(config(json))
        assertEquals(2, raw.size)
        val rule = raw.associate { (it.providerId to it.modelId) to it }.getValue("p" to "m")
        assertEquals(1_024_000L, rule.contextWindow)
    }

    @Test
    fun `缺文件与空形态安全降级为空表`() {
        assertEquals(emptyList<PersonalModelConfigRules.ModelRule>(),
            PersonalModelConfigRules.load(tempDir.resolve("missing.json")))
        assertEquals(emptyList<PersonalModelConfigRules.ModelRule>(),
            PersonalModelConfigRules.load(config("{ broken json")))
        assertEquals(emptyList<PersonalModelConfigRules.ModelRule>(),
            PersonalModelConfigRules.load(config("{}")))
        assertEquals(emptyList<PersonalModelConfigRules.ModelRule>(),
            PersonalModelConfigRules.load(config("""{"config":{}}""")))
    }
}
