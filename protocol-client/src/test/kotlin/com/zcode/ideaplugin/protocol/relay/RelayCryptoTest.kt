package com.zcode.ideaplugin.protocol.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 与 Python 探针（probe-relay-device.py）对拍的固定向量 */
class RelayCryptoTest {

    @Test
    fun `passHash 算法与探针对拍`() {
        // password = "YWJjZGVmZ2hpamtsbW5vcA"（探针 Python: base64(sha256(password))）
        assertEquals(
            "/n2t/KBewCLS+kdThgq6z3zHSNlUMQ9DW5J8YQswErU=",
            RelayCrypto.createPassHash("YWJjZGVmZ2hpamtsbW5vcA"),
        )
    }

    @Test
    fun `proof 算法与探针对拍`() {
        assertEquals(
            "dGijO4a_DW5qD4NdGavXHzRJW18qiKeJyqOtfOK4RoM",
            RelayCrypto.calculateProof(
                passHash = "/n2t/KBewCLS+kdThgq6z3zHSNlUMQ9DW5J8YQswErU=",
                nonce = "nonce123",
                role = "device",
                deviceSid = "sid-abc",
            ),
        )
    }

    @Test
    fun `createPassword 24 字节 base64url 无 padding`() {
        val password = RelayCrypto.createPassword()
        assertTrue(password.length == 32 && !password.contains("=") && !password.contains("+") && !password.contains("/"))
        assertEquals(24, java.util.Base64.getUrlDecoder().decode(password).size)
    }

    @Test
    fun `QR URL percent-encode base64 敏感字符`() {
        val cred = RelayCredentials(deviceMid = "mid-1", deviceSid = "sid-1", passHash = "/n2t+KB=w")
        val url = RelayCrypto.buildQrUrl(cred, deviceName = "我的 IDE")
        assertTrue(url.startsWith("https://zcode.z.ai/remote/v4?"))
        assertTrue(url.contains("hash=%2Fn2t%2BKB%3Dw"), "base64 的 +/= 必须编码: $url")
        assertTrue(url.contains("app_version=${Relay.APP_VERSION}"))
        assertTrue(url.contains("name="))
        // 中文名也须编码
        assertTrue(!url.contains("我的"), "非 ASCII 须编码: $url")
    }

    @Test
    fun `deviceSid 为空时 QR URL 返回空串`() {
        val cred = RelayCredentials(deviceMid = "mid", deviceSid = null, passHash = "x")
        assertEquals("", RelayCrypto.buildQrUrl(cred, "n"))
    }
}
