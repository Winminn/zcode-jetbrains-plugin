package com.zcode.ideaplugin

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ExitPlanMode 空 accept 应答判定的纯函数测试（缺陷修复回归）：
 * 空 answer 的 accept 不得按批准处理（旧版 normalize 成 "approve"，
 * 前端防线失守时会把"用户没说批准"变成"批准执行"）。
 */
class ZCodeServiceImplTest {

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
}
