# ZC GUI 插件图标

当前正式图标：`zcgui-window-soft.svg`——深空渐变底 + 白色斜杠像素图案（自研品牌标识，非 Z.ai 官方标识，无侵权风险）。

## 设计概念

| 元素 | 含义 | 实现细节 |
|---|---|---|
| 渐变底圆角方块 | 应用窗口底板 | 对角渐变 `#1E293B → #312E81`（深空蓝→靛），明暗主题通用 |
| 像素斜杠 | ZC GUI → 斜杠意象，数字化 | 18 列 × 13 行网格（30×42 长方格，块 26×36）；斜杠 6 块宽**每行左移 1 格**，左右边缘与上下斜缝严格平行直线；视觉斜率 30/42 ≈ 0.71；白色按行渐隐（顶行 100% → 底行 50% 透明度）与渐变底融合出层次 |

## 文件清单

| 文件 | 用途 |
|---|---|
| `zcgui-window-soft.svg` | **正式图标源文件** |
| `gen-png.js` | PNG 生成脚本（需要时 `node gen-png.js`，Edge headless 透明渲染到 `png/`） |
| `Zai.svg` | 旧版 Z.ai 官方标识存档（已弃用，保留备查） |

## 接入（已完成）

`zcgui-window-soft.svg` 已覆盖以下三处：

- `intellij-plugin/src/main/resources/icons/zcgui.svg` —— ToolWindow / 菜单图标（代码引用 `ZCodeIcons.ZcGui`）
- `intellij-plugin/src/main/resources/META-INF/pluginIcon.svg` —— 插件 Logo（亮色主题）
- `intellij-plugin/src/main/resources/META-INF/pluginIcon_dark.svg` —— 插件 Logo（深色主题，与亮色同源）
- `webview/src/components/ZaiIcon.tsx` —— 欢迎页 logo + 模型图标 fallback（`block` variant 内联同款 SVG path）

换图标：改 `zcgui-window-soft.svg` 后覆盖以上文件并重新构建。Marketplace 上传图需要时 `node gen-png.js` 生成 `png/zcgui-window-soft_640.png`。

## 小尺寸说明

16px 下渐变/渐隐细节自然消失，剩余"深色圆角块 + 白色斜杠"剪影可辨；32~48px 渐隐层次渐现；128px+ 全部细节呈现。

## 修改指引

像素块坐标：`x = 244 + 30×列`、`y = 242 + 42×行`，块 26×36，白色按行 `fill-opacity` 从 1.00（顶行）递减至 0.50（底行），每行步进约 0.04。
