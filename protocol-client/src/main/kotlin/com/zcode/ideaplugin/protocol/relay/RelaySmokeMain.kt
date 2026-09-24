package com.zcode.ideaplugin.protocol.relay

import com.zcode.ideaplugin.protocol.relay.ChannelCodec.ChValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path

/**
 * relay 冒烟入口（真实云端 + 真实 H5，M3 半语义验证）：
 *
 *   ./gradlew :protocol-client:run -PmainClass=com.zcode.ideaplugin.protocol.relay.RelaySmokeMainKt \
 *     --args="120 [凭据json路径]"
 *
 * 应答面（模拟 IDE 宿主 RemoteChannelHandlers 的纯协议分支，验证 H5 接受度）：
 * - L4：bootstrap/bridge-open（RelayBridge）
 * - 首屏 stub：setting/oauth/model-provider/broadcast/system
 * - zcode-agent.helloConversationV4 / initializeConversationV4 / subscribe*V4（假 ack）
 * - window-controller.subscribeControllerV4：回 ack + 立即推 controller snapshot
 * - zcode-task.prepareWorkspace/listTaskList（静态空）
 * - 其余 Method not found（H5 容错）
 *
 * 协议细节调试仍优先用 scripts/probe-relay-device.py（帧日志更全）。
 */
fun main(args: Array<String>) {
    val durationSec = args.getOrNull(0)?.toLongOrNull() ?: 60
    val credPath = args.getOrNull(1)?.let { Path.of(it) }
        ?: sequenceOf(
            Path.of("docs/internal/probe/relay-credentials.local.json"),
            Path.of("../docs/internal/probe/relay-credentials.local.json"),
        ).firstOrNull { java.nio.file.Files.exists(it) }
        ?: throw IllegalArgumentException("未找到凭据文件，用法：main <durationSec> <credentialsJson>")

    val json = Json { ignoreUnknownKeys = true }
    val stored = json.parseToJsonElement(java.nio.file.Files.readString(credPath)).jsonObject
    fun str(key: String): String? = (stored[key] as? JsonPrimitive)?.content

    var credentials = RelayCredentials(
        deviceMid = str("deviceMid") ?: "kotlin-smoke-${System.currentTimeMillis()}",
        deviceSid = str("deviceSid"),
        passHash = str("passHash") ?: RelayCrypto.createPassHash(RelayCrypto.createPassword()),
    )
    val mockWorkspacePath = (args.getOrNull(2) ?: "G:\\smoke\\workspace").replace('\\', '/')
    println("[smoke] 凭据: mid=${credentials.deviceMid} sid=${credentials.deviceSid ?: "(register 分配)"}")

    val bridge = RelayBridge(
        workspacesProvider = {
            listOf(
                RelayBridge.RelayWorkspace(path = mockWorkspacePath, label = "Smoke-Workspace"),
                RelayBridge.RelayWorkspace(path = "G:/other/project", label = "Another-Project"),
            )
        },
        // 首页任务行（官方 HAR 定案形状）；taskId 与 controller/tasks-index 假任务同源
        tasksProvider = {
            listOf(
                buildJsonObject {
                    put("archived", false)
                    put("createdAt", System.currentTimeMillis() - 3600_000)
                    put("displayStatus", "completed")
                    put("provider", "glm")
                    put("taskId", "sess_smoke_task_1")
                    put("title", "Smoke 测试会话")
                    put("updatedAt", System.currentTimeMillis())
                    put("workspaceKind", "local")
                    put("workspaceLabel", "Smoke-Workspace")
                    put("workspacePath", mockWorkspacePath)
                },
            )
        },
    )

    val payloadTypes = HashMap<String, Int>()
    val channelCalls = HashMap<String, Int>()

    val client = RelayClient(
        config = RelayClient.RelayConfig(deviceName = "ZCode-Host-Smoke"),
        credentials = credentials,
    )
    client.onDeviceRegistered = { credentials = it }
    client.onStateChange = { state ->
        if (state == RelayState.WAITING_TERMINAL && credentials.deviceSid != null) {
            println("\n[smoke] QR URL（手机/浏览器扫码配对；含敏感 passHash 勿外传）：\n" +
                RelayCrypto.buildQrUrl(credentials, deviceName = "ZCode-Host-Smoke") + "\n")
        }
        if (state == RelayState.PAIRED) println("[smoke] 🎉 手机已配对")
    }
    client.onRelayError = { code, msg -> println("[smoke] relay error: code=$code msg=$msg") }
    client.onPayload = { payload: JsonObject, sender ->
        val key = (payload["zcode_type"] as? JsonPrimitive)?.content ?: "?"
        if (key != Relay.PAYLOAD_RPC_FRAME && key != Relay.PAYLOAD_RPC_FRAME_ACK) {
            synchronized(payloadTypes) { payloadTypes[key] = (payloadTypes[key] ?: 0) + 1 }
            println("[smoke] 📱 payload $key: ${payload.toString().take(240)}")
        }
        if (bridge.handlePayload(payload, sender)) {
            client.sendChannelMessage(
                payload["bridgeSessionId"]?.jsonPrimitive?.content!!,
                ChannelCodec.encodeInitialize(),
            )
            println("[smoke] bridge ready + Initialize")
        }
    }
    client.onChannelRequest = { bridgeId, request, responder ->
        synchronized(channelCalls) {
            val key = "${request.channel}.${request.method}"
            channelCalls[key] = (channelCalls[key] ?: 0) + 1
        }
        println("[smoke] 📞 ${request.channel}.${request.method} id=${request.id} args=${request.args.joinToString { argToString(it) }.take(180)}")
        handleChannel(bridgeId, request, responder, mockWorkspacePath, client, bridge)
    }
    client.onChannelEventListen = { bridgeId, listenerId, channel, event, _ ->
        bridge.handleEventListen(bridgeId, listenerId, channel, event)
        println("[smoke] 👂 #$listenerId $channel.$event")
    }
    client.onChannelEventDispose = { listenerId -> bridge.handleEventDispose(listenerId) }

    client.connect()
    client.startHeartbeat()
    println("[smoke] 运行 ${durationSec}s …")
    Thread.sleep(durationSec * 1000)
    println("[smoke] 结束: state=${client.currentState}")
    synchronized(payloadTypes) { println("[smoke] payload 统计: $payloadTypes") }
    synchronized(channelCalls) { println("[smoke] channel 调用统计: $channelCalls") }
    client.close()
}

