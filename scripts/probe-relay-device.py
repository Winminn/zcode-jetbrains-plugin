#!/usr/bin/env python3
"""手机远程会话探针：以 device 角色直连云端 relay，注册设备→认证→生成配对 QR→抓手机端全部帧。

协议逆向自 app.asar (WebRemoteControlDeviceTransport / createNodeWebRemoteControlRelayAuthProvider)
与 zcode.cjs (rpc-frame wire 协议)，参数名逆向自 zcode.z.ai/remote/v4 H5 bundle (src-Cp2yGfcd.js gE)。

用法：
  python probe-relay-device.py                 # 注册新设备，打印 QR，抓帧 10 分钟
  python probe-relay-device.py --duration 600  # 自定义时长（秒）
  python probe-relay-device.py --reuse         # 复用已保存凭据（重连同一 deviceSid）

产物：
  docs/internal/probe/relay-credentials.local.json   凭据（敏感，目录已 gitignore）
  docs/internal/probe/relay-frames-<时间戳>.jsonl    全部帧日志（含分片重组后的内层消息）
"""
import argparse
import asyncio
import base64
import hashlib
import hmac
import json
import os
import sys
import time
import uuid
import zlib

import websockets

ORIGIN = "https://zcode.z.ai"
RELAY_WS = "wss://zcode.z.ai/ws"
REMOTE_URL = f"{ORIGIN}/remote/v4"
APP_VERSION = "3.8.1"  # 官方客户端当前版本（ZCode.exe 3.8.1.5310，>=3.4.0 走 v4）

HERE = os.path.dirname(os.path.abspath(__file__))
PROBE_DIR = os.path.normpath(os.path.join(HERE, "..", "docs", "internal", "probe"))
CRED_FILE = os.path.join(PROBE_DIR, "relay-credentials.local.json")


