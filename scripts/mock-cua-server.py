"""最小 stdio MCP server（diag 注入链路验证用）。

协议子集：initialize / notifications/initialized / tools/list / tools/call。
提供一个 echo 工具，用于验证 session/create 注入 → spawn → 工具面暴露全链路。
"""
import json
import os
import sys


def send(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


TOOLS = [
    {
        "name": "echo",
        "description": "Echo back the input text (diag mock).",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    }
]


def main():
    env_mark = os.environ.get("ZCUA_DIAG", "")
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            m = json.loads(line)
        except Exception:
            continue
        method, mid = m.get("method"), m.get("id")
        if method == "initialize":
            send({"jsonrpc": "2.0", "id": mid, "result": {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "zcgui-mock-cua", "version": "0.0.1"},
            }})
        elif method == "tools/list":
            send({"jsonrpc": "2.0", "id": mid, "result": {"tools": TOOLS}})
        elif method == "tools/call":
            name = (m.get("params") or {}).get("name")
            args = (m.get("params") or {}).get("arguments") or {}
            text = json.dumps({"tool": name, "args": args, "envMark": env_mark},
                              ensure_ascii=False)
            send({"jsonrpc": "2.0", "id": mid, "result": {
                "content": [{"type": "text", "text": text}], "isError": False}})
        elif mid is not None:
            send({"jsonrpc": "2.0", "id": mid, "error": {
                "code": -32601, "message": f"mock 未实现: {method}"}})


if __name__ == "__main__":
    main()
