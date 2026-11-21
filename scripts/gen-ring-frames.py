#!/usr/bin/env python3
"""
生成手环提醒页的横向倒计时数字帧图与提示图。

背景：Vela 手环快应用不支持 canvas 与 transform rotate（会导致手环重启），
横向显示只能靠"预渲染旋转文字的 PNG"实现：本脚本把 "MM:SS" 横排白字
顺时针旋转 90° 后存为帧图，提醒页用 image 组件按秒切换。

产物（均提交进仓库，CI 不需要跑本脚本）：
  src/common/ring/frames/00XX.png  倒计时帧（XX = 00..59，秒数）
  src/common/ring/hint.png         "上滑关闭"提示小字（同样旋转 90°）

用法：python3 scripts/gen-ring-frames.py
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT_FRAMES = ROOT / "src" / "common" / "ring" / "frames"
OUT_RING = ROOT / "src" / "common" / "ring"

# 2x 尺寸生成：手环 9（192 宽）缩小显示更锐利，9 Pro（336 宽）约 1:1
FONT_SIZE = 150          # 横排数字字高（px）
COLON_PAD = 10           # 冒号左右留隙
ROTATION = -90           # PIL 负角度 = 顺时针 90°，与"内容横置、抬腕正读"一致

FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
]


def pick_font() -> ImageFont.FreeTypeFont:
    for path in FONT_CANDIDATES:
        if Path(path).exists():
            return ImageFont.truetype(path, FONT_SIZE)
    raise SystemExit("未找到可用的粗体字体，请安装 fonts-dejavu-core")


def render_horizontal(text: str, font) -> Image.Image:
    """横排渲染一段白字黑底文本，返回未旋转的横向图。"""
    probe = Image.new("L", (1, 1))
    draw = ImageDraw.Draw(probe)
    box = draw.textbbox((0, 0), text, font=font)
    w, h = box[2] - box[0], box[3] - box[1]
    img = Image.new("RGB", (w + 40, h + 40), "#000000")
    ImageDraw.Draw(img).text((20 - box[0], 20 - box[1]), text, font=font, fill="#ffffff")
    return img


def save_rotated(img: Image.Image, path: Path) -> None:
    """顺时针转 90° 落盘；黑底与页面背景同色，白字抗锯齿。"""
    rotated = img.rotate(ROTATION, expand=True, resample=Image.BICUBIC)
    path.parent.mkdir(parents=True, exist_ok=True)
    rotated.save(path, optimize=True)


def main() -> None:
    font = pick_font()

    # 60 帧倒计时：MM 恒为 00（AOS 前 60 秒窗口），逐秒 00:59 → 00:00
    digits = [":", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9"]
    widths = {}
    for ch in digits + list("MMSS"):
        pass
    # 逐字测宽，手工拼 "MM:SS" 保证冒号两侧留隙均匀（等宽字体数字同宽）
    digit_w = font.getbbox("0")[2] - font.getbbox("0")[0]
    colon_w = font.getbbox(":")[2] - font.getbbox(":")[0]
    total_w = 4 * digit_w + colon_w + 2 * COLON_PAD
    asc, desc = font.getmetrics()
    h = asc + desc

    for sec in range(59, -1, -1):
        text = f"00:{sec:02d}"
        img = Image.new("RGB", (total_w + 40, h + 40), "#000000")
        draw = ImageDraw.Draw(img)
        x = 20
        # 逐字绘制：M M : S S，冒号两侧加固定留隙
        for ch in text:
            if ch == ":":
                x += COLON_PAD
            bbox = draw.textbbox((x, 20), ch, font=font)
            draw.text((x - bbox[0], 20), ch, font=font, fill="#ffffff")
            x += (colon_w if ch == ":" else digit_w)
            if ch == ":":
                x += COLON_PAD
        save_rotated(img, OUT_FRAMES / f"{sec:02d}.png")

    # "上滑关闭"提示小字：同样旋转，字号小一档
    hint_font = ImageFont.truetype(FONT_CANDIDATES[0], 44)
    save_rotated(render_horizontal("上滑关闭", hint_font), OUT_RING / "hint.png")

    frames = sorted(OUT_FRAMES.glob("*.png"))
    total_kb = sum(f.stat().st_size for f in frames) / 1024
    print(f"生成 {len(frames)} 帧倒计时 + hint.png，共 {total_kb:.0f} KB")
    print(f"样张尺寸: {Image.open(frames[0]).size}")


if __name__ == "__main__":
    main()
