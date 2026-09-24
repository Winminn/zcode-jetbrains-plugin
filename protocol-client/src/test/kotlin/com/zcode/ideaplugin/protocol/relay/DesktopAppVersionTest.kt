package com.zcode.ideaplugin.protocol.relay

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * asar 头解析单测（缺陷CZ：app_version 语义=客户端版本，从 app.asar package.json 读）。
 * fixture 按 @electron/asar 布局手工构造：16 字节头 + JSON 目录（4 字节对齐 padding）+ data 区。
 */
class DesktopAppVersionTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `读出 package-json version`() {
        val asar = buildAsar("""{"name":"@zcode/desktop","version":"3.14.3"}""")
        assertEquals("3.14.3", DesktopAppVersion.readAsarPackageVersion(asar))
    }

    @Test
    fun `目录 JSON 长 4 字节对齐时 padding 不破坏解析`() {
        // JSON 目录长度不齐 4 时须按 padded 长度定位 data 区（真机 asar 即此形态）
        val pkg = """{"name":"@zcode/desktop","version":"9.9.9"}"""
        val dir = """{"files":{"package.json":{"offset":"0","size":${pkg.length}},"a.js":{"offset":"${pkg.length}","size":3}},"x":1}"""
        val asar = buildAsar(pkg, dirJson = dir)
        assertEquals("9.9.9", DesktopAppVersion.readAsarPackageVersion(asar))
    }

    @Test
    fun `非 asar 文件返回 null 不抛`() {
        val f = tmp.resolve("not-asar.bin").toFile()
        f.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertNull(DesktopAppVersion.readAsarPackageVersion(f.toPath()))
    }

    @Test
    fun `resources 布局推导与缓存`() {
        val resources = tmp.resolve("resources")
        val glm = resources.resolve("glm")
        glm.toFile().mkdirs()
        buildAsar("""{"version":"3.14.3"}""").toFile().renameTo(resources.resolve("app.asar").toFile())
        val zcodeCjs = glm.resolve("zcode.cjs")
        zcodeCjs.toFile().writeText("// cli")
        assertEquals("3.14.3", DesktopAppVersion.read(zcodeCjs))
        // 无 app.asar（手动配置 CLI 场景）→ null
        assertNull(DesktopAppVersion.read(tmp.resolve("other").resolve("zcode.cjs").also { it.toFile().parentFile.mkdirs(); it.toFile().writeText("//") }))
    }

    /** 构造最小 asar：u32 头（pickle/padded/padded/actual）+ 目录 JSON + 4 对齐 padding + package.json 内容 */
    private fun buildAsar(packageJson: String, dirJson: String = """{"files":{"package.json":{"offset":"0","size":${packageJson.length}}}}"""): Path {
        val dirBytes = dirJson.toByteArray(Charsets.UTF_8)
        val padded = (dirBytes.size + 3) and 3.inv()
        val out = tmp.resolve("app-asar-${System.nanoTime()}.asar")
        out.toFile().outputStream().use { fs ->
            fun u32(v: Int) = fs.write(byteArrayOf(
                (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
            ))
            u32(4); u32(padded); u32(padded); u32(dirBytes.size)
            fs.write(dirBytes)
            repeat(padded - dirBytes.size) { fs.write(0) }
            fs.write(packageJson.toByteArray(Charsets.UTF_8))
        }
        return out
    }
}
