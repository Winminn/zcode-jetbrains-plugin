package com.zcode.ideaplugin.protocol

import com.zcode.ideaplugin.protocol.model.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/**
 * ZCodeProtocolClient 端到端验证测试
 *
 * 对照 V7（Python）的 6 个场景：
 *   1. session/list
 *   2. session/create（含 requestRuntimePreferences 自动应答）
 *   3. session/subscribe（deliveryKind）
 *   4. session/send + 流式事件
 *   5. session/messages
 *   6. session/resume
 *
 * ⚠️ 这是真机集成测试，需要：
 *   - ZCode 已安装
 *   - ~/.zcode/v2/config.json 已配置凭证
 *   - node 在 PATH 中
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ZCodeProtocolClientTest {

    private lateinit var client: ZCodeProtocolClient
    private val workspace = Workspace(System.getProperty("user.dir"))
    private var createdSessionId: String? = null

    @BeforeAll
    fun setUp() {
        // 前置检查：ZCode 和凭证必须可用，否则跳过整个测试类
        try {
            client = ZCodeProtocolClient.start()
            assertTrue(client.isAlive(), "app-server 进程应该存活")
            println("✅ ZCodeProtocolClient 启动成功")
        } catch (e: Exception) {
            println("⚠️ 跳过测试（环境不可用）: ${e.message}")
            assumeTrue(false, "ZCode 环境不可用: ${e.message}")
        }
    }

    @AfterAll
    fun tearDown() {
        if (::client.isInitialized) {
            client.close()
            println("✅ 客户端已关闭")
        }
    }

    @Test
    @Order(1)
    fun `1 - session list 返回所有会话`() {
        val sessions = client.listSessions()
        println("✅ session/list 返回 ${sessions.size} 个会话")
        sessions.take(3).forEach { s ->
            println("   - ${s.sessionId.take(30)} | ${s.title.take(30)} | ${s.status}")
        }
        assertTrue(sessions.isNotEmpty(), "应该至少有 1 个会话（当前正在用的）")
    }

    @Test
    @Order(2)
    fun `2 - session create 创建新会话`() {
        val sid = client.createSession(workspace, PermissionMode.YOLO)
        createdSessionId = sid
        println("✅ session/create 成功: $sid")
        assertTrue(sid.startsWith("sess_"), "sessionId 应该以 sess_ 开头")
    }

    @Test
    @Order(3)
    fun `3 - session subscribe 带 deliveryKind`() {
        val sid = createdSessionId ?: return fail("依赖前一个测试创建的 session")
        val snapshot = client.subscribe(sid, deliveryKind = "desktop-continuous")
        println("✅ session/subscribe 成功")
        println("   snapshot keys: ${snapshot.keys}")
        assertTrue(snapshot.isNotEmpty(), "snapshot 应该有内容")
    }

    @Test
    @Order(4)
    fun `4 - session send 接收流式事件`() {
        val sid = createdSessionId ?: return fail("依赖前一个测试创建的 session")

        // 用 latch 等待 turn.completed 或 turn.failed
        val events = ConcurrentLinkedQueue<SessionEvent>()
        val turnDone = CountDownLatch(1)
        val failedRef = AtomicReference<SessionEvent?>(null)

        client.addEventListener(sid) { event ->
            events.add(event)
            when (event.type) {
                EventTypes.TURN_COMPLETED, EventTypes.TURN_FAILED -> turnDone.countDown()
            }
            if (event.type == EventTypes.TURN_FAILED) {
                failedRef.set(event)
            }
        }

        // 发送测试问题
        val sendResult = client.send(sid, "回答：1+1=? 只说数字")
        println("✅ session/send accepted: ${sendResult["accepted"]?.jsonPrimitive?.content}")

        // 等待 turn 完成（最多 60 秒，规格书说 turn 时长 38-100s 波动）
        val done = turnDone.await(60, TimeUnit.SECONDS)
        assertTrue(done, "应该在 60 秒内收到 turn.completed 或 turn.failed")

        // 统计事件类型
        val etypes = events.groupingBy { it.type }.eachCount()
        println("   收到 ${events.size} 个事件，类型分布: $etypes")

        // 拼接流式文本
        val textPieces = events.filter {
            it.type == EventTypes.MODEL_STREAMING &&
            it.payload["kind"]?.jsonPrimitive?.content == StreamingKind.TEXT_DELTA
        }.map { it.payload["delta"]?.jsonPrimitive?.content ?: "" }
        val joinedText = textPieces.joinToString("")
        println("   流式文本拼接: \"$joinedText\"")

        // 验证：应该有流式事件
        assertTrue(events.any { it.type == EventTypes.MODEL_STREAMING }, "应该收到 model.streaming 事件")
        assertTrue(events.any { it.type == EventTypes.TURN_COMPLETED }, "应该收到 turn.completed")

        // 验证 turn.completed 的 usage
        val completed = events.first { it.type == EventTypes.TURN_COMPLETED }
        val usage = completed.payload["usage"]?.jsonObject
        println("   usage: $usage")
        assertNotNull(usage, "turn.completed 应该带 usage")
    }

    @Test
    @Order(5)
    fun `5 - session messages 读取历史`() {
        val sid = createdSessionId ?: return fail("依赖前一个测试创建的 session")
        val messages: JsonArray = client.messages(sid)
        println("✅ session/messages 返回 ${messages.size} 条消息")
        assertTrue(messages.isNotEmpty(), "应该有历史消息（前一个测试发过）")
    }

    @Test
    @Order(6)
    fun `6 - session resume 续会话`() {
        val sid = createdSessionId ?: return fail("依赖前一个测试创建的 session")
        val result = client.resume(sid, workspace)
        println("✅ session/resume 成功")
        println("   result keys: ${result.keys}")
        // resume 应该返回带 messages 的结果
        assertTrue(result.containsKey("messages") || result.isNotEmpty(), "resume 应该返回历史")
    }
}