private fun handleChannel(
    bridgeId: String,
    request: ChannelCodec.ChannelRequest,
    responder: RelayClient.ChannelResponder,
    workspacePath: String,
    client: RelayClient,
    bridge: RelayBridge,
) {
    val method = request.method ?: return responder.error("method missing")
    val args = (request.args.firstOrNull() as? ChValue.Obj)?.json as? JsonObject ?: JsonObject(emptyMap())
    val obj: kotlinx.serialization.json.JsonElement? = when (request.channel) {
        "setting" -> when (method) {
            // 同源 ~/.zcode/v2/setting.json（模型选择状态 familyModes/selectedKeys 真身所在）
            "get" -> {
                val src = sharedSettingJson()
                buildJsonObject {
                    src?.forEach { (k, v) -> put(k, v) }
                    if (src == null || !src.containsKey("recentProjects")) put("recentProjects", kotlinx.serialization.json.JsonArray(emptyList()))
                    if (src == null || !src.containsKey("locale")) put("locale", "zh-CN")
                }
            }
            "update", "updateDataBaseDir", "ensureDefaultProject" -> JsonObject(emptyMap())
            else -> null
        }
        "oauth" -> when (method) {
            // 实验定案中：familyModes bigmodel='oauth' 时 H5 过滤链要求 oauth 会话
            // authenticated（signed-out=oauth 系 provider 全过滤、模型选择器不渲染，
            // 2026-08-25 smoke 三轮实测推定）。userInfo 造中性值对齐官方形状
            "restoreCachedSessionState" -> buildJsonObject {
                put("status", "authenticated")
                put("userInfo", buildJsonObject {
                    put("id", "zcode-idea-host")
                    put("username", "IDE")
                    put("displayName", "IDE")
                    put("avatarUrl", "")
                })
            }
            "restoreCachedSession" -> Json.parseToJsonElement("null")
            // 官方回 family 名（源=setting.json providerFamilyDomain，HAR 实测 'bigmodel'）
            "getActiveProvider" -> {
                val family = sharedSettingJson()?.get("providerFamilyDomain")?.jsonPrimitive?.contentOrNull
                family?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: Json.parseToJsonElement("null")
            }
            else -> null
        }
        "model-provider" -> when (method) {
            // getAllCached 回退数组（与 getAll 同形）：官方 {providerIds,updatedAt} 形状
            // 实测让 H5 首渲染把对象灌进 provider store → Xut e.find 崩（第十~十三轮
            // IAB 实测）；数组形态 H5 容错（第九轮不崩实证）
            "getAll", "getAllCached" -> kotlinx.serialization.json.JsonArray(smokeProviders())
            "getDisplayOrder" -> JsonObject(emptyMap())
            // H5 发送门禁（resolveZCodeAgentStartupReadiness）：providers 非空且含
            // baseURL+凭证+可用 model 的条目才 ready；空 providers=「当前没有可用模型」
            // 弹窗+发送被拒（2026-08-25 装机 HAR 定案）。由 getAll 形状派生 registry 形状
            "getProviderRegistrySnapshot" -> buildJsonObject {
                val providers = smokeProviders().mapNotNull { p ->
                    val pv = p as? JsonObject ?: return@mapNotNull null
                    val baseURL = pv["endpoints"]?.jsonObject?.get("baseURL")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    buildJsonObject {
                        put("providerId", pv["id"]?.jsonPrimitive?.contentOrNull ?: "")
                        pv["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { put("label", it) }
                        put("apiFormat", "anthropic-messages")
                        put("apiKeyRequired", true)
                        put("baseURL", baseURL)
                        put("kind", "anthropic")
                        put("apiKey", buildJsonObject { put("source", "credential"); put("key", "config.json") })
                        put("source", "custom")
                        put("models", pv["models"]?.let { ms ->
                            kotlinx.serialization.json.JsonArray(ms.jsonArray.mapNotNull { m ->
                                val mv = m as? JsonObject ?: return@mapNotNull null
                                buildJsonObject {
                                    put("modelId", mv["id"]?.jsonPrimitive?.contentOrNull ?: return@buildJsonObject)
                                    mv["name"]?.jsonPrimitive?.contentOrNull?.let { put("label", it) }
                                }
                            })
                        } ?: kotlinx.serialization.json.JsonArray(emptyList()))
                    }
                }
                put("generatedAt", System.currentTimeMillis())
                put("revision", "sha256-${providers.size}-${System.currentTimeMillis() / 600_000L}")
                put("providers", kotlinx.serialization.json.JsonArray(providers))
            }
            // 官方对已配 key 的 provider 回 null（HAR id=28/29/45 实测）
            "refreshCodingPlanApiKey" -> Json.parseToJsonElement("null")
            else -> null
        }
        "usage-stats" -> when (method) {
            // coding-plan 配额重置面板空态（官方真实实现调 BigModel HTTP /status）
            "getCodingPlanResetStatus" -> buildJsonObject {
                put("availableFiveHourResets", kotlinx.serialization.json.JsonArray(emptyList()))
                put("availableWeekResets", kotlinx.serialization.json.JsonArray(emptyList()))
                put("latestFiveHourResetHistory", null as String?)
                put("latestWeekResetHistory", null as String?)
            }
            // coding-plan 有订阅模板（HAR id=26）：remaining.isShow=false 避开假额度数字；
            // start-plan 等无套餐 provider 回 no_plan 形状（HAR id=37）
            "getEntitlementSnapshot" -> {
                val preferred = args["preferredProviderId"]?.jsonPrimitive?.contentOrNull
                if (preferred == "builtin:bigmodel-coding-plan") {
                    buildJsonObject {
                        put("generatedAt", System.currentTimeMillis())
                        put("authenticated", true)
                        put("context", buildJsonObject {
                            put("scope", "personal"); put("productId", "product-d46f8b"); put("displayName", "GLM Coding Max")
                        })
                        put("provider", buildJsonObject {
                            put("id", "builtin:bigmodel-coding-plan"); put("name", "BigModel - Coding Plan")
                        })
                        put("remaining", buildJsonObject {
                            put("count", 5000); put("isShow", false); put("percentage", 5)
                            put("nextResetTime", System.currentTimeMillis() + 86400_000L)
                        })
                        put("subscription", buildJsonObject {
                            put("identityType", "unknown"); put("identityMasked", null as String?)
                            // details 空=H5 判「无有效订阅」→自动弹 CodingPlanUpgradeDialog
                            // →对话框内 e.find 崩（第十一轮 IAB 实测）；照官方模板放一条
                            put("details", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
                                put("productId", "product-d46f8b")
                                put("productName", "GLM Coding Max")
                                put("purchaseTime", null as String?); put("beginTime", null as String?)
                                put("billingCycle", "annually")
                                put("renewTime", java.time.Instant.ofEpochMilli(System.currentTimeMillis() + 31536000_000L).toString())
                                put("expireTime", java.time.Instant.ofEpochMilli(System.currentTimeMillis() + 63072000_000L).toString())
                            })))
                        })
                        put("quota", buildJsonObject {
                            put("level", "max"); put("limits", kotlinx.serialization.json.JsonArray(emptyList()))
                        })
                    }
                } else {
                    buildJsonObject {
                        put("generatedAt", System.currentTimeMillis())
                        put("authenticated", true)
                        put("unavailableReason", "no_plan")
                        put("context", buildJsonObject { put("scope", "personal") })
                        put("provider", buildJsonObject {
                            put("id", preferred ?: ""); put("name", preferred ?: "")
                        })
                        put("remaining", null as String?)
                    }
                }
            }
            else -> null
        }
        "coding-plan-subscription" -> when (method) {
            "getOffPeakClientConfig" -> JsonObject(emptyMap())
            "getBillingDiscount" -> JsonObject(emptyMap())
            "getEnterprisePricing" -> buildJsonObject { put("productList", kotlinx.serialization.json.JsonArray(emptyList())) }
            else -> null
        }
        "off-peak-task" -> when (method) {
            "getCodingPlanSupport" -> JsonObject(emptyMap())
            else -> null
        }
        "broadcast" -> when (method) {
            "send", "releaseClaim" -> JsonObject(emptyMap())
            else -> null
        }
        "system" -> when (method) {
            "info" -> buildJsonObject {
                put("platform", "win32"); put("arch", "x64"); put("hostname", "smoke")
                put("release", "10.0.22631"); put("cwd", workspacePath)
            }
            else -> null
        }
        "zcode-agent" -> when (method) {
            "helloConversationV4" -> buildJsonObject {
                put("kind", "hello"); put("protocolVersion", 3)
                put("connectionId", "smoke-conn-1")
                put("clientMode", "web-remote-replayable")
                put("deliveryProfile", "replayable")
                put("serverTime", System.currentTimeMillis())
                put("capabilities", buildJsonObject {
                    put("nativeDialogs", false); put("localTerminal", false)
                    put("binaryFrames", false); put("compression", "none")
                    put("workspaceHookReview", false)
                })
                put("auth", buildJsonObject {})
            }
            "initializeConversationV4" -> JsonObject(emptyMap())
            "subscribeConversationV4", "subscribeSessionsIndexV4" -> {
                val sessionId = args["sessionId"]?.jsonPrimitive?.content
                val topic = if (sessionId != null) "conversation/$sessionId" else "sessions-index/$workspacePath"
                val subId = "smoke-sub-" + java.util.UUID.randomUUID().toString().take(8)
                println("[smoke] 订阅 $topic（假 ack + sessions-index 推 initial 快照帧）")
                // sessions-index：立即推 v4 initial snapshot 帧（形状对齐 app-server
                // 实测 diag-v4-sessions-index-dump：frame{topic,subscriptionId,fromSeq,
                // toSeq,sentAt,payload{kind:snapshot,snapshot{protocolVersion,workspaceId,
                // logEpoch,sessions[]}}}；conversation 帧结构不同，此处不推
                if (sessionId == null) {
                    val session = buildJsonObject {
                        put("sessionId", "sess_smoke_task_1")
                        put("workspaceId", workspacePath)
                        put("title", "Smoke 测试会话")
                        put("titleSource", "generated")
                        put("phase", "completedSuccess")
                        put("sessionEnded", true)
                        put("hasBackgroundWork", false)
                        put("lastActivityAt", System.currentTimeMillis())
                        put("createdAt", System.currentTimeMillis() - 3600_000)
                    }
                    val frame = buildJsonObject {
                        put("topic", topic)
                        put("subscriptionId", subId)
                        put("fromSeq", 0); put("toSeq", 0)
                        put("sentAt", System.currentTimeMillis())
                        put("payload", buildJsonObject {
                            put("kind", "snapshot")
                            put("snapshot", buildJsonObject {
                                put("protocolVersion", 1)
                                put("workspaceId", workspacePath)
                                put("logEpoch", "smoke-epoch")
                                put("sessions", kotlinx.serialization.json.JsonArray(listOf(session)))
                            })
                        })
                    }
                    for ((listenerId, sub) in bridge.subscriptionsFor(bridgeId)) {
                        if (sub.channel == "zcode-agent" && sub.event == "onDynamicSessionsIndexFrame") {
                            client.sendChannelEvent(bridgeId, listenerId, ChValue.Obj(frame))
                            println("[smoke] → sessions-index snapshot 已推 #$listenerId")
                        }
                    }
                }
                buildJsonObject {
                    put("ack", buildJsonObject {
                        put("subscriptionId", subId)
                        put("mode", "snapshot")
                        put("logEpoch", "smoke-epoch")
                    })
                }
            }
            "unsubscribeConversationV4" -> JsonObject(emptyMap())
            // H5 索引恢复重同步：ack.subscriptionId 回请求值（不等即 resync-ack-mismatch）
            "resyncSessionsIndexV4" -> buildJsonObject {
                put("ack", buildJsonObject {
                    put("subscriptionId", args["subscriptionId"]?.jsonPrimitive?.contentOrNull ?: "")
                    put("mode", "snapshot")
                })
            }
            "initialize" -> buildJsonObject { put("available", true); put("workspaceKey", workspacePath) }
            "readWorkspaceState" -> buildJsonObject {
                put("settings", buildJsonObject {})
                put("slashCommands", kotlinx.serialization.json.JsonArray(emptyList()))
            }
            "disposeAll" -> JsonObject(emptyMap())
            else -> null
        }
        "zcode-task" -> when (method) {
            "prepareWorkspace" -> buildJsonObject {
                put("workspacePath", workspacePath)
                put("preparedSessionId", "")
                put("version", "ZCode Protocol/1")
                put("provider", "glm")
                put("configOptions", kotlinx.serialization.json.JsonArray(emptyList()))
                put("slashCommands", kotlinx.serialization.json.JsonArray(emptyList()))
            }
            // H5 lGt 三源期望裸数组（t3 items=service 返回值再 .flat()）；
            // {items,total,hasMore} 是 listTaskList 专属分页形状
            "listTasks" -> kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
                put("taskId", "sess_smoke_task_1")
                put("title", "Smoke 测试会话")
                put("status", "completed")
                put("workspacePath", workspacePath)
                put("workspaceKind", "local")
                put("createdAt", System.currentTimeMillis() - 3600_000)
                put("updatedAt", System.currentTimeMillis())
            }))
            "listTaskList" -> buildJsonObject {
                // 一条静态假任务：驱动 H5 走进会话界面（输入框/模型选择器），
                // 复现模型区域崩溃用
                put("items", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
                    put("taskId", "sess_smoke_task_1")
                    put("title", "Smoke 测试会话")
                    put("status", "completed")
                    put("workspacePath", workspacePath)
                    put("workspaceKind", "local")
                    put("createdAt", System.currentTimeMillis() - 3600_000)
                    put("updatedAt", System.currentTimeMillis())
                })))
                put("total", 1); put("hasMore", false)
            }
            // H5 任务列表三源 merge（lGt）：listTasks/listArchivedTasks/listPinnedTasks
            // 任一 reject 即 throw"tasks-index task 行读取不完整"→ 区域错误边界
            "listArchivedTasks", "listPinnedTasks" -> kotlinx.serialization.json.JsonArray(emptyList())
            // 可选源（失败容错）：补空应答消除噪声
            "listPinnedTaskIds", "listDeletedTaskIds" -> kotlinx.serialization.json.JsonArray(emptyList())
            "checkCodexConnectivity" -> buildJsonObject { put("target", "zcode-agent"); put("reachable", true) }
            // 会话文件路径类查询（官方形状，exists=false 走 H5 容错分支）
            "getTaskSessionFilePath" -> buildJsonObject {
                val tid = args["taskId"]?.jsonPrimitive?.contentOrNull ?: ""
                put("path", "$workspacePath/$tid.zcode-session"); put("exists", false)
            }
            "getTaskNativeSessionLogFile" -> buildJsonObject {
                put("provider", "glm"); put("path", ""); put("exists", false)
            }
            else -> null
        }
        "window-controller" -> when (method) {
            "subscribeControllerV4" -> {
                val topic = args["topic"]?.jsonPrimitive?.content ?: "controller/workspaces"
                val subId = "smoke-wc-" + java.util.UUID.randomUUID().toString().take(8)
                val now = System.currentTimeMillis()
                // 立即推 controller 快照。task 行按官方宿主 HAR 抓包权威形状
                // （address/meta/membership/sourceAvailability/liveStatus/activity，
                // 旧扁平形状被 H5 zod strict 丢弃=任务列表恒空，2026-08-25 定案）
                val task = buildJsonObject {
                    put("address", buildJsonObject {
                        put("workspacePath", workspacePath)
                        put("taskId", "sess_smoke_task_1")
                    })
                    put("meta", buildJsonObject {
                        put("taskId", "sess_smoke_task_1")
                        put("traceId", "smoke-trace-1")
                        put("title", "Smoke 测试会话")
                        put("titleOverridden", false)
                        put("workspacePath", workspacePath)
                        put("createdAt", now - 3600_000)
                        put("updatedAt", now)
                        put("mode", "build")
                        put("model", "builtin:bigmodel-coding-plan/GLM-5.3")
                        put("thoughtLevel", "max")
                        put("provider", "glm")
                        put("status", "completed")
                        put("target", null as String?)
                    })
                    put("membership", buildJsonObject {
                        put("pinned", false); put("archived", false); put("active", false)
                    })
                    put("sourceAvailability", "online")
                    put("liveStatus", "completed")
                    put("activity", buildJsonObject {
                        put("phase", "completedSuccess")
                        put("lastActivityAt", now)
                        put("hasBackgroundWork", false)
                    })
                }
                // workspaces topic 推 workspaces 快照、tasks-index topic 推 tasks 快照
                // （官方两个 topic 的 snapshot 内容不同，HAR 实测）
                val snapshotContent = buildJsonObject {
                    put("protocolVersion", 3)
                    put("logEpoch", "smoke-epoch")
                    if (topic.endsWith("tasks-index")) {
                        put("tasks", kotlinx.serialization.json.JsonArray(listOf(task)))
                    } else {
                        put("workspaces", kotlinx.serialization.json.JsonArray(listOf(
                            buildJsonObject {
                                put("workspacePath", workspacePath)
                                put("sourceAvailability", "online")
                                put("connectionState", "online")
                            }
                        )))
                    }
                }
                val frame = buildJsonObject {
                    put("subscriptionId", subId)
                    put("topic", topic)
                    put("logEpoch", "smoke-epoch")
                    put("fromSeq", 1); put("toSeq", 1)
                    put("payload", buildJsonObject {
                        put("kind", "snapshot")
                        put("snapshot", snapshotContent)
                    })
                }
                for ((listenerId, sub) in bridge.subscriptionsFor(bridgeId)) {
                    if (sub.channel == "window-controller" && sub.event == "onDynamicControllerFrame") {
                        client.sendChannelEvent(bridgeId, listenerId, ChValue.Obj(frame))
                        println("[smoke] → controller snapshot 已推 #$listenerId ($topic)")
                    }
                }
                buildJsonObject {
                    put("ack", buildJsonObject {
                        put("subscriptionId", subId)
                        put("mode", "snapshot"); put("logEpoch", "smoke-epoch")
                    })
                }
            }
            else -> null
        }
        "settings-sync" -> when (method) {
            "getFirstRunPromptState" -> buildJsonObject { put("handled", true) }
            "markFirstRunPromptHandled" -> JsonObject(emptyMap())
            else -> null
        }
        "zcode-session" -> when (method) {
            "initializeWorkspace" -> buildJsonObject { put("available", true); put("workspaceKey", workspacePath) }
            else -> null
        }
        else -> null
    }
    if (obj != null) responder.success(ChValue.Obj(obj))
    else responder.error("Method not found: ${request.channel}.$method")
}

