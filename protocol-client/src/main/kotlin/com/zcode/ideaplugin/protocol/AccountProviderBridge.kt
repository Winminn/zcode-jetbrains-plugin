package com.zcode.ideaplugin.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * 账号渠道供给桥——裸 app-server 激活 zhipu-account（登录型）渠道的官方正道。
 *
 * 总根因（2026-09-21 开源仓库源码勘查，docs/internal/design-research/zcode开源仓库源码分析）：
 * 不带 Desktop Host 的 app-server Account Source 走 fail-closed（packages/provider/src/
 * sources.ts createFailClosedAccountProviderConfigSnapshot），所有 zhipu-account 渠道
 * entitled=false 退出 Registry——此前插件一系列 provider 玄学（渠道缺失/模型列表缺账号
 * 渠道/连接失败）的总根源。官方正道两条，本桥各实现一半：
 *
 * 1. 推送 `provider/updateAccountConfig`（Account Overlay 内存资格位，协议层只此一途；
 *    Desktop Host 同构做法 zcodeAgentService.syncAccountProviderConfigToClient）；
 * 2. 应答反向请求 `interaction/requestProviderRuntimeHeaders`（模型请求 attempt 前按
 *    accountAccess.mode 供给 apiKey；adapters/src/model/runner.ts——仅 zhipu-account
 *    Model 挂此端口，普通 apiKey 渠道不进）。
 *
 * 关键约束（全部源码实证，违一则静默失效）：
 * - basedOnZCodeBuiltinRevision 必须与 CLI 实际 active Built-in 文件逐字符一致
 *   （`zcode-builtin:<json.revision>:<sha256(absolutePath)>`，zcode-builtin-provider-
 *   config-source.ts L41/L208-218）；不匹配时 Registry 冻结上一份快照不发布（registry-
 *   service.ts L205-211），冷启动则 Registry 完全为空——所以 spawn 时显式传
 *   ZCODE_BUILTIN_PROVIDER_CONFIG_FILE env 对固定路径（显式对直接采用不做物化，
 *   provider-runtime-env.ts L61-66），两侧从同一路径计算。
 * - providers 值 strict 校验只允许 `{access:{type:"zhipu-account",entitled}, builtinModelIds?}`
 *   （config/schema.ts L26-30），带 api/group 等字段 parse 直接失败。
 * - entitled=true 的渠道 states 必须带 boolean current（process-provider-registry-
 *   runtime.ts L207-217 硬校验）；current 缺失仅显式 false 才阻塞资格（resolver.ts
 *   L253 accountCurrent !== false）。
 * - headersApplied:true 必须携带 requestAuth（runner-runtime-headers 校验非空）；
 *   官方所有 zhipu-account 场景都只回 apiKey（Authorization 由 CLI 自动派生），
 *   headers 仅 off-peak/组织头等特殊场景需要。
 * - team-coding-plan 的 apiKey 需远端 api_keys 列表/创建解析（accountProviderApiKeyResolver），
 *   插件不实现——该模式如实拒绝（headersApplied:false 快速失败，别拖 180s 超时）。
 */
object AccountProviderBridge {

    private val json = Json { ignoreUnknownKeys = true }

    // ============ 官方账号渠道 id（shared/model-provider-types.ts + zcode-builtin.json）============

    private val FAMILY_BY_PROVIDER = mapOf(
        "account:zai-individual-coding-plan" to "zai",
        "account:zai-team-coding-plan" to "zai",
        "account:zai-start-plan" to "zai",
        "account:bigmodel-individual-coding-plan" to "bigmodel",
        "account:bigmodel-team-coding-plan" to "bigmodel",
        "account:bigmodel-start-plan" to "bigmodel",
    )

    /** 凭证文件：config.json 同目录口径（跟随 dataBaseDir 迁移，与 [Credentials.familyOAuthToken] 一致） */
    fun credentialsPath(configPath: Path = Credentials.defaultConfigPath()): Path =
        configPath.resolveSibling("credentials.json")

    // ============ 凭证表 ============

