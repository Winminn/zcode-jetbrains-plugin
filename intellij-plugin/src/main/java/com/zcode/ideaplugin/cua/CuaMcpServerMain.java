package com.zcode.ideaplugin.cua;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * zcgui-computer-use —— 插件自带的电脑控制 MCP stdio server（MVP 坐标族）。
 *
 * 由 app-server 按 session/create 顶层 mcpServers 注入配置拉起（stdio，JSON 按行
 * 分隔，diag-cua-inject.py 实测通道）；stdin EOF 即退出（app-server 死 → 管道断 →
 * readLine 返回 null → exit，mock 实测语义成立）。
 *
 * 纯 Java 零依赖设计：-cp 只有插件自身 jar 一个元素（2026.1 平台发行无独立
 * kotlin-stdlib jar，fork JVM 不可用 Kotlin 产物，server 主体整体 Java）。
 * stderr 是唯一日志出口（stdout 是协议通道）。
 *
 * 工具面与官方 CUA（computer-use 插件 0.6.3）同名同参：11 个坐标族 + window_manage /
 * computer_batch。动作类工具默认在渲染稳定延迟后附回执截图（observe/observe_delay_ms）。
 * UIA 元素树族（get_app_state 元素索引/set_value/select_text/perform_action）二期。
 */
public final class CuaMcpServerMain {

    private static final AtomicLong STATE_SEQ = new AtomicLong(0);

    // 管道下 JVM 的 stdout/stderr 默认编码随系统 ANSI 码页走（zh-CN Windows = GBK），
    // 窗口标题里的中文会以 GBK 写出、app-server 按 UTF-8 读即乱码；stdin 已显式
    // UTF-8，协议通道与日志出口同样必须钉死 UTF-8，不能依赖启动参数补编码 flag。
    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    private static final PrintStream ERR =
            new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

    private CuaMcpServerMain() {
    }

    public static void main(String[] args) throws Exception {
        // AWT 需要桌面上下文（Robot）
        System.setProperty("java.awt.headless", "false");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StringBuilder lineBuf = new StringBuilder(256);
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            if (line.isBlank()) {
                continue;
            }
            String response;
            try {
                response = dispatch(line);
            } catch (Exception e) {
                err("dispatch crash: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                response = errorResponse(null, -32603, "internal error: " + msg(e));
            }
            if (response != null) {
                OUT.print(response);
                OUT.print("\n");
                OUT.flush();
            }
        }
        Runtime.getRuntime().halt(0);
    }

    private static void err(String msg) {
        ERR.println("[zcgui-cua] " + msg);
    }

