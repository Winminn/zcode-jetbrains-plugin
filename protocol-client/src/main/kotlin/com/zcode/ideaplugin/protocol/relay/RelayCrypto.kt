package com.zcode.ideaplugin.protocol.relay

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * relay 配对凭据算法（逆向自 app.asar createNodeWebRemoteControlRelayAuthProvider，探针实测对齐）：
 *
 *   password = base64url_nopad(24 随机字节)
 *   passHash = base64( sha256(password) )          // 标准 base64 带 padding
 *   proof    = base64url_nopad( HMAC-SHA256(passHash, "${nonce}|${role}|${deviceSid}") )
 *
 * QR URL 内嵌 passHash 属敏感凭据，勿外传；协议 wire 只用 passHash，password 仅生成时存在。
 */
object RelayCrypto {

    fun createPassword(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return bytes.toBase64UrlNoPad()
    }

    fun createPassHash(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(digest)
    }

    fun calculateProof(passHash: String, nonce: String, role: String, deviceSid: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passHash.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val message = "$nonce|$role|$deviceSid".toByteArray(Charsets.UTF_8)
        return mac.doFinal(message).toBase64UrlNoPad()
    }

    /**
     * 配对 QR 指向的 H5 URL（H5 gE() 逆向 + 实测接受）。base64 的 +/= 必须 percent-encode。
     * appVersion 为 null 时不出 app_version 参数（宿主版本读不到的场景；实测 H5 无该
     * 参数可正常加载，服务端按未知版本走默认分支，胜过硬编码过期版本号）。
     */
    fun buildQrUrl(
        credentials: RelayCredentials,
        deviceName: String,
        origin: String = Relay.DEFAULT_ORIGIN,
        appVersion: String? = Relay.APP_VERSION,
        theme: String = "dark",
        timestampMs: Long = System.currentTimeMillis(),
    ): String {
        fun enc(v: String) = URLEncoder.encode(v, Charsets.UTF_8)
        return buildString {
            append(origin).append(Relay.REMOTE_PAGE)
            append("?sid=").append(enc(credentials.deviceSid ?: return ""))
            append("&hash=").append(enc(credentials.passHash))
            append("&t=").append(timestampMs)
            append("&mid=").append(enc(credentials.deviceMid))
            append("&name=").append(enc(deviceName))
            if (!appVersion.isNullOrEmpty()) append("&app_version=").append(enc(appVersion))
            append("&theme=").append(theme)
        }
    }

    private fun ByteArray.toBase64UrlNoPad(): String {
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(this)
        return encoded
    }
}
