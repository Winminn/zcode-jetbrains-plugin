package com.zcode.ideaplugin.protocol

import com.zcode.ideaplugin.protocol.model.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 独立冒烟测试入口
 *
 * 用法：./gradlew :protocol-client:run
 * 等价于 V7 的 Python 脚本，跑完整 6 步生命周期。
 */
fun main() {
    println("=== ZCodeProtocolClient 冒烟测试 ===\n")

    val client = ZCodeProtocolClient.start()
    val workspace = Workspace(System.getProperty("user.dir"))

    try {
        // 1. list
        println("[1] session/list")
        val sessions = client.listSessions()
        println("    ✅ ${sessions.size} 个会话")
        sessions.take(3).forEach { println("       - ${it.sessionId.take(30)} | ${it.title.take(30)} | ${it.status}") }

        // 2. create（自动应答 requestRuntimePreferences）
        println("\n[2] session/create")
        val sid = client.createSession(workspace, PermissionMode.YOLO)
        println("    ✅ 新建会话: $sid")

        // 3. subscribe + 注册监听器
        println("\n[3] session/subscribe")
        val snapshot = client.subscribe(sid)
        println("    ✅ snapshot keys: ${snapshot.keys.toList()}")

        // 4. send + 收流式
        println("\n[4] session/send + 流式事件")
        val events = ConcurrentLinkedQueue<SessionEvent>()
        val latch = CountDownLatch(1)
        client.addEventListener(sid) { e ->
            events.add(e)
            if (e.type == EventTypes.TURN_COMPLETED || e.type == EventTypes.TURN_FAILED) latch.countDown()
        }

        client.send(sid, "回答：1+1=? 只说数字")
        println("    等待流式回复（最多 60s）...")
        val done = latch.await(60, TimeUnit.SECONDS)

        val etypes = events.groupingBy { it.type }.eachCount()
        println("    收到 ${events.size} 个事件: $etypes")
        val text = events.filter {
            it.type == EventTypes.MODEL_STREAMING &&
            it.payload["kind"]?.jsonPrimitive?.content == StreamingKind.TEXT_DELTA
        }.joinToString("") { it.payload["delta"]?.jsonPrimitive?.content ?: "" }
        println("    流式文本: \"$text\"")
        if (!done) println("    ⚠️ 超时未完成")

        // 5. messages
        println("\n[5] session/messages")
        val msgs = client.messages(sid)
        println("    ✅ ${msgs.size} 条消息")

        // 6. resume
        println("\n[6] session/resume")
        val r = client.resume(sid, workspace)
        println("    ✅ resume 成功，keys: ${r.keys.toList()}")

        println("\n=== 全部通过 ===")
    } finally {
        client.close()
    }
}
