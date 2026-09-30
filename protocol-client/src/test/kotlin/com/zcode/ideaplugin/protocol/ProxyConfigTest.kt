package com.zcode.ideaplugin.protocol

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 网络代理配置（issue #12）单元测试
 *
 * - toEnvMap 对齐官方客户端 buildAgentRuntimeEnv（app.asar 逆向实证）：代理值同时打
 *   HTTP_PROXY/HTTPS_PROXY/ALL_PROXY/ZCODE_HTTP_PROXY 四键（zcode.cjs 主请求路径只认
 *   ZCODE_HTTP_PROXY，普通 HTTPS_PROXY 仅 WebFetch 兜底）
 * - 读 ~/.zcode/v2/setting.json 三键，缺失/损坏/文件缺失均等价未配置
 * - 归一规则与客户端一致：无协议前缀补 http://；no-proxy 逗号分隔逐项 trim
 */
class ProxyConfigTest {

    private val home = Files.createTempDirectory("proxy-config-test")

    @AfterTest
    fun cleanup() {
        home.toFile().deleteRecursively()
    }

    private fun givenSetting(content: String) {
        Files.createDirectories(home.resolve(".zcode/v2"))
        home.resolve(".zcode/v2/setting.json").toFile().writeText(content)
    }

    // ============ 读取 ============

    @Test
    fun `setting 缺失时读取等价未配置`() {
        assertTrue(ProxyConfigStore.read(home.toString()).isEmpty)
    }

    @Test
    fun `读三键并 trim`() {
        givenSetting("""{"httpProxy": " http://127.0.0.1:7890 ", "httpProxyNoProxy": "localhost,127.0.0.1", "httpProxyCaCertPath": "C:/ca.pem", "other": 1}""")
        val cfg = ProxyConfigStore.read(home.toString())
        assertEquals("http://127.0.0.1:7890", cfg.httpProxy)
        assertEquals("localhost,127.0.0.1", cfg.noProxy)
        assertEquals("C:/ca.pem", cfg.caCertPath)
        assertTrue(!cfg.isEmpty)
    }

    @Test
    fun `空串字段等价未配置（客户端 normalizeSettingsPatch 语义）`() {
        givenSetting("""{"httpProxy": "", "httpProxyNoProxy": "  ", "httpProxyCaCertPath": ""}""")
        assertTrue(ProxyConfigStore.read(home.toString()).isEmpty)
    }

    @Test
    fun `损坏 JSON 降级未配置不抛`() {
        givenSetting("{not json")
        assertTrue(ProxyConfigStore.read(home.toString()).isEmpty)
    }

    // ============ toEnvMap（官方 buildAgentRuntimeEnv 同构）============

    @Test
    fun `代理值打满四键`() {
        val env = ProxyConfig(httpProxy = "http://127.0.0.1:7890").toEnvMap()
        assertEquals(
            mapOf(
                "HTTP_PROXY" to "http://127.0.0.1:7890",
                "HTTPS_PROXY" to "http://127.0.0.1:7890",
                "ALL_PROXY" to "http://127.0.0.1:7890",
                "ZCODE_HTTP_PROXY" to "http://127.0.0.1:7890",
            ),
            env,
        )
    }

    @Test
    fun `无协议前缀自动补 http`() {
        val env = ProxyConfig(httpProxy = "127.0.0.1:7890").toEnvMap()
        assertEquals("http://127.0.0.1:7890", env["ZCODE_HTTP_PROXY"])
    }

    @Test
    fun `socks 协议原样保留（与客户端同规则，不做二次加工）`() {
        val env = ProxyConfig(httpProxy = "socks5://127.0.0.1:1080").toEnvMap()
        assertEquals("socks5://127.0.0.1:1080", env["ZCODE_HTTP_PROXY"])
    }

    @Test
    fun `noProxy 打三键并归一空白项`() {
        val env = ProxyConfig(noProxy = "localhost, 127.0.0.1 , ,*.internal").toEnvMap()
        assertEquals("localhost,127.0.0.1,*.internal", env["ZCODE_NO_PROXY"])
        assertEquals("localhost,127.0.0.1,*.internal", env["NO_PROXY"])
        assertEquals("localhost,127.0.0.1,*.internal", env["no_proxy"])
    }

