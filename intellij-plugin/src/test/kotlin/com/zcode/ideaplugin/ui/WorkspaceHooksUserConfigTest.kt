package com.zcode.ideaplugin.ui

// 用户级钩子（~/.zcode/cli/config.json）：直读列表 + 启停/删除写盘（不走 CLI，home 注入临时目录）

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceHooksUserConfigTest {

    @TempDir
    lateinit var home: Path

    private val configPath: Path get() = home.resolve(".zcode/cli/config.json")

    private fun writeConfig(json: String) {
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, json)
    }

    private fun readConfig(): JsonObject =
        Json.parseToJsonElement(Files.readString(configPath)).let { it as JsonObject }

    private fun items(status: JsonObject): JsonArray = status["items"]!!.jsonArray

    @Test
    fun `missing file yields empty user list with scope and path`() {
        val status = WorkspaceHooksCli.userHooksList(home)
        assertEquals("user", status["scope"]!!.jsonPrimitive.content)
        assertEquals(configPath.toString().replace('\\', '/'),
            status["userConfigPath"]!!.jsonPrimitive.content.replace('\\', '/'))
        assertEquals(0, items(status).size)
    }

    @Test
    fun `parses events matcher args and default enabled`() {
        writeConfig(
            """
            {
              "skills": {},
              "hooks": {
                "events": {
                  "PreToolUse": [
                    { "matcher": "Bash", "hooks": [
                      { "type": "process", "command": "node", "args": ["hook.js", "--x"] },
                      { "type": "command", "command": "bash inline.sh", "enabled": false }
                    ]}
                  ],
                  "Stop": [
                    { "hooks": [ { "type": "command", "command": "notify.cjs" } ] }
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        val status = WorkspaceHooksCli.userHooksList(home)
        val items = items(status)
        assertEquals(3, items.size)

        val first = items[0].jsonObject
        assertEquals("user-hook-0-PreToolUse-0-0", first["reviewItemId"]!!.jsonPrimitive.content)
        assertEquals("PreToolUse", first["event"]!!.jsonPrimitive.content)
        assertEquals("Bash", first["matcher"]!!.jsonPrimitive.content)
        assertEquals("node hook.js --x", first["displayCommand"]!!.jsonPrimitive.content)
        assertEquals(true, first["configuredEnabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("user_scope", first["trustState"]!!.jsonPrimitive.content)
        assertEquals(configPath.toString(), first["sourcePath"]!!.jsonPrimitive.content)

        val disabled = items[1].jsonObject
        assertEquals(false, disabled["configuredEnabled"]!!.jsonPrimitive.content.toBoolean())

        val stop = items[2].jsonObject
        assertNull(stop["matcher"])
        assertEquals("notify.cjs", stop["displayCommand"]!!.jsonPrimitive.content)
    }

    @Test
    fun `config without hooks or events yields empty list`() {
        writeConfig("""{ "skills": {}, "mcp": {} }""")
        assertEquals(0, items(WorkspaceHooksCli.userHooksList(home)).size)

        writeConfig("""{ "hooks": { "events": {} } }""")
        assertEquals(0, items(WorkspaceHooksCli.userHooksList(home)).size)
    }

    @Test
    fun `malformed nodes are skipped`() {
        writeConfig(
            """
            { "hooks": { "events": {
              "PreToolUse": "not-an-array",
              "Stop": [ { "hooks": [ { "type": "command" }, { "command": "ok.sh" } ] } ]
            } } }
            """.trimIndent(),
        )
        val items = items(WorkspaceHooksCli.userHooksList(home))
        assertEquals(1, items.size)
        assertEquals("ok.sh", items[0].jsonObject["displayCommand"]!!.jsonPrimitive.content)
    }

    @Test
    fun `disable writes enabled false and preserves other top-level sections`() {
        writeConfig(
            """
            {
              "skills": { "a": true },
              "mcp": { "x": 1 },
              "hooks": { "events": { "Stop": [ { "hooks": [
                { "type": "command", "command": "notify.cjs" }
              ] } ] } }
            }
            """.trimIndent(),
        )

        val status = WorkspaceHooksCli.userSetEnabled(
            "user-hook-0-Stop-0-0", configPath.toString(), false, home,
        )
        assertEquals("user", status["scope"]!!.jsonPrimitive.content)
        assertEquals(false, items(status)[0].jsonObject["configuredEnabled"]!!.jsonPrimitive.content.toBoolean())

        val onDisk = readConfig()
        assertEquals(true, onDisk["skills"]!!.jsonObject["a"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(1, onDisk["mcp"]!!.jsonObject["x"]!!.jsonPrimitive.content.toInt())
        val hook = onDisk["hooks"]!!.jsonObject["events"]!!.jsonObject["Stop"]!!
            .jsonArray[0].jsonObject["hooks"]!!.jsonArray[0].jsonObject
        assertEquals(false, hook["enabled"]!!.jsonPrimitive.content.toBoolean())

        // 再启用：删 enabled 字段（缺省即启用）
        WorkspaceHooksCli.userSetEnabled("user-hook-0-Stop-0-0", configPath.toString(), true, home)
        val after = readConfig()["hooks"]!!.jsonObject["events"]!!.jsonObject["Stop"]!!
            .jsonArray[0].jsonObject["hooks"]!!.jsonArray[0].jsonObject
        assertFalse(after.containsKey("enabled"))
    }

    @Test
    fun `delete prunes tree and preserves other sections`() {
        writeConfig(
            """
            {
              "plugins": [],
              "hooks": { "events": {
                "PreToolUse": [ { "matcher": "Bash", "hooks": [ { "command": "a.sh" } ] } ],
                "Stop": [ { "hooks": [ { "command": "b.sh" } ] } ]
              } }
            }
            """.trimIndent(),
        )

        val status = WorkspaceHooksCli.userDelete("user-hook-0-Stop-0-0", configPath.toString(), home)
        assertEquals(1, items(status).size)
        assertEquals("PreToolUse", items(status)[0].jsonObject["event"]!!.jsonPrimitive.content)

        val onDisk = readConfig()
        assertTrue(onDisk.containsKey("plugins"))
        val events = onDisk["hooks"]!!.jsonObject["events"]!!.jsonObject
        assertFalse(events.containsKey("Stop"))
        assertTrue(events.containsKey("PreToolUse"))
    }

    @Test
    fun `foreign source path and bad locator are rejected`() {
        writeConfig("""{ "hooks": { "events": { "Stop": [ { "hooks": [ { "command": "a.sh" } ] } ] } } }""")

        assertThrows(IllegalStateException::class.java) {
            WorkspaceHooksCli.userSetEnabled("user-hook-0-Stop-0-0", "E:/elsewhere/config.json", false, home)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceHooksCli.userSetEnabled("bad-id", configPath.toString(), false, home)
        }
        assertThrows(IllegalStateException::class.java) {
            WorkspaceHooksCli.userDelete("user-hook-0-NoSuchEvent-0-0", configPath.toString(), home)
        }
    }
}
