package com.zcode.ideaplugin.protocol

import kotlinx.serialization.json.jsonObject

/**
 * app-server stderr 的模型 API 错误解析器（vercel.ai APICallError dump 兜底通道）
 *
 * 背景（2026-08-18 缺陷）：模型后端返回 429 quota_exceeded 等错误时，app-server
 * （zcode.cjs）按 isRetryable 指数退避持续重试，turn 终止帧（turn.failed）迟迟不发
 * ——协议事件流上无任何错误迹象，前端无限转圈且无提示。stderr 的 APICallError
 * dump 是错误的第一现场（比终止帧早数分钟），此处轻量解析后经
 * ZCodeProtocolClient.backendErrorHandler 推给宿主展示。
 *
 * stderr 首行样本（2026-08-18 idea.log 实录）：
 * ```
 * APICallError [AI_APICallError]: Error code: 429 - {'error': {'code': 'token_quota_exceeded', 'message': 'Token Plan Person monthly quota limit exceeded', 'type': 'quota_exceeded'}, 'id': 'as-0bx6jqnjsi'}
 * ```
 * 后续行（statusCode:/responseBody: 等）不解析——首行已含全部关键信息。
 *
 * 线程模型：仅在 stderr drain 线程逐行调用，无可变共享状态，无需并发控制。
 */
class BackendErrorDetector(
    /** 同签名去重窗口：重试会反复打出同样 dump，窗口内只上报一次 */
    private val dedupeWindowMs: Long = 60_000,
    /** 可注入时钟（测试用） */
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var lastSignature: String? = null
    private var lastReportAt = 0L

    /** 解析结果（statusCode/code 可缺，message 必有）*/
    data class BackendApiError(
        val statusCode: Int?,
        val code: String?,
        val message: String,
    ) {
        /** 配额类错误（token_quota_exceeded 等）：确定性失败，重试不会成功 */
        val isQuotaError: Boolean
            get() = code?.lowercase()?.contains("quota") == true ||
                message.lowercase().contains("quota_exceeded")
    }

    /**
     * 喂入一行 stderr。命中 APICallError 首行且通过去重窗口时返回解析结果，否则 null。
     */
    fun feed(line: String): BackendApiError? {
        if (!line.contains("APICallError") || !line.contains("Error code:")) return null

        val statusCode = STATUS_CODE_RE.find(line)?.groupValues?.get(1)?.toIntOrNull()
        val code = CODE_RE.find(line)?.groupValues?.get(1)
        val message = MESSAGE_RE.find(line)?.groupValues?.get(1)
        // 信息量不足（非标准格式 dump）不上报，避免误导
        if (statusCode == null && code == null && message.isNullOrBlank()) return null

        val signature = "$statusCode|$code"
        val t = now()
        if (signature == lastSignature && t - lastReportAt < dedupeWindowMs) return null
        lastSignature = signature
        lastReportAt = t

        return BackendApiError(statusCode = statusCode, code = code, message = message ?: line.take(200))
    }

    // ===== 崩溃级进程异常（官方机器可读契约，2026-09-21 开源源码 process-diagnostic.ts）=====

    /**
     * `[zcode-process-exception] {version,errorId,kind,origin,name,message,stack,occurredAt}`
     * 单行 JSON——app-server 崩溃级错误的官方 stderr 契约（任何入口异常的统一出口，
     * cli/src/main.ts 写入）。与 [feed] 的 APICallError 语义不同：那是模型 API 错误
     * （进程活着、服务端在重试），这是进程要挂了——readLoop 断连收尾会另行走，此处
     * 只负责把结构化根因实时递给宿主展示（比断连后翻 stderr 尾部更直接）。
     *
     * @return 解析结果；非契约行返回 null。按 errorId 去重（重试/多行重复只报一次）。
     */
    fun parseProcessException(line: String): ProcessException? {
        if (!line.startsWith(PROCESS_EXCEPTION_PREFIX)) return null
        val payload = try {
            json.parseToJsonElement(line.substring(PROCESS_EXCEPTION_PREFIX.length)).jsonObject
        } catch (_: Exception) {
            return null
        }
        val errorId = payload.str("errorId") ?: return null
        synchronized(this) {
            if (errorId == lastProcessExceptionId) return null
            lastProcessExceptionId = errorId
        }
        return ProcessException(
            errorId = errorId,
            kind = payload.str("kind") ?: "unknown",
            origin = payload.str("origin") ?: "unknown",
            name = payload.str("name") ?: "",
            message = payload.str("message") ?: "",
        )
    }

    /** 进程崩溃契约的解析结果（stack/occurredAt/version 对用户无增量，不入上报） */
    data class ProcessException(
        val errorId: String,
        val kind: String,
        val origin: String,
        val name: String,
        val message: String,
    )

    private var lastProcessExceptionId: String? = null
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

    companion object {
        private val STATUS_CODE_RE = Regex("""Error code:\s*(\d+)""")
        private val CODE_RE = Regex("""'code':\s*'([^']*)'""")
        private val MESSAGE_RE = Regex("""'message':\s*'([^']*)'""")
        private const val PROCESS_EXCEPTION_PREFIX = "[zcode-process-exception] "
    }
}
