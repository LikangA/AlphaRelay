"""生成启动图标（各密度 PNG）。"""
import os
from PIL import Image, ImageDraw

ROOT = r"C:\Users\li\Documents\deepseek-harness\default-workspace\AlphaRelay"
RES = os.path.join(ROOT, "app", "src", "main", "res")

BRAND = (255, 106, 0, 255)
WHITE = (255, 255, 255, 255)

S = 1024
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# 圆角底
d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.22), fill=BRAND)

# 相机机身
d.rounded_rectangle([int(0.16 * S), int(0.30 * S), int(0.84 * S), int(0.76 * S)],
                    radius=int(0.07 * S), fill=WHITE)
# 取景器凸起
d.rounded_rectangle([int(0.34 * S), int(0.23 * S), int(0.52 * S), int(0.32 * S)],
                    radius=int(0.03 * S), fill=WHITE)
# 镜头
cx, cy = int(0.50 * S), int(0.53 * S)
r = int(0.13 * S)
d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=BRAND)
r2 = int(0.075 * S)
d.ellipse([cx - r2, cy - r2, cx + r2, cy + r2], fill=WHITE)
# 闪光灯
fx, fy, fr = int(0.72 * S), int(0.38 * S), int(0.022 * S)
d.ellipse([fx - fr, fy - fr, fx + fr, fy + fr], fill=BRAND)

sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for density, px in sizes.items():
    out_dir = os.path.join(RES, "mipmap-" + density)
    os.makedirs(out_dir, exist_ok=True)
    img.resize((px, px), Image.LANCZOS).save(os.path.join(out_dir, "ic_launcher.png"))
    print("wrote", os.path.join(out_dir, "ic_launcher.png"), px)

# 顺手存一份大图做展示
os.makedirs(os.path.join(ROOT, "docs"), exist_ok=True)
img.resize((512, 512), Image.LANCZOS).save(os.path.join(ROOT, "docs", "icon_512.png"))
print("ok")
