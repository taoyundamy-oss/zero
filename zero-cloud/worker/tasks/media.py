"""tasks.media — cloud_media（S6.1 / D119 · P0-1/F2 封板）

ffmpeg 在 worker 镜像直跑（不进 sandbox——C3.3 已装 ffmpeg，ffprobe 随包）。
动作：cut（-ss/-t 片段，流复制无损快切）/ concat（多输入合流）/ transcode（格式+分辨率+码率）
/ extract_audio（视频→mp3/m4a）/ probe（ffprobe 元数据，无产物）。

协议复用文件体系（Z6）：输入 file_id → _load_file_meta（owner 断言/TTL）→ 产物 _register_file
（download_file 拉回）。输入总量 ≤512MB（MEDIA_INPUT_QUOTA_MB——仅输入配额，非工作空间上限）。

工作空间守护（P0-1/F2）：
  ① 执行前 df 可用空间检查（输入总量×3 预估中转+产物），不足诚实拒绝
  ② 媒体任务并发=1：独立 media 队列 + 专用 worker（compose worker-media --concurrency=1）
  ③ 失败/超时 finally 清理临时目录；kill 场景由「下任务启动清扫 .tmp_*」兜底
     （并发=1 ⇒ 清扫时必无其他活动媒体任务，残留皆为可安全删除的孤儿）

安全（D119）：ffmpeg 参数白名单化构造——列表 argv 模板拼接，禁 shell；无网络；
数值参数（时长/分辨率/码率）正则+界域校验后才入 argv。

队列：media（celery_app task_routes "tasks.media.*"→media，与 fast/cpu/download 隔离）。
超时 600s（subprocess timeout，杀 ffmpeg 进程树）；失败即 failed（max_retries=0 诚实失败）。
"""
import json
import os
import re
import shutil
import subprocess
import time
from pathlib import Path

from celery_app import app, update_meta
from tasks.lib.files import (register_file as _register_file,
    file_result as _file_result, load_file_meta as _load_file_meta,
    resolve_under_data_dir as _resolve_under_data_dir, guess_mime)

DATA_DIR = os.getenv("DATA_DIR", "/data/zero")
MEDIA_INPUT_QUOTA_MB = int(os.getenv("MEDIA_INPUT_QUOTA_MB", "512"))
FFMPEG_TIMEOUT_S = 600

# 视频容器族（cut/concat/transcode/extract_audio 源；probe 另含音频）
VIDEO_EXT = {".mp4", ".webm", ".mkv", ".mov", ".avi", ".gif", ".flv", ".ts", ".m4v"}
AUDIO_EXT = {".mp3", ".m4a", ".wav", ".aac", ".flac", ".ogg"}
TRANSCODE_TARGET = {"mp4", "webm", "mkv", "mov", "avi", "gif"}
AUDIO_TARGET = {"mp3", "m4a"}

# S5.1：MIME_OUT 并入 tasks.lib.files.EXT_MIME（guess_mime 统一出口）
# 动作 × 允许的源扩展名（gateway 同表早验，worker 权威复验）
# S6.1-fix（L3 实测）：cut/transcode 开放音频源（.m4a/.mp3/.wav 切片/转码——ffmpeg 原生
# 支持；音频源仅音频目标，视频→音频走 extract_audio）
ACTION_EXT = {
    "cut": VIDEO_EXT | AUDIO_EXT,
    "concat": VIDEO_EXT,
    "transcode": VIDEO_EXT | AUDIO_EXT,
    "extract_audio": VIDEO_EXT,
    "probe": VIDEO_EXT | AUDIO_EXT,
}

_TIME_RE = re.compile(r"^([0-9]{1,2}:)?[0-9]{1,2}:[0-9]{2}(\.[0-9]{1,3})?$|^[0-9]+(\.[0-9]+)?$")


def _disk_free_bytes() -> int:
    st = os.statvfs(DATA_DIR)
    return st.f_bavail * st.f_frsize


def _sweep_stale_tmp() -> int:
    """清扫 kill 残留的临时目录（.tmp_*）——并发=1 ⇒ 现存 .tmp_* 皆为孤儿。@return 清扫数"""
    media_dir = Path(DATA_DIR) / "media"
    if not media_dir.is_dir():
        return 0
    n = 0
    for p in media_dir.glob(".tmp_*"):
        shutil.rmtree(p, ignore_errors=True)
        n += 1
    return n


def _valid_time(v) -> str:
    s = str(v or "").strip()
    if not _TIME_RE.match(s):
        raise ValueError(f"时间参数非法: {v!r}（支持秒数 90 或 mm:ss / hh:mm:ss）")
    return s


def _valid_int(v, lo: int, hi: int, name: str) -> int:
    try:
        n = int(v)
    except (TypeError, ValueError):
        raise ValueError(f"{name} 须为整数") from None
    if n < lo or n > hi:
        raise ValueError(f"{name} 取值范围 {lo}-{hi}")
    return n


