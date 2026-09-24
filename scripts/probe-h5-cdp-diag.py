#!/usr/bin/env python3
"""M2 诊断：CDP 直连宿主 IAB 的 H5 页面（zcode.z.ai/remote/v4），
注入 console/错误拦截后刷新页面，收集 H5 真实运行日志——定位手机端
不发首个 channel 调用的 UI 启动 gate。

用法：python probe-h5-cdp-diag.py [等待秒数=15]
依赖：宿主 IDE IAB 的 CDP 端口（DevToolsActivePort，插件 browser-use 自检同款）。
"""
import asyncio
import json
import sys
import urllib.request

import websockets

PORT_FILE = r"C:/Users/Administrator/AppData/Local/JetBrains/IntelliJIdea2026.1/jcef_cache/DevToolsActivePort"


async def main():
    wait_s = int(sys.argv[1]) if len(sys.argv) > 1 else 15
    port = open(PORT_FILE).read().splitlines()[0].strip()
    targets = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/json/list", timeout=3))
    # 最新的 Web Remote Control 页面
    h5 = [t for t in targets if t.get("type") == "page" and "remote/v4" in (t.get("url") or "")]
    if not h5:
        print("未找到 H5 页面 target")
        return
    target = h5[-1]
    ws_url = target["webSocketDebuggerUrl"]
    print(f"H5 target: {target['url'][:110]}")

    async with websockets.connect(ws_url, max_size=8 * 1024 * 1024) as ws:
        mid = 0

        async def cmd(method, **params):
            nonlocal mid
            mid += 1
            await ws.send(json.dumps({"id": mid, "method": method, "params": params}))
            while True:
                msg = json.loads(await ws.recv())
                if msg.get("id") == mid:
                    return msg.get("result", msg.get("error"))

        await cmd("Runtime.enable")
        await cmd("Page.enable")

        # 注入日志拦截（普通函数包裹，evaluate 完成即生效，reload 前装好）
        await cmd(
            "Page.addScriptToEvaluateOnNewDocument",
            source="""
window.__diag = [];
const wrap = (orig, level) => function(...a) {
  try {
    window.__diag.push(level + ' ' + a.map(x => {
      try { return typeof x === 'string' ? x : JSON.stringify(x); } catch { return String(x); }
    }).join(' ').slice(0, 400));
  } catch {}
  return orig.apply(this, a);
};
console.log = wrap(console.log.bind(console), 'LOG');
console.info = wrap(console.info.bind(console), 'INFO');
console.warn = wrap(console.warn.bind(console), 'WARN');
console.error = wrap(console.error.bind(console), 'ERR');
window.addEventListener('error', e => window.__diag.push('UNCAUGHT ' + (e.message || '') + ' @' + (e.filename || '') + ':' + e.lineno));
window.addEventListener('unhandledrejection', e => window.__diag.push('REJECT ' + String(e.reason).slice(0, 300)));
""",
        )
        await cmd("Page.reload")
        await asyncio.sleep(wait_s)

        result = await cmd(
            "Runtime.evaluate",
            expression="window.__diag ? window.__diag.slice(-160).join('\\n') : '(no diag)'",
            returnByValue=True,
        )
        logs = result.get("result", {}).get("value", "(evaluate failed)")
        print("=== H5 日志（尾部 160 条）===")
        print(logs)


asyncio.run(main())
