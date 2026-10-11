package com.zcode.ideaplugin.cua;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 进程/窗口枚举（PowerShell + Win32 EnumWindows，一次性调用，结果缓存 5s）。
 *
 * 刻意不返回窗口矩形：PowerShell（Win32 system-DPI 空间）与 AWT（per-monitor
 * 逻辑空间）坐标系不同源（见 CuaRobot 头注）。MVP 只给 pid / 进程名 / 顶层窗口
 * 标题 / 是否前台。
 *
 * 为什么不走 Get-Process MainWindowTitle：MainWindowHandle 是"主窗口"启发式，
 * 多窗口进程（360 浏览器实测 3 个可见顶层窗口）可能整体解析不出带标题的主窗口，
 * 整个进程从 list_apps 消失；且前台进程恰好无主窗口标题时全表没有 active:true。
 */
final class CuaApps {

    static final class AppEntry {
        final int pid;
        final String name;
        final String title;
        final boolean active;
        /** 主窗口图标 PNG 字节（32×32，提取失败为 null——JSON 省略 icon 字段）。 */
        final byte[] iconPng;

        AppEntry(int pid, String name, String title, boolean active, byte[] iconPng) {
            this.pid = pid;
            this.name = name;
            this.title = title;
            this.active = active;
            this.iconPng = iconPng;
        }
    }

    private static final long CACHE_MS = 5000;
    private static volatile List<AppEntry> cache;
    private static volatile long cacheAt;

    private CuaApps() {
    }

    /**
     * C# 侧 EnumWindows 每可见顶层窗口出一行「pid\t是否前台\t标题」，PS 侧按 pid
     * 归并成一进程一条（标题取 Z 序最顶窗口，前台窗口覆盖标题并标 active）。
     * 过滤：不可见 / DWM cloaked（attr 14，挂起 UWP 的幽灵帧）/ 无标题 / 小于
     * 80x60（顺带排除最小化窗口，其 rect 落在 -32000 且高度不足）。
     * 每进程提取 exe 图标（.NET Icon.ExtractAssociatedIcon 托管实现转 32x32 PNG
     * base64；失败=null 不挡枚举主路径）。图标刻意不走 WM_GETICON/GCLP 原生 HICON：
     * Icon.FromHandle+ToBitmap 处理窗口类图标实测把 PowerShell 无异常硬崩（进程消失
     * 零输出），托管 ExtractAssociatedIcon 才稳。
     *
     * Add-Type 区约束：单引号字符串内禁单引号字符字面量（用 (char)NN 代）。
     */
    private static final String PS_SCRIPT = String.join("\n",
        "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8",
        "$OutputEncoding = [System.Text.Encoding]::UTF8",
        // -ReferencedAssemblies 必须显式带 System.Drawing：-AssemblyName 只加载会话，
        // 不进 Add-Type 的 C# 编译引用集（实测编译报「System.Drawing 中不存在 Icon」）
        "Add-Type -ReferencedAssemblies System.Drawing '",
        "using System;",
        "using System.Runtime.InteropServices;",
        "using System.Text;",
        "public static class W {",
        "  delegate bool EnumProc(IntPtr h, IntPtr l);",
        "  [DllImport(\"user32.dll\")] static extern bool EnumWindows(EnumProc cb, IntPtr l);",
        "  [DllImport(\"user32.dll\")] static extern bool IsWindowVisible(IntPtr h);",
        "  [DllImport(\"user32.dll\")] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);",
        "  [DllImport(\"user32.dll\")] static extern int GetWindowTextLength(IntPtr h);",
        "  [DllImport(\"user32.dll\")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);",
        "  [DllImport(\"user32.dll\")] static extern bool GetWindowRect(IntPtr h, out RECT r);",
        "  [DllImport(\"user32.dll\")] static extern IntPtr GetForegroundWindow();",
        "  [DllImport(\"dwmapi.dll\")] static extern int DwmGetWindowAttribute(IntPtr h, int attr, out int val, int size);",
        "  [DllImport(\"kernel32.dll\")] static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);",
        "  [DllImport(\"kernel32.dll\")] static extern bool QueryFullProcessImageName(IntPtr p, uint flags, StringBuilder name, ref uint size);",
        "  [DllImport(\"kernel32.dll\")] static extern bool CloseHandle(IntPtr h);",
        "  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L; public int T; public int R; public int B; }",
        "  public static string ExePath(uint pid) {",
        "    IntPtr p = OpenProcess(0x1000, false, pid);",
        "    if (p == IntPtr.Zero) return null;",
        "    try { StringBuilder sb = new StringBuilder(1024); uint size = 1024;",
        "      return QueryFullProcessImageName(p, 0, sb, ref size) ? sb.ToString() : null;",
        "    } finally { CloseHandle(p); }",
        "  }",
        "  public static string Enumerate() {",
        "    IntPtr fg = GetForegroundWindow();",
        "    StringBuilder sb = new StringBuilder();",
        "    EnumWindows(delegate(IntPtr h, IntPtr l) {",
        "      if (!IsWindowVisible(h)) return true;",
        "      int cloaked; DwmGetWindowAttribute(h, 14, out cloaked, 4);",
        "      if (cloaked != 0) return true;",
        "      int len = GetWindowTextLength(h);",
        "      if (len == 0) return true;",
        "      RECT r; GetWindowRect(h, out r);",
        "      if (r.R - r.L < 80 || r.B - r.T < 60) return true;",
        "      uint pid; GetWindowThreadProcessId(h, out pid);",
        "      StringBuilder t = new StringBuilder(len + 2); GetWindowText(h, t, t.Capacity);",
        "      if (sb.Length > 0) sb.Append((char)10);",
        "      sb.Append(pid).Append((char)9).Append(h == fg ? (char)49 : (char)48).Append((char)9).Append(t);",
        "      return true;",
        "    }, IntPtr.Zero);",
        "    return sb.ToString();",
        "  }",
        "}' | Out-Null",
        "$names = @{}",
        "Get-Process | ForEach-Object { $names[[int]$_.Id] = $_.ProcessName }",
        "$apps = @{}",
        "foreach ($row in @([W]::Enumerate() -split [char]10)) {",
        "  $p = @($row -split [char]9)",
        "  if ($p.Count -lt 3) { continue }",
        "  $wPid = [int]$p[0]; $isFg = [int]$p[1]; $title = ($p[2..($p.Count - 1)] -join [char]9)",
        "  if (-not $apps.ContainsKey($wPid)) {",
        "    $n = $names[$wPid]; if (-not $n) { $n = 'pid' + $wPid }",
        "    $apps[$wPid] = @{ pid = $wPid; name = $n; title = $title; active = ($isFg -eq 1) }",
        "  } elseif ($isFg -eq 1) {",
        "    $apps[$wPid].title = $title",
        "    $apps[$wPid].active = $true",
        "  }",
        "}",
        "$out = @()",
        "foreach ($key in @($apps.Keys)) {",
        "  $e = $apps[$key]",
        "  $icon = $null",
        "  try {",
        "    $exe = [W]::ExePath([uint32]$key)",
        "    if ($exe) {",
        "      $ic = [System.Drawing.Icon]::ExtractAssociatedIcon($exe)",
        "      if ($ic) {",
        "        $bmp = New-Object System.Drawing.Bitmap 32, 32",
        "        $g = [System.Drawing.Graphics]::FromImage($bmp)",
        "        $g.DrawImage($ic.ToBitmap(), 0, 0, 32, 32)",
        "        $g.Dispose()",
        "        $ms = New-Object System.IO.MemoryStream",
        "        $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)",
        "        $icon = [System.Convert]::ToBase64String($ms.ToArray())",
        "        $ms.Dispose(); $bmp.Dispose()",
        "      }",
        "    }",
        "  } catch { $icon = $null }",
        "  $out += [PSCustomObject]@{ pid = $e.pid; name = $e.name; title = $e.title; active = [bool]$e.active; icon = $icon }",
        "}",
        "if ($out.Count -eq 0) { '[]' } else { @($out) | ConvertTo-Json -Compress -Depth 2 }");

