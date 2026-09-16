package com.zcode.ideaplugin.ui

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 定时消息纯逻辑测试：宽限窗自动分派判定 + 存储序列化回环。
 *
 * 分派规则（设计文档第五节）：到点且在宽限窗（30min）内才自动发；
 * 超宽限保持待发呈「已过期」卡等用户手动决定；hold（切会话回退挂起）永不自动。
 */
class ZCodeScheduledMessageServiceTest {

    private fun item(fireAt: Long, hold: Boolean = false) = ZCodeScheduledMessageService.Item(
        id = "s1",
        sessionId = "sess_1",
        workspacePath = "G:\\mock",
        text = "定时任务",
        fireAt = fireAt,
        createdAt = 0,
        hold = hold,
    )

    @Test
    fun `未到点不自动分派`() {
        val now = 1_000_000L
        assertFalse(ZCodeScheduledMessageService.shouldAutoFire(item(fireAt = now + 1), now))
    }

    @Test
    fun `到点在宽限窗内自动分派`() {
        val fireAt = 1_000_000L
        // 恰到点 / 到点后 29min59s
        assertTrue(ZCodeScheduledMessageService.shouldAutoFire(item(fireAt), fireAt))
        assertTrue(ZCodeScheduledMessageService.shouldAutoFire(item(fireAt), fireAt + 29 * 60_000 + 59_999))
    }

    @Test
    fun `超宽限窗不再自动分派（转已过期等手动决定）`() {
        val fireAt = 1_000_000L
        // 恰好 30min 边界仍在窗内（now - fireAt <= grace，与 webview 过期判定对齐），
        // 再多 1ms 即超窗
        assertTrue(ZCodeScheduledMessageService.shouldAutoFire(item(fireAt), fireAt + ZCodeScheduledMessageService.GRACE_MS))
        assertFalse(ZCodeScheduledMessageService.shouldAutoFire(item(fireAt), fireAt + ZCodeScheduledMessageService.GRACE_MS + 1))
        assertFalse(ZCodeScheduledMessageService.shouldAutoFire(item(fireAt), fireAt + 2 * 60 * 60_000))
    }

    @Test
    fun `hold 挂起项任何时刻都不自动分派`() {
        val fireAt = 1_000_000L
        assertFalse(ZCodeScheduledMessageService.shouldAutoFire(item(fireAt, hold = true), fireAt + 1000))
    }

    @Test
    fun `已放弃的项不再进入自动分派候选（防标签风暴）`() {
        val fireAt = 1_000_000L
        val due = item(fireAt)
        val other = item(fireAt).copy(id = "s2")
        // 开标签一次仍不就绪的项（giveUp）被排除，其余到期项不受影响
        val candidates = ZCodeScheduledMessageService.autoDispatchCandidates(
            listOf(due, other), fireAt + 1000, giveUp = setOf("s1"),
        )
        assertEquals(listOf("s2"), candidates.map { it.id })
        // giveUp 为空时行为与 shouldAutoFire 过滤一致
        assertEquals(
            listOf("s1", "s2"),
            ZCodeScheduledMessageService.autoDispatchCandidates(listOf(due, other), fireAt + 1000).map { it.id },
        )
        // giveUp 项被手动重定时（giveUp 清空）后重新参与
        assertTrue(
            ZCodeScheduledMessageService.autoDispatchCandidates(listOf(due), fireAt + 1000, giveUp = emptySet()).isNotEmpty(),
        )
    }

    @Test
    fun `序列化回环保留全部字段`() {
        val src = listOf(
            ZCodeScheduledMessageService.Item("a", "sess", "G:\\p", "文本\n多行", 123L, 456L, hold = true, providerId = "p1", modelId = "glm-5.3"),
            ZCodeScheduledMessageService.Item("b", "sess2", "", "", -1L, 0L, hold = false),
        )
        val json = ZCodeScheduledMessageService.itemsToJson(src).toString()
        val parsed = ZCodeScheduledMessageService.parseItems(json)
        assertEquals(src, parsed)
    }