def _run_ffmpeg(argv: list[str]) -> None:
    """白名单 argv 直跑（列表形式，禁 shell）。非零退出 → 携 stderr 尾部失败"""
    proc = subprocess.run(argv, capture_output=True, text=True, timeout=FFMPEG_TIMEOUT_S)
    if proc.returncode != 0:
        raise RuntimeError(f"ffmpeg 退出 {proc.returncode}: {proc.stderr.strip()[-250:]}")


@app.task(name="tasks.media.process", bind=True, max_retries=0)
def media_process(self, action: str, file_ids: list[str], params: dict | None = None):
    task_id = self.request.id
    params = params or {}
    update_meta(task_id, status="running", started_at=int(time.time()), retry_count=0)
    tmp_dir = Path(DATA_DIR) / "media" / f".tmp_{task_id}"
    out_path: Path | None = None
    registered = False
    try:
        if action not in ACTION_EXT:
            raise ValueError(f"未知 action: {action}（支持: {' '.join(ACTION_EXT)}）")
        if not file_ids or len(file_ids) > 16:
            raise ValueError("file_ids 须为 1-16 个")

        # ── 输入解析 + 配额（输入总量 ≤512MB——仅输入配额）──
        srcs: list[Path] = []
        total_in = 0
        for fid in file_ids:
            meta = _load_file_meta(fid)
            src = _resolve_under_data_dir(meta.get("path", ""))
            if not src.is_file():
                raise FileNotFoundError(f"输入文件缺失: {src.name}")
            ext = src.suffix.lower()
            if ext not in ACTION_EXT[action]:
                raise ValueError(
                    f"action {action} 不支持源格式 {ext}（支持: {' '.join(sorted(ACTION_EXT[action]))}）")
            srcs.append(src)
            total_in += src.stat().st_size
        if total_in > MEDIA_INPUT_QUOTA_MB * 1024 * 1024:
            raise ValueError(f"输入总量 {total_in // 1048576}MB 超过 {MEDIA_INPUT_QUOTA_MB}MB 配额（仅输入配额，可分批）")

        # ── P0-1 工作空间守护：df 可用空间 ≥ 输入×3（预估中转+产物）──
        free = _disk_free_bytes()
        need = total_in * 3
        if free < need:
            raise ValueError(
                f"磁盘可用空间不足：需 ~{need // 1048576}MB（输入×3 预估），当前 {free // 1048576}MB——稍后重试或清理")

        # ── kill 残留清扫（并发=1 ⇒ 安全）──
        swept = _sweep_stale_tmp()
        if swept:
            update_meta(task_id, note=f"swept_stale_tmp={swept}")

        media_dir = Path(DATA_DIR) / "media"
        tmp_dir.mkdir(parents=True, exist_ok=True)

        if action == "probe":
            proc = subprocess.run(
                ["ffprobe", "-v", "quiet", "-print_format", "json",
                 "-show_format", "-show_streams", str(srcs[0])],
                capture_output=True, text=True, timeout=120)
            if proc.returncode != 0:
                raise RuntimeError(f"ffprobe 退出 {proc.returncode}: {proc.stderr.strip()[-200:]}")
            info = json.loads(proc.stdout or "{}")
            fmt = info.get("format", {})
            streams = [
                {k: s.get(k) for k in
                 ("codec_name", "codec_type", "width", "height", "r_frame_rate",
                  "sample_rate", "channels", "bit_rate") if s.get(k) is not None}
                for s in info.get("streams", [])[:12]]
            result = {
                "action": "probe", "source_file_id": file_ids[0],
                "duration_s": fmt.get("duration"), "size": fmt.get("size"),
                "bit_rate": fmt.get("bit_rate"), "format_name": fmt.get("format_name"),
                "streams": streams,
            }
            update_meta(task_id, status="done", finished_at=int(time.time()), result=result)
            return result

        # ── 产物路径（tmp 内写，成功后移入 media/ 登记——kill 半成品随 .tmp_ 清扫）──
        if action == "cut":
            start = _valid_time(params.get("start", "0"))
            has_dur = params.get("duration") is not None
            has_end = params.get("end") is not None
            if has_dur and has_end:
                raise ValueError("duration 与 end 二选一")
            out_ext = srcs[0].suffix.lower()
            out_path = tmp_dir / f"out{out_ext}"
            argv = ["ffmpeg", "-y", "-ss", start, "-i", str(srcs[0])]
            if has_dur:
                argv += ["-t", _valid_time(params["duration"])]
            elif has_end:
                argv += ["-to", _valid_time(params["end"])]
            argv += ["-c", "copy", str(out_path)]  # 流复制：快且无损
            _run_ffmpeg(argv)
            summary = {"start": start,
                       **({"duration": str(params["duration"])} if has_dur else
                          {"end": str(params["end"])} if has_end else {})}

        elif action == "concat":
            if len(srcs) < 2:
                raise ValueError("concat 需 ≥2 个输入 file_id")
            exts = {p.suffix.lower() for p in srcs}
            if len(exts) != 1:
                raise ValueError(f"concat 输入格式须一致（当前: {' '.join(sorted(exts))}）——请先 transcode 统一")
            out_ext = srcs[0].suffix.lower()
            list_file = tmp_dir / "concat.txt"
            list_file.write_text(
                "".join(f"file '{p}'\n" for p in srcs), encoding="utf-8")
            out_path = tmp_dir / f"out{out_ext}"
            _run_ffmpeg(["ffmpeg", "-y", "-f", "concat", "-safe", "0",
                         "-i", str(list_file), "-c", "copy", str(out_path)])
            summary = {"parts": len(srcs)}

        elif action == "transcode":
            # S6.1-fix（L3 实测）：音频源（.m4a/.mp3/.wav…）→ 仅音频目标（m4a→mp3 等）；
            # 分辨率参数仅视频源；码率音频源落 -b:a
            src_is_audio = srcs[0].suffix.lower() in AUDIO_EXT
            allowed_targets = AUDIO_TARGET if src_is_audio else TRANSCODE_TARGET
            fmt = str(params.get("format", "")).lower().lstrip(".")
            if fmt not in allowed_targets:
                raise ValueError(
                    f"目标格式仅支持: {' '.join(sorted(allowed_targets))}"
                    + ("（音频源仅音频目标；视频→音频请用 extract_audio）" if src_is_audio else ""))
            out_ext = f".{fmt}"
            out_path = tmp_dir / f"out{out_ext}"
            argv = ["ffmpeg", "-y", "-i", str(srcs[0])]
            if src_is_audio:
                if params.get("width") is not None or params.get("height") is not None:
                    raise ValueError("音频源不支持分辨率参数（width/height 仅视频源）")
                if params.get("bitrate_kbps") is not None:
                    argv += ["-b:a", f"{_valid_int(params['bitrate_kbps'], 16, 320, 'bitrate_kbps')}k"]
            else:
                vf = []
                if params.get("width") is not None:
                    vf.append(f"scale={_valid_int(params['width'], 16, 7680, 'width')}:-2")
                elif params.get("height") is not None:
                    vf.append(f"scale=-2:{_valid_int(params['height'], 16, 4320, 'height')}")
                if vf:
                    argv += ["-vf", vf[0]]
                if params.get("bitrate_kbps") is not None:
                    argv += ["-b:v", f"{_valid_int(params['bitrate_kbps'], 64, 20000, 'bitrate_kbps')}k"]
                if fmt == "mp4":
                    argv += ["-movflags", "+faststart"]
            argv.append(str(out_path))
            _run_ffmpeg(argv)
            summary = {"format": fmt, **({"bitrate_kbps": int(params["bitrate_kbps"])} if params.get("bitrate_kbps") is not None else {})}

        elif action == "extract_audio":
            fmt = str(params.get("format", "mp3")).lower().lstrip(".")
            if fmt not in AUDIO_TARGET:
                raise ValueError(f"音频目标格式仅支持: {' '.join(sorted(AUDIO_TARGET))}")
            out_ext = f".{fmt}"
            out_path = tmp_dir / f"out{out_ext}"
            if fmt == "mp3":
                argv = ["ffmpeg", "-y", "-i", str(srcs[0]), "-vn",
                        "-c:a", "libmp3lame", "-q:a", "2", str(out_path)]
            else:
                argv = ["ffmpeg", "-y", "-i", str(srcs[0]), "-vn",
                        "-c:a", "aac", "-b:a", "192k", str(out_path)]
            _run_ffmpeg(argv)
            summary = {"format": fmt}

        else:
            raise ValueError(f"未知 action: {action}")

        if not out_path.is_file() or out_path.stat().st_size == 0:
            raise RuntimeError("ffmpeg 未产出有效文件")
        # 成功：tmp → media/ 固定位（同 fs replace；后续 .tmp_ 清扫不触及固定位）
        final_dir = media_dir
        final_dir.mkdir(parents=True, exist_ok=True)
        final_path = final_dir / f"{task_id}{out_path.suffix.lower()}"
        os.replace(out_path, final_path)
        out_path = final_path
        reg = _register_file(out_path, guess_mime(out_path))
        registered = True
        result = {"action": action, "source_file_ids": file_ids, **summary, **_file_result(reg)}
        update_meta(task_id, status="done", finished_at=int(time.time()), result=result)
        return result

    except Exception as e:
        update_meta(task_id, status="failed", error=str(e)[:300], finished_at=int(time.time()))
        raise
    finally:
        # P0-1/F2：临时文件必清（失败/超时/异常路径；成功路径 tmp 内已空）
        shutil.rmtree(tmp_dir, ignore_errors=True)
        if not registered and out_path is not None:
            # 未登记的半成品产物（ffmpeg 后、登记前失败）——不留污染
            try:
                out_path.unlink(missing_ok=True)
            except OSError:
                pass
