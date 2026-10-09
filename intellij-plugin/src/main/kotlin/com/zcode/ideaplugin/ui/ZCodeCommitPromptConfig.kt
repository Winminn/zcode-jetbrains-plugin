package com.zcode.ideaplugin.ui

import kotlinx.serialization.json.JsonPrimitive

/**
 * AI 提交信息附加要求（AI Commit 按钮，C1）
 *
 * 存储：webview kv 通道（PropertiesComponent KEY_WEBVIEW_KV）的 `zcode.commit.prompt`
 * 键——前端行为设置页（utils/commitPromptConfig.ts）失焦即写，Kotlin 侧生成时即时
 * 读取，无消息往返（ZCodeAskUserConfig 同款通道）。空/缺失 = 未配置（只用内置规约
 * +仓库近期提交风格参照）。
 */
object ZCodeCommitPromptConfig {

    /** kv 通道里的配置键（前端 utils/commitPromptConfig.ts 同源）*/
    const val KV_KEY = "zcode.commit.prompt"

    /** 读附加提示词（缺失/损坏回空串，绝不抛异常打断提交流程）*/
    fun readPrompt(): String = try {
        parse(
            com.intellij.ide.util.PropertiesComponent.getInstance()
                .getValue(com.zcode.ideaplugin.ui.ZCodeLanguageService.KEY_WEBVIEW_KV)
        )
    } catch (_: Exception) {
        ""
    }

    /** 纯解析（单测覆盖）：kvstore JSON 原文 → 提示词文本（trim 后空 = 未配置）*/
    internal fun parse(kvStoreRaw: String?): String {
        if (kvStoreRaw.isNullOrBlank()) return ""
        val root = try {
            kotlinx.serialization.json.Json.parseToJsonElement(kvStoreRaw)
                as? kotlinx.serialization.json.JsonObject ?: return ""
        } catch (_: Exception) {
            return ""
        }
        return try {
            (root[KV_KEY] as? JsonPrimitive)?.content?.trim() ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}
