package com.zcode.ideaplugin.protocol

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * AccountProviderBridge 单元测试——裸 app-server 账号渠道供给链（updateAccountConfig
 * 推送构造 + requestProviderRuntimeHeaders 应答）。
 *
 * schema 依据（2026-09-21 开源源码）：
 * - providers 值 strict 只允许 {access:{type:"zhipu-account",entitled}, builtinModelIds?}
 * - entitled=true 渠道 states 必须带 boolean current
 * - builtin revision = "zcode-builtin:<json revision>:<sha256-hex(绝对路径)>"
 *
 * 激活判据（2026-09-21 真机教训后收紧）：目录条目 × mode 排除（team/off-peak）×
 * 端点门控（zcode-plan 网关）× 凭证材料——start-plan 的 JWT 存在只说明登录过，
 * 不等于有套餐，且其端点是滑块门控网关。
 */
class AccountProviderBridgeTest {

    /** 官方 zcode-builtin.json 同构样本：6 个 zhipu-account 条目（zai/bigmodel 各三档 + off-peak） */
    private fun builtinFile(revision: Any = 30): Path {
        fun acc(pid: String, mode: String, url: String) =
            """{"providerId":"$pid","providerName":"$pid-name","config":{"access":{"type":"zhipu-account","mode":"$mode"},"api":{"type":"anthropic-messages","baseUrl":"$url"},"builtinModelIds":["GLM-5.3","GLM-5.3-Flash"]}}"""
        val content = """
        {"revision": $revision, "config": {"providerConfigRules": {"providerRules": [
          ${acc("account:zai-individual-coding-plan", "individual-coding-plan", "https://api.z.ai/api/anthropic")},
          ${acc("account:zai-team-coding-plan", "team-coding-plan", "https://api.z.ai/api/anthropic")},
          ${acc("account:zai-start-plan", "start-plan", "https://zcode.z.ai/api/v1/zcode-plan/anthropic")},
          ${acc("account:bigmodel-individual-coding-plan", "individual-coding-plan", "https://open.bigmodel.cn/api/anthropic")},
          ${acc("account:bigmodel-team-coding-plan", "team-coding-plan", "https://open.bigmodel.cn/api/anthropic")},
          ${acc("account:bigmodel-start-plan", "start-plan", "https://zcode.z.ai/api/v1/zcode-plan/anthropic")},
          ${acc("account:bigmodel-offpeak-idle-plan", "off-peak", "https://zcode.z.ai/api/v1/off-peak/anthropic")}
        ]}}}
        """.trimIndent()
        val f = Files.createTempFile("zcode-builtin", ".json")
        Files.writeString(f, content)
        return f
    }

    /** 全凭证齐备的 credentials 表（zai + bigmodel 各 individual/start） */
    private fun fullEntries(): Map<String, String> = mapOf(
        "oauth:zai:user_info" to """{"id":"user-zai-01","name":"tester"}""",
        "oauth:bigmodel:user_info" to """{"id":"user-bigmodel-01","name":"tester"}""",
        "zcodejwttoken" to "jwt-token-value",
        "account-provider:coding-plan:account:zai-individual-coding-plan:account:user-zai-01:api-key" to "zai-plan-key",
        "account-provider:coding-plan:account:bigmodel-individual-coding-plan:account:user-bigmodel-01:api-key" to "bigmodel-plan-key",
    )

    // ===== requestAuthApiKey（反向请求应答判据）=====

    @Test
    fun `individual 模式按 identity 取 plan key`() {
        assertEquals(
            "zai-plan-key",
            AccountProviderBridge.requestAuthApiKey("account:zai-individual-coding-plan", "individual-coding-plan", fullEntries()),
        )
        assertEquals(
            "bigmodel-plan-key",
            AccountProviderBridge.requestAuthApiKey("account:bigmodel-individual-coding-plan", "individual-coding-plan", fullEntries()),
        )
    }

    @Test
    fun `start-plan 模式取 zcodejwttoken`() {
        assertEquals(
            "jwt-token-value",
            AccountProviderBridge.requestAuthApiKey("account:zai-start-plan", "start-plan", fullEntries()),
        )
    }

    @Test
    fun `team 模式与未知渠道不供给`() {
        assertNull(AccountProviderBridge.requestAuthApiKey("account:zai-team-coding-plan", "team-coding-plan", fullEntries()))
        assertNull(AccountProviderBridge.requestAuthApiKey("unknown-provider", "individual-coding-plan", fullEntries()))
        assertNull(AccountProviderBridge.requestAuthApiKey("account:zai-individual-coding-plan", null, fullEntries()))
    }

