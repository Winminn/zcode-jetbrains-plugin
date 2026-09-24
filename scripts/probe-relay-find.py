#!/usr/bin/env python3
"""mmap 版关键词字节偏移搜索（zcode.cjs / app.asar），支持多关键词一次扫。"""
import mmap
import sys

PATHS = {
    "z": r"C:\Users\Administrator\AppData\Local\Programs\ZCode\resources\glm\zcode.cjs",
    "a": r"C:\Users\Administrator\AppData\Local\Programs\ZCode\resources\app.asar",
}

if __name__ == "__main__":
    keys = [k.encode() for k in sys.argv[1:]]
    for tag, path in PATHS.items():
        with open(path, "rb") as f:
            with mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ) as mm:
                for key in keys:
                    hits, pos = [], 0
                    while True:
                        i = mm.find(key, pos)
                        if i < 0:
                            break
                        hits.append(i)
                        pos = i + 1
                    print(f"{tag} {key.decode()!r}: {len(hits)} hits -> {hits[:25]}")
