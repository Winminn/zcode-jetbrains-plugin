# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

最新版本块会被 `patchPluginXml` 提取为插件 change-notes（展示在 Marketplace 与 IDE 插件详情页），保持格式：`## [版本] - 日期` + `- ` 列表。

## [0.2.1] - 2026-08-19

### Fixed

- **CLI 升级/重启后恢复会话无限转圈自动收尾**：ZCode 桌面端自动更新会杀掉插件依赖的 app-server 进程，恢复后的回合在服务端真实执行但事件流零下发，界面只认终止帧导致无限转圈——新增流式静默对账看门狗（60 秒无事件即静默探测服务端快照），回合已完成自动落地收尾、流丢失自动收尾并提示重发
- **冷会话发送失败自动恢复**：send 撞 `-32004 Session is not active`（升级/重启后新进程未激活会话）时自动 resume 后重试一次，不再需要手动从历史记录重开；该错误同时追加中文提示与操作引导（五语言）
- 移除已过时的「发布到 JetBrains Marketplace」README 章节与打 tag 自动发布工作流（改为手动发布）

## [0.2.0] - 2026-08-18

首个稳定发布版本。把 [ZCode](https://zcode.z.ai/cn) 编码助手带进 JetBrains IDE：不切终端、不离开编辑器，会话、对话、模型与任务管理都在一个工具窗口里完成，AI 的 browser-use 还能直接驱动插件内嵌浏览器干活。

> 社区第三方插件，与 ZCode / Z.ai 官方无关。使用前需本机安装 ZCode CLI 并完成登录。

### 对话

- 流式输出：思考过程 / 正文 / 工具调用实时渲染，Markdown / Mermaid / 代码高亮
- 思考耗时统计、消息排队（生成中回车自动排队，排队卡片可立即发送 / 删除）
- Ctrl+F 会话内搜索（大小写 / 整词 / 正则）、消息锚点导航（用户消息圆点定位 + hover 预览）

### 多任务

- 多标签页并行会话（每标签独立上下文互不串扰），重启 IDE 自动恢复
- 会话列表 / 重命名 / 搜索 / 批量多选删除

### 过程可视

- 任务清单（TodoWrite）实时进度
- 子代理（Agent）面板与执行过程 / 最终报告弹窗
- 文件改动统计（点击在编辑器打开、行内 diff 前后对比）
- AskUserQuestion 交互弹窗、计划模式（ExitPlanMode）审批弹窗

### 内嵌浏览器 · browser-use 宿主

- Header 一键在聊天区右侧展开浏览器分栏：多 tab（全局共享、跨会话沿用）、后退 / 前进 / 刷新 / 地址栏 / 自由尺寸 / DevTools / 外部打开
- 插件作为宿主实现 ZCode app-server 的 browser-use 反向协议（`interaction/browserList` / `browserExecute`），AI 的浏览器工具零配置落到内嵌 JCEF 浏览器执行
- 导航与采集：newTab / navigate / screenshot / evaluate，截图直接回传模型
- playwright 定位器透传：getByRole / getByText / label / testid / and / or / nth / css 链等选择器引擎，ARIA 树 DOM 快照供 AI 读取
- CUA 鼠标键盘：坐标点击 / 输入 / 拖拽 / 滚动 / 组合按键，JS 对话框自动挂起处理
- tab 生命周期：markDeliverable / markHandoff / finalize 标记与回读，tab.close 真关闭
- 自由尺寸：DevTools 设备工具栏形态——虚拟屏居中信箱、缩放档、尺寸持久化
- playwright 能力不可用时优雅降级（title / get_visible_dom / screenshot 组合），链路始终可用

### 运行时控制

- 模型下拉切换、权限模式（build / edit / plan / yolo）与思考级别（随模型动态）调整
- 待命态（未建会话）可预选模式与思考级别，建会话即生效
- 上下文容量圆环（含用量构成与缓存命中）、5 小时 / 每周额度查询

### 设置中心

- 七页签：基础（主题 / 字体 / 语言 / 自定义配色 + 环境路径）、模型（provider 分组只读清单，路径跟随数据目录迁移）、用量（额度卡片 + 模型 / 工具用量曲线与明细表）、记忆（AGENTS.md 指令记忆 + 自动记忆）、技能（全局 / 项目 / 插件三来源扫描，行内启用禁用）、MCP（服务器清单 / 工具列表 / 连接日志）、其他（输入历史补全开关与历史记录管理）

### 环境检测

- 启动自检 Node.js（≥18）/ ZCode CLI / 登录凭证三件套，异常时顶栏提醒条逐项给出修复入口与重新检测
- 路径可手动配置，留空自动探测；Windows 下 CLI 自动探测覆盖单用户安装（`%LOCALAPPDATA%\Programs\ZCode`）与全局安装（`%ProgramFiles%\ZCode`、`%ProgramFiles(x86)%\ZCode`）三类位置

### IDE 集成

- 项目视图 / 编辑器标签右键发送文件、编辑器右键发送选中代码到输入框（Ctrl+Alt+K）、复制选区引用（路径 + 行号）
- 文件、记忆、技能、MCP 配置均可一键在编辑器打开

### 输入增强

- `@` 引用文件（chip + 补全，粘贴绝对路径自动转 chip）、`/` 调用技能、长文本粘贴折叠
- 输入历史回溯与前缀幽灵补全（Tab 采纳）；单条历史长度上限 2000 字符，避免超长内容撑爆存储

### 多语言

- 简体中文 / English / 日本語 / 한국어 / 繁體中文，跟随 IDE 界面语言自动切换

### 兼容性

- IntelliJ Platform 2024.1 ~ 2026.3（sinceBuild 241 / untilBuild 263.*），JDK 17
- 2026.2 起 JCEF API 剥离为独立捆绑插件，已声明可选依赖 `com.intellij.modules.jcef` 兼容

## [0.1.4] - 2026-08-18

### Fixed

- 修复未安装 ZCode CLI 时 ToolWindow 创建失败导致 IDE 主界面不渲染：`initJcef` 里 `ensureUserInputHandler`/`ensureBrowserExecutor` 调用捕获 `EnvCheckException`，CLI 不可用时 webview 正常加载并由前端显示环境提醒

## [0.1.3] - 2026-08-18

### Fixed

- 修复 0.1.2 的 JCEF 依赖回归：`com.intellij.modules.jcef` 改为可选依赖（`optional="true"`），2026.2+ 上要求该插件存在，2026.1 及以下（JCEF 仍在平台核心）跳过不影响加载

## [0.1.2] - 2026-08-18

### Fixed

- 修复 2026.2 上 `NoClassDefFoundError: JBCefJSQuery$Response`：2026.2 起 JCEF API 从平台核心剥离为独立捆绑插件，`plugin.xml` 新增 `<depends>com.intellij.modules.jcef</depends>`

## [0.1.1] - 2026-08-18

### Fixed

- 消除 Plugin Verifier 报告的全部 deprecated API 警告：`JBCefJSQuery.create(JBCefBrowser)`（scheduled for removal）改用 `create(JBCefBrowserBase)` 重载；`JBUI.scale(float)`（6 处）改用 `JBUIScale.scale(float)`

### Changed

- 兼容上限从 2026.1（261.*）扩到 2026.3（263.*）

## [0.1.0] - 2026-08-18

### Added

- 三模块项目骨架：protocol-client（纯 Kotlin JSON-RPC 协议客户端，可独立测试）/ intellij-plugin / webview
- ToolWindow 会话管理：会话列表、历史浏览、对话与流式输出
- 富消息渲染：子代理、记忆、MCP 工具、技能管理、多标签体验
- 内置 JCEF 浏览器与 browser-use 集成（AI 浏览器工具落到内嵌浏览器执行）
- 设置页基础设置页签，配置持久化迁移至 IDE 侧
- 运行环境依赖检测与配置、计划审批交互重构
- 待命态预选模式与思考级别选择
- 输入框内置命令提示、代码块背景与命令 chip 图标
- 模型管理页签：供应商启用/禁用切换、套餐徽章、按项目过滤加载会话列表
- 全插件国际化（中/英/日/韩 UI 文案）
- 编辑器右键动作：发送文件/选中代码到输入框、复制选区引用（Ctrl+Alt+K）

### Fixed

- 模型 API 错误无提示、429 配额超限无限转圈
- 输入历史冷启动水合失败（kvLoad 拉取兜底 + 水合事件解锁缓存）
- 环境配置回显断流、凭证数据目录跟随等多项修复
- 插件重装后 K/V 存储清空问题

### Changed

- README 中英双语化并补充界面预览截图
