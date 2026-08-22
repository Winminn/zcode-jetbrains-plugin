package com.zcode.ideaplugin.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ZCodeNotifyService.parseConfig 纯解析测试（webview kv 通道 JSON → NotifyConfig）：
 * 与前端 utils/notifyConfig.ts 的读写语义对齐——缺失/损坏/类型不对逐字段回默认，
 * 保证"前端写、Kotlin 读"两端默认值一致（默认关闭；无焦点门控字段）。
 */
class ZCodeNotifyServiceTest {

    private fun kv(confJson: String?): String =
        """{"zcode.language":"zh","zcode.notify.config":${confJson?.let { "\"$it\"" } ?: "null"}}"""

    @Test
    fun `kvstore 缺失或无配置键回默认值（默认关闭）`() {
        assertEquals(ZCodeNotifyService.NotifyConfig(), ZCodeNotifyService.parseConfig(null))
        assertEquals(ZCodeNotifyService.NotifyConfig(), ZCodeNotifyService.parseConfig("""{"zcode.language":"zh"}"""))
    }

    @Test
    fun `正常解析开关（开启即始终弹，无焦点门控字段）`() {
        val raw = kv("""{\"notifyEnabled\":true}""")
        assertTrue(ZCodeNotifyService.parseConfig(raw).notifyEnabled)
    }

    @Test
    fun `部分字段缺席与类型不对回默认（字符串 false 不生效，对齐前端语义）`() {
        val badType = kv("""{\"notifyEnabled\":\"false\"}""")
        assertFalse(ZCodeNotifyService.parseConfig(badType).notifyEnabled)

        val badType2 = kv("""{\"notifyEnabled\":\"true\"}""")
        assertFalse(ZCodeNotifyService.parseConfig(badType2).notifyEnabled)
    }

    @Test
    fun `损坏 JSON 各级均回默认值不抛异常`() {
        assertEquals(ZCodeNotifyService.NotifyConfig(), ZCodeNotifyService.parseConfig("{broken"))
        assertEquals(ZCodeNotifyService.NotifyConfig(), ZCodeNotifyService.parseConfig(kv("{broken")))
    }

    @Test
    fun `默认值契约：默认关闭（前端 DEFAULT_NOTIFY_CONFIG 同源）`() {
        assertFalse(ZCodeNotifyService.NotifyConfig().notifyEnabled)
    }

    @Test
    fun `旧版遗留的 notifyOnlyUnfocused 字段被忽略`() {
        val raw = kv("""{\"notifyEnabled\":true,\"notifyOnlyUnfocused\":false}""")
        val c = ZCodeNotifyService.parseConfig(raw)
        assertTrue(c.notifyEnabled)
    }
}
