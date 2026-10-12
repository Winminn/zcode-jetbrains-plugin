package com.zcode.ideaplugin.ui

// resolveScriptPath：displayCommand → 钩子脚本文件解析（不启动 CLI，纯文件系统用例）

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceHooksCliScriptResolveTest {

    @TempDir
    lateinit var ws: Path

    private fun mk(rel: String): Path {
        val p = ws.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, "# hook\n")
        return p
    }

    @Test
    fun `bash with quoted absolute path`() {
        val script = mk(".zcode/hooks/frontend-keyword-hook.sh")
        val cmd = "bash \"$script\""
        assertEquals(script.normalize(), WorkspaceHooksCli.resolveScriptPath(ws.toString(), cmd))
    }

    @Test
    fun `node quoted absolute path with trailing args`() {
        val script = mk(".zcode/hooks/block-default-mvn.js")
        val cmd = "node \"$script\" --strict --x"
        assertEquals(script.normalize(), WorkspaceHooksCli.resolveScriptPath(ws.toString(), cmd))
    }

    @Test
    fun `relative script path resolves against workspace`() {
        val script = mk(".zcode/hooks/rel.sh")
        assertEquals(
            script.normalize(),
            WorkspaceHooksCli.resolveScriptPath(ws.toString(), "bash .zcode/hooks/rel.sh"),
        )
    }

    @Test
    fun `runner flags before script are skipped`() {
        val script = mk("hooks/a.sh")
        assertEquals(
            script.normalize(),
            WorkspaceHooksCli.resolveScriptPath(ws.toString(), "bash -e hooks/a.sh"),
        )
    }

    @Test
    fun `cmd slash c`() {
        val script = mk("hooks/a.bat")
        assertEquals(
            script.normalize(),
            WorkspaceHooksCli.resolveScriptPath(ws.toString(), "cmd /c hooks/a.bat"),
        )
    }

    @Test
    fun `unknown runner treats first token as target`() {
        val script = mk("run.sh")
        assertEquals(
            script.normalize(),
            WorkspaceHooksCli.resolveScriptPath(ws.toString(), "./run.sh"),
        )
    }

    @Test
    fun `inline command flag yields null`() {
        assertNull(WorkspaceHooksCli.resolveScriptPath(ws.toString(), "bash -c \"echo hi\""))
        assertNull(WorkspaceHooksCli.resolveScriptPath(ws.toString(), "node -e \"console.log(1)\""))
    }

    @Test
    fun `missing file yields null`() {
        assertNull(
            WorkspaceHooksCli.resolveScriptPath(ws.toString(), "bash \"${ws.resolve("nope.sh")}\""),
        )
    }

    @Test
    fun `empty command yields null`() {
        assertNull(WorkspaceHooksCli.resolveScriptPath(ws.toString(), "   "))
    }
}
