"""tasks.python_exec — Docker 沙箱执行用户 Python 代码（C2.4b IO 终局版 D110/Z26）

链路：Worker → docker-socket-proxy（端点白名单 D102）→ Docker daemon → 临时沙箱容器

IO 方案演进（Z25/Z26 实测收敛）：
  ✗ docker cp 注入（PUT archive）——daemon 对 read-only 容器一律 400（Z25）
  ✗ tmpfs 产物 + GET archive 取回——tmpfs 随容器退出即销毁，wait 后取必空（Z26）
  ✓ 终局：双 bind——任务目录根 ro 挂 /zero/task（code.py + in/ 输入）；任务 out/ 子目录
    rw 挂 /zero/io/out（产物直落宿主任务目录，容器退出不丢，零 archive API 依赖）

D105 不变量（创建后对 daemon 实录的 HostConfig 硬校验，13 项对账）：
  零 devices · privileged=False · cap_add 空 · network=none · read_only · user=65534:65534
  pids=64 · mem=1g · cpus=1 · no-new-privileges · tmpfs 恰为 {/tmp}
  binds 恰为 2 项且匹配任务模式（ro 根 + rw out，路径 worker 计算，Z24 owner 断言前置）

其余：输入 ≤64MB/≤20 文件；产物软顶 128MB（超限诚实失败，部分保留）；timeout_s [1,300]；
docker-py 客户端 timeout 360（Z16）；stdout ≤100KB，>32KB 落 result_ref（Z5）；
max_retries=0（沙箱问题 NON_RETRYABLE）；任务目录（含 code/输入副本/产物源）用后即删。
"""
import os
import re
import shutil
import time
from pathlib import Path

import docker

from celery_app import app, save_big_result, update_meta
from tasks.file import _load_file_meta, _register_file, _resolve_under_data_dir

# 产物专用 mime 映射（download 的 EXT_MIME 面向媒体流，此处覆盖文件产物全谱）
OUTPUT_MIME = {
    ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".webp": "image/webp",
    ".gif": "image/gif", ".svg": "image/svg+xml", ".bmp": "image/bmp",
    ".pdf": "application/pdf", ".csv": "text/csv", ".txt": "text/plain", ".md": "text/markdown",
    ".json": "application/json", ".html": "text/html", ".xml": "application/xml",
    ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    ".zip": "application/zip", ".mp4": "video/mp4", ".mp3": "audio/mpeg", ".m4a": "audio/mp4",
    ".wav": "audio/wav", ".flac": "audio/flac", ".tsv": "text/tab-separated-values",
}

SANDBOX_IMAGE = os.getenv("SANDBOX_IMAGE", "zero-python-sandbox:1.0")
MAX_OUTPUT = 100_000
MEM_LIMIT = "1g"                      # Z21：库 RSS ~700m + 运行时余量
INPUT_CAP_BYTES = 64 * 1024 * 1024    # 输入总量硬顶
OUTPUT_CAP_BYTES = 128 * 1024 * 1024  # 产物软顶
MAX_INPUT_FILES = 20
CLIENT_TIMEOUT = 360                  # Z16：>timeout_s 上限 300 + 余量
TASKS_ROOT = Path(os.getenv("DATA_DIR", "/data/zero")) / "tasks"

# Z26：唯一合法 binds——恰 2 项，任务目录（worker 计算）：根 ro + out 子目录 rw
_BIND_ROOT_RE = re.compile(r"^/data/zero/tasks/[a-f0-9]{32}:/zero/task:ro$")
_BIND_OUT_RE = re.compile(r"^/data/zero/tasks/[a-f0-9]{32}/out:/zero/io/out(:rw)?$")

_client = None


def _docker() -> docker.DockerClient:
    global _client
    if _client is None:
        _client = docker.DockerClient(
            base_url=os.getenv("DOCKER_HOST", "tcp://docker-socket-proxy:2375"),
            timeout=CLIENT_TIMEOUT,
        )
    return _client


def _assert_d105(hc: dict, user: str, task_id: str) -> None:
    """对 daemon 记录的 HostConfig 逐项校验（13 项对账——防未来代码漂移）"""
    bad = []
    if hc.get("Privileged"):
        bad.append("privileged")
    binds = hc.get("Binds") or []
    ok_binds = (
        len(binds) == 2
        and any(_BIND_ROOT_RE.match(b) and task_id in b for b in binds)
        and any(_BIND_OUT_RE.match(b) and task_id in b for b in binds)
    )
    if not ok_binds:
        bad.append(f"binds={binds}")
    if hc.get("Devices"):
        bad.append("devices")
    if hc.get("CapAdd"):
        bad.append("cap_add")
    if hc.get("NetworkMode") != "none":
        bad.append(f"network={hc.get('NetworkMode')}")
    if not hc.get("ReadonlyRootfs"):
        bad.append("writable-rootfs")
    if user != "65534:65534":
        bad.append(f"user={user}")
    tmpfs = hc.get("Tmpfs") or {}
    if set(tmpfs.keys()) != {"/tmp"}:
        bad.append(f"tmpfs={sorted(tmpfs.keys())}")
    if bad:
        raise RuntimeError(f"D105 SandboxPolicy 违规（任务中止，不重试）: {bad}")


