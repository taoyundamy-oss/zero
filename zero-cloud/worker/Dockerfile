FROM python:3.12-slim

WORKDIR /app

# ffmpeg：yt-dlp 合流（bv*+ba）与音频提取（-x mp3）必需（C3.3）
# tesseract+chi_sim/ghostscript/ocrmypdf：C3.5 OCR 兜底档与 pdf_ocr 回嵌（D108）
# fonts-noto-cjk：C3.5 测试夹具中文渲染（C2.4a 起沙箱镜像亦需要）
RUN (sed -i 's|deb.debian.org|mirrors.aliyun.com|g' /etc/apt/sources.list.d/debian.sources 2>/dev/null || true) \
    && (sed -i 's|deb.debian.org|mirrors.aliyun.com|g' /etc/apt/sources.list 2>/dev/null || true) \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        ffmpeg tesseract-ocr tesseract-ocr-chi-sim ghostscript fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*

COPY worker/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt -i https://mirrors.aliyun.com/pypi/simple/

# S5.1：构建上下文改为仓库根（compose context: .）——ssrf 单源经 common/ 并入
COPY worker/ .
COPY common/ssrf.py ./ssrf.py

# compose 中 command 会覆盖；此为缺省对齐值
CMD ["celery", "-A", "celery_app", "worker", "--loglevel=info", "--concurrency=2", "-Q", "fast,cpu,download,sandbox"]
