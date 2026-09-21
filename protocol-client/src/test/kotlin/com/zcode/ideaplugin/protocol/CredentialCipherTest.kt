package com.zcode.ideaplugin.protocol

import kotlin.test.*

/**
 * CredentialCipher 单元测试——官方 credential-cipher.ts 的 Kotlin 复刻验证。
 *
 * 测试向量 2026-09-21 用官方同构 node 脚本生成（AES-256-GCM，key=sha256(secret)，
 * iv 12B + tag 16B，格式 enc:v1:<iv b64url>.<tag b64url>.<ct b64url>），
 * secret 固定 "test-secret-vector"；生成侧先用官方 decrypt 自检通过。
 */
class CredentialCipherTest {

    private val secret = "test-secret-vector"

    @Test
    fun `官方向量解密`() {
        assertEquals(
            "hello-zcode-test",
            CredentialCipher.decryptWithSecret(
                "enc:v1:Az6ACWnDtfFoRAqe.SOmqAIxSWV0lCQ7y1buGew.qjA0n5e1T88yX6-LVGogXA",
                secret,
            ),
        )
        assertEquals(
            "sk-zcgui-1234567890abcdef",
            CredentialCipher.decryptWithSecret(
                "enc:v1:M4VVhjx7O8hLww-H.hY53ybAakL4ntQTn3lW39w.tyWnAIYBEe2hoq1ONRtVsqqNXi_w-hnljw",
                secret,
            ),
        )
        // 空串也是合法明文（tag-only 密文）
        assertEquals(
            "",
            CredentialCipher.decryptWithSecret(
                "enc:v1:5Vl2JTH_ry5Rkkhb.1KU0i0oNWTRwqxVeOvAbfw.",
                secret,
            ),
        )
    }

    @Test
    fun `非 enc v1 前缀原样返回`() {
        assertEquals("sk-plain-key", CredentialCipher.decryptWithSecret("sk-plain-key", secret))
        assertEquals("", CredentialCipher.decryptWithSecret("", secret))
        // 前缀形态但非 v1（未来版本）原样返回，交给调用方按"不可用"处理
        assertEquals("enc:v2:xx.yy.zz", CredentialCipher.decryptWithSecret("enc:v2:xx.yy.zz", secret))
    }

    @Test
    fun `secret 不匹配抛解密异常`() {
        val e = assertFailsWith<CredentialCipher.CredentialDecryptException> {
            CredentialCipher.decryptWithSecret(
                "enc:v1:Az6ACWnDtfFoRAqe.SOmqAIxSWV0lCQ7y1buGew.qjA0n5e1T88yX6-LVGogXA",
                "wrong-secret",
            )
        }
        assertTrue(e.message.orEmpty().contains("key mismatch or corrupted"))
    }

    @Test
    fun `格式非法抛解密异常`() {
        // 段数不足（缺 authTag 段）
        assertFailsWith<CredentialCipher.CredentialDecryptException> {
            CredentialCipher.decryptWithSecret("enc:v1:PxF3MTNx5Y6AeX1q.SyeHH5eO1JmWUXSKG47svw", secret)
        }
        // base64url 非法字符
        assertFailsWith<CredentialCipher.CredentialDecryptException> {
            CredentialCipher.decryptWithSecret("enc:v1:!!!.!!!.!!!", secret)
        }
    }
}
