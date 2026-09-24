#!/usr/bin/env python3
"""按字节锚点切片 dump zcode.cjs / app.asar 的 relay 协议实现，供 PoC 提取细节。"""
import sys

ZCODE = r"C:\Users\Administrator\AppData\Local\Programs\ZCode\resources\glm\zcode.cjs"
ASAR = r"C:\Users\Administrator\AppData\Local\Programs\ZCode\resources\app.asar"


def dump(path: str, start: int, end: int, label: str):
    with open(path, "rb") as f:
        f.seek(start)
        data = f.read(end - start)
    text = data.decode("utf-8", errors="replace")
    print(f"===== {label} [{start}:{end}] ({len(data)} bytes) =====")
    print(text)
    print(f"===== END {label} =====\n")


if __name__ == "__main__":
    path = sys.argv[1]
    start, end = int(sys.argv[2]), int(sys.argv[3])
    label = sys.argv[4] if len(sys.argv) > 4 else "slice"
    dump({"z": ZCODE, "a": ASAR}[path], start, end, label)
