# -*- coding: utf-8 -*-
"""AVD 走查辅助：解析 uiautomator dump，输出可点击节点与边界（用于定位 Compose 应用坐标）。

用法：python scripts/avd_ui_probe.py <ui.xml> [关键字]
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def center(bounds: str) -> tuple[int, int] | None:
    m = BOUNDS.match(bounds or "")
    if not m:
        return None
    x1, y1, x2, y2 = (int(g) for g in m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else "ui.xml"
    keyword = sys.argv[2] if len(sys.argv) > 2 else None

    root = ET.parse(path).getroot()
    print(f"total nodes: {sum(1 for _ in root.iter('node'))}")
    print(f"{'text':<28} {'class':<22} {'clickable':<10} center")
    for node in root.iter("node"):
        text = (node.get("text") or "").strip()
        desc = (node.get("content-desc") or "").strip()
        label = text or desc
        clickable = node.get("clickable")
        if keyword and keyword not in label:
            continue
        if not label and clickable != "true":
            continue
        c = center(node.get("bounds"))
        print(f"{label[:26]:<28} {((node.get('class') or '').split('.')[-1])[:20]:<22} {str(clickable):<10} {c}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
