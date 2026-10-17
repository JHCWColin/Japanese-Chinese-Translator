#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generate Android vector drawables from Lucide icons (https://lucide.dev, ISC licence).

Lucide icons are 24x24 stroked SVGs (stroke-width 2, round caps/joins).  This script downloads
the SVG of every icon in ICONS from the lucide-static package and converts it to
app/src/main/res/drawable/ic_<name>.xml.  Strokes are black; the app tints them at run time.

    python tools/gen_icons.py
"""
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ICONS = [
    "history", "settings", "arrow-left", "cpu", "palette", "monitor", "languages", "gauge", "info",
    "minimize-2", "columns-2", "square-round-corner", "frame", "move-vertical", "a-large-small",
    "activity", "smartphone", "contrast", "type", "align-justify", "bold", "eraser", "keyboard",
    "lock", "scissors", "copy", "memory-stick", "save", "list-ordered", "book-open", "trash-2",
    "brain", "sparkles", "zap", "scan-text",
]
URL = "https://unpkg.com/lucide-static@latest/icons/%s.svg"
OUT = Path(__file__).resolve().parent.parent / "app/src/main/res/drawable"
NS = "{http://www.w3.org/2000/svg}"


def f(v):
    return ("%.4f" % float(v)).rstrip("0").rstrip(".")


def shape_to_path(el):
    tag = el.tag.replace(NS, "")
    a = el.attrib
    if tag == "path":
        return a["d"]
    if tag == "circle":
        cx, cy, r = float(a["cx"]), float(a["cy"]), float(a["r"])
        return "M%s,%s a%s,%s 0 1,0 %s,0 a%s,%s 0 1,0 %s,0" % (f(cx - r), f(cy), f(r), f(r), f(2 * r), f(r), f(r), f(-2 * r))
    if tag == "ellipse":
        cx, cy, rx, ry = float(a["cx"]), float(a["cy"]), float(a["rx"]), float(a["ry"])
        return "M%s,%s a%s,%s 0 1,0 %s,0 a%s,%s 0 1,0 %s,0" % (f(cx - rx), f(cy), f(rx), f(ry), f(2 * rx), f(rx), f(ry), f(-2 * rx))
    if tag == "line":
        return "M%s,%s L%s,%s" % (a["x1"], a["y1"], a["x2"], a["y2"])
    if tag in ("polyline", "polygon"):
        pts = re.findall(r"-?\d*\.?\d+", a["points"])
        pairs = ["%s,%s" % (pts[i], pts[i + 1]) for i in range(0, len(pts), 2)]
        return "M" + " L".join(pairs) + (" Z" if tag == "polygon" else "")
    if tag == "rect":
        x, y, w, h = (float(a.get(k, 0)) for k in ("x", "y", "width", "height"))
        rx = float(a.get("rx", a.get("ry", 0)))
        ry = float(a.get("ry", rx))
        if rx <= 0:
            return "M%s,%s h%s v%s h%s Z" % (f(x), f(y), f(w), f(h), f(-w))
        return ("M%s,%s h%s a%s,%s 0 0,1 %s,%s v%s a%s,%s 0 0,1 %s,%s h%s a%s,%s 0 0,1 %s,%s v%s a%s,%s 0 0,1 %s,%s Z"
                % (f(x + rx), f(y), f(w - 2 * rx), f(rx), f(ry), f(rx), f(ry), f(h - 2 * ry),
                   f(rx), f(ry), f(-rx), f(ry), f(-(w - 2 * rx)),
                   f(rx), f(ry), f(-rx), f(-ry), f(-(h - 2 * ry)),
                   f(rx), f(ry), f(rx), f(-ry)))
    raise ValueError("unsupported element <%s>" % tag)


def convert(name, svg):
    root = ET.fromstring(svg)
    paths = []
    for el in root.iter():
        if el.tag == NS + "svg" or el.tag is ET.Comment:
            continue
        paths.append(shape_to_path(el))
    out = ['<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="24dp" android:height="24dp"',
           '    android:viewportWidth="24" android:viewportHeight="24">']
    for d in paths:
        out.append('    <path android:pathData="%s"' % d.replace("\n", " "))
        out.append('        android:strokeColor="#FF000000" android:strokeWidth="2"')
        out.append('        android:strokeLineCap="round" android:strokeLineJoin="round" />')
    out.append("</vector>")
    return "\n".join(out) + "\n"


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    bad = []
    for name in ICONS:
        try:
            svg = urllib.request.urlopen(URL % name, timeout=30).read().decode("utf-8")
        except Exception as e:  # noqa: BLE001
            bad.append("%s (%s)" % (name, e))
            continue
        (OUT / ("ic_" + name.replace("-", "_") + ".xml")).write_text(convert(name, svg), encoding="utf-8")
        print("ok", name)
    if bad:
        sys.exit("failed: " + ", ".join(bad))


if __name__ == "__main__":
    main()