    @Test
    fun `损坏或空存储解析回空列表不抛异常`() {
        assertTrue(ZCodeScheduledMessageService.parseItems(null).isEmpty())
        assertTrue(ZCodeScheduledMessageService.parseItems("").isEmpty())
        assertTrue(ZCodeScheduledMessageService.parseItems("not json {").isEmpty())
        assertTrue(ZCodeScheduledMessageService.parseItems("[{\"id\":1}]").isEmpty()) // 缺字段条目被跳过
    }


    @Test
    fun `旧存储无模型字段解析为空（跟随会话）`() {
        val raw = "[{\"id\": \"a\", \"sessionId\": \"s\", \"workspacePath\": \"\", \"text\": \"x\", \"fireAt\": 1, \"createdAt\": 2, \"hold\": false}]"
        val parsed = ZCodeScheduledMessageService.parseItems(raw)
        assertEquals(1, parsed.size)
        assertEquals(null, parsed[0].providerId)
        assertEquals(null, parsed[0].modelId)
    }
    @Test
    fun `已发记录序列化回环保留全部字段`() {
        val src = listOf(
            ZCodeScheduledMessageService.FireRecord("sess", "定时任务", 123L, 789L),
            ZCodeScheduledMessageService.FireRecord("sess2", "", -1L, 0L),
        )
        val json = ZCodeScheduledMessageService.firedToJson(src).toString()
        assertEquals(src, ZCodeScheduledMessageService.parseFired(json))
    }

    @Test
    fun `已发记录损坏或空存储解析回空列表不抛异常`() {
        assertTrue(ZCodeScheduledMessageService.parseFired(null).isEmpty())
        assertTrue(ZCodeScheduledMessageService.parseFired("").isEmpty())
        assertTrue(ZCodeScheduledMessageService.parseFired("not json {").isEmpty())
        assertTrue(ZCodeScheduledMessageService.parseFired("[{\"text\":\"x\"}]").isEmpty()) // 缺字段条目被跳过
    }

    // ============ /goal 命令解析（directSend 兜底拦截；与 webview goalCommand.ts 同语义）============

    @Test
    fun `goal 无参与子命令解析`() {
        assertEquals(ZCodeScheduledMessageService.GoalCommand("show", null), ZCodeScheduledMessageService.parseGoalCommand("/goal"))
        assertEquals(ZCodeScheduledMessageService.GoalCommand("show", null), ZCodeScheduledMessageService.parseGoalCommand("/goal "))
        assertEquals(ZCodeScheduledMessageService.GoalCommand("pause", null), ZCodeScheduledMessageService.parseGoalCommand("/goal PAUSE"))
        assertEquals(ZCodeScheduledMessageService.GoalCommand("resume", null), ZCodeScheduledMessageService.parseGoalCommand("/goal resume"))
        assertEquals(ZCodeScheduledMessageService.GoalCommand("clear", null), ZCodeScheduledMessageService.parseGoalCommand("/goal clear"))
    }

    @Test
    fun `goal 目标文本解析为 set（多行保留）`() {
        assertEquals(
            ZCodeScheduledMessageService.GoalCommand("set", "修复登录页\n并补测试"),
            ZCodeScheduledMessageService.parseGoalCommand("/goal 修复登录页\n并补测试"),
        )
    }

    @Test
    fun `非 goal 命令文本返回 null（不误伤普通定时消息）`() {
        assertEquals(null, ZCodeScheduledMessageService.parseGoalCommand("早安摘要"))
        assertEquals(null, ZCodeScheduledMessageService.parseGoalCommand("/goals"))
        assertEquals(null, ZCodeScheduledMessageService.parseGoalCommand("先跑 /goal"))
        assertEquals(null, ZCodeScheduledMessageService.parseGoalCommand("/compact"))
        assertEquals(null, ZCodeScheduledMessageService.parseGoalCommand(""))
    }

    // ============ automation/* 纯映射（AI 的 Cron* 工具宿主落点，第一期=一次性任务）============

