# -*- coding: utf-8 -*-
"""AVD 走查辅助：从截图定量分析「输入框是否被软键盘遮挡」。

原理：
1. 从底部向上扫描，找出 IME（软键盘）区域——Gboard 数字/文本键盘为浅灰底 + 白色按键，
   其顶部与上方应用内容之间存在明显分界；此处用「行内颜色方差突变」粗略定位不稳定，
   因此改为更可靠的判据：把「app 内容区最下方非键盘像素」与 uiautomator 测得的
   输入框 bounds 做比较。
2. 直接给定两个坐标（输入框底边 y、键盘顶边 y），由脚本判断可见性。
   键盘顶边可通过 --ime-top 传入；未传入时用启发式检测底部键盘区域高度。

用法：
  python scripts/avd_ime_check.py <shot.png> --field-bottom <y> [--ime-top <y>]
"""
from __future__ import annotations

import argparse
import sys

from PIL import Image


def detect_keyboard_top(img: Image.Image, threshold: int = 6) -> int:
    """启发式检测键盘顶边：从底部向上找第一处「行平均色突变」的位置。

    键盘区域内部各行颜色高度一致（按键白 + 背景浅灰），而应用内容区含文字、
    卡片边框，行间差异更大。这里取行均值序列的差分，找底部区域第一个显著跳变。
    """
    gray = img.convert("L")
    w, h = gray.size
    px = gray.load()

    row_means = []
    for y in range(h):
        total = 0
        step = max(1, w // 60)
        n = 0
        for x in range(0, w, step):
            total += px[x, y]
            n += 1
        row_means.append(total / n)

    # 从底部 1/4 处向上找第一个差分超过 threshold 的位置
    for y in range(h - 2, int(h * 0.35), -1):
        if abs(row_means[y] - row_means[y - 1]) > threshold:
            return y
    return h


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("image")
    ap.add_argument("--field-bottom", type=int, required=True, help="输入框底边 y（uiautomator bounds）")
    ap.add_argument("--field-top", type=int, default=None, help="输入框顶边 y")
    ap.add_argument("--ime-top", type=int, default=None, help="键盘顶边 y；缺省用启发式检测")
    ap.add_argument("--out", default=None, help="输出标注图路径")
    args = ap.parse_args()

    img = Image.open(args.image)
    w, h = img.size

    ime_top = args.ime_top if args.ime_top is not None else detect_keyboard_top(img)
    visible = args.field_bottom < ime_top
    overlap = max(0, args.field_bottom - ime_top)

    print(f"图片尺寸        : {w}x{h}")
    print(f"键盘顶边 y      : {ime_top}{'（启发式检测）' if args.ime_top is None else '（外部给定）'}")
    print(f"输入框底边 y    : {args.field_bottom}")
    if args.field_top is not None:
        print(f"输入框顶边 y    : {args.field_top}")
    print(f"判定            : {'[OK] 输入框完整位于键盘上方' if visible else '[FAIL] 输入框被键盘遮挡'}"
          + (f"（重叠 {overlap}px）" if overlap else ""))

    if args.out:
        from PIL import ImageDraw
        marked = img.convert("RGB")
        d = ImageDraw.Draw(marked)
        d.line([(0, ime_top), (w, ime_top)], fill=(255, 0, 0), width=6)
        d.line([(0, args.field_bottom), (w, args.field_bottom)], fill=(0, 160, 0), width=6)
        if args.field_top is not None:
            d.line([(0, args.field_top), (w, args.field_top)], fill=(0, 160, 0), width=6)
        marked.thumbnail((520, 1160))
        marked.save(args.out)
        print(f"标注图已保存    : {args.out}")

    return 0 if visible else 2


if __name__ == "__main__":
    sys.exit(main())