    /** 有可见顶层窗口的进程清单（5s 缓存；失败抛 CuaException）。 */
    static List<AppEntry> listApps() {
        long now = System.currentTimeMillis();
        List<AppEntry> cached = cache;
        if (cached != null && now - cacheAt < CACHE_MS) {
            return cached;
        }
        String out = runPowerShell(PS_SCRIPT, 15000);
        Object parsed;
        try {
            parsed = CuaMiniJson.parse(out);
        } catch (Exception e) {
            throw new CuaRobot.CuaException("INTERNAL", "process enumeration returned invalid JSON: " + e.getMessage());
        }
        List<Object> items = new ArrayList<>();
        if (parsed instanceof List) {
            items.addAll((List<?>) parsed);
        } else if (parsed instanceof Map) {
            items.add(parsed);
        }
        List<AppEntry> apps = new ArrayList<>(items.size());
        for (Object e : items) {
            if (!(e instanceof Map)) {
                continue;
            }
            Map<?, ?> m = (Map<?, ?>) e;
            Object pidV = m.get("pid");
            if (!(pidV instanceof Double)) {
                continue;
            }
            byte[] icon = null;
            Object iconV = m.get("icon");
            if (iconV instanceof String && !((String) iconV).isEmpty()) {
                try {
                    icon = Base64.getDecoder().decode((String) iconV);
                } catch (IllegalArgumentException ignore) {
                    // 图标坏了不挡枚举：icon 字段省略
                }
            }
            apps.add(new AppEntry(
                (int) Math.round((Double) pidV),
                String.valueOf(m.get("name")),
                String.valueOf(m.get("title")),
                Boolean.TRUE.equals(m.get("active")),
                icon));
        }
        cache = apps;
        cacheAt = now;
        return apps;
    }

