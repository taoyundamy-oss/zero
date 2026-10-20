# Zero Python Sandbox — D105 SandboxPolicy 镜像（C2.2 · C2.4a 库白名单 D109 · C2.4b IO D110）
# 库白名单预装（非运行时 pip——供应链红线；需要新库时显式评估后追加并重固定 digest）
# 字体：fonts-noto-cjk（Z22——matplotlib 中文标签防豆腐块）
# 入口约定（C2.4b/Z25）：直接执行 /zero/task/code.py——Worker 宿主侧建任务目录
#   （code.py + in/）bind-ro 挂入；产物写 /zero/io/out/（tmpfs）。
#   docker cp 注入通道被 daemon 拒绝（PUT archive 对 read-only 容器一律 400，Z25）。
# 运行身份：由 Worker 以 --user 65534:65534（nobody, non-root）创建
FROM python:3.12-slim

# Aliyun apt 镜像（E4 同则）+ CJK 字体
RUN (sed -i 's|deb.debian.org|mirrors.aliyun.com|g' /etc/apt/sources.list.d/debian.sources 2>/dev/null || true) \
    && (sed -i 's|deb.debian.org|mirrors.aliyun.com|g' /etc/apt/sources.list 2>/dev/null || true) \
    && apt-get update \
    && apt-get install -y --no-install-recommends fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*

# 库白名单（D109：11 库；版本下限 + 镜像 digest 冻结双保险）
# matplotlib：Agg 后端（无显示）+ 缓存目录指向运行时 tmpfs（read-only rootfs 下可写，Z22）
RUN pip install --no-cache-dir \
        "numpy>=2.0" "pandas>=2.2" "matplotlib>=3.9" "scipy>=1.14" "sympy>=1.13" \
        "scikit-learn>=1.5" "openpyxl>=3.1" "python-docx>=1.1" "Pillow>=10.4" \
        "pypdf>=4.3" "beautifulsoup4>=4.12" \
        -i https://mirrors.aliyun.com/pypi/simple/

# Z22 实测修订：字体已装但 matplotlib 默认 DejaVu Sans 仍出豆腐块——
# 覆写镜像内置 matplotlibrc 默认字体族（用户代码零配置，中文开箱即用）
RUN MPLRC=$(python3 -c "import matplotlib, os; print(os.path.join(os.path.dirname(matplotlib.__file__), 'mpl-data', 'matplotlibrc'))") \
    && sed -i 's|^#font.family:.*|font.family: sans-serif|; s|^#font.sans-serif:.*|font.sans-serif: Noto Sans CJK SC, DejaVu Sans|; s|^#axes.unicode_minus:.*|axes.unicode_minus: False|' "$MPLRC" \
    && grep -E '^font\.|^axes\.unicode' "$MPLRC"

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONHASHSEED=0 \
    MPLBACKEND=Agg \
    MPLCONFIGDIR=/tmp/mplconfig

CMD ["python3", "/zero/task/code.py"]
