package com.zcode.ideaplugin.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AI 提交信息附加要求读取（kv 通道解析）回归 */
class ZCodeCommitPromptConfigTest {

    @Test
    fun `kv 原文含提示词键时解析出文本`() {
        val kv = """{"zcode.commit.prompt":"类型标记用 feat#/fix# 风格"}"""
        assertEquals("类型标记用 feat#/fix# 风格", ZCodeCommitPromptConfig.parse(kv))
    }

    @Test
    fun `键缺失或空白回空串`() {
        assertEquals("", ZCodeCommitPromptConfig.parse("""{"other.key":"x"}"""))
        assertEquals("", ZCodeCommitPromptConfig.parse("""{"zcode.commit.prompt":"   "}"""))
        assertEquals("", ZCodeCommitPromptConfig.parse(null))
        assertEquals("", ZCodeCommitPromptConfig.parse(""))
    }

    @Test
    fun `损坏 JSON 不抛异常回空串`() {
        assertTrue(ZCodeCommitPromptConfig.parse("{broken").isEmpty())
    }
}