def _collect_outputs(out_dir: Path, task_id: str) -> list[dict]:
    """宿主任务目录 out/ 直扫 → 搬入持久目录 process/{task_id}/ 登记
    （任务目录 finally 会 rmtree——产物必须先搬走，否则登记后即被删下载 404）"""
    dest_root = Path(os.getenv("DATA_DIR", "/data/zero")) / "process" / task_id
    dest_root.mkdir(parents=True, exist_ok=True)
    outputs = []
    total = 0
    overflow = False
    for p in sorted(out_dir.iterdir()) if out_dir.is_dir() else []:
        if not p.is_file():
            continue
        total += p.stat().st_size
        if total > OUTPUT_CAP_BYTES:
            overflow = True
            break
        dest = dest_root / p.name
        shutil.move(str(p), str(dest))
        ext = p.suffix.lower()
        reg = _register_file(dest, OUTPUT_MIME.get(ext, "application/octet-stream"))
        outputs.append({"file_id": reg["file_id"], "filename": p.name,
                        "mime": reg["mime"], "size": reg["size"]})
    if overflow:
        raise ValueError(f"产物总量超 {OUTPUT_CAP_BYTES // 1048576}MB 软顶（已保留 {len(outputs)} 个）")
    return outputs


@app.task(name="tasks.python_exec", bind=True, max_retries=0)
def python_exec(self, code: str, timeout_s: int = 30, input_file_ids: list | None = None):
    task_id = self.request.id
    input_file_ids = input_file_ids or []
    started = int(time.time())
    update_meta(task_id, status="running", started_at=started, retry_count=0)
    container = None
    task_dir = TASKS_ROOT / task_id
    out_dir = task_dir / "out"
    try:
        timeout_s = max(1, min(int(timeout_s), 300))
        if len(input_file_ids) > MAX_INPUT_FILES:
            raise ValueError(f"input_file_ids 超 {MAX_INPUT_FILES} 个上限")

        # 宿主侧任务目录：code.py + in/<输入>（Z24 owner 断言 + 总量硬顶）+ out/
        in_dir = task_dir / "in"
        out_dir.mkdir(parents=True, exist_ok=True)
        out_dir.chmod(0o1777)  # 容器内 nobody(65534) 可写（worker root 建目录缺省 755 不可写）
        in_dir.mkdir(parents=True, exist_ok=True)
        (task_dir / "code.py").write_text(code, encoding="utf-8")
        total_in = 0
        for fid in input_file_ids:
            meta = _load_file_meta(fid)  # 存在性 + TTL（缺失/过期抛 FileNotFoundError）
            if meta.get("owner_id") != "zero-main":
                raise ValueError(f"file_id 归属校验失败: {fid}")
            src = _resolve_under_data_dir(meta.get("path", ""))
            if not src.is_file():
                raise FileNotFoundError(f"输入文件缺失: {src.name}")
            total_in += src.stat().st_size
            if total_in > INPUT_CAP_BYTES:
                raise ValueError(f"输入总量超 {INPUT_CAP_BYTES // 1048576}MB 上限")
            shutil.copyfile(src, in_dir / src.name)

        container = _docker().containers.create(
            SANDBOX_IMAGE,
            command=["python3", "/zero/task/code.py"],
            network_mode="none",
            privileged=False,
            read_only=True,
            cap_drop=["ALL"],
            user="65534:65534",
            pids_limit=64,
            mem_limit=MEM_LIMIT,
            nano_cpus=1_000_000_000,
            tmpfs={"/tmp": "size=64m"},
            volumes={
                str(task_dir): {"bind": "/zero/task", "mode": "ro"},
                str(out_dir): {"bind": "/zero/io/out", "mode": "rw"},
            },
            security_opt=["no-new-privileges"],
            labels={"zero.sandbox": "1", "zero.task": task_id},
        )
        container.reload()  # 取 daemon 实录做 D105 硬校验（先校验后启动）
        _assert_d105(container.attrs["HostConfig"], container.attrs["Config"].get("User") or "", task_id)
        container.start()

        try:
            exit_code = container.wait(timeout=timeout_s).get("StatusCode", -1)
            timed_out = False
        except Exception:
            container.kill()
            exit_code = -1
            timed_out = True

        output = container.logs(stdout=True, stderr=True, tail=2000).decode("utf-8", "replace")[:MAX_OUTPUT]
        outputs = [] if timed_out else _collect_outputs(out_dir, task_id)
        duration = int(time.time()) - started
        result = {
            "exit_code": exit_code,
            "timed_out": timed_out,
            "duration_s": duration,
            "input_files": len(input_file_ids),
            "output_files": outputs,
        }
        if len(output) > 32_000:
            result["stdout_full_ref"] = save_big_result(task_id, {"stdout": output})
            result["stdout_tail"] = output[:2_000]
            result["note"] = "输出超 32KB，完整内容见 stdout_full_ref"
        else:
            result["stdout"] = output
        if outputs:
            result["note"] = (result.get("note", "") + " " if result.get("note") else "") + \
                "产物已登记 file_id，可用 download_file / cloud_file_url 获取"
        update_meta(task_id, status="done", finished_at=int(time.time()), result=result)
        return result
    except Exception as e:
        update_meta(task_id, status="failed", error=str(e)[:300], finished_at=int(time.time()))
        raise
    finally:
        if container is not None:
            try:
                container.remove(force=True)
            except Exception:
                pass  # 残留容器可由宿主 docker prune 清理；记录在 meta.error 已够
        shutil.rmtree(task_dir, ignore_errors=True)  # 任务目录用后即删（code/输入副本/产物源）