    private static String msg(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** @return 要写回 stdout 的 JSON 文本；通知返回 null。 */
    private static String dispatch(String line) {
        Object parsed;
        try {
            parsed = CuaMiniJson.parse(line);
        } catch (Exception e) {
            return errorResponse(null, -32700, "parse error");
        }
        if (!(parsed instanceof Map)) {
            return null;
        }
        Map<?, ?> msg = (Map<?, ?>) parsed;
        String method = (String) msg.get("method");
        Object id = msg.get("id");
        if (method == null) {
            return null; // response/unknown：忽略
        }
        switch (method) {
            case "initialize":
                return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson(id)
                    + ",\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"tools\":{}},"
                    + "\"serverInfo\":{\"name\":\"zcgui-computer-use\",\"version\":\"0.1.0\"}}}";
            case "notifications/initialized":
            case "notifications/cancelled":
                return null;
            case "ping":
                return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson(id) + ",\"result\":{}}";
            case "tools/list": {
                StringBuilder sb = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":").append(idJson(id))
                    .append(",\"result\":{\"tools\":[");
                for (int i = 0; i < TOOLS.length; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(TOOLS[i]);
                }
                return sb.append("]}}").toString();
            }
            case "tools/call": {
                Map<?, ?> params = asMap(msg.get("params"));
                String name = params.get("name") instanceof String ? (String) params.get("name") : "";
                Map<?, ?> toolArgs = asMap(params.get("arguments"));
                long started = System.currentTimeMillis();
                String result;
                try {
                    result = callTool(name, toolArgs);
                } catch (CuaRobot.CuaException e) {
                    err("tool " + name + " failed: " + e.code + " " + e.getMessage());
                    result = CuaMiniJson.textResult(cuaErrorJson(e.code, msg(e)), true);
                } catch (Exception e) {
                    err("tool " + name + " crashed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    result = CuaMiniJson.textResult(cuaErrorJson("INTERNAL", msg(e)), true);
                }
                err("tool " + name + " ok=" + !result.contains("\"isError\":true")
                    + " " + (System.currentTimeMillis() - started) + "ms");
                return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson(id) + ",\"result\":" + result + "}";
            }
            default:
                return errorResponse(id, -32601, "method not found: " + method);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> asMap(Object o) {
        return o instanceof Map ? (Map<Object, Object>) o : Map.of();
    }

    private static String idJson(Object id) {
        if (id == null) {
            return "null";
        }
        if (id instanceof Double) {
            double d = (Double) id;
            return d == Math.floor(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        return CuaMiniJson.str(String.valueOf(id));
    }

    private static String errorResponse(Object id, int code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson(id) + ",\"error\":{\"code\":" + code
            + ",\"message\":" + CuaMiniJson.str(message) + "}}";
    }

    private static String cuaErrorJson(String code, String message) {
        return "{\"code\":" + CuaMiniJson.str(code) + ",\"message\":" + CuaMiniJson.str(message) + "}";
    }

    // ───────────────────────── 工具分发 ─────────────────────────

    private static String callTool(String name, Map<?, ?> args) {
        switch (name) {
            case "list_apps": return toolListApps();
            case "list_windows": return toolListWindows(args);
            case "get_app_state": return toolGetAppState(args);
            case "left_click": return toolClick(args);
            case "left_click_drag": return toolDrag(args);
            case "scroll": return toolScroll(args);
            case "type": return toolType(args);
            case "key": return toolKey(args);
            case "paste": return toolPaste(args);
            case "window_manage": return toolWindowManage(args);
            case "computer_batch": return toolBatch(args);
            case "request_access":
                return CuaMiniJson.textResult(
                    "{\"ready\":true,\"accessibility\":\"granted\",\"screenRecording\":\"granted\"}", false);
            case "stop_computer_control":
                return CuaMiniJson.textResult("{\"stopped\":true}", false);
            default:
                return CuaMiniJson.textResult(cuaErrorJson("INTERNAL", "unknown tool: " + name), true);
        }
    }

    // ───────────────────── 动作统一出口（observe 回执） ─────────────────────

    /**
     * 动作后应用侧渲染有时间窗（菜单/窗口动画、激活重排）：回执截图若紧跟着动作打出
     * 去，拍到的是动作前的画面（真机 WorkBuddy 验收实锤：点开托盘弹层后立即截图，
     * 图里没有弹层）。回执前默认等 400ms 盖住常规动画；慢过渡（应用冷启动、长动画）
     * 用 observe_delay_ms 调大，赶时间用 0 跳过。
     */
    private static final long OBSERVE_DELAY_DEFAULT_MS = 400;
    private static final long OBSERVE_DELAY_MAX_MS = 5000;

    private static long observeDelayMs(Map<?, ?> args) {
        Object v = args.get("observe_delay_ms");
        long ms = v instanceof Double ? Math.round((Double) v) : OBSERVE_DELAY_DEFAULT_MS;
        return Math.max(0, Math.min(OBSERVE_DELAY_MAX_MS, ms));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 动作收尾：observe != false 时在动作点所在屏补一张回执截图（单调用完成
     * 「执行+观察」，省掉每动作后的手动 re-observe 轮次——真机 WorkBuddy 验收
     * 一半调用是 get_app_state）。返回完整 result 信封。
     *
     * @param forceForegroundProbe focus/restore 这类切前台动作必须强制并行前台探测，
     *                             否则解析 pid 时的枚举缓存（动作前旧前台）在 2s 新鲜
     *                             窗口内会盖住真实前台
     */
    private static String finishAction(Map<?, ?> args, int[] screenPoint, String resultJson,
                                       boolean forceForegroundProbe) {
        if (Boolean.FALSE.equals(args.get("observe"))) {
            return CuaMiniJson.textResult(resultJson, false);
        }
        int display = screenPoint != null ? CuaRobot.displayIndexAt(screenPoint[0], screenPoint[1]) : -1;
        if (display < 0) {
            CuaRobot.CaptureLedger led = CuaRobot.lastCapture();
            display = led != null ? led.displayIndex : 0;
        }
        long settle = observeDelayMs(args);
        // 前台行新鲜度：枚举缓存 >2s 时起并行轻量探测（与 settle/截图重叠，零串行
        // 开销），动作切换前台后回执不再报旧 active（真机两次误导实证）。
        // focus/restore 本身就切前台——跳过缓存捷径强制探测（解析 pid 的枚举发生在
        // 动作之前，缓存必然是动作前的旧前台）
        List<CuaApps.AppEntry> freshCache = forceForegroundProbe ? null : CuaApps.peekApps(2000);
        java.util.concurrent.Future<CuaApps.Foreground> fgProbe =
            freshCache != null ? null : CuaApps.foregroundProbeAsync();
        sleep(settle);
        Object[] obs = captureObserved(display,
            "Fresh screenshot AFTER the action (render settle " + settle
                + "ms). This is now the LATEST screenshot: next action coordinates are pixels of THIS image.");
        String head = "{\n  \"result\": " + resultJson + ",\n  \"observed\": {\n"
            + obs[0] + "  }\n}";
        return CuaMiniJson.imageResult(head + appLineSuffix(fgProbe), (String) obs[1], "image/jpeg");
    }

    private static String finishAction(Map<?, ?> args, int[] screenPoint, String resultJson) {
        return finishAction(args, screenPoint, resultJson, false);
    }

    /** 截图 + 坐标台账更新，返回 {observed 内部行, jpeg base64}；note 由调用方按语境给。 */
    private static Object[] captureObserved(int display, String note) {
        Object[] cap = CuaRobot.captureScreenScaled(display);
        byte[] jpeg = (byte[]) cap[0];
        int imgW = (Integer) cap[1];
        int imgH = (Integer) cap[2];
        java.awt.Rectangle area = (java.awt.Rectangle) cap[3];
        String sid = "s" + STATE_SEQ.incrementAndGet();
        CuaRobot.setLastCapture(new CuaRobot.CaptureLedger(display, imgW, imgH, area, sid));
        String inner = "    \"state_id\": \"" + sid + "\",\n"
            + "    \"screen\": {\"display\": " + display
            + ", \"x\": " + area.x + ", \"y\": " + area.y
            + ", \"width\": " + area.width + ", \"height\": " + area.height
            + ", \"image_size\": \"" + imgW + "x" + imgH + "\"},\n"
            + "    \"note\": " + CuaMiniJson.str(note) + "\n";
        return new Object[]{inner, Base64.getEncoder().encodeToString(jpeg)};
    }

    /**
     * 当前前台进程行：并行探测结果优先（实时真相，动作切前台后旧缓存会骗人），
     * 探测失败/未启动时退回枚举缓存（可能 ≤5s 旧）。
     */
    private static String appLineSuffix(java.util.concurrent.Future<CuaApps.Foreground> probe) {
        try {
            List<CuaApps.AppEntry> apps = CuaApps.listApps();
            if (probe != null) {
                CuaApps.Foreground fg = null;
                try {
                    fg = probe.get(900, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Exception ignore) {
                    // 超时/失败：退回缓存行，不挡回执主路径
                }
                if (fg != null && fg.pid > 0) {
                    String name = null;
                    String iconKey = null;
                    for (CuaApps.AppEntry a : apps) {
                        if (a.pid == fg.pid) {
                            name = a.name;
                            iconKey = iconKeyOf(a);
                            break;
                        }
                    }
                    StringBuilder sb = new StringBuilder("\napp: pid=").append(fg.pid);
                    if (name != null) {
                        sb.append(" name=").append(name);
                    }
                    sb.append(" title=").append(CuaMiniJson.escape(fg.title)).append(" active=true");
                    if (iconKey != null) {
                        sb.append(" icon=").append(iconKey);
                    }
                    return sb.toString();
                }
            }
            for (CuaApps.AppEntry a : apps) {
                if (a.active) {
                    return "\n" + appLineOf(a);
                }
            }
            return "";
        } catch (Exception ignore) {
            return "";
        }
    }

    private static Map<?, ?> appRefOf(Map<?, ?> args) {
        Map<?, ?> ref = asMap(args.get("app_ref"));
        if (!ref.isEmpty()) {
            return ref;
        }
        ref = asMap(args.get("app"));
        if (!ref.isEmpty()) {
            return ref;
        }
        throw new CuaRobot.CuaException("INVALID_APP", "app_ref is required ({name} or {pid})");
    }

    private static int intArg(Map<?, ?> args, String key) {
        Object v = args.get(key);
        return v instanceof Double ? (int) Math.round((Double) v) : -1;
    }

    private static int[] requireCoord(Map<?, ?> args, String key) {
        Object t = args.get(key);
        if (!(t instanceof List) || ((List<?>) t).size() < 2) {
            throw new CuaRobot.CuaException("INTERNAL",
                key + " must be [x, y] pixels of the latest screenshot");
        }
        List<?> l = (List<?>) t;
        Object xv = l.get(0);
        Object yv = l.get(1);
        if (!(xv instanceof Double) || !(yv instanceof Double)) {
            throw new CuaRobot.CuaException("INTERNAL",
                key + " must be two integers [x, y] (got " + t + ")");
        }
        return new int[]{(int) Math.round((Double) xv), (int) Math.round((Double) yv)};
    }

    /** 图标 PNG → 内容寻址 key（落盘失败/无图标返回 null）。 */
    private static String iconKeyOf(CuaApps.AppEntry a) {
        return a.iconPng != null ? CuaIcons.store(a.iconPng) : null;
    }

    /** app 身份行（get_app_state/list_windows 文本尾部；icon key 供前端渲染应用图标）。 */
    private static String appLineOf(CuaApps.AppEntry app) {
        String iconKey = iconKeyOf(app);
        return "app: pid=" + app.pid + " name=" + app.name + " title=" + app.title
            + " active=" + app.active
            + (iconKey != null ? " icon=" + iconKey : "");
    }

    private static String toolListApps() {
        List<CuaApps.AppEntry> apps = CuaApps.listApps();
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < apps.size(); i++) {
            CuaApps.AppEntry a = apps.get(i);
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"pid\":").append(a.pid)
                .append(",\"name\":").append(CuaMiniJson.str(a.name))
                .append(",\"bundle_id\":null,\"active\":").append(a.active)
                .append(",\"title\":").append(CuaMiniJson.str(a.title));
            // 图标只出 12 位内容寻址 key（PNG 在本机磁盘旁路，模型不吃图/token）
            String iconKey = iconKeyOf(a);
            if (iconKey != null) {
                items.append(",\"icon\":\"").append(iconKey).append('"');
            }
            items.append('}');
        }
        return CuaMiniJson.textResult("{\"apps\":[" + items + "]}", false);
    }

    private static String toolListWindows(Map<?, ?> args) {
        Map<?, ?> ref = appRefOf(args);
        List<CuaApps.AppEntry> apps = CuaApps.listApps();
        CuaApps.AppEntry app = CuaApps.resolveApp(ref, apps);
        if (app == null) {
            throw new CuaRobot.CuaException("INVALID_APP",
                "target app is not running among windowed processes; call list_apps and use a listed name/pid");
        }
        // 真枚举（含隐藏窗）：hide 型托盘应用失焦后主窗不可见，一进程一主窗口的旧
        // 近似既看不到它，也无法区分多窗/辅助窗；window_id 供 window_manage 定向
        List<CuaApps.WinEntry> wins = CuaApps.listWindowsOf(app.pid);
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < wins.size(); i++) {
            CuaApps.WinEntry w = wins.get(i);
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"window_id\":").append(w.hwnd)
                .append(",\"title\":").append(CuaMiniJson.str(w.title))
                .append(",\"class\":").append(CuaMiniJson.str(w.cls))
                .append(",\"rect\":{\"x\":").append(w.rect[0]).append(",\"y\":").append(w.rect[1])
                .append(",\"width\":").append(w.rect[2]).append(",\"height\":").append(w.rect[3]).append('}')
                .append(",\"visible\":").append(w.visible)
                .append(",\"iconic\":").append(w.iconic)
                .append('}');
        }
        return CuaMiniJson.textResult("{\"windows\":[" + items + "]}\n" + appLineOf(app), false);
    }

    private static String toolGetAppState(Map<?, ?> args) {
        int display = intArg(args, "display");
        if (display < 0) {
            display = 0;
        }
        // include_screenshot=false：进程身份/状态探针，不出图——「pid 还在吗/标题变了
        // 没有」这类确认不再烧 200KiB 截图预算；坐标语义不受影响（仍指 AI 手里最新一张）
        if (Boolean.FALSE.equals(args.get("include_screenshot"))) {
            Map<?, ?> probeRef = asMap(args.get("app_ref"));
            if (probeRef.isEmpty()) {
                probeRef = asMap(args.get("app"));
            }
            String probeLine = "";
            if (!probeRef.isEmpty()) {
                CuaApps.AppEntry app = null;
                try {
                    app = CuaApps.resolveApp(probeRef, CuaApps.listApps());
                } catch (Exception ignore) {
                    // 枚举失败不挡探针
                }
                probeLine = app != null ? "\n" + appLineOf(app)
                    : "\napp: not found among windowed processes";
            }
            return CuaMiniJson.textResult(
                "{\"note\":\"screenshot suppressed (include_screenshot=false); coordinates still refer to the LATEST screenshot you hold\"}"
                    + probeLine, false);
        }
        // crop = 上一张截图像素矩形（无台账按目标屏原生像素）：子区域自动放大到
        // 长边 ≤1600——认托盘溢出面板/小图标是主用例（真机 13 个 20px 图标不可辨）
        int[] crop = null;
        Object cropV = args.get("crop");
        if (cropV instanceof List && ((List<?>) cropV).size() >= 4) {
            List<?> l = (List<?>) cropV;
            crop = new int[4];
            for (int i = 0; i < 4; i++) {
                Object v = l.get(i);
                if (!(v instanceof Double)) {
                    throw new CuaRobot.CuaException("INTERNAL", "crop must be four integers [x, y, w, h]");
                }
                crop[i] = (int) Math.round((Double) v);
            }
        }
        Object[] cap = CuaRobot.captureScreenScaled(display, crop);
        byte[] jpeg = (byte[]) cap[0];
        int imgW = (Integer) cap[1];
        int imgH = (Integer) cap[2];
        java.awt.Rectangle area = (java.awt.Rectangle) cap[3];
        String base64 = Base64.getEncoder().encodeToString(jpeg);
        String sid = "s" + STATE_SEQ.incrementAndGet();
        CuaRobot.setLastCapture(new CuaRobot.CaptureLedger(display, imgW, imgH, area, sid));
        StringBuilder head = new StringBuilder();
        head.append("{\n");
        head.append("  \"state_id\": \"").append(sid).append("\",\n");
        head.append("  \"screen\": {\"display\": ").append(display)
            .append(", \"x\": ").append(area.x).append(", \"y\": ").append(area.y)
            .append(", \"width\": ").append(area.width).append(", \"height\": ").append(area.height)
            .append(", \"image_size\": \"").append(imgW).append("x").append(imgH).append("\"},\n");
        head.append("  \"note\": \"The image IS the coordinate raster: read pixels off the returned image and pass them unchanged to click/drag/scroll (the server maps them onto the screen). ")
            .append(crop != null
                ? "Cropped zoomed view of the rect in screen above.\"\n"
                : "Element indices are not available in this version.\"\n");
        head.append("}");
        // app_ref 给了时附上解析到的进程身份（宽容：找不到不报错，只附提示）
        Map<?, ?> ref = asMap(args.get("app_ref"));
        if (ref.isEmpty()) {
            ref = asMap(args.get("app"));
        }
        String appLine = "";
        if (!ref.isEmpty()) {
            CuaApps.AppEntry app = null;
            try {
                app = CuaApps.resolveApp(ref, CuaApps.listApps());
            } catch (Exception ignore) {
                // 枚举失败不影响截图主路径
            }
            appLine = app != null
                ? "\n" + appLineOf(app)
                : "\napp: not found among windowed processes (screenshot still attached)";
        }
        return CuaMiniJson.imageResult(head.toString() + appLine, base64, "image/jpeg");
    }

    /** AI 坐标（最新截图像素）→ 屏幕坐标；未截过图时按屏幕原生像素直用。 */
    private static int[] toScreen(int[] img) {
        CuaRobot.CaptureLedger led = CuaRobot.lastCapture();
        if (led == null) {
            return img;
        }
        return led.toScreen(img[0], img[1]);
    }

    /**
     * expect_window_pid 护栏（click/drag 共用）：按下点必须命中预期窗口，不符即拒。
     * 误命中后面应用会「代它」执行不可逆动作（真机两连穿到后面的 IDE 触发重构对话框、
     * 托盘双绿图标误点开 Clash Verge 都是这一类），点击和拖拽同罪同防。
     */
    private static void requireHitPid(Map<?, ?> args, int x, int y) {
        Object expectV = args.get("expect_window_pid");
        if (!(expectV instanceof Double)) {
            return;
        }
        int expectPid = (int) Math.round((Double) expectV);
        Object[] hit = CuaApps.hitWindowWithTitle(x, y);
        int hitPid = hit[0] instanceof Integer ? (Integer) hit[0] : -1;
        if (hitPid != expectPid) {
            throw new CuaRobot.CuaException("GUARD_MISMATCH",
                "point (" + x + ", " + y + ") hits pid " + hitPid
                    + " (" + hit[1] + "), expected pid " + expectPid
                    + "; refused to avoid acting inside the wrong app");
        }
    }

    private static String toolClick(Map<?, ?> args) {
        int[] img = requireCoord(args, "target");
        int[] t = toScreen(img);
        requireHitPid(args, t[0], t[1]);
        int count = intArg(args, "click_count");
        if (count <= 0) {
            count = 1;
        }
        Object mb = args.get("mouse_button");
        CuaRobot.click(t[0], t[1], mb instanceof String ? (String) mb : null, count);
        return finishAction(args, t, "{\"clicked\":[" + t[0] + "," + t[1] + "]}");
    }

    private static String toolDrag(Map<?, ?> args) {
        int[] fromImg = requireCoord(args, "from_target");
        int[] from = toScreen(fromImg);
        int[] to = toScreen(requireCoord(args, "to"));
        // 护栏：按下点必须命中预期窗口（真机两连穿到后面 IDE 的直接解）
        requireHitPid(args, from[0], from[1]);
        Object mb = args.get("mouse_button");
        CuaRobot.drag(from[0], from[1], to[0], to[1], mb instanceof String ? (String) mb : null);
        return finishAction(args, to, "{\"dragged\":[[" + from[0] + "," + from[1] + "],[" + to[0] + "," + to[1] + "]]}");
    }

    private static String toolScroll(Map<?, ?> args) {
        int[] t = toScreen(requireCoord(args, "target"));
        Object dir = args.get("scroll_direction");
        if (!(dir instanceof String)) {
            throw new CuaRobot.CuaException("INTERNAL", "scroll_direction is required (up|down|left|right)");
        }
        int amount = intArg(args, "scroll_amount");
        if (amount <= 0) {
            amount = 1;
        }
        CuaRobot.scroll(t[0], t[1], (String) dir, Math.min(30, amount));
        return finishAction(args, t, "{\"scrolled\":[\""
            + CuaMiniJson.escape(String.valueOf(dir)) + "\"," + Math.min(30, amount) + "]}");
    }

    private static String toolType(Map<?, ?> args) {
        Object text = args.get("text");
        if (!(text instanceof String)) {
            throw new CuaRobot.CuaException("INTERNAL", "text is required");
        }
        CuaRobot.typeText((String) text);
        int[] at = CuaRobot.pointer();
        return finishAction(args, at, "{\"typed\":" + CuaMiniJson.str((String) text) + "}");
    }

    private static String toolPaste(Map<?, ?> args) {
        Object text = args.get("text");
        if (!(text instanceof String)) {
            throw new CuaRobot.CuaException("INTERNAL", "text is required");
        }
        CuaRobot.paste((String) text);
        int[] at = CuaRobot.pointer();
        return finishAction(args, at, "{\"pasted\":" + CuaMiniJson.str((String) text) + "}");
    }

    private static String toolKey(Map<?, ?> args) {
        Object text = args.get("text");
        if (!(text instanceof String)) {
            throw new CuaRobot.CuaException("INTERNAL", "text is required (key or '+'-separated chord)");
        }
        List<Integer> codes = CuaKeymap.resolveChord((String) text);
        if (codes == null || codes.isEmpty()) {
            throw new CuaRobot.CuaException("ACTION_UNAVAILABLE",
                "unknown key name in \"" + text + "\"; use names like Return/Tab/Escape/Control_L+a/super+c/Up");
        }
        Object holdV = args.get("hold_seconds");
        long hold = holdV instanceof Double ? (long) ((Double) holdV * 1000) : 0L;
        CuaRobot.pressChord(codes, hold);
        int[] at = CuaRobot.pointer();
        return finishAction(args, at, "{\"keyed\":" + CuaMiniJson.str((String) text) + "}");
    }

    // ───────────────────────── window_manage ─────────────────────────

    private static String toolWindowManage(Map<?, ?> args) {
        Map<?, ?> ref = appRefOf(args);
        // 宽容解析：常规 listApps（可见窗口）失败后走 Get-Process 全表——
        // 最小化窗口被主枚举过滤（rect -32000 落小窗判据），restore/focus 必须可达
        int pid = -1;
        String appName = "";
        CuaApps.AppEntry app = null;
        try {
            app = CuaApps.resolveApp(ref, CuaApps.listApps());
        } catch (Exception ignore) {
            // 枚举失败不挡：还有 loose 路径
        }
        if (app != null) {
            pid = app.pid;
            appName = app.name;
        } else {
            Object pidV = ref.get("pid");
            if (pidV instanceof Double) {
                pid = (int) Math.round((Double) pidV);
            } else {
                Object nameV = ref.get("name");
                if (nameV instanceof String && !((String) nameV).isBlank()) {
                    appName = (String) nameV;
                    pid = CuaApps.pidByNameLoose(appName);
                }
            }
        }
        if (pid <= 0) {
            throw new CuaRobot.CuaException("INVALID_APP",
                "target app is not running; call list_apps and use a listed name/pid");
        }
        Object actionV = args.get("action");
        String action = actionV instanceof String && !((String) actionV).isEmpty()
            ? (String) actionV : "query";
        int[] rect = null;
        if (action.equals("move") || action.equals("resize")) {
            rect = new int[]{intArg(args, "x"), intArg(args, "y"), intArg(args, "width"), intArg(args, "height")};
        }
        long windowId = longArg(args, "window_id", -1);
        CuaApps.WindowInfo wi = CuaApps.windowManage(pid, action, rect, windowId);
        StringBuilder json = new StringBuilder();
        int display = CuaRobot.displayIndexAt(
            wi.rect[0] + wi.rect[2] / 2, wi.rect[1] + wi.rect[3] / 2);
        json.append("{\"pid\":").append(pid)
            .append(",\"name\":").append(CuaMiniJson.str(appName))
            .append(",\"title\":").append(CuaMiniJson.str(wi.title))
            .append(",\"action\":").append(CuaMiniJson.str(action))
            .append(",\"rect\":{\"x\":").append(wi.rect[0]).append(",\"y\":").append(wi.rect[1])
            .append(",\"width\":").append(wi.rect[2]).append(",\"height\":").append(wi.rect[3]).append('}')
            .append(",\"display\":").append(display)
            .append(",\"iconic\":").append(wi.iconic)
            .append(",\"zoomed\":").append(wi.zoomed);
        // applied 是回执可信度的关键：钉死型应用会同步拒收 SetWindowPos（返回 True
        // 但几何没变），不标记的话 AI 只能逐次人肉比对 rect
        boolean mutating = action.equals("move") || action.equals("resize");
        if (mutating) {
            json.append(",\"applied\":").append(wi.applied);
            if (wi.clamped) {
                json.append(",\"clamped\":true");
            }
        }
        if (wi.wasHidden) {
            json.append(",\"wasHidden\":true");
        }
        String note;
        if (mutating && !wi.applied) {
            note = wi.clamped
                ? "size was clamped UP to the app's own minimum (read-back > requested); the app enforces a minimum window size"
                : "window did NOT take the requested bounds (auto-retried " + wi.attempts
                    + "x; diag " + wi.diag + ") - it likely pins its own geometry (assistant/tray apps do). Do NOT retry the same move: durable options are the app's own title-bar buttons or OS snap via key super+Left/super+Right/super+Up";
        } else if (wi.wasHidden) {
            note = action.equals("query")
                ? "this window is currently hidden (tray-style); this read-only query did not change that"
                : "window was hidden (tray-style); it has been re-shown"
                    + (action.equals("focus") ? " and focused" : "");
        } else {
            note = "rect is in the same logical space as screenshot pixels; verify with a screenshot if you need visual confirmation";
        }
        json.append(",\"note\":").append(CuaMiniJson.str(note)).append('}');
        // 变更类动作默认附回执截图：窗口动画（restore/maximize 数百 ms）是渲染窗的
        // 重灾区，rect 数值落位不等于画面已画完；query 是纯读不附图
        if (action.equals("query") || Boolean.FALSE.equals(args.get("observe"))) {
            return CuaMiniJson.textResult(json.toString(), false);
        }
        return finishAction(args, new int[]{wi.rect[0] + wi.rect[2] / 2, wi.rect[1] + wi.rect[3] / 2},
            json.toString(), action.equals("focus") || action.equals("restore"));
    }

    // ───────────────────────── computer_batch ─────────────────────────

    private static final int BATCH_MAX_STEPS = 20;
    private static final long BATCH_BUDGET_MS = 30_000;
    private static final long BATCH_WAIT_MAX_MS = 5_000;

    /**
     * 批量宏：steps 顺序执行（坐标均相对执行前最新那张截图），失败即停并回报
     * 已执行步数；settle_ms（默认 0）在每个动作步之后垫一拍等 UI 渲染出来再走
     * 下一步（wait 步本身就是等待不重复垫）；observe_final 默认 true，完成后
     * 停 observe_delay_ms 再回一张截图；失败同样回现场截图，AI 不必再花一轮
     * get_app_state 就能看清停在哪、画面长什么样。
     */
    private static String toolBatch(Map<?, ?> args) {
        Object stepsObj = args.get("steps");
        if (!(stepsObj instanceof List) || ((List<?>) stepsObj).isEmpty()) {
            throw new CuaRobot.CuaException("INTERNAL", "steps must be a non-empty array");
        }
        List<?> steps = (List<?>) stepsObj;
        if (steps.size() > BATCH_MAX_STEPS) {
            throw new CuaRobot.CuaException("INTERNAL",
                "too many steps: " + steps.size() + " (max " + BATCH_MAX_STEPS + ")");
        }
        long settle = settleMs(args);
        long deadline = System.currentTimeMillis() + BATCH_BUDGET_MS;
        StringBuilder done = new StringBuilder();
        int executed = 0;
        try {
            for (Object stepObj : steps) {
                if (!(stepObj instanceof Map)) {
                    throw new CuaRobot.CuaException("INTERNAL", "each step must be an object with an action");
                }
                Map<?, ?> step = (Map<?, ?>) stepObj;
                Object actionV = step.get("action");
                if (!(actionV instanceof String)) {
                    throw new CuaRobot.CuaException("INTERNAL", "step missing action (index " + executed + ")");
                }
                String action = (String) actionV;
                long now = System.currentTimeMillis();
                if (now > deadline) {
                    throw new CuaRobot.CuaException("TIMEOUT",
                        "batch budget (" + BATCH_BUDGET_MS + "ms) exceeded after " + executed + " steps");
                }
                switch (action) {
                    case "wait": {
                        long ms = longArg(step, "ms", 500);
                        Thread.sleep(Math.min(ms, BATCH_WAIT_MAX_MS));
                        break;
                    }
                    case "click": {
                        int[] t = toScreen(requireCoord(step, "target"));
                        int count = intArg(step, "click_count");
                        CuaRobot.click(t[0], t[1], strOrNull(step.get("mouse_button")), count > 0 ? count : 1);
                        break;
                    }
                    case "drag": {
                        int[] from = toScreen(requireCoord(step, "from_target"));
                        int[] to = toScreen(requireCoord(step, "to"));
                        CuaRobot.drag(from[0], from[1], to[0], to[1], strOrNull(step.get("mouse_button")));
                        break;
                    }
                    case "scroll": {
                        int[] t = toScreen(requireCoord(step, "target"));
                        Object dir = step.get("scroll_direction");
                        if (!(dir instanceof String)) {
                            throw new CuaRobot.CuaException("INTERNAL", "scroll step needs scroll_direction");
                        }
                        int amount = intArg(step, "scroll_amount");
                        CuaRobot.scroll(t[0], t[1], (String) dir, amount > 0 ? Math.min(30, amount) : 1);
                        break;
                    }
                    case "type": {
                        Object text = step.get("text");
                        if (!(text instanceof String)) {
                            throw new CuaRobot.CuaException("INTERNAL", "type step needs text");
                        }
                        CuaRobot.typeText((String) text);
                        break;
                    }
                    case "key": {
                        Object text = step.get("text");
                        if (!(text instanceof String)) {
                            throw new CuaRobot.CuaException("INTERNAL", "key step needs text");
                        }
                        List<Integer> codes = CuaKeymap.resolveChord((String) text);
                        if (codes == null || codes.isEmpty()) {
                            throw new CuaRobot.CuaException("ACTION_UNAVAILABLE",
                                "unknown key name in \"" + text + "\"");
                        }
                        CuaRobot.pressChord(codes, 0);
                        break;
                    }
                    default:
                        throw new CuaRobot.CuaException("INTERNAL",
                            "unknown step action: " + action + " (click|drag|scroll|type|key|wait)");
                }
                executed++;
                if (done.length() > 0) {
                    done.append(',');
                }
                done.append(CuaMiniJson.str(action));
                if (settle > 0 && !action.equals("wait") && executed < steps.size()) {
                    sleep(settle);
                }
            }
        } catch (CuaRobot.CuaException e) {
            String body = "{\"executed\":" + executed + ",\"failed_step\":" + executed
                + ",\"steps_done\":[" + done + "],\"error\":"
                + cuaErrorJson(e.code, msg(e)) + "}";
            if (Boolean.FALSE.equals(args.get("observe_final"))) {
                return CuaMiniJson.textResult(body, true);
            }
            try {
                long settle2 = observeDelayMs(args);
                sleep(settle2);
                CuaRobot.CaptureLedger led = CuaRobot.lastCapture();
                int display = led != null ? led.displayIndex : 0;
                Object[] obs = captureObserved(display,
                    "Screenshot AFTER the batch stopped on failure at step " + executed
                        + " (render settle " + settle2 + "ms); screen state is unverified territory.");
                return CuaMiniJson.imageResult(
                    body + ",\n  \"observed\": {\n" + obs[0] + "  }\n}",
                    (String) obs[1], "image/jpeg", true);
            } catch (Exception crash) {
                // 回执截图失败不掩盖原始错误
                err("batch failure receipt capture failed: " + msg(crash));
                return CuaMiniJson.textResult(body, true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CuaRobot.CuaException("INTERNAL", "batch interrupted after " + executed + " steps");
        }
        String head = "{\"executed\":" + executed + ",\"steps_done\":[" + done + "]";
        if (!Boolean.FALSE.equals(args.get("observe_final"))) {
            long settle2 = observeDelayMs(args);
            List<CuaApps.AppEntry> freshCache = CuaApps.peekApps(2000);
            java.util.concurrent.Future<CuaApps.Foreground> fgProbe =
                freshCache != null ? null : CuaApps.foregroundProbeAsync();
            sleep(settle2);
            CuaRobot.CaptureLedger led = CuaRobot.lastCapture();
            int display = led != null ? led.displayIndex : 0;
            Object[] obs = captureObserved(display,
                "Fresh screenshot AFTER all steps (render settle " + settle2
                    + "ms); next action coordinates are pixels of THIS image.");
            return CuaMiniJson.imageResult(head + ",\n  \"observed\": {\n" + obs[0] + "  }\n}"
                + appLineSuffix(fgProbe), (String) obs[1], "image/jpeg");
        }
        return CuaMiniJson.textResult(head + "}", false);
    }

    private static String strOrNull(Object o) {
        return o instanceof String ? (String) o : null;
    }

    private static long longArg(Map<?, ?> args, String key, long dflt) {
        Object v = args.get(key);
        return v instanceof Double ? Math.round((Double) v) : dflt;
    }

    /** batch 步间 settle（默认 0）：动作步渲染出新 UI 后下一步再走。 */
    private static long settleMs(Map<?, ?> args) {
        Object v = args.get("settle_ms");
        long ms = v instanceof Double ? Math.round((Double) v) : 0;
        return Math.max(0, Math.min(OBSERVE_DELAY_MAX_MS, ms));
    }

    // ───────────────────────── tools/list schema ─────────────────────────

    private static final String COORD =
        "{\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"minItems\":2,\"maxItems\":2}";
    private static final String COORD4 =
        "{\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"minItems\":4,\"maxItems\":4}";
    private static final String APP_REF =
        "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
            + "\"pid\":{\"type\":\"integer\"},\"bundle_id\":{\"type\":\"string\"}}}";

    private static final String[] TOOLS = {
        tool("list_apps",
            "List windowed desktop apps (pid, name, title, active). Call this first to find the app to control.",
            ""),
        tool("list_windows",
            "List ALL top-level windows of one app, including hidden ones (window_id for window_manage targeting; class + rect + visible/iconic flags).",
            prop("app_ref", APP_REF)),
        tool("get_app_state",
            "Screenshot a display and return it with screen bounds. Coordinates you read on the image are the click coordinates. display: screen index (default 0). crop: [x,y,w,h] pixels of the LATEST screenshot to capture only a sub-region, zoomed to 1600px long edge (identify tray icons / small controls). include_screenshot=false skips the image and returns process identity only (cheap probe; coordinates still refer to the LATEST screenshot you hold).",
            prop("app_ref", APP_REF)
                + ",\"display\":{\"type\":\"integer\"},\"include_screenshot\":{\"type\":\"boolean\"}"
                + ",\"crop\":" + COORD4),
        tool("left_click",
            "Click at [x, y] pixels of the LATEST screenshot. mouse_button: left/right/middle; click_count 2 = double click. expect_window_pid: guard - verify the point hits this pid's window first, refuse otherwise (prevents acting inside the wrong app, e.g. two look-alike tray icons). observe (default true): attach a fresh screenshot of the result in the same response, after a render-settle wait (default 400ms so menus/animations land in frame; observe_delay_ms overrides, 0..5000).",
            prop("target", COORD)
                + ",\"mouse_button\":{\"type\":\"string\",\"enum\":[\"left\",\"right\",\"middle\"]}"
                + ",\"click_count\":{\"type\":\"integer\"}"
                + ",\"expect_window_pid\":{\"type\":\"integer\"}"
                + ",\"observe\":{\"type\":\"boolean\"}"
                + ",\"observe_delay_ms\":{\"type\":\"integer\"}"),
        tool("left_click_drag",
            "Press at from_target [x,y], interpolate to to [x,y], release. expect_window_pid: guard - verify the press point hits this pid's window first, refuse otherwise (prevents dragging inside the wrong app when resizing window edges). observe (default true) attaches a result screenshot after a render settle (observe_delay_ms, default 400ms).",
            prop("from_target", COORD) + ",\"to\":" + COORD
                + ",\"mouse_button\":{\"type\":\"string\",\"enum\":[\"left\",\"right\",\"middle\"]}"
                + ",\"expect_window_pid\":{\"type\":\"integer\"}"
                + ",\"observe\":{\"type\":\"boolean\"}"
                + ",\"observe_delay_ms\":{\"type\":\"integer\"}"),
        tool("scroll",
            "Move pointer to target [x,y] and scroll. scroll_direction: up|down|left|right; scroll_amount: wheel notches. observe (default true) attaches a result screenshot after a render settle (observe_delay_ms, default 400ms).",
            prop("target", COORD)
                + ",\"scroll_direction\":{\"type\":\"string\",\"enum\":[\"up\",\"down\",\"left\",\"right\"]}"
                + ",\"scroll_amount\":{\"type\":\"integer\"}"
                + ",\"observe\":{\"type\":\"boolean\"}"
                + ",\"observe_delay_ms\":{\"type\":\"integer\"}"),
        tool("type",
            "Type text into the focused control via clipboard paste (Unicode-safe). observe (default true) attaches a result screenshot after a render settle (observe_delay_ms, default 400ms).",
            prop("text", "{\"type\":\"string\"}")
                + ",\"observe\":{\"type\":\"boolean\"}"
                + ",\"observe_delay_ms\":{\"type\":\"integer\"}"),
        tool("key",
            "Press a key or '+'-separated chord, e.g. \"Return\", \"Control_L+a\", \"super+c\", \"Up\". hold_seconds holds the chord down. observe (default true) attaches a result screenshot after a render settle (observe_delay_ms, default 400ms - raise it when the key opens a slow menu or window).",
            prop("text", "{\"type\":\"string\"}") + ",\"hold_seconds\":{\"type\":\"number\"}"
                + ",\"observe\":{\"type\":\"boolean\"}"
                + ",\"observe_delay_ms\":{\"type\":\"integer\"}"),
        tool("paste",
            "Put text on the clipboard and press Ctrl+V. observe (default true) attaches a result screenshot after a render settle (observe_delay_ms, default 400ms).",
            prop("text", "{\"type\":\"string\"}")
                + ",\"format\":{\"type\":\"string\",\"enum\":[\"text\",\"md\",\"html\"]}"
                + ",\"observe\":{\"type\":\"boolean\"}"
                + ",\"observe_delay_ms\":{\"type\":\"integer\"}"),
        tool("window_manage",
            "Move/resize/focus/minimize/maximize/restore a window via the OS - NO pixel dragging needed. action=query returns the current rect (same logical space as screenshot pixels); move/resize need full x,y,width,height (query first, then apply). window_id (from list_windows) targets a specific window of the process. Mutating actions attach a result screenshot once the window settles. move/resize report applied:false when the app refused the bounds (pinned-geometry apps do; then prefer the app's own buttons or OS snap via key super+Left, and don't retry). focus re-shows hidden windows of hide-on-deactivate tray apps.",
            prop("app_ref", APP_REF)
                + ",\"action\":{\"type\":\"string\",\"enum\":[\"query\",\"move\",\"resize\",\"focus\",\"minimize\",\"maximize\",\"restore\"]}"
                + ",\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"}"
                + ",\"width\":{\"type\":\"integer\"},\"height\":{\"type\":\"integer\"}"
                + ",\"window_id\":{\"type\":\"integer\"}"
                + ",\"observe\":{\"type\":\"boolean\"}"
                + ",\"observe_delay_ms\":{\"type\":\"integer\"}"),
        tool("computer_batch",
            "Run several primitive actions in ONE call: steps=[{action:click|drag|scroll|type|key|wait, ...same params as the single tools}]. Coordinates are pixels of the latest screenshot (taken before the batch). Stops at first failure and reports executed count - with a screenshot of where it stopped. settle_ms (default 0) waits after each non-wait step so freshly rendered UI exists before the next step; observe_final (default true) attaches one screenshot after all steps, delayed by observe_delay_ms (default 400ms). Use for keyboard sequences like open-start-menu-and-search (super -> type -> Return).",
            prop("steps", "{\"type\":\"array\",\"maxItems\":20,\"items\":{\"type\":\"object\",\"properties\":{"
                + "\"action\":{\"type\":\"string\",\"enum\":[\"click\",\"drag\",\"scroll\",\"type\",\"key\",\"wait\"]},"
                + "\"target\":" + COORD + ",\"from_target\":" + COORD + ",\"to\":" + COORD
                + ",\"mouse_button\":{\"type\":\"string\",\"enum\":[\"left\",\"right\",\"middle\"]}"
                + ",\"click_count\":{\"type\":\"integer\"}"
                + ",\"scroll_direction\":{\"type\":\"string\",\"enum\":[\"up\",\"down\",\"left\",\"right\"]}"
                + ",\"scroll_amount\":{\"type\":\"integer\"}"
                + ",\"text\":{\"type\":\"string\"},\"ms\":{\"type\":\"integer\"}"
                + "},\"required\":[\"action\"],\"additionalProperties\":false}}")
                + ",\"settle_ms\":{\"type\":\"integer\"}"
                + ",\"observe_final\":{\"type\":\"boolean\"}"
                + ",\"observe_delay_ms\":{\"type\":\"integer\"}"),
        tool("request_access", "No-op on Windows: always ready.",
            prop("capabilities", "{\"type\":\"array\",\"items\":{\"type\":\"string\"}}")),
        tool("stop_computer_control", "Acknowledge a stop request (no-op).",
            prop("reason", "{\"type\":\"string\"}")),
    };

    private static String prop(String name, String schema) {
        return "\"" + name + "\":" + schema;
    }

    private static String tool(String name, String description, String props) {
        return "{\"name\":" + CuaMiniJson.str(name)
            + ",\"description\":" + CuaMiniJson.str(description)
            + ",\"inputSchema\":{\"type\":\"object\",\"properties\":{"
            + props + "},\"additionalProperties\":false}}";
    }
}
