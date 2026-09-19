// 数组 params 帧回归夹具（2026-09-19 卡死事故）：模拟新版 CLI app-server 的
// process/mcpResourceSamples 推送——params 为 JSON 数组，0.3.6 的 dispatchMessage
// 用 ?.jsonObject 强转抛 IllegalArgumentException 杀死 reader 线程，之后所有请求
// 超时（新会话也建不了）。本脚本启动即推杀手帧，并在第二次 session/list 前再推
// 一帧，验证运行中收到杀手帧后 reader 仍继续处理后续帧。
if (process.argv[2] === "--version") { console.log("0.16.5"); process.exit(0); }

const killerFrame = JSON.stringify({
  method: "process/mcpResourceSamples",
  params: [{ mcpId: "mcp-a", rssKb: 12345 }, { mcpId: "mcp-b", rssKb: 67890 }],
});

let listCount = 0;
let buf = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (d) => {
  buf += d;
  let i;
  while ((i = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, i);
    buf = buf.slice(i + 1);
    if (!line.trim()) continue;
    let m;
    try { m = JSON.parse(line); } catch { continue; }
    if (m && m.method === "session/list") {
      listCount += 1;
      if (listCount === 2) process.stdout.write(killerFrame + "\n"); // 运行中途再来一帧
      process.stdout.write(JSON.stringify({ id: m.id, result: { sessions: [] } }) + "\n");
    }
  }
});
// 启动即推：杀手帧（数组 params）+ 一帧正常对象 params 通知
process.stdout.write(killerFrame + "\n");
process.stdout.write(JSON.stringify({ method: "process/resourceSample", params: { role: "agent_node" } }) + "\n");
