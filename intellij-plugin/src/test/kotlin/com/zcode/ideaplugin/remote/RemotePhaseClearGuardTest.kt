package com.zcode.ideaplugin.remote

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 缺陷EH：stop 序列相位补清的守卫语义（真实调用 clearPhaseIfEntryBefore）：
 *  条目时刻早于 stop 才清；stop 后新回合刷新的条目（>= stop 时刻）保留防误清。 */
class RemotePhaseClearGuardTest {

    @Suppress("UNCHECKED_CAST")
    private fun runningMap(svc: ZCodeRemoteService): java.util.concurrent.ConcurrentMap<String, Long> {
        val f = ZCodeRemoteService::class.java.getDeclaredField("runningSessionIds")
        f.isAccessible = true
        return f.get(svc) as java.util.concurrent.ConcurrentMap<String, Long>
    }

    @Test
    fun `stale entry before stop is cleared`() {
        val svc = ZCodeRemoteService()
        val stopStart = 1_000_000L
        runningMap(svc)["sess_a"] = 999_999L // stop 之前的相位写入
        svc.clearPhaseIfEntryBefore("sess_a", stopStart)
        assertFalse(runningMap(svc).containsKey("sess_a"), "stop 前的旧条目应被清出")
    }

    @Test
    fun `entry refreshed after stop is kept`() {
        val svc = ZCodeRemoteService()
        val stopStart = 1_000_000L
        runningMap(svc)["sess_b"] = 1_000_001L // stop 后新回合 turn.started 抢跑刷新
        svc.clearPhaseIfEntryBefore("sess_b", stopStart)
        assertTrue(runningMap(svc).containsKey("sess_b"), "stop 后刷新的条目不可误清")
    }

    @Test
    fun `subagent session is filtered`() {
        val svc = ZCodeRemoteService()
        runningMap(svc)["sess_subagent_x"] = 1L
        svc.clearPhaseIfEntryBefore("sess_subagent_x", 2_000_000L)
        assertTrue(runningMap(svc).containsKey("sess_subagent_x"), "子代理会话不进任务列表，相位入口应短路")
    }
}
