"""生成界面示意图（不是真机截图，用来对齐视觉预期）。"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = r"C:\Users\li\Documents\deepseek-harness\default-workspace\AlphaRelay"
OUT = os.path.join(ROOT, "docs", "mockup_main.png")
os.makedirs(os.path.dirname(OUT), exist_ok=True)

W, H = 900, 1780
BG = (247, 245, 242)
SURFACE = (255, 255, 255)
SURFACE2 = (241, 238, 233)
INK = (27, 27, 29)
MUTED = (107, 107, 112)
BRAND = (255, 106, 0)
THUMB = (228, 224, 217)

REG = r"C:\Windows\Fonts\msyh.ttc"
BOLD = r"C:\Windows\Fonts\msyhbd.ttc"
if not os.path.exists(REG):
    REG = r"C:\Windows\Fonts\simhei.ttf"
if not os.path.exists(BOLD):
    BOLD = REG


def font(size, bold=False):
    return ImageFont.truetype(BOLD if bold else REG, size)


img = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(img)

# ---------- 状态栏 ----------
d.rectangle([0, 0, W, 62], fill=SURFACE)
d.text((34, 18), "9:41", font=font(24, True), fill=INK)
for i in range(3):
    x = W - 60 - i * 16
    d.rectangle([x, 40 - (10 + i * 5), x + 9, 44], fill=INK)
d.rounded_rectangle([W - 130, 22, W - 74, 42], radius=10, outline=INK, width=3)
d.rectangle([W - 72, 28, W - 66, 36], fill=INK)
d.rectangle([W - 126, 26, W - 100, 38], fill=INK)

# ---------- 顶栏 ----------
d.rectangle([0, 62, W, 190], fill=SURFACE)
d.text((36, 100), "AlphaRelay 相机直传", font=font(34, True), fill=INK)
d.text((W - 120, 108), "刷新", font=font(24, True), fill=BRAND)
for i in range(3):
    cy = 108 + i * 11
    d.ellipse([W - 182, cy, W - 176, cy + 6], fill=MUTED)

# ---------- 状态卡 ----------
d.rounded_rectangle([36, 214, W - 36, 214 + 196], radius=34, fill=SURFACE2)
d.ellipse([64, 252, 82, 270], fill=(46, 160, 67))
d.text((94, 244), "ILCE-7C", font=font(32, True), fill=INK)
d.text((64, 300), "SLOT1 · 剩余 42.6 GB / 共 59.4 GB", font=font(21), fill=MUTED)
d.text((64, 344), "MTP 已连接 · 486 个文件 · ARW 231 / JPEG 198 / HEIF 51", font=font(19), fill=MUTED)

# ---------- 筛选 chips ----------
chips = [("全部", True), ("RAW", False), ("JPEG", False), ("HEIF", False), ("视频", False)]
x = 36
for label, active in chips:
    w = 46 + len(label) * 22
    d.rounded_rectangle([x, 444, x + w, 500], radius=28,
                        fill=(255, 226, 206) if active else SURFACE,
                        outline=None if active else (219, 214, 206), width=2)
    d.text((x + 22, 458), label, font=font(21, active), fill=(59, 26, 0) if active else MUTED)
    x += w + 14

# ---------- 进度卡 ----------
d.rounded_rectangle([36, 528, W - 36, 528 + 150], radius=32, fill=SURFACE2)
d.text((62, 552), "正在导出 128 / 486", font=font(20, True), fill=INK)
d.text((62, 584), "DSC01234.ARW · 24.8 MB", font=font(18), fill=MUTED)
d.rounded_rectangle([62, 626, W - 62, 636], radius=5, fill=(222, 217, 209))
d.rounded_rectangle([62, 626, 62 + int((W - 124) * 0.263), 636], radius=5, fill=BRAND)
d.text((W - 128, 650), "取消", font=font(19, True), fill=BRAND)

# ---------- 网格 ----------
def photo(w, h, palette, seed):
    tile = Image.new("RGB", (w, h), palette[0])
    td = ImageDraw.Draw(tile)
    for y in range(h):
        t = y / h
        c = tuple(int(palette[0][i] * (1 - t) + palette[1][i] * t) for i in range(3))
        td.line([(0, y), (w, y)], fill=c)
    # 远山
    td.polygon([(0, h), (0, int(h * 0.62)), (int(w * 0.28), int(h * 0.34)),
                (int(w * 0.52), int(h * 0.66)), (int(w * 0.74), int(h * 0.42)), (w, int(h * 0.72)), (w, h)],
               fill=palette[2])
    td.polygon([(0, h), (0, int(h * 0.78)), (int(w * 0.36), int(h * 0.58)),
                (int(w * 0.66), int(h * 0.86)), (w, int(h * 0.66)), (w, h)], fill=palette[3])
    td.ellipse([int(w * 0.68), int(h * 0.16), int(w * 0.86), int(h * 0.16) + int(w * 0.18)], fill=palette[4])
    return tile


cards = [
    ("DSC01234.ARW", "24.8 MB · 11-08 15:20", "RAW", ((84, 118, 156), (196, 214, 226), (58, 78, 96), (38, 52, 64), (255, 236, 196))),
    ("DSC01235.JPG", "9.6 MB · 11-08 15:20", "JPEG", ((214, 160, 112), (247, 220, 180), (150, 102, 70), (92, 62, 44), (255, 248, 222))),
    ("DSC01236.ARW", "25.1 MB · 11-08 15:22", "RAW", ((96, 72, 110), (222, 178, 170), (72, 52, 84), (44, 32, 54), (250, 226, 190))),
    ("DSC01237.HIF", "7.2 MB · 11-08 15:23", "HEIF", ((70, 120, 106), (198, 226, 206), (46, 82, 72), (30, 54, 48), (252, 244, 208))),
    ("C0001.MP4", "412 MB · 11-08 15:25", "视频", ((52, 62, 78), (120, 138, 158), (36, 44, 56), (24, 28, 36), (236, 240, 246))),
    ("DSC01238.ARW", "24.4 MB · 11-08 15:27", "RAW", ((160, 96, 76), (238, 196, 160), (112, 64, 52), (70, 40, 34), (255, 240, 206))),
]

col_w, gap, left = (W - 72 - 20) // 2, 20, 36
card_h, thumb_h = 336, 236
top = 712
for i, (name, meta, badge, pal) in enumerate(cards):
    r, c = divmod(i, 2)
    cx = left + c * (col_w + gap)
    cy = top + r * (card_h + gap)
    # 卡片阴影感：先画一层浅色
    d.rounded_rectangle([cx + 2, cy + 4, cx + col_w + 2, cy + card_h + 4], radius=28, fill=(232, 228, 221))
    d.rounded_rectangle([cx, cy, cx + col_w, cy + card_h], radius=28, fill=SURFACE2)
    tile = photo(col_w, thumb_h, pal, i)
    # 顶部圆角遮罩
    mask = Image.new("L", (col_w, thumb_h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, col_w, thumb_h + 28], radius=28, fill=255)
    img.paste(tile, (cx, cy), mask)
    # 徽标
    bw = 70 if badge in ("RAW", "JPEG", "HEIF") else 66
    d.rounded_rectangle([cx + 12, cy + 12, cx + 12 + bw, cy + 44], radius=10, fill=(0, 0, 0))
    d.text((cx + 22, cy + 18), badge, font=font(16, True), fill=(255, 255, 255))
    if i in (0, 2):
        d.ellipse([cx + col_w - 52, cy + 12, cx + col_w - 12, cy + 52], fill=BRAND)
        d.line([cx + col_w - 42, cy + 33, cx + col_w - 33, cy + 42], fill=(255, 255, 255), width=4)
        d.line([cx + col_w - 33, cy + 42, cx + col_w - 21, cy + 24], fill=(255, 255, 255), width=4)
    d.text((cx + 16, cy + thumb_h + 14), name, font=font(19, True), fill=INK)
    d.text((cx + 16, cy + thumb_h + 46), meta, font=font(16), fill=MUTED)

# ---------- 底部操作栏 ----------
bar_top = 1596
d.rectangle([0, bar_top, W, H], fill=SURFACE)
d.text((36, bar_top + 30), "共 486 个文件 · 已选 2 个", font=font(20), fill=MUTED)
d.text((W - 320, bar_top + 28), "全选", font=font(21, True), fill=BRAND)
d.rounded_rectangle([W - 210, bar_top + 18, W - 36, bar_top + 78], radius=30, fill=BRAND)
d.text((W - 178, bar_top + 32), "导出 (2)", font=font(21, True), fill=(255, 255, 255))
d.rectangle([0, H - 34, W, H], fill=SURFACE)
d.rounded_rectangle([W // 2 - 60, H - 22, W // 2 + 60, H - 14], radius=4, fill=(200, 196, 190))

# ---------- 角标说明 ----------
d.text((36, H - 74), "界面示意图 · 非真机截图", font=font(16), fill=(160, 156, 150))

img.save(OUT)
print("saved", OUT, img.size)
