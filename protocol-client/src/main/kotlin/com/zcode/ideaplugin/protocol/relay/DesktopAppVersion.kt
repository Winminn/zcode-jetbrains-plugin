package com.zcode.ideaplugin.protocol.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * 远程配对的宿主版本解析（app_version 语义 = ZCode 客户端版本，3.x 体系）。
 *
 * 官方二维码 URL 的 app_version 是 ZCode App 版本（如 3.14.3，ZCode.exe
 * ProductVersion 同源）；`zcode.cjs --version` 输出的 0.16.x 是 CLI 包版本，
 * 属另一体系——旧实现误用后者落在服务端版本分支的「旧宿主拉
 * cdn.zcode-ai.com 兼容配置」档（该 CDN 不可用 → H5 启动反复刷新，缺陷CZ）。
 *
 * 插件宿主的 zcode.cjs 取自本机 ZCode App 安装（resources/glm/zcode.cjs），
 * 上报该 App 的版本语义成立：从 CLI 路径两级祖先（resources/）读 app.asar
 * 头部目录中的 package.json.version。asar 解析只读头部 JSON 目录 + 目标字节，
 * 按 mtime 缓存，手动配置 CLI（无 App 安装）返回 null 走兜底常量。
 */
object DesktopAppVersion {

    private val cache = ConcurrentHashMap<Path, Pair<Long, String?>>()

    /** zcode.cjs → <app>/resources/app.asar → package.json.version；不可用返回 null */
    fun read(zcodePath: Path): String? {
        val asar = zcodePath.parent?.parent?.resolve("app.asar") ?: return null
        if (!Files.isRegularFile(asar)) return null
        val mtime = try {
            Files.getLastModifiedTime(asar).toMillis()
        } catch (e: Exception) {
            return null
        }
        cache[asar]?.let { (cachedMtime, cached) -> if (cachedMtime == mtime) return cached }
        val version = readAsarPackageVersion(asar)
        cache[asar] = mtime to version
        return version
    }

    /**
     * asar 布局（@electron/asar）：
     *   [0..4)   pickle 头长度字段
     *   [4..8)   json padded 长度（同 u32[2]）
     *   [8..12)  json padded 长度
     *   [12..16) json 实际字节数
     *   [16..]   JSON 文件目录（files 树，offset 为 string 需转数值）
     *   data 区起点 = 16 + jsonPadded
     */
    internal fun readAsarPackageVersion(asar: Path): String? = runCatching {
        RandomAccessFile(asar.toFile(), "r").use { raf ->
            val head = ByteArray(16)
            raf.readFully(head)
            val jsonPadded = leU32(head, 8)
            val jsonLen = leU32(head, 12)
            if (jsonLen <= 0 || jsonLen > MAX_HEADER_JSON_BYTES) return@use null
            val jsonBytes = ByteArray(jsonLen)
            raf.readFully(jsonBytes)
            val pkgEntry = Json.parseToJsonElement(jsonBytes.decodeToString())
                .jsonObject["files"]?.jsonObject?.get("package.json")?.jsonObject ?: return@use null
            val offset = pkgEntry["offset"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@use null
            val size = pkgEntry["size"]?.jsonPrimitive?.longOrNull ?: return@use null
            if (size <= 0 || size > MAX_PACKAGE_JSON_BYTES) return@use null
            raf.seek(16L + jsonPadded + offset)
            val buf = ByteArray(size.toInt())
            raf.readFully(buf)
            Json.parseToJsonElement(buf.decodeToString())
                .jsonObject["version"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    /** asar 目录树理论上可达数 MB，超限视为损坏文件拒绝解析 */
    private const val MAX_HEADER_JSON_BYTES = 64 * 1024 * 1024

    private const val MAX_PACKAGE_JSON_BYTES = 1024 * 1024

    private fun leU32(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)
}