    /**
     * 读 credentials.json 全表并逐值解密。单值解密失败跳过该值（按缺失处理）——
     * 一条坏值不该连坐整个账号渠道面；整体解析失败返回空表（调用方放弃推送）。
     */
    fun readCredentialEntries(credPath: Path = credentialsPath()): Map<String, String> {
        if (!credPath.isRegularFile()) return emptyMap()
        return try {
            val root = json.parseToJsonElement(credPath.readText()).jsonObject
            buildMap {
                root.forEach { (k, v) ->
                    val raw = (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@forEach
                    this[k] = try {
                        CredentialCipher.decrypt(raw)
                    } catch (_: CredentialCipher.CredentialDecryptException) {
                        return@forEach
                    }
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** 账号身份：oauth:{family}:user_info JSON 的 id 字段（官方 loadAccountIdentity 同源） */
    fun identityOf(entries: Map<String, String>, family: String): String? = try {
        entries["oauth:$family:user_info"]?.let {
            json.parseToJsonElement(it).jsonObject["id"]?.jsonPrimitive?.content?.takeIf { id -> id.isNotBlank() }
        }
    } catch (_: Exception) {
        null
    }

    // ============ 反向请求应答：requestAuth 供给 ============

    /**
     * 按模型请求的 accountAccess 选 apiKey（官方 accountProviderRequestAuthService
     * 材料选择判据的插件侧子集）。返回 null = 无法供给（调用方回 headersApplied:false）。
     *
     * @param providerId params.providerId，形如 "account:zai-individual-coding-plan"
     * @param mode params.accountAccess.mode（start-plan / individual-coding-plan / team-coding-plan）
     */
    fun requestAuthApiKey(providerId: String, mode: String?, entries: Map<String, String> = readCredentialEntries()): String? {
        val family = FAMILY_BY_PROVIDER[providerId] ?: return null
        return when (mode) {
            "start-plan" -> entries["zcodejwttoken"]?.takeIf { it.isNotBlank() }
            "individual-coding-plan" -> {
                val identity = identityOf(entries, family) ?: return null
                entries[individualPlanKey(providerId, identity)]?.takeIf { it.isNotBlank() }
            }
            // team 的 key 需远端 api_keys 解析，插件无此能力：不供给
            else -> null
        }
    }

    /** individual 套餐凭证 key（官方 accountProviderCredentialKey.ts L20-36；identity 走 JS encodeURIComponent 同款转义） */
    fun individualPlanKey(providerId: String, identity: String): String =
        "account-provider:coding-plan:$providerId:account:${jsEncodeURIComponent(identity)}:api-key"

    // ============ 额度凭证：setting.json 选中账号渠道 ============

    /** 账号渠道 kind 全集（FAMILY_BY_PROVIDER 条目 `account:<family>-<kind>` 后缀同源） */
    private val ACCOUNT_KINDS = setOf("individual-coding-plan", "team-coding-plan", "start-plan")

    /** 选中账号渠道的额度凭证候选（baseDomain 推导交调用方 quotaCredentialsOf） */
    class SelectedAccountCredential(
        val providerId: String,
        val providerName: String,
        val baseUrl: String,
        val apiKey: String,
    )

    /**
     * setting.json 选中账号渠道的额度凭证（2026-09-22 v2 额度链补口）：
     * `providerFamilyConnectionSelections[providerFamilyDomain].kind` 指向账号渠道时，
     * 取该渠道目录条目的 baseUrl + [requestAuthApiKey] 解密 key（与
     * requestProviderRuntimeHeaders 供给同源——账号渠道计费态不在 provider_config.json，
     * 明文链扫不到）。team（无材料）/ captcha 网关（start-plan）/ 选择非账号渠道 /
     * 任一材料缺失 → null，调用方落回 provider_config.json 明文链。
     */
    fun selectedAccountCredential(
        zcodePath: Path?,
        home: String = System.getProperty("user.home") ?: ".",
        entries: Map<String, String> = readCredentialEntries(),
    ): SelectedAccountCredential? {
        val setting = Path.of(home, ".zcode", "v2", "setting.json")
        val (providerId, mode) = try {
            if (!setting.isRegularFile()) return null
            val root = json.parseToJsonElement(setting.readText()).jsonObject
            val selections = root["providerFamilyConnectionSelections"]?.jsonObject ?: return null
            val domain = root["providerFamilyDomain"]?.jsonPrimitive?.contentOrNull
                ?: selections.keys.firstOrNull()
                ?: return null
            val kind = selections[domain]?.jsonObject?.get("kind")?.jsonPrimitive?.content
                ?.takeIf { it in ACCOUNT_KINDS }
                ?: return null
            val pid = "account:$domain-$kind"
            // FAMILY_BY_PROVIDER 复核：kind 形状漂移时不误挂未知渠道
            if (pid in FAMILY_BY_PROVIDER) pid to kind else return null
        } catch (_: Exception) {
            return null
        }
        val entry = BuiltinModelCatalog.accountProviderEntries(zcodePath, home)
            .find { it.providerId == providerId } ?: return null
        if (RuntimeModels.isCaptchaGatedBaseUrl(entry.baseUrl)) return null
        val key = requestAuthApiKey(providerId, mode, entries) ?: return null
        return SelectedAccountCredential(providerId, entry.providerName, entry.baseUrl, key)
    }

    /** JS encodeURIComponent 语义（Java URLEncoder 的空格→+ 与 !'()* 差异在此对齐） */
    private fun jsEncodeURIComponent(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (c.isLetterOrDigit() || c in "-_.~!*'()") append(c)
            else append('%').append(String.format("%02X", b))
        }
    }

    // ============ 推送构造：Account Overlay ============

    /** 推送参数（provider/updateAccountConfig params），sessionReady 后由 client 发送 */
    data class AccountOverlay(
        val revision: String,
        val basedOnZCodeBuiltinRevision: String,
        val providers: JsonObject,
        val states: JsonObject,
    )

    /**
     * 构造 Account Overlay；无可激活渠道 / Built-in 文件不可读返回 null（不推送）。
     *
     * 激活渠道 = [activatableAccountChannels] 同一判定（目录条目 × 端点门控 × 凭证材料）。
     * states.current 恒 true：插件侧全部视为可用（official CLI 硬校验要求 boolean）。
     */
    fun buildAccountOverlay(
        builtinFile: Path,
        entries: Map<String, String> = readCredentialEntries(),
    ): AccountOverlay? {
        val basedOn = builtinRevision(builtinFile) ?: return null
        val channels = BuiltinModelCatalog.accountProviderEntriesFromFile(builtinFile)
            .filter { isActivatable(it, entries) }
        if (channels.isEmpty()) return null
        val providers = LinkedHashMap<String, JsonObject>()
        val states = LinkedHashMap<String, JsonObject>()
        for (c in channels) {
            providers[c.providerId] = buildJsonObject {
                put("access", buildJsonObject {
                    put("type", "zhipu-account")
                    put("entitled", true)
                })
            }
            states[c.providerId] = buildJsonObject {
                put("availability", "available")
                put("entitled", true)
                put("current", true)
            }
        }
        // revision 内容寻址（官方 createAccountProviderConfigSnapshot 同构：内容变 → revision 变；
        // CLI 按 receivedRevision 去重，同值 status:"unchanged" 不重复 refresh）
        val revision = "account:zcgui:" + sha256(basedOn + providers.keys.sorted().joinToString(","))
        return AccountOverlay(
            revision = revision,
            basedOnZCodeBuiltinRevision = basedOn,
            providers = JsonObject(providers),
            states = JsonObject(states),
        )
    }

    /**
     * 可激活的账号渠道（推送、模型列表、发送守卫三方共用的唯一判定，2026-09-21 收紧）：
     * 1. 目录条目存在（模型权威 = builtinModelIds，官方 registry 同源）；
     * 2. mode 排除 team-coding-plan（apiKey 需远端 api_keys 解析，插件无材料）与
     *    off-peak（服务端票据）；
     * 3. 端点非 captcha 门控（start-plan 系 zcode.z.ai 的 zcode-plan 网关，滑块人机验证
     *    插件无法代答——真机教训：JWT 存在只说明登录过，不等于有套餐，激活了也只是
     *    每回合必失败）；
     * 4. 凭证材料齐备（individual 要 identity + coding-plan key）。
     */
    fun activatableAccountChannels(
        zcodePath: Path?,
        home: String = System.getProperty("user.home") ?: ".",
        entries: Map<String, String> = readCredentialEntries(),
    ): List<BuiltinModelCatalog.AccountProviderEntry> =
        BuiltinModelCatalog.accountProviderEntries(zcodePath, home).filter { isActivatable(it, entries) }

    private fun isActivatable(
        e: BuiltinModelCatalog.AccountProviderEntry,
        entries: Map<String, String>,
    ): Boolean {
        if (e.mode == "team-coding-plan" || e.mode == "off-peak") return false
        if (RuntimeModels.isCaptchaGatedBaseUrl(e.baseUrl)) return false
        val family = FAMILY_BY_PROVIDER[e.providerId] ?: return false
        return when (e.mode) {
            "start-plan" -> !entries["zcodejwttoken"].isNullOrBlank() && identityOf(entries, family) != null
            else -> {
                val identity = identityOf(entries, family) ?: return false
                !entries[individualPlanKey(e.providerId, identity)].isNullOrBlank()
            }
        }
    }

    /**
     * Built-in revision 计算：`zcode-builtin:<json 顶层 revision>:<sha256(绝对路径)>`。
     * 路径字符串必须与传给 CLI 的 env 逐字符一致（CLI 端 sha256(resolve(path))）——
     * 调用方应直接传写进 env 的同一个 Path。
     */
    fun builtinRevision(builtinFile: Path): String? {
        return try {
            if (!builtinFile.isRegularFile()) return null
            val root = json.parseToJsonElement(builtinFile.readText()).jsonObject
            val rev = (root["revision"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
            val absPath = builtinFile.toAbsolutePath().normalize().toString()
            "zcode-builtin:$rev:${sha256(absPath)}"
        } catch (_: Exception) {
            null
        }
    }

    /**
     * sha256 **hex 小写**——官方 sourceKey = createHash("sha256").update(path).digest("hex")
     * （zcode-builtin-provider-config-source.ts L41）。初版误用 Base64 导致 basedOn
     * 逐字符不匹配 → Registry 冻结不发布、推送整体失效（2026-09-21 对拍抓出）。
     */
    private fun sha256(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ============ spawn env 对 ============

    /** CLI provider 文件 env 对键名（provider-node/runtime-paths.ts） */
    const val ENV_BUILTIN = "ZCODE_BUILTIN_PROVIDER_CONFIG_FILE"
    const val ENV_PERSONAL = "ZCODE_PERSONAL_PROVIDER_CONFIG_FILE"

    /**
     * spawn 注入的 provider 文件 env 对（必须成对，缺失一侧 CLI 直接抛错）。
     * 显式对直接采用、不做 SEA 物化缓存（provider-runtime-env.ts L61-66）——路径受控
     * 后 revision 两侧可精确对齐，顺带绕开多进程共享缓存撕裂（0.3.x 实踩 -32603）。
     *
     * Built-in 候选序沿用 [BuiltinModelCatalog] 目录定位的读序：v2/runtime CDN 缓存
     * （用户目录可写，CDN 60s 刷新可落盘）→ 安装目录自带（只读，刷新失败仅 stderr
     * 提示不影响功能）→ AppData 兜底。personal 走 [Credentials.personalProviderConfigPath]
     * （跟随 dataBaseDir）。
     */
    fun buildProviderFileEnv(zcodePath: Path?): Map<String, String> {
        val builtin = BuiltinModelCatalog.locateBuiltinFile(zcodePath)
            ?: return emptyMap()
        return mapOf(
            ENV_BUILTIN to builtin.toAbsolutePath().normalize().toString(),
            ENV_PERSONAL to Credentials.personalProviderConfigPath().toAbsolutePath().normalize().toString(),
        )
    }
}
