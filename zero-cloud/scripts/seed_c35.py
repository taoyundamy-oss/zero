"""C3.5 验证种子：Noto CJK 渲染中文文字 → 无文本层扫描 PDF + PNG（worker 容器内运行）。

用法：
  docker cp /opt/zero-cloud/scripts/seed_c35.py zero-worker:/tmp/seed_c35.py
  docker exec zero-worker python /tmp/seed_c35.py
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, "/app")
from tasks.file import _register_file  # noqa: E402

from PIL import Image, ImageDraw, ImageFont  # noqa: E402

FONT = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
out = Path("/data/zero/test")
out.mkdir(parents=True, exist_ok=True)

img = Image.new("RGB", (900, 320), "white")
d = ImageDraw.Draw(img)
font = ImageFont.truetype(FONT, 40)
d.text((40, 50), "零云 OCR 双引擎验证", fill="black", font=font)
d.text((40, 140), "扫描件识别测试 2026", fill="black", font=font)
d.text((40, 230), "Engine Test 12345", fill="black", font=font)

png = out / "c35-scan.png"
img.save(png)
pdf = out / "c35-scan.pdf"   # PIL 直存 PDF = 纯图像页，无文本层（扫描件等价物）
img.save(pdf)

for p, mime in [(pdf, "application/pdf"), (png, "image/png")]:
    print(p.name, json.dumps(_register_file(p, mime), ensure_ascii=False))
