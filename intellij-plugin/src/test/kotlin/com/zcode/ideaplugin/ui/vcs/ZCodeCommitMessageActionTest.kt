package com.zcode.ideaplugin.ui.vcs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AI Commit Message（C1）纯函数回归：prompt 拼装与模型输出清洗。
 * （Action 的 diff 生成走 git4idea，属集成面，由真机/IAB 验证承担。）
 */
class ZCodeCommitMessageActionTest {

    @Test
    fun `buildPrompt 包含规约与完整 diff`() {
        val diff = "diff --git a/A.kt b/A.kt\n@@ -1 +1 @@\n-old\n+new\n"
        val prompt = ZCodeCommitMessageAction.buildPrompt(diff)
        assertTrue(prompt.contains("Conventional Commits"))
        assertTrue(prompt.contains("feat, fix"))
        assertTrue(prompt.endsWith(diff))
    }

    @Test
    fun `buildPrompt 风格参照段存在时声明风格优先（fix# 仓库不出括号 scope）`() {
        val prompt = ZCodeCommitMessageAction.buildPrompt(
            "diff",
            styleSubjects = listOf("feat# 逐轮文件更改条", "fix# 修复窄窗口放大模糊"),
        )
        assertTrue(prompt.contains("近期提交风格"))
        assertTrue(prompt.contains("feat# 逐轮文件更改条"))
        assertTrue(prompt.contains("以近期提交风格为准"))
    }

    @Test
    fun `buildPrompt 附加要求段置入且在风格段之后`() {
        val prompt = ZCodeCommitMessageAction.buildPrompt(
            "diff",
            styleSubjects = listOf("fix# x"),
            extraPrompt = "  正文必须两行以内  ",
        )
        assertTrue(prompt.contains("用户附加要求"))
        assertTrue(prompt.contains("正文必须两行以内"))
        assertTrue(prompt.indexOf("近期提交风格") < prompt.indexOf("用户附加要求"))
    }

    @Test
    fun `buildPrompt 空风格与空白附加要求不产出空段落`() {
        val prompt = ZCodeCommitMessageAction.buildPrompt("diff", styleSubjects = emptyList(), extraPrompt = "   ")
        assertFalse(prompt.contains("近期提交风格"))
        assertFalse(prompt.contains("用户附加要求"))
    }

    @Test
    fun `extractMessage 原样返回裸文本`() {
        assertEquals(
            "feat: add login",
            ZCodeCommitMessageAction.extractMessage("feat: add login"),
        )
    }

    @Test
    fun `extractMessage 剥掉 markdown 围栏（模型不听只输出指令的兜底）`() {
        val fenced = "```\nfix: correct null check\n\n- guard empty input\n```"
        assertEquals("fix: correct null check\n\n- guard empty input", ZCodeCommitMessageAction.extractMessage(fenced))
    }

    @Test
    fun `extractMessage 剥带语言标注的围栏`() {
        assertEquals("docs: readme", ZCodeCommitMessageAction.extractMessage("```text\ndocs: readme\n```"))
    }

    @Test
    fun `extractMessage 空白收敛为空（空结果走失败分支）`() {
        assertEquals("", ZCodeCommitMessageAction.extractMessage("   \n  "))
    }
}
