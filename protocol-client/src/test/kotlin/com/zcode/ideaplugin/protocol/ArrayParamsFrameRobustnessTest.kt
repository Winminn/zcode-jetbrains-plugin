package com.zcode.ideaplugin.protocol

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * 数组 params 帧鲁棒性回归（2026-09-19 卡死事故）
 *
 * 新版 CLI app-server 每 5 分钟（ZZe=5*6e4）推 process/mcpResourceSamples，
 * params 为 JSON 数组（MCP 进程采样列表）。0.3.6 的 dispatchMessage 对
 * msg["params"]?.jsonObject 强转抛 IllegalArgumentException，穿透 readLoop
 * （外层只 catch IOException）杀死 reader 线程——之后 app-server 虽活着但
 * 响应再也读不回，表现为全线请求超时、新会话 createSession 20s 超时。
 *
 * 夹具 fake-app-server-array-frame.js 启动即推杀手帧、并在第二次
 * session/list 前再推一帧。修复后：数组 params 走 as? 落空对象被当未知
 * 通知忽略；即使有其他形状异常也被 readLoop 单帧兜底吞掉，线程不死。
 * 修复前：reader 死于启动首帧，awaitReady 等不到 session/list 响应超时。
 */
class ArrayParamsFrameRobustnessTest {

    private fun fakeServerPath(): Path = Path.of(
        javaClass.getResource("/fake-app-server-array-frame.js")!!.toURI()
    )

    @Test
    fun `数组 params 通知帧不杀死 reader 线程`() {
        val client = ZCodeProtocolClient.start(
            zcodePath = fakeServerPath(),
            readyTimeoutMs = 15_000,
        )
        try {
            // 启动即推的杀手帧已被消化：probe（session/list #1）成功返回即隐含 reader 存活
            assertTrue(client.isAlive(), "app-server 进程应该存活")
            // 二次往返前夹具会再推一帧杀手帧：验证运行中收到后线程继续工作
            assertTrue(client.listSessions().isEmpty(), "杀手帧之后 session/list 仍应正常往返")
        } finally {
            client.close()
        }
    }
}
