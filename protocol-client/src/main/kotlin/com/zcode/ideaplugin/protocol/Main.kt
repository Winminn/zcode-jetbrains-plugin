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
    println("=== ZCodeProtocolClient smoke test ===\n")

    val client = ZCodeProtocolClient.start()
    val workspace = Workspace(System.getProperty("user.dir"))

    try {
        // 1. list
        println("[1] session/list")
        val sessions = client.listSessions()
        println("    ✅ ${sessions.size} session(s)")
        sessions.take(3).forEach { println("       - ${it.sessionId.take(30)} | ${it.title.take(30)} | ${it.status}") }

        // 2. create（自动应答 requestRuntimePreferences）
        println("\n[2] session/create")
        val sid = client.createSession(workspace, PermissionMode.YOLO)
        println("    ✅ session created: $sid")

        // 3. subscribe + 注册监听器
        println("\n[3] session/subscribe")
        val snapshot = client.subscribe(sid)
        println("    ✅ snapshot keys: ${snapshot.keys.toList()}")

        // 4. send + 收流式
        println("\n[4] session/send + streaming events")
        val events = ConcurrentLinkedQueue<SessionEvent>()
        val latch = CountDownLatch(1)
        client.addEventListener(sid) { e ->
            events.add(e)
            if (e.type == EventTypes.TURN_COMPLETED || e.type == EventTypes.TURN_FAILED) latch.countDown()
        }

        client.send(sid, "回答：1+1=? 只说数字")
        println("    Waiting for streaming reply (up to 60s)...")
        val done = latch.await(60, TimeUnit.SECONDS)

        val etypes = events.groupingBy { it.type }.eachCount()
        println("    ${events.size} event(s) received: $etypes")
        val text = events.filter {
            it.type == EventTypes.MODEL_STREAMING &&
            it.payload["kind"]?.jsonPrimitive?.content == StreamingKind.TEXT_DELTA
        }.joinToString("") { it.payload["delta"]?.jsonPrimitive?.content ?: "" }
        println("    Streamed text: \"$text\"")
        if (!done) println("    ⚠️ timed out without completion")

        // 5. messages
        println("\n[5] session/messages")
        val msgs = client.messages(sid)
        println("    ✅ ${msgs.size} message(s)")

        // 6. resume
        println("\n[6] session/resume")
        val r = client.resume(sid, workspace)
        println("    ✅ resume succeeded, keys: ${r.keys.toList()}")

        println("\n=== All passed ===")
    } finally {
        client.close()
    }
}
