package com.zcode.ideaplugin.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * workspace settings → configOptions 官方形状转换（手机远程 readWorkspaceState 桥）。
 * 对齐官方 asar chunk-QSBP2774 BL（zcodeSessionSettingsToZCodeConfigOptions），
 * 2026-08-25 真机 HAR 定案：stub 形状错误导致 H5 输入框不渲染。
 */
class RemoteConfigOptionsTest {

    private val settings = buildJsonObject {
        put("mode", buildJsonObject { put("current", "yolo") })
        put("model", buildJsonObject {
            put("current", buildJsonObject {
                put("providerId", "anthropic"); put("modelId", "GLM-5.2")
            })
            put("available", JsonArray(listOf(buildJsonObject {
                put("label", "GLM-5.2"); put("providerLabel", "anthropic")
                put("ref", buildJsonObject {
                    put("providerId", "anthropic"); put("modelId", "GLM-5.2")
                })
                put("reasoning", buildJsonObject {
                    put("enabled", true)
                    put("defaultLevel", "max")
                    put("levels", JsonArray(listOf(
                        buildJsonObject { put("value", "max"); put("label", "max") },
                        buildJsonObject { put("value", "high"); put("label", "high") },
                    )))
                })
            })))
        })
        put("thoughtLevel", buildJsonObject {
            put("enabled", true)
            put("current", "max")
            put("defaultLevel", "max")
            put("available", JsonArray(listOf(
                buildJsonObject { put("value", "max"); put("label", "max") },
                buildJsonObject { put("value", "high"); put("label", "high") },
            )))
        })
    }

    private fun item(opt: JsonObject, id: String) = opt["configOptions"]!!.jsonArray.first {
        it.jsonObject["id"]!!.jsonPrimitive.content == id
    }.jsonObject

    @Test
    fun `Model 配置项形状对齐官方`() {
        val out = buildJsonObject { put("configOptions", buildRemoteConfigOptions(settings)) }
        val model = item(out, "model")
        assertEquals("Model", model["name"]!!.jsonPrimitive.content)
        assertEquals("select", model["type"]!!.jsonPrimitive.content)
        // currentValue = formatZCodeModelRef(model.current)
        assertEquals("anthropic/GLM-5.2", model["currentValue"]!!.jsonPrimitive.content)
        val opt = model["options"]!!.jsonArray[0].jsonObject
        assertEquals("anthropic/GLM-5.2", opt["value"]!!.jsonPrimitive.content)
        assertEquals("GLM-5.2", opt["name"]!!.jsonPrimitive.content)
        assertEquals("anthropic", opt["modelProviderId"]!!.jsonPrimitive.content)
        assertEquals("anthropic", opt["modelProviderName"]!!.jsonPrimitive.content)
        // reasoning.enabled=true → modelThoughtLevels + defaultLevel
        assertEquals(listOf("max", "high"),
            opt["modelThoughtLevels"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("max", opt["modelDefaultThoughtLevel"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Mode 配置项固定四项与回退`() {
        val out = buildJsonObject { put("configOptions", buildRemoteConfigOptions(settings)) }
        val mode = item(out, "mode")
        assertEquals("yolo", mode["currentValue"]!!.jsonPrimitive.content)
        assertEquals(listOf("build", "edit", "plan", "yolo"),
            mode["options"]!!.jsonArray.map { it.jsonObject["value"]!!.jsonPrimitive.content })
        // 未知 mode 回退 build
        val bad = buildJsonObject { put("mode", buildJsonObject { put("current", "hack") }) }
        assertEquals("build", normalizeRemoteMode(bad["mode"]!!.jsonObject["current"]!!.jsonPrimitive.content))
    }

    @Test
    fun `Thought Level 配置项随 enabled 出现`() {
        val out = buildJsonObject { put("configOptions", buildRemoteConfigOptions(settings)) }
        val tl = item(out, "thought_level")
        assertEquals("Thought Level", tl["name"]!!.jsonPrimitive.content)
        assertEquals("max", tl["currentValue"]!!.jsonPrimitive.content)
        assertEquals(listOf("max", "high"),
            tl["options"]!!.jsonArray.map { it.jsonObject["value"]!!.jsonPrimitive.content })
        // disabled 时不出现
        val disabled = buildJsonObject {
            put("mode", buildJsonObject { put("current", "yolo") })
            put("thoughtLevel", buildJsonObject { put("enabled", false) })
        }
        val out2 = buildJsonObject { put("configOptions", buildRemoteConfigOptions(disabled)) }
        assertEquals(listOf("model", "mode"),
            out2["configOptions"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
    }

    @Test
    fun `variant 模型引用追加美元段`() {
        val ref = buildJsonObject {
            put("providerId", "27d2ecde"); put("modelId", "deepseek-v4-flash"); put("variant", "max")
        }
        assertEquals("27d2ecde/deepseek-v4-flash\$max", formatZCodeModelRef(ref))
        val noVariant = buildJsonObject { put("providerId", "a"); put("modelId", "b") }
        assertEquals("a/b", formatZCodeModelRef(noVariant))
    }

    @Test
    fun `缺字段容错不抛异常`() {
        val empty = buildJsonObject {}
        val out = buildJsonObject { put("configOptions", buildRemoteConfigOptions(empty)) }
        val model = item(out, "model")
        assertEquals("", model["currentValue"]!!.jsonPrimitive.content)
        assertEquals(0, model["options"]!!.jsonArray.size)
    }
}
