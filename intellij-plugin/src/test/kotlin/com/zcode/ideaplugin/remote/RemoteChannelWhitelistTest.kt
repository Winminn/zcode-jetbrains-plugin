package com.zcode.ideaplugin.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 路由白名单曾与 handle() when 分支双源漂移三次（oauth/settings-sync/bots、
 * model-selection/provider-settings），漏项被 KNOWN_CHANNELS 挡在 handler 门外
 * 全回 Method not found——H5 模型选择器「加载失败」（2026-09-23 真机日志实锤）。
 * 现白名单由 RemoteChannelHandlers.CHANNELS 单一派生；本测试钉死：
 * ① 派生关系成立；② 本缺陷两 channel 恒在白名单；③ CHANNELS 改动必须过字面量清单
 * 这道有意识更新门。
 */
class RemoteChannelWhitelistTest {

    @Test
    fun `whitelist covers all handled channels`() {
        assertTrue(RemoteChannelRouter.KNOWN_CHANNELS.containsAll(RemoteChannelHandlers.CHANNELS))
    }

    @Test
    fun `defect channels are whitelisted`() {
        assertTrue("model-selection" in RemoteChannelRouter.KNOWN_CHANNELS)
        assertTrue("provider-settings" in RemoteChannelRouter.KNOWN_CHANNELS)
    }

    @Test
    fun `handled channels match exhaustive literal list`() {
        assertEquals(
            setOf(
                "setting", "oauth", "model-provider", "model-selection", "provider-settings",
                "zcode-agent", "zcode-task", "zcode-session", "window-controller", "git",
                "usage-stats", "coding-plan-subscription", "off-peak-task", "subagents",
                "skills", "client-scenes", "settings-sync", "bots",
            ),
            RemoteChannelHandlers.CHANNELS,
        )
    }
}
