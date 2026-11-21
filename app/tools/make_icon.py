#!/usr/bin/env python3
"""生成启动图标（交接包里的 mipmap 丢了，重新画一套）。

画法：圆角深蓝底 + 白色打印机轮廓 + 一张正在吐出的纸。
"""
import os

from PIL import Image, ImageDraw

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
BG = (37, 99, 235)      # 蓝
BG2 = (14, 165, 233)    # 渐变用浅蓝
INK = (255, 255, 255)


def draw_icon(size):
    s = size * 8                      # 先按 8 倍画，再缩，边缘更干净
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 底色：竖直渐变 + 圆角
    grad = Image.new("RGBA", (1, s))
    gd = ImageDraw.Draw(grad)
    for y in range(s):
        t = y / max(1, s - 1)
        gd.point((0, y), (
            int(BG[0] + (BG2[0] - BG[0]) * t),
            int(BG[1] + (BG2[1] - BG[1]) * t),
            int(BG[2] + (BG2[2] - BG[2]) * t),
            255))
    bg = grad.resize((s, s))
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, s - 1, s - 1], radius=int(s * 0.22), fill=255)
    img.paste(bg, (0, 0), mask)

    # 打印机：机身
    body = [int(s * 0.16), int(s * 0.44), int(s * 0.84), int(s * 0.78)]
    d.rounded_rectangle(body, radius=int(s * 0.07), fill=INK)
    # 出纸口（挖一条底色缝）
    slot = [int(s * 0.28), int(s * 0.60), int(s * 0.72), int(s * 0.66)]
    d.rounded_rectangle(slot, radius=int(s * 0.03), fill=BG)

    # 上面那张纸
    paper = [int(s * 0.28), int(s * 0.16), int(s * 0.72), int(s * 0.50)]
    d.rounded_rectangle(paper, radius=int(s * 0.02), fill=INK)
    # 纸上的两行字（用底色）
    for i, y in enumerate((0.26, 0.34, 0.42)):
        d.rounded_rectangle([int(s * 0.36), int(s * y), int(s * (0.64 - i * 0.06)),
                             int(s * (y + 0.03))], radius=int(s * 0.012), fill=BG)

    # 底部出纸
    out = [int(s * 0.30), int(s * 0.70), int(s * 0.70), int(s * 0.86)]
    d.rounded_rectangle(out, radius=int(s * 0.02), fill=INK)

    return img.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    project = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    base = os.path.join(project, "res")
    for dens, px in SIZES.items():
        d = os.path.join(base, "mipmap-" + dens)
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, "ic_launcher.png")
        draw_icon(px).save(p)
        print("%s  %dx%d" % (p, px, px))
