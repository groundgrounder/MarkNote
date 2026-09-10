#!/usr/bin/env python3
"""渲染 MarkNote 图标的各密度 PNG 与预览图（与矢量自适应图标同一设计）。"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path("/Users/blz/Documents/kimi/tasks/2026-09-06/16-08-47-699afa44")
RES = ROOT / "MarkNote/app/src/main/res"

BG = (74, 90, 207, 255)        # #4A5ACF
WHITE = (255, 255, 255, 255)
AMBER = (255, 213, 79, 255)    # #FFD54F

# 108 视口中的 glyph 坐标（与 ic_launcher_foreground.xml 一致）
M_PATH = [(30, 70), (30, 38), (42, 56), (54, 38), (54, 70)]
ARROW_STEM = [(71, 38), (71, 61)]
ARROW_HEAD = [(63, 54), (71, 64), (79, 54)]
STROKE = 7.0


def draw_glyph(draw: ImageDraw.ImageDraw, scale: float,
               m_color=WHITE, arrow_color=AMBER):
    w = STROKE * scale
    r = w / 2

    def stroke(points, color):
        pts = [(x * scale, y * scale) for x, y in points]
        draw.line(pts, fill=color, width=int(round(w)), joint="curve")
        for x, y in pts:  # 圆头
            draw.ellipse([x - r, y - r, x + r, y + r], fill=color)

    stroke(M_PATH, m_color)
    stroke(ARROW_STEM, arrow_color)
    stroke(ARROW_HEAD, arrow_color)


def render_icon(size: int) -> Image.Image:
    ss = 4  # 超采样抗锯齿
    img = Image.new("RGBA", (size * ss, size * ss), BG)
    d = ImageDraw.Draw(img)
    draw_glyph(d, size * ss / 108)
    return img.resize((size, size), Image.LANCZOS)


def rounded(img: Image.Image, radius_ratio: float) -> Image.Image:
    """套圆角（或圆形）蒙版，radius_ratio=0.5 即圆形。"""
    size = img.width
    ss = 4
    mask = Image.new("L", (size * ss, size * ss), 0)
    d = ImageDraw.Draw(mask)
    r = size * ss * radius_ratio
    d.rounded_rectangle([0, 0, size * ss, size * ss], radius=r, fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def main():
    # 1. 各密度 legacy PNG
    for dpi, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                      ("xxhdpi", 144), ("xxxhdpi", 192)]:
        out = RES / f"mipmap-{dpi}" / "ic_launcher.png"
        render_icon(size).save(out)
        print("written", out.name, size, "->", out.parent.name)

    # 2. Play Store 512
    play = ROOT / "ic_launcher-playstore.png"
    render_icon(512).save(play)
    print("written", play)

    # 3. 预览看板：圆角图标 / 圆形图标 / 单色主题图标
    W, H = 1500, 940
    board = Image.new("RGBA", (W, H), (245, 246, 250, 255))
    d = ImageDraw.Draw(board)

    icon_sq = rounded(render_icon(360), 0.22)   # squircle 近似
    icon_rd = rounded(render_icon(360), 0.5)    # 圆形
    # 单色主题图标：浅紫底 + 深色 glyph（模拟 themed icon 效果）
    mono = Image.new("RGBA", (360 * 4, 360 * 4), (197, 202, 233, 255))
    dm = ImageDraw.Draw(mono)
    dark = (38, 50, 56, 255)
    draw_glyph(dm, 360 * 4 / 108, m_color=dark, arrow_color=dark)
    icon_mono = rounded(mono.resize((360, 360), Image.LANCZOS), 0.5)

    xs = [140, 570, 1000]
    shapes = ["rect", "circle", "circle"]
    for x, ic, shape in zip(xs, [icon_sq, icon_rd, icon_mono], shapes):
        # 投影形状与图标一致
        sh = Image.new("RGBA", (400, 400), (0, 0, 0, 0))
        ds = ImageDraw.Draw(sh)
        if shape == "rect":
            ds.rounded_rectangle([30, 36, 390, 396], radius=88, fill=(30, 40, 80, 36))
        else:
            ds.ellipse([30, 36, 390, 396], fill=(30, 40, 80, 36))
        board.alpha_composite(sh, (x - 20, 190))
        board.alpha_composite(ic, (x, 200))

    try:
        font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 44)
        font_sm = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 32)
        font_title = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 72)
    except OSError:
        font = font_sm = font_title = ImageFont.load_default()

    d.text((W // 2, 90), "MarkNote", font=font_title, fill=(30, 33, 45, 255), anchor="mm")
    for x, label in zip(xs, ["Adaptive (squircle)", "Round", "Themed (monochrome)"]):
        d.text((x + 180, 620), label, font=font, fill=(90, 95, 110, 255), anchor="mm")
    d.text((W // 2, 700), "Markdown mark  ·  #4A5ACF / #FFD54F", font=font_sm,
           fill=(120, 125, 140, 255), anchor="mm")

    preview = ROOT / "icon_preview.png"
    board.convert("RGB").save(preview)
    print("written", preview)


if __name__ == "__main__":
    main()
