package com.zcode.ideaplugin.protocol

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * respondWorkspaceHookReview 信封回归测试 —— 假 app-server 回显信封字段，
 * 无真实模型调用：
 *   - type/type=respondWorkspaceHookReview
 *   - payload 携带标识回传与 trust_selected 决策
 *   - commandId/clientId/issuedAt 信封三件套
 */
class WorkspaceHookReviewRespondTest {

    @TempDir
    lateinit var tempDir: Path

    private val fakeServerJs = """
        import readline from 'node:readline';
        const rl = readline.createInterface({ input: process.stdin });
        rl.on('line', line => {
            let m; try { m = JSON.parse(line); } catch { return; }
            if (m.id === undefined || !m.method) return;
            if (m.method === 'v4/command') {
                const p = m.params || {};
                const pl = p.payload || {};
                const dec = pl.decision || {};
                process.stdout.write(JSON.stringify({ id: m.id, result: {
                    status: 'accepted',
                    echoType: p.type,
                    echoSessionId: p.sessionId,
                    echoInteractionId: pl.interactionId,
                    echoBundle: pl.bundleDigest,
                    echoAction: dec.action,
                    echoItems: dec.reviewItemIds,
                    hasCommandId: typeof p.commandId === 'string' && p.commandId.length > 0,
                    clientId: p.clientId,
                    hasIssuedAt: typeof p.issuedAt === 'number'
                } }) + '\n');
            } else {
                process.stdout.write(JSON.stringify({ id: m.id, result: {} }) + '\n');
            }
        });
    """.trimIndent()

    private fun startFakeClient(): ZCodeProtocolClient {
        val script = tempDir.resolve("fake-hook-review.mjs").also { it.writeText(fakeServerJs) }
        return ZCodeProtocolClient.start(
            zcodePath = script,
            credentials = ZCodeCredentials("test-model", "http://127.0.0.1:9", "test-key")
        )
    }

    @Test
    fun `应答信封字段正确`() {
        startFakeClient().use { client ->
            val identity = buildJsonObject {
                put("taskId", "task_1")
                put("runId", "run_1")
                put("workspaceIdentity", "ws_key")
                put("bundleDigest", "sha256:b1")
                put("reviewFlowId", "flow_1")
                put("generation", 1)
                put("interactionId", "workspace-hook-interaction:uuid")
            }
            val r = client.respondWorkspaceHookReview("sess_fake", identity, listOf("item_1", "item_2"))
            assertEquals("accepted", r["status"]?.jsonPrimitive?.content)
            assertEquals("respondWorkspaceHookReview", r["echoType"]?.jsonPrimitive?.content)
            assertEquals("sess_fake", r["echoSessionId"]?.jsonPrimitive?.content)
            assertEquals("workspace-hook-interaction:uuid", r["echoInteractionId"]?.jsonPrimitive?.content)
            assertEquals("sha256:b1", r["echoBundle"]?.jsonPrimitive?.content)
            assertEquals("trust_selected", r["echoAction"]?.jsonPrimitive?.content)
            assertEquals(listOf("item_1", "item_2"),
                r["echoItems"]?.jsonArray?.map { it.jsonPrimitive.content })
            assertEquals("zcode-idea-plugin", r["clientId"]?.jsonPrimitive?.content)
            assertEquals("true", r["hasCommandId"]?.jsonPrimitive?.content)
            assertEquals("true", r["hasIssuedAt"]?.jsonPrimitive?.content)
        }
    }
}