    private fun varargJson(vararg pairs: Pair<String, Any?>): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            pairs.forEach { (k, v) ->
                when (v) {
                    null -> {} // 不放入键
                    is String -> put(k, v)
                    is Number -> put(k, v.toLong())
                    is Boolean -> put(k, v)
                    else -> throw IllegalArgumentException("unsupported: $v")
                }
            }
        }

    private fun automationError(block: () -> Unit): String {
        val e = org.junit.jupiter.api.Assertions.assertThrows(ZCodeScheduledMessageService.AutomationHostError::class.java) { block() }
        return e.message ?: ""
    }

    @Test
    fun `create 延时形态映射为一次性触发（占位 cron 被忽略）`() {
        val now = 1_000_000_000L
        val spec = ZCodeScheduledMessageService.automationCreateToSpec(
            varargJson(
                "prompt" to "跑一遍回归测试",
                "title" to "8分钟后跑回归",
                "cronExpr" to "* * * * *",
                "relativeDelayMinutes" to 8L,
                "recurring" to false,
                "targetTaskId" to "sess_1",
            ),
            now,
        )
        assertEquals("跑一遍回归测试", spec.prompt)
        assertEquals("sess_1", spec.targetTaskId)
        assertEquals("8分钟后跑回归", spec.title)
        assertEquals(now + 8 * 60_000, spec.fireAt)
    }

    @Test
    fun `create 标题缺省从提示词派生（首行截 24 字）`() {
        val spec = ZCodeScheduledMessageService.automationCreateToSpec(
            varargJson(
                "prompt" to "${"长".repeat(30)}\n第二行",
                "cronExpr" to "0 9 30 7 *",
                "recurring" to false,
                "targetTaskId" to "s",
            ),
            0L,
        )
        assertTrue(spec.title.endsWith("…"))
        assertEquals(25, spec.title.length)
    }

    @Test
    fun `create 绝对时刻 cron 取本地时区未来触发`() {
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.of(2026, 9, 16, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        // 明天 09:30（未来）
        val fireAt = ZCodeScheduledMessageService.automationCreateToSpec(
            varargJson("prompt" to "p", "cronExpr" to "30 9 17 9 *", "recurring" to false, "targetTaskId" to "s"),
            now,
        ).fireAt
        val expect = java.time.ZonedDateTime.of(2026, 9, 17, 9, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expect, fireAt)
    }

    @Test
    fun `create 跨年目标翻转到明年`() {
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.of(2026, 12, 20, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val fireAt = ZCodeScheduledMessageService.automationCreateToSpec(
            varargJson("prompt" to "p", "cronExpr" to "0 10 15 1 *", "recurring" to false, "targetTaskId" to "s"),
            now,
        ).fireAt
        val expect = java.time.ZonedDateTime.of(2027, 1, 15, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expect, fireAt)
    }

    @Test
    fun `create 刚错过的目标在宽限内（60s 外）报过期，60s 内立即执行`() {
        val zone = java.time.ZoneId.systemDefault()
        val target = java.time.ZonedDateTime.of(2026, 9, 16, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        // 错过 5 分钟：宽限内但超 60s → 报过期（官方 StaleOneShot 语义）
        val now1 = target + 5 * 60_000
        val msg = automationError {
            ZCodeScheduledMessageService.pinnedOneShotFireAt("0 9 16 9 *", now1)
        }
        assertTrue(msg.contains("已过去"), msg)
        // 错过 30 秒：60s 窗口内 → 立即执行（返回 now）
        val now2 = target + 30_000
        assertEquals(now2, ZCodeScheduledMessageService.pinnedOneShotFireAt("0 9 16 9 *", now2))
    }

    @Test
    fun `create 不支持的形状统一拒绝（周期、间隔、maxRuns、区间步进）`() {
        val base = arrayOf("prompt" to "p", "targetTaskId" to "s")
        assertTrue(
            automationError {
                ZCodeScheduledMessageService.automationCreateToSpec(varargJson(*base, "cronExpr" to "*/20 * * * *", "recurring" to true), 0L)
            }.contains("仅支持一次性"),
        )
        assertTrue(
            automationError {
                ZCodeScheduledMessageService.automationCreateToSpec(
                    varargJson(*base, "cronExpr" to "0 9 * * 1-5", "recurring" to false), 0L,
                )
            }.contains("形态暂不支持"),
        )
        assertTrue(
            automationError {
                ZCodeScheduledMessageService.automationCreateToSpec(
                    varargJson(*base, "cronExpr" to "* * * * *", "recurring" to false, "intervalUnit" to "minute", "interval" to 20L), 0L,
                )
            }.contains("仅支持一次性"),
        )
        assertTrue(
            automationError {
                ZCodeScheduledMessageService.automationCreateToSpec(varargJson(*base, "relativeDelayMinutes" to 5L, "recurring" to false, "maxRuns" to 3L), 0L)
            }.contains("仅支持一次性"),
        )
        // 缺 prompt / 缺目标会话 / 延时越界
        assertTrue(automationError { ZCodeScheduledMessageService.automationCreateToSpec(varargJson("targetTaskId" to "s"), 0L) }.contains("prompt"))
        assertTrue(
            automationError { ZCodeScheduledMessageService.automationCreateToSpec(varargJson("prompt" to "p"), 0L) }.contains("targetTaskId"),
        )
        assertTrue(
            automationError {
                ZCodeScheduledMessageService.automationCreateToSpec(varargJson(*base, "relativeDelayMinutes" to 0L, "recurring" to false), 0L)
            }.contains("relativeDelayMinutes"),
        )
        // 两个字段都缺（recurring 默认 true 会先撞不支持话术，须显式一次性）
        assertTrue(
            automationError {
                ZCodeScheduledMessageService.automationCreateToSpec(varargJson(*base, "recurring" to false), 0L)
            }.contains("触发时间"),
        )
    }

    @Test
    fun `item 转 automation 应答字段严格对齐 schema（无多余字段）`() {
        val item = ZCodeScheduledMessageService.Item(
            id = "sched_1", sessionId = "sess_1", workspacePath = "G:\\p",
            text = "跑回归", fireAt = 1_777_777_777_000L, createdAt = 0, title = "跑回归任务",
        )
        val auto = ZCodeScheduledMessageService.itemToAutomation(item)
        // strict schema：多余字段会让 zcode.cjs 校验崩，必填字段一个不能少
        assertEquals(
            setOf("automationId", "title", "cronExpr", "prompt", "enabled", "lifecycleStatus", "nextRunAt", "runCount", "recurring", "targetTaskId"),
            auto.keys.toSet(),
        )
        assertEquals("sched_1", auto["automationId"]!!.jsonPrimitive.content)
        assertEquals("跑回归任务", auto["title"]!!.jsonPrimitive.content)
        assertEquals("跑回归", auto["prompt"]!!.jsonPrimitive.content)
        assertEquals("sess_1", auto["targetTaskId"]!!.jsonPrimitive.content)
        assertEquals(true, auto["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("active", auto["lifecycleStatus"]!!.jsonPrimitive.content)
        assertEquals(1_777_777_777_000L, auto["nextRunAt"]!!.jsonPrimitive.long)
        assertEquals(0L, auto["runCount"]!!.jsonPrimitive.long)
        assertEquals(false, auto["recurring"]!!.jsonPrimitive.boolean)
        // cronExpr 为派生显示形态（分 时 日 月 *，本地时区）
        assertEquals(ZCodeScheduledMessageService.oneShotCronFromFireAt(1_777_777_777_000L), auto["cronExpr"]!!.jsonPrimitive.content)
        // 无 title 的用户建条目：标题派生、无 sessionId 不回填 targetTaskId
        val userItem = item.copy(title = null, sessionId = "")
        val auto2 = ZCodeScheduledMessageService.itemToAutomation(userItem)
        assertFalse("targetTaskId" in auto2)
        assertTrue(auto2["title"]!!.jsonPrimitive.content.isNotEmpty())
    }

    @Test
    fun `title 序列化回环（旧存储无 title 兼容）`() {
        val src = listOf(
            ZCodeScheduledMessageService.Item("a", "sess", "G:\\p", "文本", 123L, 456L, title = "AI 建的任务"),
            ZCodeScheduledMessageService.Item("b", "sess2", "", "文本2", 1L, 2L),
        )
        val parsed = ZCodeScheduledMessageService.parseItems(ZCodeScheduledMessageService.itemsToJson(src).toString())
        assertEquals(src, parsed)
        val legacy = ZCodeScheduledMessageService.parseItems(
            "[{\"id\": \"a\", \"sessionId\": \"s\", \"workspacePath\": \"\", \"text\": \"x\", \"fireAt\": 1, \"createdAt\": 2, \"hold\": false}]",
        )
        assertEquals(null, legacy[0].title)
    }
}
