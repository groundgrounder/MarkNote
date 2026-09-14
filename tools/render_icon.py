#!/usr/bin/env python3
"""渲染 MarkNote 图标的各密度 PNG、Play Store 图标与预览看板。

与矢量自适应图标（drawable/ic_launcher_background.xml + ic_launcher_foreground.xml）
用同一套几何：改图形时先改本文件顶部的常量与下面的 path 定义，两边一起对。

用法：  python3 tools/render_icon.py
产物：  app/src/main/res/mipmap-*/ic_launcher.png   （legacy 回退位图）
        .workbuddy/artifacts/ic_launcher-playstore.png（512，上架用）
        .workbuddy/artifacts/icon_preview.png          （看板，含单色主题图标效果）
"""
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
ART = ROOT / ".workbuddy/artifacts"

# ---- 底色：与 ic_launcher_background.xml 的渐变端点一致 ----
BG_FROM = (0x4C, 0x58, 0xDA)
BG_TO = (0x2C, 0x35, 0xA0)
BG_VEC = (42.0, 108.0)          # 渐变方向（108 网格坐标）

# ---- 前景：与 ic_launcher_foreground.xml 一致 ----
WHITE = (255, 255, 255, 255)
ACCENT = (0x9A, 0xA8, 0xFF, 255)
SW = 8.0
M_PATH = [(29.25, 70.6), (29.25, 39.1), (40.0, 61.15), (50.75, 39.1), (50.75, 70.6)]
ARROW_STEM = [(71.75, 39.1), (71.75, 66.6)]
ARROW_HEAD = [(64.75, 59.6), (71.75, 66.6), (78.75, 59.6)]


def gradient_bg(size: int) -> Image.Image:
    """按 BG_VEC 方向生成线性渐变；先算小图再放大，渐变足够平滑。"""
    g = 64
    small = Image.new("RGB", (g, g))
    px = small.load()
    vx, vy = BG_VEC
    denom = vx * vx + vy * vy
    for j in range(g):
        for i in range(g):
            x = i / (g - 1) * 108.0
            y = j / (g - 1) * 108.0
            t = (x * vx + y * vy) / denom
            t = 0.0 if t < 0.0 else (1.0 if t > 1.0 else t)
            px[i, j] = tuple(round(a + (b - a) * t) for a, b in zip(BG_FROM, BG_TO))
    return small.resize((size, size), Image.BICUBIC).convert("RGBA")


def stroke(draw: ImageDraw.ImageDraw, pts, width, color, cap="butt", join="round",
           scale=1.0):
    """按矢量语义画描边：线段画四边形，端头/关节按 cap/join 补圆。

    这样 PNG 与 VectorDrawable 的输出才对得上（PIL 的 line(width=) 会自己加方头）。
    """
    p = [(x * scale, y * scale) for x, y in pts]
    w = width * scale
    r = w / 2
    for a, b in zip(p, p[1:]):
        dx, dy = b[0] - a[0], b[1] - a[1]
        length = math.hypot(dx, dy)
        if not length:
            continue
        nx, ny = -dy / length * r, dx / length * r
        draw.polygon([(a[0] + nx, a[1] + ny), (b[0] + nx, b[1] + ny),
                      (b[0] - nx, b[1] - ny), (a[0] - nx, a[1] - ny)], fill=color)
    if join == "round":
        for cx, cy in p[1:-1]:
            draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)
    if cap == "round":
        for cx, cy in (p[0], p[-1]):
            draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)


def draw_glyph(draw, scale, m_color=WHITE, a_color=ACCENT):
    stroke(draw, M_PATH, SW, m_color, cap="butt", join="miter", scale=scale)
    stroke(draw, ARROW_STEM, SW, a_color, cap="butt", scale=scale)
    stroke(draw, ARROW_HEAD, SW, a_color, cap="round", join="round", scale=scale)


def render_icon(size: int, ss: int = 4) -> Image.Image:
    img = gradient_bg(size * ss)
    draw_glyph(ImageDraw.Draw(img), size * ss / 108)
    return img.resize((size, size), Image.LANCZOS)


