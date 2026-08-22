package com.zcode.ideaplugin.ui

import java.io.File

/**
 * 内置浏览器设置（与 ZCode 客户端公用配置，对齐客户端 设置→浏览器 三块能力）：
 *
 * 1. 浏览器控制 = **只读展示**（用户决定：修改以 ZCode 客户端为准）。
 *    zcode.cjs discoverPluginsSync 以 `~/.zcode/cli/plugins/data/` 下的目录为启用判据
 *    （目录存在即启用，内容为空；实测 `plugins list` 的 [enabled] 与 data 目录一一对应），
 *    客户端侧开关操作的也是同一目录——插件侧读目录即得客户端的最新状态。
 *    （曾实现可写开关，但 zcode.cjs 每次加载插件都 mkdirSync 重建 data 目录——长驻
 *    app-server 会把删除的目录"复活"，禁用必须重启后端、中断进行中对话，体验代价
 *    过大，改为只读。）
 *
 * 2. 忽略证书校验 = ~/.zcode/v2/setting.json 的 embeddedBrowserAllowInsecureCertificates
 *    （ZCodeClientSettingStore 读写，客户端同键）。Chromium 的
 *    --ignore-certificate-errors 是进程级开关，注入通道是平台 EP
 *    com.intellij.jcef.appRequiredArgumentsProvider（ZCodeJcefArgsProvider，
 *    SettingsHelper.loadArgs 在 JBCefApp 初始化时合并）——JCEF 进程已启动后参数
 *    不会重读，故"修改后需重启生效"，与客户端文案一致。
 *    ⚠️ 平台没有 `ide.browser.jcef.args` 这个 registry key（实测 2024.1/2026.1 均未定义，
 *    Registry.get 对未定义 key 直接抛 "is not defined"——0.2.3 测试包报错根因）。
 */
object ZCodeBrowserSettingStore {

    /** Browser Use 官方插件（zcode-plugins-official 市场）*/
    private const val PLUGIN_ID = "browser-use@zcode-plugins-official"

    private const val IGNORE_CERT_FLAG = "--ignore-certificate-errors"

    /** 本 JCEF 进程启动参数已定时的快照（provider 首次被调时记录；null=JCEF 尚未初始化）*/
    @Volatile
    internal var ignoreCertAppliedAtStartup: Boolean? = null

    /** ~/.zcode/cli/plugins/（config.json 同根；浏览器控制与本插件的其他 CLI 配置共用此树）*/
    fun pluginsRoot(home: String = System.getProperty("user.home")): File =
        File(File(File(home, ".zcode"), "cli"), "plugins")

    /** 启用判据目录（存在即启用；zcode.cjs yS() 规范化 pluginId 为目录名）*/
    private fun dataDir(home: String): File = File(File(pluginsRoot(home), "data"), PLUGIN_ID)

    /** 安装判据：cache/zcode-plugins-official/browser-use/ 下有任意版本目录 */
    fun isPluginInstalled(home: String = System.getProperty("user.home")): Boolean {
        val versions = File(File(File(pluginsRoot(home), "cache"), "zcode-plugins-official"), "browser-use")
        return versions.isDirectory && versions.list()?.isNotEmpty() == true
    }

    /** 浏览器控制是否开启（data 目录存在即启用；只读，客户端侧修改）*/
    fun isBrowserControlEnabled(home: String = System.getProperty("user.home")): Boolean =
        dataDir(home).isDirectory

    /**
     * JCEF 启动参数（ZCodeJcefArgsProvider.getOptions 的实现体）：
     * 读 setting.json 期望值，记录快照（供"待重启"判定），开启则返回证书 flag。
     */
    fun jcefStartupArgs(home: String = System.getProperty("user.home")): List<String> {
        val want = ZCodeClientSettingStore.readEmbeddedBrowserInsecure(home)
        ignoreCertAppliedAtStartup = want
        return if (want) listOf(IGNORE_CERT_FLAG) else emptyList()
    }

    /** 忽略证书当前是否已随本 JCEF 进程生效（未初始化时按期望值——下次启动即生效）*/
    fun isIgnoreCertFlagActive(home: String = System.getProperty("user.home")): Boolean =
        ignoreCertAppliedAtStartup ?: ZCodeClientSettingStore.readEmbeddedBrowserInsecure(home)

    /** 期望值与已生效值是否不一致（true = 改动尚未重启生效；JCEF 未初始化不算 pending）*/
    fun isIgnoreCertPendingRestart(home: String = System.getProperty("user.home")): Boolean {
        val applied = ignoreCertAppliedAtStartup ?: return false
        return applied != ZCodeClientSettingStore.readEmbeddedBrowserInsecure(home)
    }
}
