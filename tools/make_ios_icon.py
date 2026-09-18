#!/usr/bin/env python3
"""Renders the iOS app icon from the same shield the Android launcher uses.

iOS has no equivalent of Android's adaptive-icon XML: an app icon has to be a
raster image, and without one the app installs with a blank white square on the
home screen. Rather than check in an opaque PNG nobody can edit, this script
draws it, so the icon stays a readable definition and can be regenerated.

The path below is copied verbatim from
android/app/src/main/res/drawable/ic_launcher_foreground.xml, and the
background is the same #3B5FE3, so both apps look like one product.

No third-party imaging library: nothing of the sort is installable in the
sandbox this was written in, and a hard dependency for one 1024x1024 image
would be a poor trade anyway. The polygon is scan-filled with analytic
horizontal coverage and 4x vertical supersampling, and the PNG is written
directly (zlib is in the standard library).

    python3 tools/make_ios_icon.py

Apple requires the icon be opaque with square corners -- the system applies the
rounded mask -- so this emits RGB with no alpha channel.
"""

import struct
import zlib

SIZE = 1024
BG = (0x3B, 0x5F, 0xE3)
FG = (0xFF, 0xFF, 0xFF)

# The shield, in the Android drawable's 108x108 viewport.
#   M54,14 L24,26 v24 c0,23 13,42 30,48 c17,-6 30,-25 30,-48 V26 z
SHIELD = [
    ("M", (54, 14)),
    ("L", (24, 26)),
    ("L", (24, 50)),
    ("C", (24, 73), (37, 92), (54, 98)),
    ("C", (71, 92), (84, 73), (84, 50)),
    ("L", (84, 26)),
]

# Fraction of the icon's height the shield occupies. Apple's icons run edge to
# edge more than Android's, which reserves an outer ring for the mask, but a
# shield that fills the square reads as a shape rather than a symbol.
FILL_RATIO = 0.66
FLATTEN_STEPS = 24          # cubic -> line segments
SUBSAMPLES = 4              # vertical supersampling per pixel row


def flatten(path):
    """Turns the path into a closed polygon in viewport coordinates."""
    points = []
    cursor = None
    for command in path:
        kind = command[0]
        if kind == "M":
            cursor = command[1]
            points.append(cursor)
        elif kind == "L":
            cursor = command[1]
            points.append(cursor)
        elif kind == "C":
            p0, (c1, c2, p1) = cursor, command[1:]
            for step in range(1, FLATTEN_STEPS + 1):
                t = step / FLATTEN_STEPS
                u = 1.0 - t
                x = (u * u * u * p0[0] + 3 * u * u * t * c1[0]
                     + 3 * u * t * t * c2[0] + t * t * t * p1[0])
                y = (u * u * u * p0[1] + 3 * u * u * t * c1[1]
                     + 3 * u * t * t * c2[1] + t * t * t * p1[1])
                points.append((x, y))
            cursor = p1
    return points


def to_device(points):
    """Scales and centres the polygon inside the SIZE x SIZE icon."""
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    min_x, max_x, min_y, max_y = min(xs), max(xs), min(ys), max(ys)
    scale = (SIZE * FILL_RATIO) / (max_y - min_y)
    off_x = (SIZE - (max_x - min_x) * scale) / 2 - min_x * scale
    off_y = (SIZE - (max_y - min_y) * scale) / 2 - min_y * scale
    return [(x * scale + off_x, y * scale + off_y) for x, y in points]


def add_span(row, x0, x1, weight):
    """Accumulates coverage for a horizontal span, partial at both ends."""
    x0 = max(0.0, x0)
    x1 = min(float(SIZE), x1)
    if x1 <= x0:
        return
    first, last = int(x0), int(x1)
    if first == last:
        row[first] += (x1 - x0) * weight
        return
    row[first] += (first + 1 - x0) * weight
    for i in range(first + 1, last):
        row[i] += weight
    if last < SIZE:
        row[last] += (x1 - last) * weight


def coverage_rows(polygon):
    """Yields one row of per-pixel coverage (0..1) at a time."""
    edges = []
    for i in range(len(polygon)):
        (x0, y0), (x1, y1) = polygon[i], polygon[(i + 1) % len(polygon)]
        if y0 != y1:
            edges.append((x0, y0, x1, y1))
    weight = 1.0 / SUBSAMPLES

    for py in range(SIZE):
        row = [0.0] * SIZE
        for sub in range(SUBSAMPLES):
            y = py + (sub + 0.5) / SUBSAMPLES
            crossings = []
            for x0, y0, x1, y1 in edges:
                if (y0 <= y < y1) or (y1 <= y < y0):
                    crossings.append(x0 + (y - y0) * (x1 - x0) / (y1 - y0))
            crossings.sort()
            # Even-odd fill: the shield is a single non-self-intersecting loop.
            for i in range(0, len(crossings) - 1, 2):
                add_span(row, crossings[i], crossings[i + 1], weight)
        yield row


def chunk(tag, data):
    return (struct.pack(">I", len(data)) + tag + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))


def render(path):
    polygon = to_device(flatten(SHIELD))
    raw = bytearray()
    for row in coverage_rows(polygon):
        raw.append(0)  # filter type: none
        for value in row:
            alpha = value if value < 1.0 else 1.0
            for channel in range(3):
                raw.append(int(BG[channel] + (FG[channel] - BG[channel]) * alpha + 0.5))

    header = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 2, 0, 0, 0)  # 8-bit RGB
    with open(path, "wb") as out:
        out.write(b"\x89PNG\r\n\x1a\n")
        out.write(chunk(b"IHDR", header))
        out.write(chunk(b"IDAT", zlib.compress(bytes(raw), 9)))
        out.write(chunk(b"IEND", b""))


if __name__ == "__main__":
    import os
    target = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "ios", "OpenLink", "Assets.xcassets", "AppIcon.appiconset", "AppIcon.png")
    render(target)
    print("wrote", target, os.path.getsize(target), "bytes")
