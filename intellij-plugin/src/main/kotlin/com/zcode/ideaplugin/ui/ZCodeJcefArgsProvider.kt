package com.zcode.ideaplugin.ui

import com.intellij.ui.jcef.JBCefAppRequiredArgumentsProvider

/**
 * JCEF 启动参数 provider（EP com.intellij.jcef.appRequiredArgumentsProvider，主 plugin.xml 注册）：
 *
 * 内置浏览器「忽略证书校验」开启时把 --ignore-certificate-errors 注入 chromium 命令行。
 * Chromium 的证书校验开关是进程级，平台唯一官方注入通道就是这个 EP——
 * SettingsHelper.loadArgs 在 JBCefApp 初始化（首个 JCEF browser 创建）时合并各
 * provider 的 options。因此改开关后需重启 IDE 生效（与 ZCode 客户端文案一致）；
 * 首次调用即"本 JCEF 进程参数已定"，顺带记录快照供设置页展示"待重启"状态。
 */
class ZCodeJcefArgsProvider : JBCefAppRequiredArgumentsProvider {

    override val options: List<String>
        get() = ZCodeBrowserSettingStore.jcefStartupArgs()
}
