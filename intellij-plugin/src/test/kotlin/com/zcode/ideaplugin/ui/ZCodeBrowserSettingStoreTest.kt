package com.zcode.ideaplugin.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * ZCodeBrowserSettingStore（内置浏览器设置，与 ZCode 客户端公用）单元测试。
 * 浏览器控制为**只读展示**（用户决定：修改以客户端为准）——插件侧只验证读取判据：
 * data/ 目录存在即启用、cache/ 有版本即已安装。写入路径已随可写开关一并移除；
 * 「忽略证书校验」开关已整体移除（IntelliJ JCEF 的可信站点弹窗接管，开关无实际意义）。
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
}
