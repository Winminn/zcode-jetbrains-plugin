package com.zcode.ideaplugin.ui

// removeHookFromConfig：config.json 钩子树移除 + 逐级剪枝（纯函数，不启动 CLI）

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkspaceHooksCliDeleteTest {

    private fun hook(cmd: String, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("type", "command")
        put("command", cmd)
        extra()
    }

    private fun group(vararg hooks: JsonObject, matcher: String? = null): JsonObject = buildJsonObject {
        if (matcher != null) put("matcher", matcher)
        put("hooks", JsonArray(hooks.toList()))
    }

    private fun config(events: JsonObject, rootEnabled: Boolean? = true, topExtra: JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject {
            topExtra()
            put("hooks", buildJsonObject {
                if (rootEnabled != null) put("enabled", rootEnabled)
                put("events", events)
            })
        }

    private fun events(vararg pairs: Pair<String, JsonArray>): JsonObject = buildJsonObject {
        for ((event, arr) in pairs) put(event, arr)
    }

    private fun arr(vararg groups: JsonObject) = JsonArray(groups.toList())

    @Test
    fun `removing only hook of event prunes event key, keeps others and root enabled`() {
        val input = config(
            events(
                "UserPromptSubmit" to arr(group(hook("bash a.sh"))),
                "PreToolUse" to arr(group(hook("node b.js"))),
            ),
        ) { put("theme", "dark") }

        val out = WorkspaceHooksCli.removeHookFromConfig(input, "workspace-hook-0-UserPromptSubmit-0-0")

        val hooks = out["hooks"]!!.jsonObject
        assertEquals(true, hooks["enabled"]!!.jsonPrimitive.boolean)
        val outEvents = hooks["events"]!!.jsonObject
        assertEquals(setOf("PreToolUse"), outEvents.keys)
        assertEquals(
            "node b.js",
            outEvents["PreToolUse"]!!.jsonArray[0].jsonObject["hooks"]!!.jsonArray[0]
                .jsonObject["command"]!!.jsonPrimitive.content,
        )
        assertEquals("dark", out["theme"]!!.jsonPrimitive.content)
    }

    @Test
    fun `removing one hook among two keeps sibling and group fields`() {
        val input = config(
            events(
                "PreToolUse" to arr(
                    group(hook("a.sh"), hook("b.sh") { put("timeout", 10) }, matcher = "Bash"),
                ),
            ),
        )

        val out = WorkspaceHooksCli.removeHookFromConfig(input, "workspace-hook-0-PreToolUse-0-0")

        val g = out["hooks"]!!.jsonObject["events"]!!.jsonObject["PreToolUse"]!!.jsonArray[0].jsonObject
        assertEquals("Bash", g["matcher"]!!.jsonPrimitive.content)
        val remaining = g["hooks"]!!.jsonArray
        assertEquals(1, remaining.size)
        assertEquals("b.sh", remaining[0].jsonObject["command"]!!.jsonPrimitive.content)
        assertEquals(10, remaining[0].jsonObject["timeout"]!!.jsonPrimitive.int)
    }

    @Test
    fun `empty group removed and later groups shift forward`() {
        val input = config(
            events(
                "PreToolUse" to arr(
                    group(hook("a.sh")),
                    group(hook("b.sh"), matcher = "Write"),
                ),
            ),
        )

        val out = WorkspaceHooksCli.removeHookFromConfig(input, "workspace-hook-0-PreToolUse-0-0")

        val groups = out["hooks"]!!.jsonObject["events"]!!.jsonObject["PreToolUse"]!!.jsonArray
        assertEquals(1, groups.size)
        val only = groups[0].jsonObject
        assertEquals("Write", only["matcher"]!!.jsonPrimitive.content)
        assertEquals("b.sh", only["hooks"]!!.jsonArray[0].jsonObject["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `last event removed keeps hooks shell with root enabled`() {
        val input = config(
            events("Stop" to arr(group(hook("a.sh")))),
            rootEnabled = true,
        )

        val out = WorkspaceHooksCli.removeHookFromConfig(input, "workspace-hook-0-Stop-0-0")

        val hooks = out["hooks"]!!.jsonObject
        assertEquals(true, hooks["enabled"]!!.jsonPrimitive.boolean)
        assertNull(hooks["events"])
    }

    @Test
    fun `last hook with bare hooks object removes hooks key entirely`() {
        val input = buildJsonObject {
            put("other", 1)
            put("hooks", buildJsonObject {
                put("events", buildJsonObject {
                    put("Stop", JsonArray(listOf(group(hook("a.sh")))))
                })
            })
        }

        val out = WorkspaceHooksCli.removeHookFromConfig(input, "workspace-hook-0-Stop-0-0")

        assertNull(out["hooks"])
        assertEquals(1, out["other"]!!.jsonPrimitive.int)
    }

    @Test
    fun `successive deletions across events leave enabled shell`() {
        val input = config(
            events(
                "UserPromptSubmit" to arr(group(hook("a.sh") { put("statusMessage", "hi") })),
                "PostToolUse" to arr(group(hook("c.js"))),
            ),
        )

        val after1 = WorkspaceHooksCli.removeHookFromConfig(input, "workspace-hook-0-PostToolUse-0-0")
        assertTrue(after1["hooks"]!!.jsonObject["events"]!!.jsonObject.containsKey("UserPromptSubmit"))
        val after2 = WorkspaceHooksCli.removeHookFromConfig(after1, "workspace-hook-0-UserPromptSubmit-0-0")
        // 连删两个事件后只剩根 enabled，剪枝到 hooks 壳
        val hooks = after2["hooks"]!!.jsonObject
        assertEquals(true, hooks["enabled"]!!.jsonPrimitive.boolean)
        assertFalse(hooks.containsKey("events"))
    }

    @Test
    fun `malformed locators and missing nodes throw`() {
        val input = config(events("Stop" to arr(group(hook("a.sh")))))

        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceHooksCli.removeHookFromConfig(input, "too-few-parts")
        }
        assertThrows(IllegalStateException::class.java) {
            WorkspaceHooksCli.removeHookFromConfig(input, "workspace-hook-0-Stop-0-x")
        }
        assertThrows(IllegalStateException::class.java) {
            WorkspaceHooksCli.removeHookFromConfig(input, "workspace-hook-0-NoSuchEvent-0-0")
        }
        assertThrows(IllegalStateException::class.java) {
            WorkspaceHooksCli.removeHookFromConfig(input, "workspace-hook-0-Stop-5-0")
        }
        assertThrows(IllegalStateException::class.java) {
            WorkspaceHooksCli.removeHookFromConfig(input, "workspace-hook-0-Stop-0-7")
        }
        val noEvents = buildJsonObject { put("hooks", buildJsonObject { put("enabled", true) }) }
        assertThrows(IllegalStateException::class.java) {
            WorkspaceHooksCli.removeHookFromConfig(noEvents, "workspace-hook-0-Stop-0-0")
        }
    }
}
