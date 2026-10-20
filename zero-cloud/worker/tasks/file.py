"""tasks.file — 文件任务（C3.1 convert；C3.2 process；C3.5 OCR 双引擎）

convert：/data/zero 输入文件 → Gotenberg LibreOffice → PDF → 登记新 file_id（Z6 元数据）
process：pdf_extract_text(ocr) / pdf_split / docx_read / xlsx_read / image_resize / pdf_ocr / image_ocr
  （全部 AUTO——只读提取或产生新文件，不破坏原件，设计 §工具表）
队列：fast（task_routes "tasks.file.*"）。

D108 OCR 双引擎：
  - 内容提取：qwen-vl（DashScope 原生多模态端点，Z18——非 OpenAI 兼容；97%+）→ tesseract 降级链
  - 可搜索 PDF 回嵌：ocrmypdf/tesseract（VL 无法回嵌文字层——此路径唯一）
  - 渲页：pypdfium2（Z13——禁 PyMuPDF/AGPL）
  - Key 缺省/调用失败 → 自动降级 tesseract（fail-safe，Z18）

D106（SSRF）：本任务对 Gotenberg 只发 multipart 文件字节，零 URL source——
LibreOffice 路由本无 downloadFrom 字段，即使误传，L3 DOCKER-USER 已断私网出口。

大文本阈值与 python_exec 同款：内联 ≤32KB，超出落 result_ref（Z5）。
失败即 failed（max_retries=0，诚实失败同 python_exec）；worker 崩溃由 acks_late 重派。
"""
import hashlib
import json
import os
import subprocess
import time
import uuid as uuidlib
from pathlib import Path

import httpx

from celery_app import app, save_big_result, update_meta
from tasks.lib.files import (register_file as _register_file,
    file_result as _file_result, load_file_meta as _load_file_meta,
    resolve_under_data_dir as _resolve_under_data_dir, sha256_of as _sha256,
    MAX_FILE_SIZE_MB, DATA_DIR, guess_mime)


GOTENBERG_URL = os.getenv("GOTENBERG_URL", "http://gotenberg:3000")
# S5.1：文件助手已归位 tasks/lib/files.py（上方别名导入，本模块行为零变化）

# D108：VL 档配置（Key 缺省自动降级 tesseract）
DASHSCOPE_API_KEY = os.getenv("DASHSCOPE_API_KEY", "")
VL_MODEL = os.getenv("VL_MODEL", "qwen-vl-plus")
VL_ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
VL_BATCH = 6          # 单请求图数上限（20 页 150dpi PNG 全量拼装会超请求体上限）
OCR_RENDER_DPI = 150
OCR_MAX_PAGES = 20    # 单次 OCR 页数上限（超出截断并提示分批）

# Gotenberg 8 LibreOffice 路由核心格式（先拒不支持项，免 LibreOffice 挂起/乱转）
CONVERTIBLE_EXT = {
    ".doc", ".docx", ".odt", ".rtf",
    ".xls", ".xlsx", ".ods", ".csv",
    ".ppt", ".pptx", ".odp", ".txt",
}

