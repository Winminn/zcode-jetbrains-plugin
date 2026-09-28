package com.zcode.ideaplugin.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * modelSelection 档位解析（缺陷CX，issue#25）：send 恒带目录默认档 max 被服务端回合
 * 装配写回会话，冲掉用户设置的思考档。修复=带「最后已知会话档位」，跨模型窗口按目标
 * 模型值集裁决。spawn 路径不测：纯函数，无进程/目录依赖。
 */
class ThoughtLevelResolverTest {

    private val glm = listOf("disabled", "low", "high", "max")
    private val qwen = listOf("enabled", "off")

    @Test
    fun `会话档在值集内则原样使用`() {
        assertEquals("low", resolveSelectionReasoningLevel("low", glm, "max"))
        assertEquals("disabled", resolveSelectionReasoningLevel("disabled", glm, "max"))
        assertEquals("off", resolveSelectionReasoningLevel("off", qwen, "enabled"))
    }

    @Test
    fun `无会话档走目录默认档`() {
        assertEquals("max", resolveSelectionReasoningLevel(null, glm, "max"))
        assertEquals("enabled", resolveSelectionReasoningLevel(null, qwen, "enabled"))
        // 缓存 miss（进程重启后首条）= null 请求，行为与修复前一致
        assertNull(resolveSelectionReasoningLevel(null, glm, null))
    }

    @Test
    fun `跨模型非法档回退目录默认档`() {
        // 切模型落定窗口：缓存还是 GLM 的 max，目标模型 qwen 无 max → 回退默认（防服务端
        // 强校验回合失败）
        assertEquals("enabled", resolveSelectionReasoningLevel("max", qwen, "enabled"))
        assertEquals("enabled", resolveSelectionReasoningLevel("disabled", qwen, "enabled"))
    }

    @Test
    fun `值集缺失时请求档交服务端裁决`() {
        // 目录缺失（fail-soft null）：不武断回退，原样带出由服务端裁决
        assertEquals("low", resolveSelectionReasoningLevel("low", null, null))
        assertNull(resolveSelectionReasoningLevel(null, null, null))
    }

    @Test
    fun `空白串请求档视同无档`() {
        assertEquals("max", resolveSelectionReasoningLevel("", glm, "max"))
        assertEquals("max", resolveSelectionReasoningLevel("   ", glm, "max"))
    }

    @Test
    fun `精确匹配不做大小写归一`() {
        // 档位值全小写同源（服务端 settings 原样值）；大写变体不在值集 → 回退默认档，
        // 不放行大小写改写（服务端强校验按原串匹配，改写反而引入不确定性）
        assertEquals("max", resolveSelectionReasoningLevel("LOW", glm, "max"))
    }
}
