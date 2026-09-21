package com.zcode.ideaplugin.protocol

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ZCode 凭证值解密器——官方 credential-cipher.ts（apps/zcode-cli/packages/adapters/src/auth/）
 * 的 Kotlin 复刻，2026-09-21 开源仓库源码逐行核对。
 *
 * credentials.json（~/.zcode/v2/credentials.json）的 value 可能是密文：
 * - 格式 `enc:v1:<iv base64url>.<authTag base64url>.<ciphertext base64url>`
 * - AES-256-GCM，IV 12 字节、auth tag 16 字节，key = sha256(secret)
 * - secret = env ZCODE_CREDENTIAL_SECRET（trim 后非空优先），缺省
 *   `zcode-credential-fallback:{platform}:{homedir}:{username}`（Node os.platform()
 *   取值 win32/darwin/linux，userInfo().username 失败时 "unknown"）
 * - 非 `enc:v1:` 前缀原样返回（官方同款）
 *
 * 消费方：[AccountProviderBridge] 读取账号渠道凭证（zcodejwttoken / oauth:*:user_info /
 * account-provider:*:api-key）前统一过本解密器。解密失败抛 [CredentialDecryptException]，
 * 调用方按"凭证不可用"降级，不得让异常冒泡阻断主流程。
 */
object CredentialCipher {

    private const val PREFIX = "enc:v1:"
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private const val SECRET_ENV_KEY = "ZCODE_CREDENTIAL_SECRET"

    /** 解密失败（格式非法 / tag 校验不过 / key 不匹配） */
    class CredentialDecryptException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * 解密单个凭证值。非密文原样返回；密文解密失败抛 [CredentialDecryptException]。
     * secret 解析失败（如 user.home 缺失）视为环境异常同样抛出——静默降级会把"读错了
     * key"掩盖成"没有凭证"，两者排障方向完全不同。
     */
    fun decrypt(value: String): String = decryptWithSecret(value, resolveSecret())

    /** [decrypt] 的 secret 注入版（单测固定向量用；生产路径走环境解析） */
    internal fun decryptWithSecret(value: String, secret: String): String {
        if (!value.startsWith(PREFIX)) return value
        // 解码与解密统一归一为 CredentialDecryptException：调用方只需 catch 一种异常
        val parts = try {
            value.substring(PREFIX.length).split(".").map { Base64.getUrlDecoder().decode(it) }
        } catch (e: Exception) {
            throw CredentialDecryptException("Credential decrypt failed: invalid ciphertext format", e)
        }
        if (parts.size != 3) {
            throw CredentialDecryptException("Credential decrypt failed: invalid ciphertext format")
        }
        val (ivRaw, tagRaw, ctRaw) = parts
        if (ivRaw.size != IV_BYTES) {
            throw CredentialDecryptException("Credential decrypt failed: invalid IV length")
        }
        if (tagRaw.size != TAG_BYTES) {
            throw CredentialDecryptException("Credential decrypt failed: invalid auth tag length")
        }
        try {
            // JCE 的 GCM 解密约定 tag 附在密文尾部（doFinal(ct||tag)，tLen 由
            // GCMParameterSpec 声明）——官方密文格式 tag/ct 分段存储，解密前须拼回。
            // 注意 javax.crypto.Cipher 没有 setAuthTag（那是 node/BouncyCastle 语义），
            // 拆开传会在运行期报 Tag mismatch（初版实踩，2026-09-21 对拍定位）。
            val ctWithTag = ctRaw + tagRaw
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(deriveKey(secret), "AES"), GCMParameterSpec(TAG_BYTES * 8, ivRaw))
            val plain = cipher.doFinal(ctWithTag)
            return String(plain, Charsets.UTF_8)
        } catch (e: Exception) {
            throw CredentialDecryptException("Credential decrypt failed: key mismatch or corrupted ciphertext", e)
        }
    }

    /** sha256(secret) = 32 字节 AES-256 key（官方 deriveCipherKey 同款） */
    private fun deriveKey(secret: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))

    /**
     * 凭证 secret 解析（官方 resolveCredentialSecret 同款）：
     * env ZCODE_CREDENTIAL_SECRET trim 非空优先，缺省 fallback 串。
     * Node os.platform() 映射：Windows→win32、macOS→darwin、其余→linux；
     * username 走 os.userInfo().username 同语义（user.name 系统属性），
     * 解析不出用 "unknown"（官方 catch 分支同款，不抛）。
     */
    private fun resolveSecret(): String {
        System.getenv(SECRET_ENV_KEY)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val platform = when {
            System.getProperty("os.name").lowercase().contains("win") -> "win32"
            System.getProperty("os.name").lowercase().contains("mac") ||
                System.getProperty("os.name").lowercase().contains("darwin") -> "darwin"
            else -> "linux"
        }
        val home = System.getProperty("user.home") ?: "unknown"
        val username = System.getProperty("user.name")?.takeIf { it.isNotBlank() } ?: "unknown"
        return "zcode-credential-fallback:$platform:$home:$username"
    }
}
