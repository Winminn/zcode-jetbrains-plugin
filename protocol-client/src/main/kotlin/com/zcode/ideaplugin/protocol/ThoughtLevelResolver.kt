package com.zcode.ideaplugin.protocol

/**
 * send/setModel 的 modelSelection 档位解析（缺陷CX，issue#25 纯函数便于单测）。
 *
 * 背景：NEW 代 send 每条消息携带 modelSelection.options.reasoningLevel。此前恒填目录
 * 默认档（对 GLM 恒 max），服务端回合装配会把它写回会话并持久化——用户设置的思考档
 * 每回合被冲回最高，回合结束 webview 兜底重拉设置后 UI 显示「最高」。修复=改带
 * 「最后已知会话档位」，此处只做取值与合法性裁决。
 *
 * 裁决规则：
 * - 无请求档（会话从未设置/进程重启缓存为空）→ 目录默认档兜底（v2 必填，缺失 turn 在
 *   model_creation 静默 failed；与旧行为一致）
 * - 值集查询失败（目录缺失，fail-soft null）→ 请求档原样交服务端裁决
 * - 请求档在目标模型值集内（精确匹配，档位值全小写同源）→ 用请求档
 * - 请求档不在值集（跨模型窗口：切模型落定前 send 带出旧模型档位）→ 目录默认档
 *   （服务端对不支持的档位强校验，回合即失败；默认档必合法）
 */
internal fun resolveSelectionReasoningLevel(
    requested: String?,
    values: List<String>?,
    defaultLevel: String?,
): String? {
    val req = requested?.takeIf { it.isNotBlank() } ?: return defaultLevel
    if (values == null) return req
    return if (values.contains(req)) req else defaultLevel
}