    /** 按 app_ref 解析进程：{pid} 精确 &gt; {name}/{bundle_id} 大小写不敏感匹配。 */
    static AppEntry resolveApp(Map<?, ?> appRef, List<AppEntry> apps) {
        Object pidV = appRef.get("pid");
        if (pidV instanceof Double) {
            int pid = (int) Math.round((Double) pidV);
            for (AppEntry a : apps) {
                if (a.pid == pid) {
                    return a;
                }
            }
            return null;
        }
        String name = (String) appRef.get("name");
        String bundle = (String) appRef.get("bundle_id");
        String needle = name != null && !name.trim().isEmpty() ? name.trim().toLowerCase()
            : bundle != null && !bundle.trim().isEmpty() ? bundle.trim().toLowerCase() : null;
        if (needle == null) {
            return null;
        }
        AppEntry exact = null;
        AppEntry prefix = null;
        for (AppEntry a : apps) {
            String n = a.name.toLowerCase();
            if (n.equals(needle) && exact == null) {
                exact = a;
            }
            if (n.startsWith(needle) && prefix == null) {
                prefix = a;
            }
        }
        return exact != null ? exact : prefix;
    }

    private static String runPowerShell(String script, long timeoutMs) {
        Process proc;
        // -EncodedCommand（UTF-16LE base64）而非 -Command：-Command 走原生命令行
        // 再解析，开放代码里的双引号会被剥掉（实测 -split "`t" 变 -split `t 挂死），
        // 脚本越复杂越不可控；EncodedCommand 整段零接触直达。
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        try {
            proc = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .start();
        } catch (java.io.IOException e) {
            throw new CuaRobot.CuaException("INTERNAL", "cannot start powershell: " + e.getMessage());
        }
        StringBuilder stdout = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    stdout.append(line).append('\n');
                }
            } catch (Exception ignore) {
                // 进程被杀时流关闭：已读内容仍然有效
            }
        });
        reader.setDaemon(true);
        reader.start();
        try {
            if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly();
                throw new CuaRobot.CuaException("TIMEOUT", "powerShell enumeration timed out after " + timeoutMs + "ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CuaRobot.CuaException("INTERNAL", "powerShell enumeration interrupted");
        }
        if (proc.exitValue() != 0) {
            String err = drain(proc);
            throw new CuaRobot.CuaException("INTERNAL", "powerShell exited " + proc.exitValue() + ": " + truncate(err, 300));
        }
        try {
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return stdout.toString().trim();
    }

    private static String drain(Process proc) {
        try {
            byte[] b = proc.getErrorStream().readAllBytes();
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    static int selfPid() {
        return (int) ProcessHandle.current().pid();
    }

    // ───────────────────── 前台窗轻量查询（回执 active 行新鲜度） ─────────────────────

    static final class Foreground {
        final int pid;
        final String title;

        Foreground(int pid, String title) {
            this.pid = pid;
            this.title = title;
        }
    }

    private static final long FG_CACHE_MS = 1500;
    private static volatile Foreground fgCache;
    private static volatile long fgCacheAt;

    /**
     * 缓存仍新鲜（≤maxAgeMs）时返回缓存列表，否则返回 null（不触发 PS）——调用方
     * 据此决定是否起并行前台探测：枚举缓存的 active 行最长 5s 陈旧，动作切前台后
     * 回执报旧值（真机两次误导实证），轻量前台查询才是实时真相。
     */
    static List<AppEntry> peekApps(long maxAgeMs) {
        List<AppEntry> cached = cache;
        if (cached != null && System.currentTimeMillis() - cacheAt < maxAgeMs) {
            return cached;
        }
        return null;
    }

    /** GetForegroundWindow 轻量查询（一次 PS 往返 ~300ms，1.5s 自缓存）；失败返回 null。 */
    static Foreground foregroundQuick() {
        long now = System.currentTimeMillis();
        Foreground cached = fgCache;
        if (cached != null && now - fgCacheAt < FG_CACHE_MS) {
            return cached;
        }
        try {
            String out = runPowerShell(String.join("\n", WM_HEAD, "[WM]::Fg()"), 8000).trim();
            int tab = out.indexOf('\t');
            if (tab <= 0) {
                return null;
            }
            Foreground fg = new Foreground(
                Integer.parseInt(out.substring(0, tab).trim()), out.substring(tab + 1));
            fgCache = fg;
            fgCacheAt = now;
            return fg;
        } catch (Exception e) {
            return null;
        }
    }

    /** 后台起前台探测（调用方与 settle 并行后 join）；守护线程，不阻塞 stdio 事件循环。 */
    static java.util.concurrent.Future<Foreground> foregroundProbeAsync() {
        java.util.concurrent.FutureTask<Foreground> task =
            new java.util.concurrent.FutureTask<>(CuaApps::foregroundQuick);
        Thread worker = new Thread(task, "cua-fg-probe");
        worker.setDaemon(true);
        worker.start();
        return task;
    }

    // ───────────────────── 顶层窗口真枚举（list_windows） ─────────────────────

    static final class WinEntry {
        final long hwnd;
        final String title;
        final String cls;
        final int[] rect;      // 逻辑空间 {x,y,w,h}
        final boolean visible;
        final boolean iconic;

        WinEntry(long hwnd, String title, String cls, int[] rect, boolean visible, boolean iconic) {
            this.hwnd = hwnd;
            this.title = title;
            this.cls = cls;
            this.rect = rect;
            this.visible = visible;
            this.iconic = iconic;
        }
    }

    /**
     * 进程全部顶层窗口（含隐藏）：hide 型托盘应用失焦后主窗 IsWindowVisible=false，
     * 只按可见枚举会整体消失（list_apps 同理，历史局限）。0 尺寸纯消息辅助窗过滤。
     */
    static List<WinEntry> listWindowsOf(int pid) {
        String out = runPowerShell(
            String.join("\n", WM_HEAD,
                "$rows = [WM]::EnumAll([uint32]" + pid + ") -split [char]10",
                "$ratio = [WM]::GetSystemMetrics(0) / [double]" + CuaRobot.mainScreenLogicalWidth(),
                "if ($ratio -le 0) { $ratio = 1.0 }",
                "$out = @()",
                "foreach ($row in @($rows)) {",
                "  $f = @($row -split [char]9)",
                "  if ($f.Count -lt 5) { continue }",
                "  $rc = @($f[4] -split [char]44)",
                "  if ($rc.Count -lt 4) { continue }",
                "  $out += [PSCustomObject]@{ window_id = [long]$f[0]; visible = ($f[1] -eq [string][char]49);",
                "    cls = $f[2]; title = $f[3];",
                "    x = [int][math]::Round($rc[0] / $ratio); y = [int][math]::Round($rc[1] / $ratio);",
                "    w = [int][math]::Round(($rc[2] - $rc[0]) / $ratio); h = [int][math]::Round(($rc[3] - $rc[1]) / $ratio) }",
                "}",
                "if ($out.Count -eq 0) { '[]' } else { @($out) | ConvertTo-Json -Compress -Depth 2 }"), 15000);
        Object parsed;
        try {
            parsed = CuaMiniJson.parse(out);
        } catch (Exception e) {
            throw new CuaRobot.CuaException("INTERNAL",
                "window enumeration returned invalid JSON: " + e.getMessage());
        }
        List<Object> items = new ArrayList<>();
        if (parsed instanceof List) {
            items.addAll((List<?>) parsed);
        } else if (parsed instanceof Map) {
            items.add(parsed);
        }
        List<WinEntry> wins = new ArrayList<>();
        for (Object e : items) {
            if (!(e instanceof Map)) {
                continue;
            }
            Map<?, ?> m = (Map<?, ?>) e;
            Object idV = m.get("window_id");
            if (!(idV instanceof Double)) {
                continue;
            }
            int w = intOf(m.get("w"));
            int h = intOf(m.get("h"));
            wins.add(new WinEntry(Math.round((Double) idV),
                String.valueOf(m.get("title")), String.valueOf(m.get("cls")),
                new int[]{intOf(m.get("x")), intOf(m.get("y")), Math.max(0, w), Math.max(0, h)},
                Boolean.TRUE.equals(m.get("visible")), Boolean.TRUE.equals(m.get("iconic"))));
        }
        return wins;
    }

    // ───────────────────────── window_manage（Win32 语义原语） ─────────────────────────

    /** 窗口管理结果（rect 已换算回 AWT 逻辑空间）。 */
    static final class WindowInfo {
        final int pid;
        final String title;
        final int[] rect;      // 逻辑空间 {x,y,w,h}
        final boolean iconic;
        final boolean zoomed;
        boolean wasHidden;     // 动作前窗口不可见（focus/restore 已重新拉起）
        boolean applied = true;   // move/resize：读回与请求一致（±2px）
        boolean clamped;       // 尺寸被应用自身最小值钳大（读回>请求）
        int attempts = 1;      // move/resize 实际派发次数（applied:false 自动重试 1 次）
        String diag;           // 失败时的 DPI 自检值（sm/ratio），供下次排查
        int diagSm;            // 脚本内 GetSystemMetrics(0) 原值（物理/虚拟化随 awareness 摆动）

        WindowInfo(int pid, String title, int[] rect, boolean iconic, boolean zoomed) {
            this.pid = pid;
            this.title = title;
            this.rect = rect;
            this.iconic = iconic;
            this.zoomed = zoomed;
        }
    }

    /**
     * 进程名 → pid（Get-Process 全表，不要求可见窗口——最小化到任务栏的进程也能解析）。
     * 同名多进程（Store 应用双进程常见：一个真窗口 + 一个后台 broker）优先取有窗口的，
     * 全无窗口时退回第一个。
     */
    static int pidByNameLoose(String name) {
        String safe = name.replace("'", "");
        String out = runPowerShell(String.join("\n",
            WM_HEAD,
            "$found = -1",
            "Get-Process -Name '" + safe + "' -ErrorAction SilentlyContinue | ForEach-Object {",
            "  if ($found -lt 0) { $h = [WM]::BiggestOf([uint32]$_.Id, $true); if ($h -ne [IntPtr]::Zero) { $found = $_.Id } }",
            "}",
            "if ($found -lt 0) {",
            "  $p = Get-Process -Name '" + safe + "' -ErrorAction SilentlyContinue | Select-Object -First 1",
            "  if ($p) { $found = $p.Id }",
            "}",
            "$found"), 20000);
        try {
            return Integer.parseInt(out.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Win32 窗口管理直驱：SetWindowPos/ShowWindow/SetForegroundWindow——调窗口
     * 位置大小不再走「模拟人手拖边缘」（真机实测窄条命中不可靠，两连穿到后面的
     * IDE 触发重构对话框，见设计文档《电脑操作CUA工具优化-2026-10-10》）。
     *
     * 坐标系（真机 2026-10-10 实证，wmprobe）：powershell.exe 为 DPI-unaware
     * （GetSystemMetrics=物理÷主屏scale），其空间在主屏上与 AWT per-monitor 逻辑
     * 空间数值相等；不同 Windows/awareness 组合下不保证相等，故脚本内按
     * ratio = GetSystemMetrics(0) / awtW 闭环换算（awtW=CuaRobot#mainScreenLogicalWidth
     * 由 Java 传入），无论 PS 处于什么 awareness 都落对位置；rect 读回同比例还原成
     * AWT 逻辑。入参出参 x/y/w/h 一律 AWT 逻辑空间（与截图像素同源）。
     *
     * @param logicalRect move/resize 用全量 {x,y,w,h}（必须齐全），其余 action 传 null
     * @param windowId    ≥0 时直接定向该 hwnd（须属于 pid，脚本内校验），跳过 BiggestOf
     */
    static WindowInfo windowManage(int pid, String action, int[] logicalRect, long windowId) {
        int px = 0;
        int py = 0;
        int pw = 0;
        int ph = 0;
        if (action.equals("move") || action.equals("resize")) {
            if (logicalRect == null || logicalRect.length < 4
                || logicalRect[0] < 0 || logicalRect[1] < 0
                || logicalRect[2] <= 0 || logicalRect[3] <= 0) {
                throw new CuaRobot.CuaException("INTERNAL",
                    "move/resize need full x, y, width, height (query first, then apply)");
            }
            px = logicalRect[0];
            py = logicalRect[1];
            pw = logicalRect[2];
            ph = logicalRect[3];
        }
        String out = runPowerShell(
            wmScript(action, pid, px, py, pw, ph, CuaRobot.mainScreenLogicalWidth(), windowId), 15000);
        WindowInfo wi = parseWindowInfo(pid, out);
        if ((action.equals("move") || action.equals("resize")) && logicalRect != null) {
            // 回执可信度关键：SetWindowPos 返回 True 不代表生效——钉死型应用会同步
            // 钳回（WorkBuddy 实测），读回与请求比对是唯一判据。
            // applied:false 时短候重发一次：跨进程窗口操作存在应用侧瞬时拒绝
            // （焦点保护、动画中），每次重发是新 PS 进程重新尝试。
            // DPI 历史：WM_HEAD 末行强制 PER_MONITOR_AWARE_V2，坐标走物理域——
            // 该调用曾因 WM 类缺 DllImport 声明被 catch 静默吞掉，进程稳定 unaware，
            // 虚拟化坐标跨进程 SetWindowPos 到 per-monitor 窗口（explorer dpi=168）
            // 会被系统错误换算，窗口缩到 (0,0,161x243)——勿再删 DllImport。
            boolean applied = isWithin(wi.rect, logicalRect);
            int attempts = 1;
            while (!applied && attempts < 2) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                wi = parseWindowInfo(pid, runPowerShell(
                    wmScript(action, pid, px, py, pw, ph, CuaRobot.mainScreenLogicalWidth(), windowId), 15000));
                applied = isWithin(wi.rect, logicalRect);
                attempts++;
            }
            wi.attempts = attempts;
            wi.applied = applied;
            if (!wi.applied && wi.rect[2] > logicalRect[2] + 2 && wi.rect[3] > logicalRect[3] + 2) {
                wi.clamped = true;   // 被应用自身最小尺寸钳大
            }
            if (!wi.applied) {
                wi.diag = "sm=" + wi.diagSm + " awtW=" + CuaRobot.mainScreenLogicalWidth()
                    + " dispatched=" + px + "," + py + " " + pw + "x" + ph;
            }
        }
        return wi;
    }

    private static boolean isWithin(int[] actual, int[] requested) {
        int tol = 2;
        return Math.abs(actual[0] - requested[0]) <= tol
            && Math.abs(actual[1] - requested[1]) <= tol
            && Math.abs(actual[2] - requested[2]) <= tol
            && Math.abs(actual[3] - requested[3]) <= tol;
    }

    /**
     * 命中测试：逻辑点 → 该处顶层窗口（WindowFromPoint）。
     * left_click_drag 的 expect_window_pid 护栏用：拖拽按下前校验命中的窗口，
     * 防止坐标偏移点穿到后面的应用（真机两连穿教训）。
     * 返回 {pid(Integer), title(String)}；点在窗口外 pid=-1。
     */
    static Object[] hitWindowWithTitle(int logicalX, int logicalY) {
        String out = runPowerShell(hitScript(logicalX, logicalY, CuaRobot.mainScreenLogicalWidth()), 15000);
        Object parsed;
        try {
            parsed = CuaMiniJson.parse(out);
        } catch (Exception e) {
            throw new CuaRobot.CuaException("INTERNAL", "hit test returned invalid JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map)) {
            throw new CuaRobot.CuaException("INTERNAL", "hit test returned unexpected payload");
        }
        Map<?, ?> m = (Map<?, ?>) parsed;
        Object pidV = m.get("pid");
        int pid = pidV instanceof Double ? (int) Math.round((Double) pidV) : -1;
        String title = m.get("title") instanceof String ? (String) m.get("title") : "";
        return new Object[]{pid, title};
    }

    /** rect 已由脚本按 ratio 还原为 AWT 逻辑值，Java 侧只做形状收口。 */
    private static WindowInfo parseWindowInfo(int pid, String out) {
        Object parsed;
        try {
            parsed = CuaMiniJson.parse(out);
        } catch (Exception e) {
            throw new CuaRobot.CuaException("INTERNAL", "windowManage returned invalid JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map)) {
            throw new CuaRobot.CuaException("INTERNAL", "windowManage returned unexpected payload");
        }
        Map<?, ?> m = (Map<?, ?>) parsed;
        if (!Boolean.TRUE.equals(m.get("ok"))) {
            String err = m.get("error") instanceof String ? (String) m.get("error") : "unknown";
            throw new CuaRobot.CuaException("INTERNAL", "windowManage failed: " + err);
        }
        Map<?, ?> r = asMap(m.get("rect"));
        int l = intOf(r.get("l"));
        int t = intOf(r.get("t"));
        int ri = intOf(r.get("r"));
        int b = intOf(r.get("b"));
        int[] rect = new int[]{l, t, Math.max(1, ri - l), Math.max(1, b - t)};
        String title = m.get("title") instanceof String ? (String) m.get("title") : "";
        WindowInfo wi = new WindowInfo(pid, title, rect,
            Boolean.TRUE.equals(m.get("iconic")), Boolean.TRUE.equals(m.get("zoomed")));
        wi.wasHidden = Boolean.TRUE.equals(m.get("wasHidden"));
        wi.diagSm = intOf(m.get("sm"));
        return wi;
    }

    private static int intOf(Object v) {
        return v instanceof Double ? (int) Math.round((Double) v) : 0;
    }

    private static Map<?, ?> asMap(Object o) {
        return o instanceof Map ? (Map<?, ?>) o : Map.of();
    }

    /**
     * Add-Type 段约束同主枚举脚本（单引号串内禁单引号字符）；JSON 由 C# 侧手拼，
     * 标题经 Q() 转义。SetProcessDpiAwarenessContext 失败（Win10 1607 前）忽略，
     * scale 兜底靠 sm（GetSystemMetrics 物理分辨率）与 Java 侧 AWT 宽的比值。
     */
    private static final String WM_HEAD = String.join("\n",
        "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8",
        "$OutputEncoding = [System.Text.Encoding]::UTF8",
        "Add-Type '",
        "using System;",
        "using System.Runtime.InteropServices;",
        "using System.Text;",
        "public static class WM {",
        "  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L; public int T; public int R; public int B; }",
        "  [StructLayout(LayoutKind.Sequential)] public struct PT { public int X; public int Y; }",
        "  public delegate bool EnumProc(IntPtr h, IntPtr l);",
        "  [DllImport(\"user32.dll\")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);",
        "  [DllImport(\"user32.dll\")] public static extern bool IsWindowVisible(IntPtr h);",
        "  [DllImport(\"user32.dll\")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);",
        "  [DllImport(\"user32.dll\")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);",
        "  [DllImport(\"user32.dll\")] public static extern bool GetWindowRect(IntPtr h, out RECT r);",
        "  [DllImport(\"user32.dll\")] public static extern bool SetWindowPos(IntPtr h, IntPtr a, int x, int y, int w, int ht, uint f);",
        "  [DllImport(\"user32.dll\")] public static extern bool ShowWindow(IntPtr h, int cmd);",
        "  [DllImport(\"user32.dll\")] public static extern bool IsIconic(IntPtr h);",
        "  [DllImport(\"user32.dll\")] public static extern bool IsZoomed(IntPtr h);",
        "  [DllImport(\"user32.dll\")] public static extern bool SetForegroundWindow(IntPtr h);",
        "  [DllImport(\"user32.dll\")] public static extern IntPtr WindowFromPoint(PT p);",
        "  [DllImport(\"user32.dll\")] public static extern void SwitchToThisWindow(IntPtr h, bool alt);",
        "  [DllImport(\"user32.dll\")] public static extern int GetSystemMetrics(int n);",
        "  [DllImport(\"user32.dll\")] public static extern IntPtr GetAncestor(IntPtr h, uint flags);",
        "  [DllImport(\"dwmapi.dll\")] public static extern int DwmGetWindowAttribute(IntPtr h, int a, out int v, int s);",
        "  [DllImport(\"user32.dll\")] public static extern int GetClassName(IntPtr h, StringBuilder s, int n);",
        "  [DllImport(\"user32.dll\")] public static extern IntPtr GetForegroundWindow();",
        "  [DllImport(\"user32.dll\")] public static extern bool SetProcessDpiAwarenessContext(IntPtr v);",
        "  public static IntPtr RootOf(IntPtr h) {",
        "    IntPtr r = GetAncestor(h, 2);",
        "    return r == IntPtr.Zero ? h : r;",
        "  }",
        "  public static string Q(string s) {",
        "    return s == null ? \"\" : s.Replace(\"\\\\\", \"\\\\\\\\\").Replace(\"\\\"\", \"\\\\\\\"\");",
        "  }",
        "  public static string RectStr(IntPtr h) {",
        "    RECT r; GetWindowRect(h, out r);",
        "    return r.L + \",\" + r.T + \",\" + r.R + \",\" + r.B;",
        "  }",
        "  public static IntPtr BiggestOf(uint pid, bool any) {",
        "    IntPtr best = IntPtr.Zero; long bestArea = -1;",
        "    EnumWindows(delegate(IntPtr h, IntPtr l) {",
        "      if (!IsWindowVisible(h)) return true;",
        "      if (!any) { int cl; DwmGetWindowAttribute(h, 14, out cl, 4); if (cl != 0) return true; }",
        "      uint p; GetWindowThreadProcessId(h, out p);",
        "      if (p != pid) return true;",
        "      RECT r; GetWindowRect(h, out r);",
        "      if (!any && (r.R - r.L < 80 || r.B - r.T < 60)) return true;",
        "      long area = (long)(r.R - r.L) * (r.B - r.T);",
        "      if (area > bestArea) { bestArea = area; best = h; }",
        "      return true;",
        "    }, IntPtr.Zero);",
        "    return best;",
        "  }",
        "  public static IntPtr BiggestOfTitled(uint pid) {",
        "    IntPtr best = IntPtr.Zero; long bestArea = -1;",
        "    EnumWindows(delegate(IntPtr h, IntPtr l) {",
        "      uint p; GetWindowThreadProcessId(h, out p);",
        "      if (p != pid) return true;",
        "      int cl; DwmGetWindowAttribute(h, 14, out cl, 4);",
        "      if (cl != 0) return true;",
        "      StringBuilder t = new StringBuilder(256); GetWindowText(h, t, 256);",
        "      if (t.Length == 0) return true;",
        "      RECT r; GetWindowRect(h, out r);",
        "      long area = (long)(r.R - r.L) * (r.B - r.T);",
        "      if (area > bestArea) { bestArea = area; best = h; }",
        "      return true;",
        "    }, IntPtr.Zero);",
        "    return best;",
        "  }",
        "  public static string Fg() {",
        "    IntPtr h = GetForegroundWindow();",
        "    if (h == IntPtr.Zero) return \"0\\t\";",
        "    uint p = 0; GetWindowThreadProcessId(h, out p);",
        "    StringBuilder t = new StringBuilder(512); GetWindowText(h, t, 512);",
        "    return p + \"\\t\" + t;",
        "  }",
        "  public static string EnumAll(uint pid) {",
        "    StringBuilder sb = new StringBuilder();",
        "    EnumWindows(delegate(IntPtr h, IntPtr l) {",
        "      uint p; GetWindowThreadProcessId(h, out p);",
        "      if (p != pid) return true;",
        "      int cl; DwmGetWindowAttribute(h, 14, out cl, 4);",
        "      if (cl != 0) return true;",
        "      bool vis = IsWindowVisible(h);",
        "      RECT r; GetWindowRect(h, out r);",
        "      long w = r.R - (long)r.L; long ht = r.B - (long)r.T;",
        "      if (!vis && (w < 2 || ht < 2)) return true;",
        "      if (sb.Length > 0) sb.Append((char)10);",
        "      StringBuilder t = new StringBuilder(256); GetWindowText(h, t, 256);",
        "      StringBuilder c = new StringBuilder(256); GetClassName(h, c, 256);",
        "      sb.Append(h.ToInt64()).Append((char)9).Append(vis ? (char)49 : (char)48)",
        "        .Append((char)9).Append(c).Append((char)9).Append(t)",
        "        .Append((char)9).Append(r.L).Append((char)44).Append(r.T).Append((char)44).Append(r.R).Append((char)44).Append(r.B);",
        "      return true;",
        "    }, IntPtr.Zero);",
        "    return sb.ToString();",
        "  }",
        "}' | Out-Null",
        "try { [void][WM]::SetProcessDpiAwarenessContext([IntPtr](-4)) } catch { }");

    private static String wmScript(String action, int pid, int px, int py, int pw, int ph, int awtW, long windowId) {
        return String.join("\n", WM_HEAD,
            "$wpid = " + pid,
            "$awtW = " + awtW,
            // $px/$py/$pw/$ph 必须显式注入：缺了就是 $null，Round($null*ratio)=0，
            // SetWindowPos 实际派发 (0,0,0,0)——窗口被钳到最小尺寸（explorer 实测
            // 缩到 (0,0,161x243) 逻辑），且 SetWindowPos 返回 True、与 DPI 无关。
            "$px = " + px,
            "$py = " + py,
            "$pw = " + pw,
            "$ph = " + ph,
            "$ratio = [WM]::GetSystemMetrics(0) / [double]$awtW",
            "if ($ratio -le 0) { $ratio = 1.0 }",
            "$action = '" + action + "'",
            "$wid = " + windowId,
            "$allowHidden = ($action -eq 'focus') -or ($action -eq 'restore') -or ($action -eq 'query')",
            "$h = [IntPtr]::Zero",
            "$wasHidden = $false",
            "if ($wid -ge 0) {",
            "  $h = [IntPtr]$wid",
            "  $owner = [uint32]0",
            "  [void][WM]::GetWindowThreadProcessId($h, [ref]$owner)",
            "  if ($owner -ne $wpid) { '{\"ok\":false,\"error\":\"window_id does not belong to this pid\"}'; exit }",
            "} else {",
            "  $any = ($action -eq 'focus') -or ($action -eq 'restore') -or ($action -eq 'minimize')",
            "  $h = [WM]::BiggestOf([uint32]$wpid, $any)",
            "  if ($h -eq [IntPtr]::Zero -and $allowHidden) {",
            "    $h = [WM]::BiggestOfTitled([uint32]$wpid)",
            "    if ($h -ne [IntPtr]::Zero) { $wasHidden = $true }",
            "  }",
            "}",
            "if ($h -eq [IntPtr]::Zero) { '{\"ok\":false,\"error\":\"no visible top-level window for pid (it may be hidden; try action:focus)\"}'; exit }",
            "$h = [WM]::RootOf($h)",
            "$vis = [WM]::IsWindowVisible($h)",
            "if ((-not $vis) -and ($action -eq 'move' -or $action -eq 'resize' -or $action -eq 'minimize')) { '{\"ok\":false,\"error\":\"window is hidden (tray-style app); focus it first via action:focus\"}'; exit }",
            "$moveIt = $false",
            "switch ($action) {",
            "  'move' { $moveIt = $true }",
            "  'resize' { $moveIt = $true }",
            "  'focus' { if (-not $vis) { [void][WM]::ShowWindow($h, 5) }; if ([WM]::IsIconic($h)) { [void][WM]::ShowWindow($h, 9) }; if (-not [WM]::SetForegroundWindow($h)) { [void][WM]::SwitchToThisWindow($h, $true) } }",
            "  'minimize' { [void][WM]::ShowWindow($h, 6) }",
            "  'maximize' { [void][WM]::ShowWindow($h, 3) }",
            "  'restore' { [void][WM]::ShowWindow($h, 9) }",
            "  default { }",
            "}",
            "if ($moveIt) {",
            "  $mx = [int][math]::Round($px * $ratio); $my = [int][math]::Round($py * $ratio); $mw = [int][math]::Round($pw * $ratio); $mh = [int][math]::Round($ph * $ratio)",
            "  if (-not [WM]::SetWindowPos($h, [IntPtr]::Zero, $mx, $my, $mw, $mh, 0x4)) { '{\"ok\":false,\"error\":\"SetWindowPos failed\"}'; exit }",
            "}",
            "$p2 = [WM]::RectStr($h).Split([char]44)",
            "$rl = [int][math]::Round($p2[0] / $ratio)",
            "$rt = [int][math]::Round($p2[1] / $ratio)",
            "$rr = [int][math]::Round($p2[2] / $ratio)",
            "$rb = [int][math]::Round($p2[3] / $ratio)",
            "$sb = New-Object System.Text.StringBuilder 512",
            "[void][WM]::GetWindowText($h, $sb, 512)",
            "$t = $sb.ToString()",
            "$wh = $wasHidden -or (-not [WM]::IsWindowVisible($h))",
            "$whs = 'false'; if ($wh) { $whs = 'true' }",
            "('{\"ok\":true,\"pid\":' + $wpid + ',\"title\":\"' + [WM]::Q($t) + '\",\"rect\":{\"l\":' + $rl + ',\"t\":' + $rt + ',\"r\":' + $rr + ',\"b\":' + $rb + '},\"iconic\":' + ([WM]::IsIconic($h).ToString().ToLower()) + ',\"zoomed\":' + ([WM]::IsZoomed($h).ToString().ToLower()) + ',\"wasHidden\":' + $whs + ',\"sm\":' + [WM]::GetSystemMetrics(0) + ',\"ratio\":' + [math]::Round($ratio, 4) + '}')");
    }

    private static String hitScript(int lx, int ly, int awtW) {
        return String.join("\n", WM_HEAD,
            "$awtW = " + awtW,
            "$ratio = [WM]::GetSystemMetrics(0) / [double]$awtW",
            "if ($ratio -le 0) { $ratio = 1.0 }",
            "$p = New-Object WM+PT",
            "$p.X = [int][math]::Round(" + lx + " * $ratio)",
            "$p.Y = [int][math]::Round(" + ly + " * $ratio)",
            "$h = [WM]::WindowFromPoint($p)",
            "if ($h -eq [IntPtr]::Zero) { '{\"pid\":-1,\"title\":\"\"}'; exit }",
            "$pid2 = [uint32]0",
            "[void][WM]::GetWindowThreadProcessId($h, [ref]$pid2)",
            "$sb = New-Object System.Text.StringBuilder 512",
            "[void][WM]::GetWindowText($h, $sb, 512)",
            "('{\"pid\":' + $pid2 + ',\"title\":\"' + [WM]::Q($sb.ToString()) + '\"}')");
    }
}