    @Test
    fun `凭证缺失不供给`() {
        // 无 plan key
        assertNull(AccountProviderBridge.requestAuthApiKey("account:zai-individual-coding-plan", "individual-coding-plan", emptyMap()))
        // 有 key 无 identity
        val noIdentity = mapOf(
            "account-provider:coding-plan:account:zai-individual-coding-plan:account:user-zai-01:api-key" to "k",
        )
        assertNull(AccountProviderBridge.requestAuthApiKey("account:zai-individual-coding-plan", "individual-coding-plan", noIdentity))
        // identity 坏 JSON
        val badIdentity = mapOf("oauth:zai:user_info" to "{not-json")
        assertNull(AccountProviderBridge.requestAuthApiKey("account:zai-individual-coding-plan", "individual-coding-plan", badIdentity))
    }

    // ===== buildAccountOverlay（推送构造）=====

    @Test
    fun `全凭证齐备时只激活 individual 渠道（start-plan 网关门控与 team、off-peak 模式均排除）`() {
        val overlay = AccountProviderBridge.buildAccountOverlay(builtinFile(), fullEntries())!!
        assertEquals(
            setOf(
                "account:zai-individual-coding-plan",
                "account:bigmodel-individual-coding-plan",
            ),
            overlay.providers.keys,
        )
        // providers 值 strict 形状：只有 access 节
        val zai = overlay.providers["account:zai-individual-coding-plan"]!!.jsonObject
        assertEquals(setOf("access"), zai.keys)
        assertEquals(setOf("type", "entitled"), zai["access"]!!.jsonObject.keys)
        // states：entitled=true 渠道必须带 boolean current（official CLI 硬校验）
        val state = overlay.states["account:zai-individual-coding-plan"]!!.jsonObject
        assertTrue(state.keys.containsAll(setOf("availability", "entitled", "current")))
        assertEquals(true, state["entitled"]!!.jsonPrimitive.boolean)
        assertEquals(true, state["current"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `无凭证时返回 null 不推送`() {
        assertNull(AccountProviderBridge.buildAccountOverlay(builtinFile(), emptyMap()))
        // 只有 identity 没有 key 同样不推
        assertNull(
            AccountProviderBridge.buildAccountOverlay(
                builtinFile(),
                mapOf("oauth:zai:user_info" to """{"id":"u1"}"""),
            ),
        )
        // 只有 JWT（start-plan 材料）没有 individual key：start-plan 被门控排除 → 无可激活
        assertNull(
            AccountProviderBridge.buildAccountOverlay(
                builtinFile(),
                mapOf(
                    "oauth:bigmodel:user_info" to """{"id":"u1"}""",
                    "zcodejwttoken" to "jwt",
                ),
            ),
        )
    }

    @Test
    fun `revision 内容寻址且 basedOn 逐字符对齐 builtin 文件`() {
        val f = builtinFile(30)
        val overlay = AccountProviderBridge.buildAccountOverlay(f, fullEntries())!!
        val expectedBasedOn = AccountProviderBridge.builtinRevision(f)!!
        assertEquals(expectedBasedOn, overlay.basedOnZCodeBuiltinRevision)
        assertTrue(expectedBasedOn.startsWith("zcode-builtin:30:"))
        // 同输入 revision 稳定（receivedRevision 去重依赖）
        assertEquals(
            overlay.revision,
            AccountProviderBridge.buildAccountOverlay(f, fullEntries())!!.revision,
        )
        // Built-in revision 变化 → basedOn 变化（CLI 端不匹配则 Registry 不发布）
        val f2 = builtinFile(31)
        assertTrue(AccountProviderBridge.builtinRevision(f2)!!.startsWith("zcode-builtin:31:"))
    }

    @Test
    fun `builtin 文件缺失或无 revision 返回 null`() {
        assertNull(AccountProviderBridge.builtinRevision(Path.of("definitely/not/exist.json")))
        val noRev = Files.createTempFile("no-rev", ".json")
        Files.writeString(noRev, """{"providerTemplates": {}}""")
        assertNull(AccountProviderBridge.builtinRevision(noRev))
    }

    // ===== individualPlanKey（官方 credential key 形状）=====

    @Test
    fun `plan key 形状与 identity 转义`() {
        assertEquals(
            "account-provider:coding-plan:account:zai-individual-coding-plan:account:user-01:api-key",
            AccountProviderBridge.individualPlanKey("account:zai-individual-coding-plan", "user-01"),
        )
        // 需转义字符走 JS encodeURIComponent 语义（空格 %20 而非 +）
        assertEquals(
            "account-provider:coding-plan:account:zai-individual-coding-plan:account:u%20id%2F1:api-key",
            AccountProviderBridge.individualPlanKey("account:zai-individual-coding-plan", "u id/1"),
        )
    }

    // ===== selectedAccountCredential（额度凭证：setting.json 选中账号渠道）=====

    /** 临时 home：setting.json 写激活态，zcode-builtin.json 落 runtime/provider（目录候选①） */
    private fun homeWithSetting(settingJson: String): String {
        val home = Files.createTempDirectory("acct-home")
        val v2 = Files.createDirectories(home.resolve(".zcode/v2"))
        Files.writeString(v2.resolve("setting.json"), settingJson)
        val runtime = Files.createDirectories(v2.resolve("runtime/provider"))
        Files.copy(builtinFile(), runtime.resolve("zcode-builtin.json"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return home.toString()
    }

    @Test
    fun `选中 individual 账号渠道返回解密 key 凭证`() {
        val home = homeWithSetting(
            """{"providerFamilyConnectionSelections":{"bigmodel":{"kind":"individual-coding-plan"}},
               "providerFamilyDomain":"bigmodel"}""",
        )
        val c = AccountProviderBridge.selectedAccountCredential(null, home.toString(), fullEntries())!!
        assertEquals("account:bigmodel-individual-coding-plan", c.providerId)
        assertEquals("account:bigmodel-individual-coding-plan-name", c.providerName)
        assertEquals("https://open.bigmodel.cn/api/anthropic", c.baseUrl)
        assertEquals("bigmodel-plan-key", c.apiKey)
    }

    @Test
    fun `providerFamilyDomain 缺省取首个 selection 键`() {
        val home = homeWithSetting(
            """{"providerFamilyConnectionSelections":{"zai":{"kind":"individual-coding-plan"}}}""",
        )
        assertEquals(
            "zai-plan-key",
            AccountProviderBridge.selectedAccountCredential(null, home.toString(), fullEntries())!!.apiKey,
        )
    }

    @Test
    fun `选择非账号渠道或 kind 漂移返回 null`() {
        // 客户端选中自定义供应商（kind 非账号三档）
        assertNull(
            AccountProviderBridge.selectedAccountCredential(
                null,
                homeWithSetting("""{"providerFamilyConnectionSelections":{"bigmodel":{"kind":"api-key"}},"providerFamilyDomain":"bigmodel"}"""),
                fullEntries(),
            ),
        )
        // 未知 kind（形状漂移）
        assertNull(
            AccountProviderBridge.selectedAccountCredential(
                null,
                homeWithSetting("""{"providerFamilyConnectionSelections":{"bigmodel":{"kind":"future-mode"}},"providerFamilyDomain":"bigmodel"}"""),
                fullEntries(),
            ),
        )
    }

    @Test
    fun `team 无材料与 start-plan 网关门控返回 null`() {
        assertNull(
            AccountProviderBridge.selectedAccountCredential(
                null,
                homeWithSetting("""{"providerFamilyConnectionSelections":{"bigmodel":{"kind":"team-coding-plan"}},"providerFamilyDomain":"bigmodel"}"""),
                fullEntries(),
            ),
        )
        assertNull(
            AccountProviderBridge.selectedAccountCredential(
                null,
                homeWithSetting("""{"providerFamilyConnectionSelections":{"zai":{"kind":"start-plan"}},"providerFamilyDomain":"zai"}"""),
                fullEntries(),
            ),
        )
    }

    @Test
    fun `setting 缺失或凭证材料缺失返回 null`() {
        // 无 setting.json
        val emptyHome = Files.createTempDirectory("acct-empty")
        assertNull(AccountProviderBridge.selectedAccountCredential(null, emptyHome.toString(), fullEntries()))
        // setting 在但 credentials 无 individual 材料
        assertNull(
            AccountProviderBridge.selectedAccountCredential(
                null,
                homeWithSetting("""{"providerFamilyConnectionSelections":{"bigmodel":{"kind":"individual-coding-plan"}},"providerFamilyDomain":"bigmodel"}"""),
                emptyMap(),
            ),
        )
    }
}
