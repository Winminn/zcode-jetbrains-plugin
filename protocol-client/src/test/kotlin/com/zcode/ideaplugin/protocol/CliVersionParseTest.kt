package com.zcode.ideaplugin.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * `--version` 输出解析（ZCODE_APP_VERSION 注入用，UA 对齐官方客户端）。
 * spawn 路径不测：测试环境不依赖 node + zcode.cjs。
 */
class CliVersionParseTest {

    @Test
    fun `纯版本号输出`() {
        assertEquals("0.16.5", ZCodeProtocolClient.parseCliVersion("0.16.5"))
        assertEquals("0.16.5", ZCodeProtocolClient.parseCliVersion("  0.16.5\n"))
    }

    @Test
    fun `带前缀或前后缀噪声`() {
        assertEquals("1.2.3", ZCodeProtocolClient.parseCliVersion("zcode/1.2.3"))
        assertEquals("0.16.5", ZCodeProtocolClient.parseCliVersion("zcode version 0.16.5 (build 123)"))
    }

    @Test
    fun `prerelease 段保留`() {
        assertEquals("0.17.0-beta.1", ZCodeProtocolClient.parseCliVersion("0.17.0-beta.1"))
        assertEquals("2.0.0-rc.1+build.5", ZCodeProtocolClient.parseCliVersion("v2.0.0-rc.1+build.5"))
    }

    @Test
    fun `无版本串返回 null`() {
        assertNull(ZCodeProtocolClient.parseCliVersion(""))
        assertNull(ZCodeProtocolClient.parseCliVersion("command not found"))
        assertNull(ZCodeProtocolClient.parseCliVersion("v1.2"))
    }
}
