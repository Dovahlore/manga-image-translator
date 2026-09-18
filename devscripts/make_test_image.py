"""生成一张模拟漫画页，用于 manga-image-translator 的冒烟测试。
白底 + 白色气泡 + 黑色日文（横排/竖排各一处），能触发检测→OCR→翻译→抹字→嵌字全链路。
"""
from PIL import Image, ImageDraw, ImageFont
import os

FONT = r"D:\mayuq\OneDrive - bupt.edu.cn\Projects\manga-image-translator\fonts\msgothic.ttc"
OUT = r"D:\mit-work\input\test_page.png"

W, H = 900, 1200
img = Image.new("RGB", (W, H), (232, 232, 232))
d = ImageDraw.Draw(img)

# 画两格
d.rectangle([20, 20, W - 20, 580], outline=(30, 30, 30), width=4)
d.rectangle([20, 610, W - 20, H - 20], outline=(30, 30, 30), width=4)
# 简单“背景”（灰阶斜线），验证抹字修复不会把背景抹花
for x in range(30, W - 30, 24):
    d.line([(x, 30), (x + 200, 570)], fill=(205, 205, 205), width=6)
for x in range(30, W - 30, 24):
    d.line([(x, 620), (x + 200, H - 30)], fill=(198, 198, 198), width=6)

font_big = ImageFont.truetype(FONT, 44)
font_mid = ImageFont.truetype(FONT, 36)

# 气泡 1（横排）
d.ellipse([120, 120, 780, 460], fill=(255, 255, 255), outline=(20, 20, 20), width=3)
d.text((190, 240), "これは漫画の", font=font_big, fill=(0, 0, 0))
d.text((190, 310), "テストです。", font=font_big, fill=(0, 0, 0))

# 气泡 2（竖排：逐字画）
d.ellipse([300, 700, 640, 1080], fill=(255, 255, 255), outline=(20, 20, 20), width=3)
text_v = "翻訳してみよう"
x0, y0 = 560, 740
for i, ch in enumerate(text_v):
    d.text((x0, y0 + i * 40), ch, font=font_mid, fill=(0, 0, 0))

os.makedirs(os.path.dirname(OUT), exist_ok=True)
img.save(OUT)
print("written:", OUT, img.size)
