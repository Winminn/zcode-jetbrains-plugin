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
}