# process 动作 × 允许的源扩展名（gateway 同表早验，worker 权威复验）
ACTION_EXT = {
    "pdf_extract_text": {".pdf"},
    "pdf_split": {".pdf"},
    "docx_read": {".docx"},
    "xlsx_read": {".xlsx"},
    "image_resize": {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"},
    "pdf_ocr": {".pdf"},
    "image_ocr": {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"},
}

TEXT_INLINE = 32_000   # 内联上限（python_exec 同款）
TEXT_TAIL = 2_000
MAX_IMAGE_INPUT_MB = 50
MAX_IMAGE_PIXELS = 80_000_000
XLSX_MAX_ROWS = 2_000


# S5.1：文件助手已归位 tasks/lib/files.py（上方别名导入，本模块行为零变化）


# ═══════════════════════════════════════════
# C3.1 convert
# ═══════════════════════════════════════════

@app.task(name="tasks.file.convert", bind=True, max_retries=0)
def file_convert(self, file_id: str, target_format: str = "pdf"):
    task_id = self.request.id
    update_meta(task_id, status="running", started_at=int(time.time()), retry_count=0)
    try:
        if target_format != "pdf":
            raise ValueError(f"不支持的目标格式: {target_format}（仅 pdf）")
        meta = _load_file_meta(file_id)
        src = _resolve_under_data_dir(meta.get("path", ""))
        ext = src.suffix.lower()
        if ext not in CONVERTIBLE_EXT:
            raise ValueError(f"不支持的源格式 {ext}（支持: {' '.join(sorted(CONVERTIBLE_EXT))}）")
        if not src.is_file():
            raise FileNotFoundError(f"文件缺失: {src.name}")
        if src.stat().st_size > MAX_FILE_SIZE_MB * 1024 * 1024:
            raise ValueError(f"文件超过 {MAX_FILE_SIZE_MB}MB 配额上限")

        # 只收上传字节（D106）——files 字段为文件本体，无任何 URL 类字段
        with open(src, "rb") as f:
            resp = httpx.post(
                f"{GOTENBERG_URL}/forms/libreoffice/convert",
                files={"files": (src.name, f, "application/octet-stream")},
                timeout=httpx.Timeout(10.0, read=110.0),
            )
        if resp.status_code != 200:
            raise RuntimeError(f"Gotenberg {resp.status_code}: {resp.text[:150]}")

        out_dir = Path(DATA_DIR) / "convert"
        out_dir.mkdir(parents=True, exist_ok=True)
        out = out_dir / f"{task_id}.pdf"
        out.write_bytes(resp.content)

        reg = _register_file(out, "application/pdf")
        result = {"source_file_id": file_id, **_file_result(reg)}
        update_meta(task_id, status="done", finished_at=int(time.time()), result=result)
        return result
    except Exception as e:
        update_meta(task_id, status="failed", error=str(e)[:300], finished_at=int(time.time()))
        raise


# ═══════════════════════════════════════════
# C3.2 process
# ═══════════════════════════════════════════

def _pack_text(task_id: str, result: dict, key: str, text: str) -> None:
    """大文本走 result_ref（Z5），内联截断尾部预览"""
    result[f"{key}_len"] = len(text)
    if len(text) > TEXT_INLINE:
        result[f"{key}_ref"] = save_big_result(task_id, {key: text})
        result[f"{key}_tail"] = text[:TEXT_TAIL]
        result["note"] = f"{key} 超 {TEXT_INLINE} 字符，完整内容见 {key}_ref"
    else:
        result[key] = text


def _parse_pages(spec, total: int) -> tuple[list[int], str]:
    """'1-3,5'（1 基）→ 页索引列表；空 → 全部。返回 (索引, 规格描述)"""
    if spec is None or not str(spec).strip():
        return list(range(total)), "all"
    idx: list[int] = []
    seen: set[int] = set()
    for part in str(spec).split(","):
        part = part.strip()
        if not part:
            continue
        try:
            if "-" in part:
                a, _, b = part.partition("-")
                lo, hi = int(a), int(b)
                if lo < 1 or hi < lo or hi > total:
                    raise ValueError
                for i in range(lo - 1, hi):
                    if i not in seen:
                        seen.add(i)
                        idx.append(i)
            else:
                n = int(part)
                if n < 1 or n > total:
                    raise ValueError
                i = n - 1
                if i not in seen:
                    seen.add(i)
                    idx.append(i)
        except ValueError:
            raise ValueError(f"pages 非法: {part!r}（应为 1-{total} 的页码/区间，如 \"1-3,5\"）") from None
    if not idx:
        raise ValueError("pages 解析为空")
    return idx, str(spec).strip()


def _pdf_split(task_id: str, src: Path, params: dict) -> dict:
    from pypdf import PdfReader, PdfWriter
    reader = PdfReader(str(src))
    total = len(reader.pages)
    idx, spec = _parse_pages(params.get("pages"), total)
    if len(idx) == total:
        raise ValueError("pages 覆盖全部页面——拆分无意义（选择子集，如 \"2-5\"）")
    writer = PdfWriter()
    for i in idx:
        writer.add_page(reader.pages[i])
    out_dir = Path(DATA_DIR) / "process"
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"{task_id}.pdf"
    with open(out, "wb") as f:
        writer.write(f)
    reg = _register_file(out, "application/pdf")
    return {"pages": spec, "page_count_out": len(idx), "page_count_source": total, **_file_result(reg)}


def _docx_read(task_id: str, src: Path) -> dict:
    from docx import Document
    doc = Document(str(src))
    lines = [p.text for p in doc.paragraphs if p.text.strip()]
    para_count = len(lines)
    for ti, table in enumerate(doc.tables, 1):
        lines.append(f"〔表{ti}〕")
        for row in table.rows:
            lines.append(" | ".join(c.text.strip() for c in row.cells))
    result = {"paragraph_count": para_count, "table_count": len(doc.tables)}
    _pack_text(task_id, result, "text", "\n".join(lines))
    return result


def _xlsx_read(task_id: str, src: Path) -> dict:
    from openpyxl import load_workbook
    wb = load_workbook(str(src), read_only=True, data_only=True)
    lines: list[str] = []
    sheet_names = []
    try:
        for ws in wb.worksheets:
            sheet_names.append(ws.title)
            lines.append(f"〔工作表：{ws.title}〕")
            for r, row in enumerate(ws.iter_rows(values_only=True)):
                if r >= XLSX_MAX_ROWS:
                    lines.append(f"…（截断：每表最多 {XLSX_MAX_ROWS} 行）")
                    break
                vals = ["" if v is None else str(v) for v in row]
                if any(v.strip() for v in vals):
                    lines.append(" | ".join(vals))
    finally:
        wb.close()
    result = {"sheets": sheet_names}
    _pack_text(task_id, result, "text", "\n".join(lines))
    return result


def _image_resize(task_id: str, src: Path, params: dict) -> dict:
    from PIL import Image
    if src.stat().st_size > MAX_IMAGE_INPUT_MB * 1024 * 1024:
        raise ValueError(f"图片超过 {MAX_IMAGE_INPUT_MB}MB 处理上限")
    try:
        w = int(params.get("width") or 0)
        h = int(params.get("height") or 0)
    except (TypeError, ValueError):
        raise ValueError("width/height 须为整数") from None
    if w < 0 or h < 0 or w > 10_000 or h > 10_000:
        raise ValueError("width/height 取值范围 1-10000")
    if not w and not h:
        raise ValueError("image_resize 需至少提供 width 或 height 之一")

    with Image.open(src) as im0:
        fmt = (im0.format or "PNG").upper()
        if im0.width * im0.height > MAX_IMAGE_PIXELS:
            raise ValueError(f"图片像素超上限（{im0.width}x{im0.height}）")
        im = im0.convert("RGB") if im0.mode not in ("RGB", "L") else im0.copy()
    ow, oh = im.size
    if w and h:
        im = im.resize((w, h))                     # 双参：精确尺寸
    else:
        im.thumbnail((w or ow, h or oh))           # 单参：等比缩至边界内（不放大）
    ext_out = {"JPEG": ".jpg", "PNG": ".png", "WEBP": ".webp"}.get(fmt, ".png")
    out_dir = Path(DATA_DIR) / "process"
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"{task_id}{ext_out}"
    save_kw = {"quality": 85} if ext_out in (".jpg", ".webp") else {}
    im.save(out, **save_kw)
    reg = _register_file(out, f"image/{'jpeg' if ext_out == '.jpg' else ext_out.lstrip('.')}")
    return {"orig_width": ow, "orig_height": oh, "new_width": im.width, "new_height": im.height,
            **_file_result(reg)}


# ═══════════════════════════════════════════
# C3.5 OCR 双引擎（D108）
# ═══════════════════════════════════════════

def _render_pages(src: Path, page_idx: list[int], dpi: int = OCR_RENDER_DPI) -> list[bytes]:
    """pypdfium2 渲页 PNG（Z13：禁 PyMuPDF/AGPL）"""
    import io as _io

    import pypdfium2 as pdfium
    pdf = pdfium.PdfDocument(str(src))
    try:
        out = []
        scale = dpi / 72
        for i in page_idx:
            pil = pdf[i].render(scale=scale).to_pil()
            buf = _io.BytesIO()
            pil.save(buf, format="PNG")
            out.append(buf.getvalue())
        return out
    finally:
        pdf.close()


def _vl_ocr(images: list[tuple[bytes, str]]) -> str:
    """qwen-vl 提取（DashScope 原生多模态端点，Z18；分批防超请求体）。images=[(字节, mime子类型)]"""
    import base64

    texts = []
    for k in range(0, len(images), VL_BATCH):
        content = [
            {"image": f"data:image/{mime};base64," + base64.b64encode(data).decode()}
            for data, mime in images[k:k + VL_BATCH]
        ]
        content.append({"text": "提取图片中的全部文字（多图按序拼接），按阅读顺序输出纯文本，"
                                "表格可用 Markdown 保留结构；只输出文字内容，不要解释。"})
        resp = httpx.post(
            VL_ENDPOINT,
            headers={"Authorization": f"Bearer {DASHSCOPE_API_KEY}",
                     "Content-Type": "application/json"},
            json={"model": VL_MODEL,
                  "input": {"messages": [{"role": "user", "content": content}]}},
            timeout=httpx.Timeout(10.0, read=120.0),
        )
        if resp.status_code != 200:
            raise RuntimeError(f"VL HTTP {resp.status_code}: {resp.text[:120]}")
        texts.append(resp.json()["output"]["choices"][0]["message"]["content"][0]["text"] or "")
    return "\n".join(t.strip() for t in texts if t.strip())


def _tess_ocr_file(path: Path) -> str:
    proc = subprocess.run(
        ["tesseract", str(path), "stdout", "-l", "chi_sim+eng", "--psm", "6"],
        capture_output=True, text=True, timeout=120)
    if proc.returncode != 0:
        raise RuntimeError(f"tesseract 退出 {proc.returncode}: {proc.stderr.strip()[-120:]}")
    return proc.stdout.strip()


def _ocr_chain(images: list[tuple[bytes, str]], tmp_dir: Path, engine: str = "auto") -> tuple[str, str]:
    """降级链（Z18）：auto/vl 有 Key 走 VL，失败（或无 Key）落 tesseract；engine=tesseract 直走"""
    if engine == "tesseract":
        parts = []
        for k, (data, _) in enumerate(images):
            p = tmp_dir / f"ocr_{uuidlib.uuid4().hex[:8]}_{k}.png"
            p.write_bytes(data)
            try:
                parts.append(_tess_ocr_file(p))
            finally:
                p.unlink(missing_ok=True)
        return "\n\n".join(x for x in parts if x), "tesseract"
    if DASHSCOPE_API_KEY:
        try:
            return _vl_ocr(images), "vl"
        except Exception:
            if engine == "vl":
                raise  # 显式点名 vl 则不降级（诚实失败）
    # auto 降级 / 无 Key
    parts = []
    for k, (data, _) in enumerate(images):
        p = tmp_dir / f"ocr_{uuidlib.uuid4().hex[:8]}_{k}.png"
        p.write_bytes(data)
        try:
            parts.append(_tess_ocr_file(p))
        finally:
            p.unlink(missing_ok=True)
    return "\n\n".join(x for x in parts if x), "tesseract"


def _pdf_extract_text(task_id: str, src: Path, params: dict) -> dict:
    from pypdf import PdfReader
    ocr_mode = params.get("ocr", "auto")
    if ocr_mode not in ("off", "auto", "force"):
        raise ValueError("ocr 须为 off/auto/force")
    reader = PdfReader(str(src))
    total = len(reader.pages)
    idx, spec = _parse_pages(params.get("pages"), total)
    truncated = False
    if len(idx) > OCR_MAX_PAGES:
        idx = idx[:OCR_MAX_PAGES]
        truncated = True

    layered = {i: (reader.pages[i].extract_text() or "").strip() for i in idx}
    # auto：文本层空的页；force：全部（无视文本层）
    ocr_targets = [i for i in idx if ocr_mode == "force" or (ocr_mode == "auto" and not layered[i])]
    renders = {}
    if ocr_targets and ocr_mode != "off":
        renders = dict(zip(ocr_targets, _render_pages(src, ocr_targets)))

    tmp_dir = Path(DATA_DIR) / "process"
    tmp_dir.mkdir(parents=True, exist_ok=True)
    parts, engines = [], set()
    for i in idx:
        if layered[i] and ocr_mode != "force":
            parts.append(f"〔第{i + 1}页〕\n{layered[i]}")
        elif i in renders:
            text, eng = _ocr_chain([(renders[i], "png")], tmp_dir)
            engines.add(eng)
            tag = f"〔第{i + 1}页·OCR-{eng}〕"
            parts.append(f"{tag}\n{text}" if text else f"{tag}（无文字）")
        elif ocr_mode == "off":
            parts.append(f"〔第{i + 1}页〕（无文本层——扫描件？设 ocr=auto/force 可识别）")
        else:
            parts.append(f"〔第{i + 1}页〕（无内容）")
    result = {"page_count": total, "pages": spec, "ocr_mode": ocr_mode}
    if engines:
        result["ocr_engines"] = sorted(engines)
    if truncated:
        result["note"] = f"页数超 {OCR_MAX_PAGES} 上限，仅处理前 {OCR_MAX_PAGES} 页（可 pages 分批）"
    _pack_text(task_id, result, "text", "\n\n".join(parts))
    return result


def _pdf_ocr(task_id: str, src: Path, params: dict) -> dict:
    """回嵌可搜索文字层（D108：此路径恒 tesseract——VL 无法回嵌）"""
    out_dir = Path(DATA_DIR) / "process"
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"{task_id}.pdf"
    proc = subprocess.run(
        ["ocrmypdf", "-l", "chi_sim+eng", "--jobs", "2", "--deskew", "--skip-text",
         "--output-type", "pdf", str(src), str(out)],
        capture_output=True, text=True, timeout=600)
    if proc.returncode != 0:
        raise RuntimeError(f"ocrmypdf 退出 {proc.returncode}: {proc.stderr.strip()[-250:]}")
    reg = _register_file(out, "application/pdf")
    return {"engine": "tesseract", "note": "已回嵌文字层——产物可用 pdf_extract_text(ocr=off) 直接检索",
            **_file_result(reg)}


_IMG_MIME = {".jpg": "jpeg", ".jpeg": "jpeg", ".png": "png", ".webp": "webp", ".gif": "gif", ".bmp": "bmp"}


def _image_ocr(task_id: str, src: Path, params: dict) -> dict:
    engine = params.get("engine", "auto")
    if engine not in ("auto", "vl", "tesseract"):
        raise ValueError("engine 须为 auto/vl/tesseract")
    if src.stat().st_size > MAX_IMAGE_INPUT_MB * 1024 * 1024:
        raise ValueError(f"图片超过 {MAX_IMAGE_INPUT_MB}MB 处理上限")
    mime = _IMG_MIME.get(src.suffix.lower(), "png")
    tmp_dir = Path(DATA_DIR) / "process"
    tmp_dir.mkdir(parents=True, exist_ok=True)
    data = src.read_bytes()
    if engine == "tesseract":
        text, eng = _tess_ocr_file(src), "tesseract"
    else:
        try:
            text, eng = _ocr_chain([(data, mime)], tmp_dir, engine="vl")
        except Exception:
            if engine == "vl":
                raise
            text, eng = _tess_ocr_file(src), "tesseract"
    result = {"engine": eng}
    _pack_text(task_id, result, "text", text)
    return result


@app.task(name="tasks.file.process", bind=True, max_retries=0)
def file_process(self, file_id: str, action: str, params: dict | None = None):
    task_id = self.request.id
    params = params or {}
    update_meta(task_id, status="running", started_at=int(time.time()), retry_count=0)
    try:
        if action not in ACTION_EXT:
            raise ValueError(f"未知 action: {action}（支持: {' '.join(ACTION_EXT)}）")
        meta = _load_file_meta(file_id)
        src = _resolve_under_data_dir(meta.get("path", ""))
        ext = src.suffix.lower()
        if ext not in ACTION_EXT[action]:
            raise ValueError(f"action {action} 不支持源格式 {ext}（支持: {' '.join(sorted(ACTION_EXT[action]))}）")
        if not src.is_file():
            raise FileNotFoundError(f"文件缺失: {src.name}")
        if src.stat().st_size > MAX_FILE_SIZE_MB * 1024 * 1024:
            raise ValueError(f"文件超过 {MAX_FILE_SIZE_MB}MB 配额上限")

        if action == "pdf_extract_text":
            r = _pdf_extract_text(task_id, src, params)
        elif action == "pdf_split":
            r = _pdf_split(task_id, src, params)
        elif action == "docx_read":
            r = _docx_read(task_id, src)
        elif action == "xlsx_read":
            r = _xlsx_read(task_id, src)
        elif action == "pdf_ocr":
            r = _pdf_ocr(task_id, src, params)
        elif action == "image_ocr":
            r = _image_ocr(task_id, src, params)
        else:
            r = _image_resize(task_id, src, params)

        result = {"action": action, "source_file_id": file_id, **r}
        update_meta(task_id, status="done", finished_at=int(time.time()), result=result)
        return result
    except Exception as e:
        update_meta(task_id, status="failed", error=str(e)[:300], finished_at=int(time.time()))
        raise
