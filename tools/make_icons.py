#!/usr/bin/env python3
"""Generate PNG icons (no dependencies): python tools/make_icons.py"""
import struct
import zlib
from pathlib import Path

out = Path(__file__).resolve().parent.parent / "icons"
out.mkdir(exist_ok=True)


def png(size, maskable):
    bg, body, lens, ring = (11, 13, 16), (62, 166, 255), (11, 13, 16), (232, 237, 242)
    s = size
    rows = []
    for y in range(s):
        row = bytearray([0])
        for x in range(s):
            u, v = x / s, y / s
            c = bg
            k = 0.12 if maskable else 0.06  # maskable keeps content inside the safe zone
            bx0, bx1, by0, by1 = 0.2 + k, 0.8 - k, 0.32 + k / 2, 0.72 - k / 2
            if bx0 <= u <= bx1 and by0 <= v <= by1:
                c = body
            if 0.38 <= u <= 0.62 and by0 - 0.07 <= v < by0:
                c = body
            d = ((u - 0.5) ** 2 + (v - 0.52) ** 2) ** 0.5
            if d < 0.15 * (0.8 if maskable else 1):
                c = ring
            if d < 0.10 * (0.8 if maskable else 1):
                c = lens
            row += bytes(c) + b"\xff"
        rows.append(bytes(row))
    raw = zlib.compress(b"".join(rows), 9)
    chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", s, s, 8, 6, 0, 0, 0)) + chunk(b"IDAT", raw) + chunk(b"IEND", b"")


for name, size, m in [("icon-192.png", 192, False), ("icon-512.png", 512, False), ("maskable-512.png", 512, True)]:
    (out / name).write_bytes(png(size, m))
    print("wrote", name)
