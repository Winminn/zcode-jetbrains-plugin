package com.zcode.ideaplugin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ExitPlanMode 空 accept 应答判定的纯函数测试（缺陷修复回归）：
 * 空 answer 的 accept 不得按批准处理（旧版 normalize 成 "approve"，
 * 前端防线失守时会把"用户没说批准"变成"批准执行"）。
 */
class ZCodeServiceImplTest {

    /** zcode.cjs t5() 生成的标准三选项（服务端实发形态的等价样本）*/
    private fun standardOptions() = buildJsonArray {
        add(buildJsonObject {
            put("kind", "allow_once"); put("name", "Allow once"); put("optionId", "allow_once")
            put("response", buildJsonObject { put("decision", "allow"); put("reason", "Approved once") })
        })
        add(buildJsonObject {
            put("kind", "allow_always"); put("name", "Always allow in this project"); put("optionId", "allow_project")
            put("response", buildJsonObject {
                put("decision", "allow"); put("reason", "Approved for this project")
                put("permissionUpdates", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "addRules"); put("behavior", "allow")
                        put("rules", buildJsonArray { add(buildJsonObject { put("toolName", "Write") }) })
                    })
                })
            })
        })
        add(buildJsonObject {
            put("kind", "deny"); put("name", "Deny"); put("optionId", "deny")
            put("response", buildJsonObject { put("decision", "deny"); put("reason", "Denied") })
        })
    }

    @Test
    fun `ExitPlanMode accept 空 answer 按 decline`() {
        assertTrue(ZCodeServiceImpl.isDeclineResponse(isPlanApproval = true, action = "accept", answer = null))
        assertTrue(ZCodeServiceImpl.isDeclineResponse(isPlanApproval = true, action = "accept", answer = JsonPrimitive("")))
        assertTrue(ZCodeServiceImpl.isDeclineResponse(isPlanApproval = true, action = "accept", answer = JsonPrimitive("   ")))
    }

    @Test
    fun `ExitPlanMode accept 有值 answer 透传不 decline`() {
        // "approve" = 批准；意见文本 = 反馈式拒绝——两者都不归插件拦截
        assertFalse(ZCodeServiceImpl.isDeclineResponse(isPlanApproval = true, action = "accept", answer = JsonPrimitive("approve")))
        assertFalse(ZCodeServiceImpl.isDeclineResponse(isPlanApproval = true, action = "accept", answer = JsonPrimitive("计划再细化一下")))
        // 大写 "Approve" ≠ 严格小写，服务端判反馈式拒绝，同样透传
        assertFalse(ZCodeServiceImpl.isDeclineResponse(isPlanApproval = true, action = "accept", answer = JsonPrimitive("Approve")))
    }

    @Test
    fun `显式 decline 与 cancel 一律 decline`() {
        assertTrue(ZCodeServiceImpl.isDeclineResponse(isPlanApproval = true, action = "decline", answer = JsonPrimitive("approve")))
        assertTrue(ZCodeServiceImpl.isDeclineResponse(isPlanApproval = false, action = "cancel", answer = null))
    }

    @Test
    fun `AskUserQuestion 空 answer 不改语义（透传服务端）`() {
        // AskUser 空答案不属插件拦截范围：answer 缺省的 accept 原样透传
        assertFalse(ZCodeServiceImpl.isDeclineResponse(isPlanApproval = false, action = "accept", answer = null))
        // 多选数组答案同样透传
        val arr = buildJsonArray { add(JsonPrimitive("选项A")); add(JsonPrimitive("选项B")) }
        assertFalse(ZCodeServiceImpl.isDeclineResponse(isPlanApproval = false, action = "accept", answer = arr))
    }

    // ============ interaction/requestPermission 应答构建（issue #2 修复）============

    @Test
    fun `权限审批 allow_once 回传选中项 response 原样`() {
        val result = ZCodeServiceImpl.buildPermissionResult(standardOptions(), "accept", JsonPrimitive("allow_once"))
        assertEquals("allow", result["decision"]?.jsonPrimitive?.content)
        assertEquals("Approved once", result["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `权限审批 allow_project 原样透传服务端生成的 permissionUpdates`() {
        val result = ZCodeServiceImpl.buildPermissionResult(standardOptions(), "accept", JsonPrimitive("allow_project"))
        assertEquals("allow", result["decision"]?.jsonPrimitive?.content)
        val updates = result["permissionUpdates"] as? kotlinx.serialization.json.JsonArray
        assertEquals(1, updates?.size)
        // 宿主不自行构造规则：规则体必须与服务端给定内容一致（toolName=Write）
        val rule = ((updates?.first() as? JsonObject)?.get("rules") as? kotlinx.serialization.json.JsonArray)
        val tool = (rule?.first() as? JsonObject)?.get("toolName")?.jsonPrimitive?.content
        assertEquals("Write", tool)
    }

    @Test
    fun `权限审批 deny 与显式 decline 均 deny`() {
        val result = ZCodeServiceImpl.buildPermissionResult(standardOptions(), "accept", JsonPrimitive("deny"))
        assertEquals("deny", result["decision"]?.jsonPrimitive?.content)
        // action=decline（前端取消按钮）：即使 answer 有值也拒绝
        val declined = ZCodeServiceImpl.buildPermissionResult(standardOptions(), "decline", JsonPrimitive("allow_once"))
        assertEquals("deny", declined["decision"]?.jsonPrimitive?.content)
    }

    @Test
    fun `权限审批空 answer 与未知 optionId 安全侧 deny`() {
        // accept 但 answer 缺失（前端防线失守/回归）：不得默认放行
        assertEquals("deny", ZCodeServiceImpl.buildPermissionResult(standardOptions(), "accept", null)["decision"]?.jsonPrimitive?.content)
        // 未知 optionId（选项列表与服务端失配）
        assertEquals(
            "deny",
            ZCodeServiceImpl.buildPermissionResult(standardOptions(), "accept", JsonPrimitive("nonexistent"))["decision"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `权限审批 options 缺 response 时 allow_once 兜底 allow 其余 deny`() {
        // 对齐 zcode.cjs v4AnswerToPermissionResponse 兜底语义
        val bare = buildJsonArray {
            add(buildJsonObject { put("kind", "allow_once"); put("optionId", "allow_once") })
            add(buildJsonObject { put("kind", "deny"); put("optionId", "deny") })
        }
        assertEquals(
            "allow",
            ZCodeServiceImpl.buildPermissionResult(bare, "accept", JsonPrimitive("allow_once"))["decision"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "deny",
            ZCodeServiceImpl.buildPermissionResult(bare, "accept", JsonPrimitive("deny"))["decision"]?.jsonPrimitive?.content,
        )
        // 应答体不含 permissionUpdates 字段（S2 strict schema，多余字段即校验失败——此处仅验证兜底路径的干净形状）
        assertNull(ZCodeServiceImpl.buildPermissionResult(bare, "accept", JsonPrimitive("allow_once"))["permissionUpdates"])
    }

    // ============ 回合匹配判据（2026-08-27 弹窗误杀修复回归）============
    // 缺陷场景：同会话内工具超时重试 → 旧回合 turn.failed 晚于新回合弹窗创建到达 →
    // 按 sessionId 一刀切的废弃清理误杀新弹窗。修复 = pending 记创建时活动回合，
    // 废弃/合并均按 sameTurn 判据匹配。

    @Test
    fun `回合匹配 双方已知且相同视为同回合（同族重发共享、终止正常废弃）`() {
        assertTrue(ZCodeServiceImpl.sameTurn("turn-1", "turn-1"))
    }

    @Test
    fun `回合匹配 双方已知且不同视为不同回合（迟到终止不误杀新回合弹窗）`() {
        assertFalse(ZCodeServiceImpl.sameTurn("turn-1", "turn-2"))
    }

    @Test
    fun `回合匹配 任一方未知保守视为同回合（回合事件未到时保持旧清理能力）`() {
        assertTrue(ZCodeServiceImpl.sameTurn(null, "turn-2"))
        assertTrue(ZCodeServiceImpl.sameTurn("turn-1", null))
        assertTrue(ZCodeServiceImpl.sameTurn(null, null))
    }
}