def b64url_nopad(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


# ---- 凭据算法（app.asar createNodeWebRemoteControlRelayAuthProvider）----
def create_password() -> str:
    return b64url_nopad(os.urandom(24))


def create_pass_hash(password: str) -> str:
    # Node: sha256(password).digest("base64") —— 标准 base64 带 padding
    return base64.b64encode(hashlib.sha256(password.encode()).digest()).decode()


def calculate_proof(pass_hash: str, nonce: str, role: str, device_sid: str) -> str:
    # Node: hmac_sha256(key=passHash, msg=`${nonce}|${role}|${deviceSid}`).digest("base64url")
    msg = f"{nonce}|{role}|{device_sid}".encode()
    return b64url_nopad(hmac.new(pass_hash.encode(), msg, hashlib.sha256).digest())


# ---- rpc-frame 分片重组（zcode.cjs wire 协议）----
class FrameAssembler:
    """按 (bridgeSessionId, messageSeq) 收集 fragment，齐片后拼接。

    返回 dict：内层为 JSON（{"_json": ...}）或二进制 channel 帧（{"_raw": bytes}）。
    """

    def __init__(self, log):
        self.pending = {}  # key -> {count, messageBytes, parts: {index: bytes}}
        self.log = log

    def accept(self, payload: dict):
        if payload.get("zcode_type") != "rpc-frame":
            return None
        key = (payload.get("bridgeSessionId"), payload.get("messageSeq"))
        entry = self.pending.setdefault(
            key, {"count": payload["fragmentCount"], "messageBytes": payload["messageBytes"], "parts": {}}
        )
        raw = base64.b64decode(payload["dataBase64"])
        entry["parts"][payload["fragmentIndex"]] = raw
        if len(entry["parts"]) < entry["count"]:
            return None
        del self.pending[key]
        data = b"".join(entry["parts"][i] for i in range(entry["count"]))
        crc = payload.get("checksum", {})
        if crc.get("algorithm") == "crc32":
            actual = format(zlib.crc32(data) & 0xFFFFFFFF, "08x")
            if actual != crc.get("value"):
                self.log("warn", f"crc32 mismatch: expect={crc.get('value')} actual={actual}")
        try:
            return {"_json": json.loads(data.decode("utf-8"))}
        except Exception:
            return {"_raw": data}


# ---- VSCode IPC channel 协议（H5 Zbe 类逆向：枚举偏移 100/200，VSCode 序列化）----
CH_REQUEST = {"Promise": 100, "PromiseCancel": 101, "EventListen": 102, "EventDispose": 103}
CH_RESPONSE = {"Initialize": 200, "PromiseSuccess": 201, "PromiseError": 202, "PromiseErrorObj": 203, "EventFire": 204}
CH_TYPE = {"Undefined": 0, "String": 1, "Buffer": 2, "VSBuffer": 3, "Array": 4, "Object": 5, "Int": 6}


def vlq_write(value: int) -> bytes:
    if value == 0:
        return b"\x00"
    out = bytearray()
    while value:
        b = value & 0x7F
        value >>= 7
        if value:
            b |= 0x80
        out.append(b)
    return bytes(out)


def ch_serialize(value) -> bytes:
    """H5 Nm() 等价：类型标记 + VLQ 长度 + payload。"""
    if value is None:
        return bytes([CH_TYPE["Undefined"]])
    if isinstance(value, str):
        raw = value.encode("utf-8")
        return bytes([CH_TYPE["String"]]) + vlq_write(len(raw)) + raw
    if isinstance(value, bool):
        raw = json.dumps(value).encode()
        return bytes([CH_TYPE["Object"]]) + vlq_write(len(raw)) + raw
    if isinstance(value, int):
        return bytes([CH_TYPE["Int"]]) + vlq_write(value)
    if isinstance(value, list):
        out = bytes([CH_TYPE["Array"]]) + vlq_write(len(value))
        for item in value:
            out += ch_serialize(item)
        return out
    raw = json.dumps(value, ensure_ascii=False).encode("utf-8")
    return bytes([CH_TYPE["Object"]]) + vlq_write(len(raw)) + raw


def vlq_read(buf: bytes, pos: int):
    result, shift = 0, 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, pos
        shift += 7


def ch_deserialize(buf: bytes, pos: int = 0):
    """H5 Pm() 等价，返回 (value, newPos)。"""
    t = buf[pos]
    pos += 1
    if t == CH_TYPE["Undefined"]:
        return None, pos
    if t in (CH_TYPE["String"], CH_TYPE["Object"]):
        n, pos = vlq_read(buf, pos)
        raw = buf[pos:pos + n]
        pos += n
        if t == CH_TYPE["String"]:
            return raw.decode("utf-8"), pos
        return json.loads(raw.decode("utf-8")), pos
    if t in (CH_TYPE["Buffer"], CH_TYPE["VSBuffer"]):
        n, pos = vlq_read(buf, pos)
        pos += n
        return f"<{n} bytes>", pos
    if t == CH_TYPE["Array"]:
        n, pos = vlq_read(buf, pos)
        items = []
        for _ in range(n):
            v, pos = ch_deserialize(buf, pos)
            items.append(v)
        return items, pos
    if t == CH_TYPE["Int"]:
        v, pos = vlq_read(buf, pos)
        return v, pos
    raise ValueError(f"unknown type tag {t} at {pos - 1}")


def ch_parse_request(buf: bytes):
    """解析 [reqType, id, channel, method] + args。"""
    head, pos = ch_deserialize(buf, 0)
    args = None
    try:
        args, _ = ch_deserialize(buf, pos)
    except Exception:
        pass
    return head, args



class Probe:
    def __init__(self, args):
        self.args = args
        self.device_mid = uuid.uuid4().hex
        self.device_name = args.name
        self.meta = {"platform": "win32", "version": APP_VERSION, "name": self.device_name}
        self.frame_log = None
        self.assembler = None
        self.state = "idle"
        self.device_sid = None
        self.pass_hash = None
        self.ws = None
        self.acked = set()
        self.rx_count = 0
        self.payload_types = {}  # zcode_type -> count（手机端帧清单统计）
        self.channel_calls = {}  # (channel, method) -> count（channel RPC 清单）
        self.out_seq = 0
        self.out_message_seq = 0
        self.active_bridge = None  # 手机最近的 bridgeSessionId

    # ---- 日志 ----
    def log(self, level, msg):
        line = f"[{time.strftime('%H:%M:%S')}] [{level}] {msg}"
        print(line, flush=True)

    def log_frame(self, direction, msg, inner=None):
        self.rx_count += 1 if direction == "recv" else 0
        rec = {"ts": time.time(), "dir": direction, "msg": msg}
        if inner is not None:
            rec["inner"] = inner
        self.frame_log.write(json.dumps(rec, ensure_ascii=False) + "\n")
        self.frame_log.flush()

    # ---- 发送 ----
    async def send(self, obj):
        self.log_frame("send", obj)
        await self.ws.send(json.dumps(obj, ensure_ascii=False))

    async def send_data_payload(self, payload):
        await self.send({"type": "data", "payload": payload, "client_ts": int(time.time() * 1000)})

    async def send_rpc_frame(self, inner: bytes, bridge_session_id: str):
        """rpc-frame 单片发送（发送侧分片器逆向自 H5 iun 类）。"""
        self.out_seq += 1
        self.out_message_seq += 1
        await self.send_data_payload({
            "zcode_type": "rpc-frame",
            "bridgeSessionId": bridge_session_id,
            "seq": self.out_seq,
            "messageSeq": self.out_message_seq,
            "fragmentIndex": 0,
            "fragmentCount": 1,
            "messageBytes": len(inner),
            "checksum": {"algorithm": "crc32", "value": format(zlib.crc32(inner) & 0xFFFFFFFF, "08x")},
            "dataBase64": base64.b64encode(inner).decode(),
        })

    # ---- 主流程 ----
    async def run(self):
        os.makedirs(PROBE_DIR, exist_ok=True)
        cred = self.load_or_create_credentials()
        self.device_sid, self.pass_hash = cred["deviceSid"], cred["passHash"]

        log_path = os.path.join(PROBE_DIR, f"relay-frames-{time.strftime('%Y%m%d-%H%M%S')}.jsonl")
        self.frame_log = open(log_path, "w", encoding="utf-8")
        self.assembler = FrameAssembler(self.log)
        self.log("info", f"帧日志: {log_path}")
        self.log("info", f"deviceSid={self.device_sid or '(register 分配中)'} deviceMid={self.device_mid}")

        url = f"{RELAY_WS}?mid={self.device_mid}"
        self.log("info", f"连接 {url} (header X-Device-ID)")
        async with websockets.connect(
            url,
            additional_headers={"X-Device-ID": self.device_mid},
            max_size=2 * 1024 * 1024,  # maxPhysicalFrameBytes=1MB，留余量
        ) as ws:
            self.ws = ws
            if self.device_sid:
                await self.send_auth_init()
            else:
                self.state = "registering"
                await self.send({
                    "type": "device_register_init",
                    "device_mid": self.device_mid,
                    "pass_hash": self.pass_hash,
                    "meta": self.meta,
                    "client_ts": int(time.time() * 1000),
                })
            try:
                await asyncio.wait_for(self.pump(), timeout=self.args.duration)
            except asyncio.TimeoutError:
                self.log("info", f"到达 --duration={self.args.duration}s，正常收尾")

    def load_or_create_credentials(self):
        if self.args.reuse and os.path.exists(CRED_FILE):
            with open(CRED_FILE, "r", encoding="utf-8") as f:
                cred = json.load(f)
            self.device_mid = cred.get("deviceMid", self.device_mid)
            self.log("info", f"复用已存凭据 deviceSid={cred['deviceSid']}")
            return cred
        password = create_password()
        cred = {
            "deviceMid": self.device_mid,
            "deviceSid": None,  # register_ack 后回填
            "passHash": create_pass_hash(password),
            "password": password,  # 仅存档对照，协议只用 passHash
        }
        with open(CRED_FILE, "w", encoding="utf-8") as f:
            json.dump(cred, f, ensure_ascii=False, indent=2)
        return cred

    async def save_credentials(self):
        with open(CRED_FILE, "r+", encoding="utf-8") as f:
            cred = json.load(f)
            cred["deviceSid"] = self.device_sid
            f.seek(0)
            json.dump(cred, f, ensure_ascii=False, indent=2)
            f.truncate()

    async def send_auth_init(self):
        self.state = "authenticating"
        await self.send({
            "type": "auth_init",
            "role": "device",
            "device_sid": self.device_sid,
            "meta": self.meta,
            "client_ts": int(time.time() * 1000),
        })

    async def pump(self):
        heartbeat = asyncio.create_task(self.heartbeat_loop())
        try:
            while True:
                raw = await self.ws.recv()
                await self.handle_raw(raw)
        except (websockets.ConnectionClosed, asyncio.TimeoutError):
            pass
        finally:
            heartbeat.cancel()
            self.summarize()

    async def heartbeat_loop(self):
        try:
            while True:
                await asyncio.sleep(10)
                if self.device_sid and self.state in ("paired", "waiting_terminal"):
                    try:
                        await self.send({
                            "type": "pair_status_query",
                            "device_sid": self.device_sid,
                            "client_ts": int(time.time() * 1000),
                        })
                    except websockets.ConnectionClosed:
                        return
        except asyncio.CancelledError:
            pass

    async def handle_raw(self, raw):
        try:
            msg = json.loads(raw)
        except Exception:
            self.log("warn", f"非 JSON 帧: {raw[:200]!r}")
            return
        t = msg.get("type")
        if t == "device_register_ack":
            self.device_sid = msg["device_sid"]
            self.log("info", f"✅ 注册成功 device_sid={self.device_sid}")
            await self.save_credentials()
            await self.send_auth_init()
        elif t == "auth_challenge":
            nonce = msg["nonce"]
            proof = calculate_proof(self.pass_hash, nonce, "device", self.device_sid)
            self.log("info", f"auth_challenge nonce={nonce[:16]}... → 回 proof")
            await self.send({
                "type": "auth_response",
                "device_sid": self.device_sid,
                "proof": proof,
                "client_ts": int(time.time() * 1000),
            })
        elif t in ("auth_ack", "pair_status_ack"):
            status = msg.get("pair_status")
            self.log("info", f"{t} pair_status={status}")
            await self.on_pair_status(status)
        elif t == "data":
            await self.handle_data(msg.get("payload"))
        elif t == "error":
            self.log("error", f"relay error code={msg.get('code')} message={msg.get('message')}")
        else:
            self.log("info", f"其他帧 type={t}: {json.dumps(msg, ensure_ascii=False)[:300]}")
        self.log_frame("recv", msg)

    async def on_pair_status(self, status):
        if status == "waiting":
            first = self.state != "waiting_terminal"
            self.state = "waiting_terminal"
            if first:
                self.print_qr()
            else:
                self.log("info", f"心跳确认 waiting（QR URL 不变，t 参数仅时间戳）")
        elif status == "matched":
            first = self.state != "paired"
            self.state = "paired"
            if first:
                self.log("info", "🎉 手机已配对（matched）—— 开始记录手机端全部帧")

    def print_qr(self):
        from urllib.parse import urlencode
        params = {
            "sid": self.device_sid,
            "hash": self.pass_hash,
            "t": str(int(time.time() * 1000)),
            "mid": self.device_mid,
            "name": self.device_name,
            "app_version": APP_VERSION,
            "theme": "dark",
        }
        url = f"{REMOTE_URL}?{urlencode(params)}"
        self.log("info", "设备进入 waiting 状态。用手机浏览器打开（或扫码）以下 URL 完成配对：")
        print("\n" + url + "\n", flush=True)
        if not self.args.no_qr:
            try:
                import qrcode
                qr = qrcode.QRCode(border=1)
                qr.add_data(url)
                qr.make(fit=True)
                qr.print_ascii(invert=True)
            except Exception as e:
                self.log("warn", f"QR 渲染失败（不影响）：{e}")
        self.log("info", "URL 含 passHash 敏感凭据，勿外传；等待手机连接…（Ctrl+C 提前结束）")

    async def handle_data(self, payload):
        if not isinstance(payload, dict):
            self.log("info", f"data payload 非 dict: {payload!r:.200}")
            return
        zt = payload.get("zcode_type")
        # rpc-frame：先回 ack，再尝试分片重组
        if zt == "rpc-frame":
            mseq = payload.get("messageSeq")
            bkey = payload.get("bridgeSessionId")
            if (bkey, mseq) not in self.acked and payload.get("fragmentIndex") == 0:
                # 官方在每条消息首片（或单片）即回 ack；这里每 messageSeq 只回一次
                self.acked.add((bkey, mseq))
                await self.send_data_payload({
                    "zcode_type": "rpc-frame-ack",
                    "bridgeSessionId": bkey,
                    **({"bridgeGeneration": payload["bridgeGeneration"]} if "bridgeGeneration" in payload else {}),
                    **({"recoveryId": payload["recoveryId"]} if "recoveryId" in payload else {}),
                    "ackMessageSeq": mseq,
                })
            inner = self.assembler.accept(payload)
            if inner is not None:
                await self.record_inner(inner)
            else:
                frag = f" fragment={payload.get('fragmentIndex')}/{payload.get('fragmentCount')}"
                self.log("info", f"rpc-frame(分片中) bridge={bkey} mseq={mseq}{frag}")
            return
        # 非 rpc-frame 的顶层 payload：bootstrap-request / workspace-list-request / mobile-diagnostic …
        self.payload_types[zt] = self.payload_types.get(zt, 0) + 1
        brief = json.dumps(payload, ensure_ascii=False)
        self.log("info", f"📱 手机 payload zcode_type={zt}: {brief[:500]}")
        self.log_frame("recv", {"type": "data", "payload": payload}, inner={"zcode_type": zt})
        if self.args.respond:
            await self.respond(payload)

    async def respond(self, payload):
        """模拟官方桌面宿主，对手机请求回最小合法应答（schema 逆向自 H5 bundle）。"""
        zt = payload.get("zcode_type")
        rid = payload.get("requestId")
        if zt == "bootstrap-request":
            ws = self.mock_workspace()
            await self.send_data_payload({
                "zcode_type": "bootstrap-response",
                "requestId": rid,
                "success": True,
                "result": {
                    "windowControlSessionId": self.device_sid,
                    "workspaces": [ws],
                    "tasks": [self.mock_task(ws)],
                },
            })
        elif zt == "workspace-list-request":
            ws = self.mock_workspace()
            await self.send_data_payload({
                "zcode_type": "workspace-list-response",
                "requestId": rid,
                "success": True,
                "result": {
                    "workspaces": [ws],
                    "tasks": [self.mock_task(ws)],
                    "activeWorkspaceKey": ws["workspaceIdentity"],
                },
            })
        elif zt == "workspace-bridge-open":
            # bridgeSessionId 由手机端生成，原样回传；kind=local 声明桥已就绪
            self.active_bridge = payload["bridgeSessionId"]
            await self.send_data_payload({
                "zcode_type": "workspace-bridge-ready",
                "requestId": rid,
                "bridgeSessionId": payload["bridgeSessionId"],
                **({"bridgeGeneration": payload["bridgeGeneration"]} if "bridgeGeneration" in payload else {}),
                "bridge": {
                    "kind": "local",
                    "bridgeSessionId": payload["bridgeSessionId"],
                    **({"bridgeGeneration": payload["bridgeGeneration"]} if "bridgeGeneration" in payload else {}),
                    "workspaceKey": payload["workspaceKey"],
                    "workspacePath": self.args.mock_ws_path,
                },
            })
            # channel 层 Initialize 握手：官方桌面在 bridge ready 后立即推送，解锁手机服务调用
            init = ch_serialize([CH_RESPONSE["Initialize"]]) + ch_serialize(None)
            await self.send_rpc_frame(init, self.active_bridge)
            self.log("info", f"已推 channel initialize 帧（{init.hex()}），手机服务调用应解禁")
        # mobile-view-state-update / mobile-diagnostic / workspace-reconnect-request 无需即时应答

    def mock_workspace(self):
        return {
            "workspacePath": self.args.mock_ws_path,
            "workspaceIdentity": "probe-identity",
            "label": "Relay-Probe-Workspace",
            "kind": "local",
            "connectionState": "connected",
        }

    def mock_task(self, ws):
        now = time.time() * 1000
        return {
            "taskId": "probe-task-1",
            "title": "Probe task",
            "workspacePath": ws["workspacePath"],
            "workspaceIdentity": ws.get("workspaceIdentity"),
            "workspaceLabel": ws["label"],
            "workspaceKind": "local",
            "createdAt": now,
            "updatedAt": now,
        }

    async def record_inner(self, inner):
        """rpc-frame 重组出的内层消息：JSON 事件或二进制 channel 请求。"""
        if "_json" in inner:
            msg = inner["_json"]
            method = msg.get("method") or msg.get("kind") or msg.get("type") or msg.get("zcode_type")
            self.payload_types[f"rpc-frame-json:{method}"] = (
                self.payload_types.get(f"rpc-frame-json:{method}", 0) + 1
            )
            self.log("info", f"📦 rpc-frame 内层 JSON method={method}: {json.dumps(msg, ensure_ascii=False)[:600]}")
            self.log_frame("recv", {"_assembled": True}, inner=msg)
            return
        raw = inner["_raw"]
        head, args = ch_parse_request(raw)
        if not isinstance(head, list) or not head:
            self.log("info", f"📦 rpc-frame 内层二进制 head={raw[:60].hex()}")
            return
        req_type, req_id = head[0], head[1] if len(head) > 1 else None
        if req_type == CH_REQUEST["Promise"]:
            channel, method = (head[2], head[3]) if len(head) > 3 else ("?", "?")
            key = (channel, method)
            self.channel_calls[key] = self.channel_calls.get(key, 0) + 1
            self.log("info", f"📞 channel 调用 {channel}.{method} id={req_id} args={json.dumps(args, ensure_ascii=False, default=str)[:300]}")
            # 最小应答 PromiseSuccess(undefined)，驱动手机端继续
            if self.active_bridge:
                resp = ch_serialize([CH_RESPONSE["PromiseSuccess"], req_id]) + ch_serialize(None)
                await self.send_rpc_frame(resp, self.active_bridge)
        elif req_type == CH_REQUEST["EventListen"]:
            channel, method = (head[2], head[3]) if len(head) > 3 else ("?", "?")
            self.channel_calls[(channel, f"[listen]{method}")] = (
                self.channel_calls.get((channel, f"[listen]{method}"), 0) + 1
            )
            self.log("info", f"👂 channel 订阅 {channel}.{method} id={req_id}")
        elif req_type == CH_REQUEST["PromiseCancel"]:
            self.log("info", f"channel 取消 id={req_id}")
        else:
            self.log("info", f"📦 channel 其他请求 type={req_type} id={req_id}")
        self.log_frame("recv", {"_assembled": True}, inner={"channel_head": head[:4], "args": args})

    def summarize(self):
        self.log("info", f"状态={self.state} 共收帧 {self.rx_count}")
        if self.payload_types:
            self.log("info", "手机端帧清单（类型×次数）：")
            for k, v in sorted(self.payload_types.items(), key=lambda x: -x[1]):
                print(f"    {v:4d}  {k}", flush=True)
        if self.channel_calls:
            self.log("info", "channel RPC 调用清单（channel.method×次数）——0.4.0 桥接路由最小方法集：")
            for (ch, m), v in sorted(self.channel_calls.items(), key=lambda x: -x[1]):
                print(f"    {v:4d}  {ch}.{m}", flush=True)
        self.log("info", "完整帧日志见 jsonl 文件；凭据已存 relay-credentials.local.json（--reuse 可重连）")


async def main():
    ap = argparse.ArgumentParser(description="ZCode 手机远程 relay 探针（device 角色）")
    ap.add_argument("--duration", type=int, default=600, help="抓帧时长秒数（默认 600）")
    ap.add_argument("--reuse", action="store_true", help="复用已保存凭据（同 deviceSid 重连）")
    ap.add_argument("--no-qr", action="store_true", help="不渲染终端 QR")
    ap.add_argument("--name", default="ZCode-Relay-Probe", help="deviceName（默认 ZCode-Relay-Probe）")
    ap.add_argument("--respond", action="store_true", help="对手机请求回最小合法应答（模拟官方桌面）")
    ap.add_argument("--mock-ws-path", default="G:\\probe\\workspace", help="应答中 mock workspace 的路径")
    args = ap.parse_args()
    probe = Probe(args)
    try:
        await probe.run()
    except KeyboardInterrupt:
        probe.summarize()
    except Exception as e:
        probe.log("error", f"探针异常: {type(e).__name__}: {e}")
        raise


if __name__ == "__main__":
    asyncio.run(main())
