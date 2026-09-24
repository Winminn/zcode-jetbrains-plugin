package com.zcode.ideaplugin.protocol.relay

import kotlinx.serialization.json.JsonObject

/**
 * 手机远程会话 relay 协议常量与数据模型。
 *
 * 协议逆向自官方客户端（app.asar WebRemoteControlDeviceTransport / zcode.z.ai H5 bundle），
 * 实测定案见 docs/internal/design-research/手机远程会话探针报告-2026-08-24.md。
 *
 * 协议栈（L1-L6）：
 *  L1 WS 传输：wss://zcode.z.ai/ws?mid=<deviceMid>，header X-Device-ID
 *  L2 relay 信令：register/auth/心跳 JSON 文本帧（本文件常量）
 *  L3 data 信封：{type:"data", payload, client_ts}，payload.zcode_type 路由
 *  L4 控制面 payload：bootstrap / workspace-bridge 等（本文件常量）
 *  L5 rpc-frame：分片+crc32 数据面（FrameAssembler）
 *  L6 channel RPC：VSCode channel 二进制协议（ChannelCodec）
 */
object Relay {
    /**
     * 宿主版本兜底值（QR URL 与注册 meta 用，relay 不校验真实性）。app_version 语义 =
     * ZCode 客户端版本（3.x 体系，官方码与 ZCode.exe ProductVersion 同源；zcode.cjs
     * --version 的 0.16.x 是 CLI 包版本属另一体系，勿混用）。运行时从本机 App 安装的
     * app.asar 读真实版本优先（DesktopAppVersion），读不到才落到这里。旧值 3.8.1 触发
     * H5/relay 侧按版本拉 cdn.zcode-ai.com/zcode/config/default.json 兼容配置（官方
     * 3.14.3 客户端实测无此调用），该 CDN TLS 握手直接断连 → 手机页面启动反复刷新
     * （2026-09-23 定性，缺陷CZ）。兜底对齐官方客户端实测版本。
     */
    const val APP_VERSION = "3.14.3"

    const val DEFAULT_ORIGIN = "https://zcode.z.ai"
    val DEFAULT_WS_URL = "wss://zcode.z.ai/ws"

    /** 手机 H5 页面（QR 指向；appVersion>=3.4.0 走 v4） */
    const val REMOTE_PAGE = "/remote/v4"

    // ---- L2 信令帧 type ----
    const val TYPE_DEVICE_REGISTER_INIT = "device_register_init"
    const val TYPE_DEVICE_REGISTER_ACK = "device_register_ack"
    const val TYPE_AUTH_INIT = "auth_init"
    const val TYPE_AUTH_CHALLENGE = "auth_challenge"
    const val TYPE_AUTH_RESPONSE = "auth_response"
    const val TYPE_AUTH_ACK = "auth_ack"
    const val TYPE_PAIR_STATUS_ACK = "pair_status_ack"
    const val TYPE_PAIR_STATUS_QUERY = "pair_status_query"
    const val TYPE_DATA = "data"
    const val TYPE_ERROR = "error"

    /** relay error code（KICKED = 同凭据新连接顶掉旧连接） */
    const val ERR_KICKED = "KICKED"
    const val ERR_AUTH_FAILED = "AUTH_FAILED"
    const val ERR_INTERNAL = "INTERNAL"
    const val ERR_WRONG_PARAM = "WRONG_PARAM"

    // ---- L4 控制面 payload zcode_type ----
    const val PAYLOAD_BOOTSTRAP_REQUEST = "bootstrap-request"
    const val PAYLOAD_BOOTSPONSE = "bootstrap-response"
    const val PAYLOAD_WORKSPACE_LIST_REQUEST = "workspace-list-request"
    const val PAYLOAD_WORKSPACE_LIST_RESPONSE = "workspace-list-response"
    const val PAYLOAD_WORKSPACE_LIST_UPDATED = "workspace-list-updated"
    const val PAYLOAD_WORKSPACE_BRIDGE_OPEN = "workspace-bridge-open"
    const val PAYLOAD_WORKSPACE_BRIDGE_READY = "workspace-bridge-ready"
    const val PAYLOAD_WORKSPACE_BRIDGE_ERROR = "workspace-bridge-error"
    const val PAYLOAD_WORKSPACE_RECONNECT_REQUEST = "workspace-reconnect-request"
    const val PAYLOAD_WORKSPACE_RECONNECT_RESPONSE = "workspace-reconnect-response"
    const val PAYLOAD_MOBILE_VIEW_STATE_UPDATE = "mobile-view-state-update"
    const val PAYLOAD_MOBILE_DIAGNOSTIC = "mobile-diagnostic"
    const val PAYLOAD_PLATFORM_REQUEST = "platform-request"
    const val PAYLOAD_PLATFORM_RESPONSE = "platform-response"

    // ---- L5 数据面 ----
    const val PAYLOAD_RPC_FRAME = "rpc-frame"
    const val PAYLOAD_RPC_FRAME_ACK = "rpc-frame-ack"
    const val PAYLOAD_BRIDGE_DEGRADED = "bridge-degraded"

    /** rpc-frame 物理帧上限（超出须分片）；片数上限对齐官方 PROTOCOL_V4_LIMITS
     *  （zcode-protocol-v4/core.ts logicalFrameAssemblyMaxFragments=1024——此前记的
     *  64 片实为 attachmentUploadMaxChunks 附件上传参数，张冠李戴） */
    const val MAX_PHYSICAL_FRAME_BYTES = 1024 * 1024
    const val MAX_MESSAGE_BYTES = 16 * 1024 * 1024
    const val MAX_FRAGMENTS = 1024

    /** 心跳周期（探针实测官方 10s；H5 请求无应答 ~10s 断开，应答预算须小于此） */
    const val HEARTBEAT_INTERVAL_MS = 10_000L

    /** 心跳活性判定阈值：连续 3 个周期无任何入站帧（relay 对 pair_status_query 必回
     *  ack，10s 内必有应答）→ 判定 TCP 半开（对端静默消失、onClose/onError 不触发）
     *  强制重连。实测危害：device 僵死后 relay 对该 pair 的所有新 terminal 30s 清理
     *  （手机/浏览器页面连上即断，用户感知「重复打开链接一直闪」2026-09-23 实锤） */
    const val HEARTBEAT_DEAD_MS = 30_000L

    /** terminal 互顶循环判定窗口与阈值：多页面互顶时 H5 零退避重连（KICKED 帧竞速
     *  失败送不到），pair 翻转每秒 2-4 次持续不断；正常使用 10s 内 1-2 次 */
    const val TERMINAL_CHURN_WINDOW_MS = 10_000L
    const val TERMINAL_CHURN_FLIPS = 8
}

/** 设备配对凭据（deviceSid 由 relay 注册后分配；持久化责任在宿主，生产走 IDE PasswordSafe） */
data class RelayCredentials(
    val deviceMid: String,
    val deviceSid: String?,
    val passHash: String,
)

/** relay 连接运行状态（对齐官方设备侧状态机的可观察子集） */
enum class RelayState {
    IDLE, CONNECTING, REGISTERING, AUTHENTICATING, WAITING_TERMINAL, PAIRED, KICKED, CLOSED
}

/** 一条手机端顶层 payload（L4 控制面或 L5 rpc-frame 原始片） */
data class RelayPayload(val zcodeType: String, val raw: JsonObject)