    @Test
    fun `caCert 打 NODE_EXTRA_CA_CERTS 与 ZCODE_AGENT_CA_CERT`() {
        val env = ProxyConfig(caCertPath = "C:/corp/ca.pem").toEnvMap()
        assertEquals(
            mapOf(
                "NODE_EXTRA_CA_CERTS" to "C:/corp/ca.pem",
                "ZCODE_AGENT_CA_CERT" to "C:/corp/ca.pem",
            ),
            env,
        )
    }

    @Test
    fun `未配置产出空 map（spawn 不注入）`() {
        assertTrue(ProxyConfig().toEnvMap().isEmpty())
        assertTrue(ProxyConfig(httpProxy = "  ").toEnvMap().isEmpty())
    }

    // ============ toJavaProxySelector（插件进程直发网络：relay WS / monitor HTTP）============

    @Test
    fun `未配置代理返回 null 走默认直连`() {
        assertTrue(ProxyConfig().toJavaProxySelector() == null)
        assertTrue(ProxyConfig(noProxy = "localhost").toJavaProxySelector() == null)
    }

    @Test
    fun `命中 noProxy 后缀直连，其余走代理`() {
        val selector = ProxyConfig(
            httpProxy = "http://127.0.0.1:7890",
            noProxy = "localhost,127.0.0.1,*.internal",
        ).toJavaProxySelector()!!
        val uri = java.net.URI("wss://zcode.z.ai/ws")
        val proxies = selector.select(uri)
        assertEquals(1, proxies.size)
        assertEquals(java.net.Proxy.Type.HTTP, proxies[0].type())
        assertEquals(java.net.InetSocketAddress("127.0.0.1", 7890), proxies[0].address())

        // noProxy 命中（精确 + 后缀 + 通配剥前缀），大小写不敏感
        for (host in listOf("localhost", "localhost:3000", "a.internal", "A.INTERNAL", "127.0.0.1")) {
            val selected = selector.select(java.net.URI("https://$host/x"))
            assertEquals(1, selected.size, host)
            assertEquals(java.net.Proxy.NO_PROXY, selected[0], host)
        }
    }

    @Test
    fun `无端口补 80，无协议前缀经归一可用`() {
        val selector = ProxyConfig(httpProxy = "proxy.corp").toJavaProxySelector()!!
        val proxies = selector.select(java.net.URI("https://zcode.z.ai/ws"))
        assertEquals(java.net.InetSocketAddress("proxy.corp", 80), proxies[0].address())
    }

    @Test
    fun `userinfo 认证形态仍可解析出 host 与端口`() {
        val selector = ProxyConfig(httpProxy = "http://user:pass@10.0.0.9:3128").toJavaProxySelector()!!
        assertEquals(
            java.net.InetSocketAddress("10.0.0.9", 3128),
            selector.select(java.net.URI("wss://zcode.z.ai/ws"))[0].address(),
        )
    }

    // ============ 日志摘要与脱敏 ============

    @Test
    fun `logSummary 含 host 与 noProxy，未配置返回直连文案`() {
        assertEquals(
            "<no proxy, direct>",
            ProxyConfig().logSummary,
        )
        val s = ProxyConfig(httpProxy = "http://127.0.0.1:7890", noProxy = "localhost").logSummary
        assertTrue(s.contains("proxy=http://127.0.0.1:7890"), s)
        assertTrue(s.contains("noProxy=localhost"), s)
    }

    @Test
    fun `userinfo 认证段脱敏，host 保留`() {
        assertEquals(
            "http://***@127.0.0.1:7890",
            ProxyConfig.redactProxyUrl("http://user:pass@127.0.0.1:7890"),
        )
        // 无认证原样
        assertEquals(
            "http://127.0.0.1:7890",
            ProxyConfig.redactProxyUrl("http://127.0.0.1:7890"),
        )
        // logSummary 走同一脱敏
        val s = ProxyConfig(httpProxy = "http://alice:secret@proxy.corp:3128").logSummary
        assertTrue(!s.contains("alice"), s)
        assertTrue(!s.contains("secret"), s)
        assertTrue(s.contains("proxy=http://***@proxy.corp:3128"), s)
    }
}
