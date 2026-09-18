package com.zcode.ideaplugin.env

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ZCodeProviderBootstrapTest {

    /** 造一个假 CLI 布局：<root>/bin/glm/zcode.cjs（只占位，bootstrap 不读内容） */
    private fun fakeCli(root: Path): Path =
        root.resolve("bin/glm/zcode.cjs").also { Files.createDirectories(it.parent); Files.writeString(it, "cli") }

    /** 在 home 下铺客户端登录产物 v2/runtime/provider/<arch>/<ver>/endpoint-<hash>/ */
    private fun seedRuntimeProvider(home: Path, content: String, mtime: Long? = null): Path {
        val p = home.resolve(".zcode/v2/runtime/provider/linux-x86_64/3.12.3/endpoint-abc/zcode-builtin.json")
        Files.createDirectories(p.parent)
        Files.writeString(p, content)
        mtime?.let { Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(it)) }
        return p
    }

    @Test
    fun `已存在时不覆盖`(@TempDir tmp: Path) {
        val cli = fakeCli(tmp.resolve("cliroot"))
        val providerDir = cli.resolveSibling("provider")
        Files.createDirectories(providerDir)
        Files.writeString(providerDir.resolve("zcode-builtin.json"), "existing")

        val result = ZCodeProviderBootstrap.ensureBuiltinProvider(cli, home = tmp.resolve("home"))

        assertTrue(result is ZCodeProviderBootstrap.Result.Already)
        assertEquals("existing", Files.readString(providerDir.resolve("zcode-builtin.json")))
    }

    @Test
    fun `缺失时从 v2 runtime 官方落点垫入`(@TempDir tmp: Path) {
        val cli = fakeCli(tmp.resolve("cliroot"))
        val home = tmp.resolve("home")
        seedRuntimeProvider(home, "official-runtime")

        val result = ZCodeProviderBootstrap.ensureBuiltinProvider(cli, home = home)

        assertTrue(result is ZCodeProviderBootstrap.Result.Copied)
        assertEquals(
            "official-runtime",
            Files.readString(cli.resolveSibling("provider/zcode-builtin.json")),
        )
    }

    @Test
    fun `多源时取 mtime 最新的`(@TempDir tmp: Path) {
        val cli = fakeCli(tmp.resolve("cliroot"))
        val home = tmp.resolve("home")
        seedRuntimeProvider(home, "older", mtime = 1_000)
        // 第二个 endpoint 目录（不同 hash），mtime 更新
        val newer = home.resolve(".zcode/v2/runtime/provider/linux-x86_64/3.12.3/endpoint-def/zcode-builtin.json")
        Files.createDirectories(newer.parent)
        Files.writeString(newer, "newer")
        Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(2_000))

        val result = ZCodeProviderBootstrap.ensureBuiltinProvider(cli, home = home)

        assertTrue(result is ZCodeProviderBootstrap.Result.Copied)
        assertEquals("newer", Files.readString(cli.resolveSibling("provider/zcode-builtin.json")))
    }

    @Test
    fun `无源时降级 Failed 不抛不落盘`(@TempDir tmp: Path) {
        val cli = fakeCli(tmp.resolve("cliroot"))

        val result = ZCodeProviderBootstrap.ensureBuiltinProvider(cli, home = tmp.resolve("empty-home"))

        assertTrue(result is ZCodeProviderBootstrap.Result.Failed)
        assertTrue(!Files.exists(cli.resolveSibling("provider")))
    }
}
