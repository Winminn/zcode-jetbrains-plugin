package com.zcode.ideaplugin.protocol

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/**
 * -32031（restoreWarning）恢复路径回归测试 —— 假 app-server 驱动，确定性、无真实模型调用：
 *
 * 假服务器行为：
 *   - 不带 runtimeModel 的 session/send → 回 -32031「历史任务使用的模型已不可用」
 *   - 带 runtimeModel 的 session/send    → 回 accepted + 回显 runtimeModel 内容
 *   - 其余方法 → 回空 result
 *   - 以 `-p` 参数启动（CLI 模式）→ 输出 {"response":"cli-ok"}
 *
 * 场景1：send 遇 -32031 后应带 runtimeModel 重试并被接受（而不是落 CLI --resume）
 * 场景2：runtimeModel 构造不出（factory 返回 null）时才落 CLI --resume 兜底
 */
class RuntimeModelRecoveryTest {

    @TempDir
    lateinit var tempDir: Path

    private val fakeServerJs = """
        import readline from 'node:readline';
        if (process.argv.includes('-p')) {
            process.stdout.write(JSON.stringify({ response: 'cli-ok' }) + '\n');
            process.exit(0);
        }
        const rl = readline.createInterface({ input: process.stdin });
        rl.on('line', line => {
            let m; try { m = JSON.parse(line); } catch { return; }
            if (m.id === undefined || !m.method) return;
            if (m.method === 'session/send') {
                if (m.params?.runtimeModel) {
                    process.stdout.write(JSON.stringify({ id: m.id, result: {
                        accepted: true, usedRuntimeModel: true,
                        modelId: m.params.runtimeModel.model?.modelId,
                        providerId: m.params.runtimeModel.provider?.providerId
                    } }) + '\n');
                } else {
                    process.stdout.write(JSON.stringify({ id: m.id, error: { code: -32031, message: '历史任务使用的模型已不可用' } }) + '\n');
                }
            } else {
                process.stdout.write(JSON.stringify({ id: m.id, result: {} }) + '\n');
            }
        });
    """.trimIndent()

    private fun startFakeClient(factory: (() -> JsonObject?)? = null): ZCodeProtocolClient {
        val script = tempDir.resolve("fake-app-server.mjs").also { it.writeText(fakeServerJs) }
        val client = ZCodeProtocolClient.start(
            zcodePath = script,
            credentials = ZCodeCredentials("test-model", "http://127.0.0.1:9", "test-key")
        )
        if (factory != null) client.runtimeModelFactory = factory
        return client
    }

    private val fakeRuntimeModel: JsonObject = buildJsonObject {
        put("revision", "0")
        put("model", buildJsonObject {
            put("providerId", "test-provider")
            put("modelId", "GLM-9.9")
        })
    }

    @Test
    fun `send 遇到 -32031 时带 runtimeModel 重试而非 CLI fallback`() {
        startFakeClient(factory = { fakeRuntimeModel }).use { client ->
            val r = client.send("sess_fake", "你好")
            assertEquals("true", r["usedRuntimeModel"]?.jsonPrimitive?.content, "应通过 runtimeModel 重试被接受")
            assertEquals("GLM-9.9", r["modelId"]?.jsonPrimitive?.content, "runtimeModel 应原样送达服务端")
        }
    }

    @Test
    fun `runtimeModel 构造失败时落 CLI --resume 兜底`() {
        startFakeClient(factory = { null }).use { client ->
            val r = client.send("sess_fake", "你好")
            assertEquals("cli-ok", r["cliResponse"]?.jsonPrimitive?.content, "无 runtimeModel 可用时走 CLI fallback")
        }
    }

    @Test
    fun `defaultRuntimeModel 从 config 构造完整 provider 定义`() {
        // hermetic：注入临时 config（enabled anthropic provider + 两个模型）
        val config = tempDir.resolve("config.json").also { it.writeText("""
            {
              "provider": {
                "disabled-one": { "enabled": false, "kind": "anthropic", "options": {}, "models": {"X": {}} },
                "p1": {
                  "enabled": true, "kind": "anthropic", "name": "测试Provider", "source": "builtin",
                  "options": { "baseURL": "https://api.test/v1", "apiKey": "sk-test" },
                  "models": { "GLM-9.9": {}, "GLM-8.8": {} }
                }
              }
            }
        """.trimIndent()) }
        val rt = RuntimeModels.defaultRuntimeModel(config)
        assertEquals("GLM-9.9", rt?.get("model")?.jsonObject?.get("modelId")?.jsonPrimitive?.content, "取第一个 enabled provider 的第一个模型")
        assertEquals("p1", rt?.get("model")?.jsonObject?.get("providerId")?.jsonPrimitive?.content)
        val provider = rt?.get("provider")?.jsonObject!!
        assertEquals("https://api.test/v1", provider["baseURL"]?.jsonPrimitive?.content)
        assertEquals("inline", provider["apiKey"]?.jsonObject?.get("source")?.jsonPrimitive?.content)
        assertEquals(2, provider["models"]?.jsonArray?.size, "provider 全部模型都注册")
        assertEquals("0", rt["revision"]?.jsonPrimitive?.content)

        // 空 provider 表 → null（调用方走 CLI 兜底）
        val emptyConfig = tempDir.resolve("empty.json").also { it.writeText("""{"provider": {}}""") }
        assertEquals(null, RuntimeModels.defaultRuntimeModel(emptyConfig))
    }
}