def rounded(img: Image.Image, radius_ratio: float) -> Image.Image:
    size = img.width
    ss = 4
    mask = Image.new("L", (size * ss, size * ss), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size * ss, size * ss], radius=size * ss * radius_ratio, fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def main():
    ART.mkdir(parents=True, exist_ok=True)

    # 1. 各密度 legacy PNG
    for dpi, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                      ("xxhdpi", 144), ("xxxhdpi", 192)]:
        out = RES / f"mipmap-{dpi}" / "ic_launcher.png"
        render_icon(size).save(out)
        print(f"written {out.relative_to(ROOT)}  {size}px")

    # 2. Play Store 512
    play = ART / "ic_launcher-playstore.png"
    render_icon(512).save(play, format="PNG")
    print(f"written {play.relative_to(ROOT)}  512px")

    # 3. 预览看板：圆角 / 圆形 / 单色主题 / 小尺寸
    W, H = 1560, 900
    board = Image.new("RGBA", (W, H), (245, 246, 250, 255))
    d = ImageDraw.Draw(board)

    icon_sq = rounded(render_icon(320), 0.22)
    icon_rd = rounded(render_icon(320), 0.5)
    mono = Image.new("RGBA", (320 * 4, 320 * 4), (201, 210, 245, 255))
    draw_glyph(ImageDraw.Draw(mono), 320 * 4 / 108, m_color=(29, 36, 64, 255),
               a_color=(29, 36, 64, 255))
    icon_mono = rounded(mono.resize((320, 320), Image.LANCZOS), 0.5)

    xs = [110, 490, 870]
    for x, ic, shape in zip(xs, [icon_sq, icon_rd, icon_mono], ["rect", "circle", "circle"]):
        sh = Image.new("RGBA", (360, 360), (0, 0, 0, 0))
        ds = ImageDraw.Draw(sh)
        if shape == "rect":
            ds.rounded_rectangle([26, 30, 346, 350], radius=78, fill=(30, 40, 80, 38))
        else:
            ds.ellipse([26, 30, 346, 350], fill=(30, 40, 80, 38))
        board.alpha_composite(sh, (x - 20, 160))
        board.alpha_composite(ic, (x, 170))

    # 小尺寸一列，看缩到 24px 还认不认得出
    for i, s in enumerate((48, 36, 24, 16)):
        board.alpha_composite(rounded(render_icon(s * 2), 0.22).resize((s * 2, s * 2)),
                              (1240, 200 + i * 92))

    try:
        f_t = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 64)
        f_m = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 30)
        f_s = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 26)
    except OSError:
        f_t = f_m = f_s = ImageFont.load_default()

    d.text((W // 2, 80), "MarkNote", font=f_t, fill=(30, 33, 45, 255), anchor="mm")
    for x, label in zip(xs, ["Adaptive (squircle)", "Round", "Themed (monochrome)"]):
        d.text((x + 160, 530), label, font=f_m, fill=(90, 95, 110, 255), anchor="mm")
    d.text((1240, 170), "48 / 36 / 24 / 16 px", font=f_s, fill=(120, 125, 140, 255))
    d.text((W // 2, 600), "Markdown M + down arrow   ·   #4C58DA to #2C35A0   ·   #FFFFFF + #9AA8FF",
           font=f_m, fill=(120, 125, 140, 255), anchor="mm")

    preview = ART / "icon_preview.png"
    board.convert("RGB").save(preview)
    print(f"written {preview.relative_to(ROOT)}")

    # 自检：墨迹范围应与矢量一致（57.6 x 33.3，中心 54,54）
    probe = Image.new("L", (1080, 1080), 0)
    draw_glyph(ImageDraw.Draw(probe), 10, m_color=255, a_color=255)
    bb = tuple(v / 10 for v in probe.getbbox())
    print(f"ink bbox x {bb[0]:.2f}..{bb[2]:.2f} ({bb[2]-bb[0]:.2f})  "
          f"y {bb[1]:.2f}..{bb[3]:.2f} ({bb[3]-bb[1]:.2f})  "
          f"center ({(bb[0]+bb[2])/2:.2f},{(bb[1]+bb[3])/2:.2f})")


if __name__ == "__main__":
    main()
