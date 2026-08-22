package com.zcode.ideaplugin.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * ZCodeBrowserSettingStore（内置浏览器设置，与 ZCode 客户端公用）单元测试。
 * 浏览器控制为**只读展示**（用户决定：修改以客户端为准）——插件侧只验证读取判据：
 * data/ 目录存在即启用、cache/ 有版本即已安装。写入路径已随可写开关一并移除。
 */
class ZCodeBrowserSettingStoreTest {

    @TempDir
    lateinit var home: File

    private fun installPlugin(version: String = "0.3.0") {
        val dir = File(
            File(File(File(File(File(home, ".zcode"), "cli"), "plugins"), "cache"), "zcode-plugins-official"),
            "browser-use/$version"
        )
        dir.mkdirs()
        File(dir, "plugin.json").writeText("{}", Charsets.UTF_8)
    }

    /** data 启用目录（zcode.cjs discoverPluginsSync 同判据路径）*/
    private fun dataDirOf(): File =
        File(File(File(File(File(home, ".zcode"), "cli"), "plugins"), "data"), "browser-use@zcode-plugins-official")

    @Test
    fun `启用判据：data 目录存在即启用（空目录也算）`() {
        assertFalse(ZCodeBrowserSettingStore.isBrowserControlEnabled(home.absolutePath))
        val dir = dataDirOf()
        dir.mkdirs()
        // 目录内容为空——与 zcode.cjs 判据一致：目录存在即启用
        assertTrue(dir.listFiles().isNullOrEmpty())
        assertTrue(ZCodeBrowserSettingStore.isBrowserControlEnabled(home.absolutePath))
    }

    @Test
    fun `安装判据：cache 下有版本目录才算已安装`() {
        assertFalse(ZCodeBrowserSettingStore.isPluginInstalled(home.absolutePath))
        installPlugin()
        assertTrue(ZCodeBrowserSettingStore.isPluginInstalled(home.absolutePath))
    }

    @Test
    fun `忽略证书键与客户端共用（缺失默认 false，单键写入保留其余）`() {
        assertFalse(ZCodeClientSettingStore.readEmbeddedBrowserInsecure(home.absolutePath))

        val setting = ZCodeClientSettingStore.settingPath(home.absolutePath)
        setting.parentFile!!.mkdirs()
        setting.writeText(
            """{"locale":"zh-CN","embeddedBrowserAllowInsecureCertificates":false,"memoryEnabled":true}""",
            Charsets.UTF_8,
        )
        assertTrue(ZCodeClientSettingStore.writeEmbeddedBrowserInsecure(true, home.absolutePath))
        assertTrue(ZCodeClientSettingStore.readEmbeddedBrowserInsecure(home.absolutePath))

        // 只改证书键：locale 与 memoryEnabled 原样保留
        val text = setting.readText(Charsets.UTF_8)
        assertTrue("\"locale\":\"zh-CN\"" in text.replace(" ", ""), text)
        assertTrue("\"memoryEnabled\":true" in text.replace(" ", ""), text)
        assertEquals(
            "true",
            kotlinx.serialization.json.Json.parseToJsonElement(text)
                .let { (it as kotlinx.serialization.json.JsonObject)["embeddedBrowserAllowInsecureCertificates"].toString() },
        )
    }

    @Test
    fun `JCEF 启动参数快照：provider 未调时不提示待重启，调用后随期望值变化提示`() {
        // 复位快照（其他用例可能污染 object 状态）
        ZCodeBrowserSettingStore.ignoreCertAppliedAtStartup = null
        val h = home.absolutePath

        val setting = ZCodeClientSettingStore.settingPath(h)
        setting.parentFile!!.mkdirs()
        setting.writeText("""{"embeddedBrowserAllowInsecureCertificates":false}""", Charsets.UTF_8)

        // JCEF 未初始化（快照 null）：不提示待重启；展示值回退期望值
        assertFalse(ZCodeBrowserSettingStore.isIgnoreCertPendingRestart(h))
        assertFalse(ZCodeBrowserSettingStore.isIgnoreCertFlagActive(h))

        // provider 首次调用（= 本 JCEF 进程参数已定）：关闭态无 flag
        assertEquals(emptyList<String>(), ZCodeBrowserSettingStore.jcefStartupArgs(h))
        assertFalse(ZCodeBrowserSettingStore.isIgnoreCertPendingRestart(h))

        // 改开关（写 setting.json）：已生效(false) != 期望(true) → 待重启
        ZCodeClientSettingStore.writeEmbeddedBrowserInsecure(true, h)
        assertTrue(ZCodeBrowserSettingStore.isIgnoreCertPendingRestart(h))
        assertFalse(ZCodeBrowserSettingStore.isIgnoreCertFlagActive(h), "重启前生效值仍是 false")

        // 重启模拟：provider 重新调用读到 true → 一致，不再提示
        assertEquals(listOf("--ignore-certificate-errors"), ZCodeBrowserSettingStore.jcefStartupArgs(h))
        assertFalse(ZCodeBrowserSettingStore.isIgnoreCertPendingRestart(h))
        assertTrue(ZCodeBrowserSettingStore.isIgnoreCertFlagActive(h))

        ZCodeBrowserSettingStore.ignoreCertAppliedAtStartup = null
    }
}
