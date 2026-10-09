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
 * 工具面与官方 CUA（computer-use 插件 0.6.3）同名同参，MVP 实现 11 个坐标族工具；
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
            case "request_access":
                return CuaMiniJson.textResult(
                    "{\"ready\":true,\"accessibility\":\"granted\",\"screenRecording\":\"granted\"}", false);
            case "stop_computer_control":
                return CuaMiniJson.textResult("{\"stopped\":true}", false);
            default:
                return CuaMiniJson.textResult(cuaErrorJson("INTERNAL", "unknown tool: " + name), true);
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
        // MVP：一进程一主窗口近似（window_id = pid）
        String w = "{\"window_id\":" + app.pid + ",\"title\":"
            + CuaMiniJson.str(app.title) + ",\"rect\":null}";
        return CuaMiniJson.textResult("{\"windows\":[" + w + "]}\n" + appLineOf(app), false);
    }

    private static String toolGetAppState(Map<?, ?> args) {
        int display = intArg(args, "display");
        if (display < 0) {
            display = 0;
        }
        Object[] cap = CuaRobot.captureScreenScaled(display);
        byte[] jpeg = (byte[]) cap[0];
        int imgW = (Integer) cap[1];
        int imgH = (Integer) cap[2];
        java.awt.Rectangle b = CuaRobot.screenBounds(display);
        String base64 = Base64.getEncoder().encodeToString(jpeg);
        String sid = "s" + STATE_SEQ.incrementAndGet();
        CuaRobot.setLastCapture(new CuaRobot.CaptureLedger(display, imgW, imgH, b, sid));
        StringBuilder head = new StringBuilder();
        head.append("{\n");
        head.append("  \"state_id\": \"").append(sid).append("\",\n");
        head.append("  \"screen\": {\"display\": ").append(display)
            .append(", \"x\": ").append(b.x).append(", \"y\": ").append(b.y)
            .append(", \"width\": ").append(b.width).append(", \"height\": ").append(b.height)
            .append(", \"image_size\": \"").append(imgW).append("x").append(imgH).append("\"},\n");
        head.append("  \"note\": \"The image IS the coordinate raster: read pixels off the returned image and pass them unchanged to click/drag/scroll (the server maps them onto the screen). ")
            .append("Element indices are not available in this version.\"\n");
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

    private static String toolClick(Map<?, ?> args) {
        int[] img = requireCoord(args, "target");
        int[] t = toScreen(img);
        int count = intArg(args, "click_count");
        if (count <= 0) {
            count = 1;
        }
        Object mb = args.get("mouse_button");
        CuaRobot.click(t[0], t[1], mb instanceof String ? (String) mb : null, count);
        return CuaMiniJson.textResult("{}", false);
    }

    private static String toolDrag(Map<?, ?> args) {
        int[] from = toScreen(requireCoord(args, "from_target"));
        int[] to = toScreen(requireCoord(args, "to"));
        Object mb = args.get("mouse_button");
        CuaRobot.drag(from[0], from[1], to[0], to[1], mb instanceof String ? (String) mb : null);
        return CuaMiniJson.textResult("{}", false);
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
        return CuaMiniJson.textResult("{}", false);
    }

    private static String toolType(Map<?, ?> args) {
        Object text = args.get("text");
        if (!(text instanceof String)) {
            throw new CuaRobot.CuaException("INTERNAL", "text is required");
        }
        CuaRobot.typeText((String) text);
        return CuaMiniJson.textResult("{}", false);
    }

    private static String toolPaste(Map<?, ?> args) {
        Object text = args.get("text");
        if (!(text instanceof String)) {
            throw new CuaRobot.CuaException("INTERNAL", "text is required");
        }
        CuaRobot.paste((String) text);
        return CuaMiniJson.textResult("{}", false);
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
        return CuaMiniJson.textResult("{}", false);
    }

    // ───────────────────────── tools/list schema ─────────────────────────

    private static final String COORD =
        "{\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"minItems\":2,\"maxItems\":2}";
    private static final String APP_REF =
        "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
            + "\"pid\":{\"type\":\"integer\"},\"bundle_id\":{\"type\":\"string\"}}}";

    private static final String[] TOOLS = {
        tool("list_apps",
            "List windowed desktop apps (pid, name, title, active). Call this first to find the app to control.",
            ""),
        tool("list_windows",
            "List main windows of one app (window_id equals the pid in this version).",
            prop("app_ref", APP_REF)),
        tool("get_app_state",
            "Screenshot a display and return it with screen bounds. Coordinates you read on the image are the click coordinates. display: screen index (default 0).",
            prop("app_ref", APP_REF)
                + ",\"display\":{\"type\":\"integer\"},\"include_screenshot\":{\"type\":\"boolean\"}"),
        tool("left_click",
            "Click at [x, y] pixels of the LATEST screenshot. mouse_button: left/right/middle; click_count 2 = double click.",
            prop("target", COORD)
                + ",\"mouse_button\":{\"type\":\"string\",\"enum\":[\"left\",\"right\",\"middle\"]}"
                + ",\"click_count\":{\"type\":\"integer\"}"),
        tool("left_click_drag",
            "Press at from_target [x,y], interpolate to to [x,y], release.",
            prop("from_target", COORD) + ",\"to\":" + COORD
                + ",\"mouse_button\":{\"type\":\"string\",\"enum\":[\"left\",\"right\",\"middle\"]}"),
        tool("scroll",
            "Move pointer to target [x,y] and scroll. scroll_direction: up|down|left|right; scroll_amount: wheel notches.",
            prop("target", COORD)
                + ",\"scroll_direction\":{\"type\":\"string\",\"enum\":[\"up\",\"down\",\"left\",\"right\"]}"
                + ",\"scroll_amount\":{\"type\":\"integer\"}"),
        tool("type",
            "Type text into the focused control via clipboard paste (Unicode-safe).",
            prop("text", "{\"type\":\"string\"}")),
        tool("key",
            "Press a key or '+'-separated chord, e.g. \"Return\", \"Control_L+a\", \"super+c\", \"Up\". hold_seconds holds the chord down.",
            prop("text", "{\"type\":\"string\"}") + ",\"hold_seconds\":{\"type\":\"number\"}"),
        tool("paste",
            "Put text on the clipboard and press Ctrl+V.",
            prop("text", "{\"type\":\"string\"}")
                + ",\"format\":{\"type\":\"string\",\"enum\":[\"text\",\"md\",\"html\"]}"),
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
