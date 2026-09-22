package com.zcode.ideaplugin.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * 终端 Shell 偏好（插件自有配置，2026-09-21 开源协议面对照新增）
 *
 * 存储：复用 webview kv 通道（PropertiesComponent KEY_WEBVIEW_KV）的
 * `zcode.terminalShell.config` 键——行为设置页下拉写入，Kotlin 侧即时读取，
 * requestRuntimePreferences 应答时转成官方 integratedTerminalShellSelection 形状：
 * `{mode:"shell", dialect:"cmd"|"git-bash", id, label, path}`（validationAppSettings.ts；
 * 无 powershell 档位）。auto/缺省 = 不回该字段，CLI 自行探测（win32 自动找 Git Bash，
 * 找不到落 legacy-shell）。
 *
 * path 必须真实可执行（CLI accessSync 校验，失败自动落回 auto 探测），故 git-bash
 * 档位在下拉时就地探测常见安装路径，探不到则选项禁用。
 */
object ZCodeTerminalShellConfig {

    /** kv 通道里的配置键（前端行为设置页同源）*/
    const val KV_KEY = "zcode.terminalShell.config"

    /** 用户可选档位：auto=CLI 自动探测（默认）；git-bash/cmd=显式指定 */
    data class Config(val selection: String = "auto")

    /** requestRuntimePreferences 应答用的 shell 选择（null=不回字段走 auto）*/
    data class ShellSelection(val dialect: String, val id: String, val label: String, val path: String)

    fun readConfig(): Config = try {
        parseConfig(
            com.intellij.ide.util.PropertiesComponent.getInstance()
                .getValue(ZCodeLanguageService.KEY_WEBVIEW_KV)
        )
    } catch (_: Exception) {
        Config()
    }

    /** 纯解析（kvstore JSON 原文 → Config；缺失/损坏回 auto）*/
    internal fun parseConfig(kvStoreRaw: String?): Config {
        val root = try {
            Json.parseToJsonElement(kvStoreRaw ?: return Config()) as? JsonObject ?: return Config()
        } catch (_: Exception) {
            return Config()
        }
        val conf = try {
            (root[KV_KEY] as? JsonPrimitive)?.content ?: return Config()
        } catch (_: Exception) {
            return Config()
        }
        val obj = try {
            Json.parseToJsonElement(conf) as? JsonObject ?: return Config()
        } catch (_: Exception) {
            return Config()
        }
        val sel = (obj["selection"] as? JsonPrimitive)?.content ?: "auto"
        return Config(if (sel in KNOWN_SELECTIONS) sel else "auto")
    }

    /**
     * 解析成应答形状（scope 无关——shell 偏好不分会话）。返回 null = auto，应答不带
     * integratedTerminalShell 字段；git-bash 探测不到真实路径也回 null（显式指定但
     * path 不真实会被 CLI accessSync 拒掉落回 legacy-shell，不如一开始就走 auto 探测）。
     */
    fun resolveSelection(config: Config = readConfig()): ShellSelection? = when (config.selection) {
        "git-bash" -> resolveGitBashPath()?.let {
            ShellSelection("git-bash", "plugin:git-bash", "Git Bash", it)
        }
        "cmd" -> cmdPath()?.let {
            ShellSelection("cmd", "plugin:cmd", "Command Prompt", it)
        }
        else -> null
    }

    /** Git Bash 常见安装位置探测（首个存在的 bash.exe；无则 null）*/
    fun resolveGitBashPath(): String? {
        val candidates = buildList {
            val pf = System.getenv("ProgramFiles") ?: "C:\\Program Files"
            val pf86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
            val localAppData = System.getenv("LOCALAPPDATA")
            add("$pf\\Git\\bin\\bash.exe")
            add("$pf\\Git\\usr\\bin\\bash.exe")
            add("$pf86\\Git\\bin\\bash.exe")
            localAppData?.let {
                add("$it\\Programs\\Git\\bin\\bash.exe")
            }
            System.getenv("GIT_INSTALL_ROOT")?.let { add("$it\\bin\\bash.exe") }
        }
        return candidates.firstOrNull { runCatching { Files.isRegularFile(Path.of(it)) }.getOrDefault(false) }
    }

    /** cmd.exe 路径（SystemRoot 探测，恒在）*/
    private fun cmdPath(): String? {
        val root = System.getenv("SystemRoot") ?: "C:\\Windows"
        val p = "$root\\System32\\cmd.exe"
        return if (runCatching { Files.isRegularFile(Path.of(p)) }.getOrDefault(false)) p else null
    }

    private val KNOWN_SELECTIONS = setOf("auto", "git-bash", "cmd")
}