/** config.json provider 聚合（与 IDE 宿主 modelProviderList 同形：apiKey 必须 string，
 *  H5 coding-plan 链裸调 .trim()，缺失即整页崩——2026-08-24 浏览器实测） */
/** 同源 ~/.zcode/v2/setting.json（官方桌面端/CLI 共享设置真身，与宿主实现一致） */
private fun sharedSettingJson(): JsonObject? = runCatching {
    val f = Path.of(System.getProperty("user.home"), ".zcode", "v2", "setting.json").toFile()
    if (f.exists()) Json.parseToJsonElement(f.readText()).jsonObject else null
}.getOrNull()

private fun smokeProviders(): List<kotlinx.serialization.json.JsonElement> = runCatching {
    val cfg = Path.of(System.getProperty("user.home"), ".zcode", "v2", "config.json").toFile()
    if (!cfg.exists()) return emptyList()
    val providers = Json.parseToJsonElement(cfg.readText()).jsonObject["provider"]?.jsonObject ?: return emptyList()
    val now = System.currentTimeMillis()
    providers.mapNotNull { (pid, pel) ->
        val pv = pel.jsonObject
        val enabled = pv["enabled"]?.jsonPrimitive?.content?.toBoolean() ?: true
        val opts = pv["options"]?.jsonObject
        val apiKey = opts?.get("apiKey")?.jsonPrimitive?.contentOrNull ?: ""
        val baseURL = opts?.get("baseURL")?.jsonPrimitive?.contentOrNull ?: ""
        val models = pv["models"]?.jsonObject?.mapNotNull { (mid, mel) ->
            // 与宿主 modelProviderList 同形（官方宿主 HAR 抓包定案，2026-08-25）：
            // id/kinds/defaultKind/modalities/contextWindow 必填；name 仅显式配置才放；
            // 无 modelId/authenticated/endpoints.anthropic（多余键有 zod strict 丢弃风险）
            buildJsonObject {
                put("id", mid)
                mel.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.let { put("name", it) }
                put("kinds", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("anthropic"))))
                put("defaultKind", "anthropic")
                put("modalities", buildJsonObject {
                    put("input", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("text"))))
                    put("output", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("text"))))
                })
                put("contextWindow", mel.jsonObject["limit"]?.jsonObject?.get("context")?.jsonPrimitive?.content?.toLongOrNull() ?: 200000L)
                put("modified", false)
            }
        } ?: emptyList()
        buildJsonObject {
            put("id", pid)
            put("name", pv["name"]?.jsonPrimitive?.content ?: pid)
            put("enabled", enabled)
            put("endpoints", buildJsonObject {
                put("baseURL", baseURL)
                put("paths", buildJsonObject { put("anthropic", "/v1/messages") })
            })
            put("apiFormat", "anthropic-messages")
            put("source", "custom")
            put("apiKey", apiKey)
            put("defaultKind", "anthropic")
            put("models", kotlinx.serialization.json.JsonArray(models))
            put("createdAt", now); put("updatedAt", now)
        }
    }
}.getOrDefault(emptyList())

private fun argToString(value: ChValue): String = when (value) {
    is ChValue.Obj -> value.json.toString()
    is ChValue.Str -> "\"${value.value}\""
    is ChValue.IntVal -> value.value.toString()
    is ChValue.Arr -> "[${value.items.joinToString { argToString(it) }}]"
    is ChValue.Buf -> "<${value.bytes.size}B>"
    ChValue.Undefined -> "undefined"
}
