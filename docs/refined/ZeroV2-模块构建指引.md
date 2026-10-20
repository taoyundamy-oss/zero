# Zero V2 — 模块构建指引（Termux 动态模块开发规范）

> 版本 1.0 ｜ 2026-09-15 ｜ 依据《ZeroV2-模块化重构方案.md》D59/D60/**D67（最大授权）**/**D68（配置协议）**
> **读者**：① LLM（`develop_module` 元工具的 prompt 注入精简版）；② 手写模块的用户/开发者。
> **运行环境**：Termux Python 3.11+，**最大授权（D67）**——可自由 import（含 socket/subprocess/requests）、可联网、可 pip 装依赖（经 manifest `deps` 声明）、可读写 Termux 文件系统。物理边界 = Android 应用沙箱 + Termux uid。

---

## 一、模块形态：一个 Python 文件

一个动态模块 = **一个 `.py` 文件**，含工具函数 + manifest 注释 + 入口。放行流程（LLM 走 `develop_module`，手写者放 `$PREFIX/zero/modules/` 后经 Zero 动态模块页启用）：

```
写 .py → py_compile 语法门 → manifest 解析（装 deps）→ supervisor 起进程探活
→ McpClient 连端点 → 注册 dyn_<模块名>_<工具名> FC（AUTO）→ LLM/语音直接调用
```

## 二、manifest（文件第二行注释，单行 JSON）

```python
# ZERO_MANIFEST: {"deps": ["requests"], "termuxDeps": ["ffmpeg"], "runtime": "python", "configSchema": [{"key": "city", "label": "城市", "type": "text", "default": "邵阳"}]}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `deps` | List&lt;String&gt; | pip 依赖（如 `requests`、`yt-dlp`）——挂载前自动 `pip3 install --no-input --break-system-packages`（Debian 12 PEP 668 实测勘误：`--user` 亦被拦，2026-09-17） |
| `termuxDeps` | List&lt;String&gt; | pkg 依赖（如 `ffmpeg`）——bootstrap 装 |
| `runtime` | String | `python`（默认；`node` 预留） |
| `configSchema` | List&lt;Object&gt; | **配置声明（D68）**——Zero 侧模块配置页据此渲染；无配置省略 |

**configSchema 字段项**：`key`（英文标识）/ `label`（中文标签）/ `type`（`text`\|`number`\|`toggle`）/ `default` / `min`/`max`（number 可选）/ `description`（说明）。

## 三、zero_tool shim API（import 即用，文件在 `$PREFIX/zero/zero_sdk/`）

| API | 用途 |
|---|---|
| `from zero_tool import tool` + `@tool` | 装饰函数→自动注册为 FC 工具（从类型注解+docstring 生成 schema） |
| `serve()` | 以 HTTP 起服务（主传输；端口自分配经 stdout `ZERO_MOD_LISTENING <port>` 回报） |
| `serve_socket(name)` | unix 抽象 socket 兜底传输（Android 16 loopback 受限时，supervisor 经 env 自动切换） |
| `serve_file(path)` | 注册大文件回传（`GET /file/<name>` Range 流式——下载型模块把产物交还 Zero 入 SAF 用） |
| `get_config() -> dict` | **读配置（D68）**——supervisor 经 env `ZERO_MOD_CONFIG`（JSON）注入，本函数解析返回；改配置→Zero 配置页保存→模块自动重启生效 |
| `get_secret(key)` | **协议预留（P1-4，本版不实现）**——Secret（API key/Token）不入 manifest/DB/env/trace；未来经 Zero `SecureKeyStore` 注入。**当前模块需要密钥时：让用户经 Zero 对话配置（如 wechat 模块的 token 走 SecureKeyStore 案例），勿硬编码进源码** |

**入口模板**（照抄，勿改——含 SDK 自举行）：
```python
import os, sys
sys.path.insert(0, os.path.join(          # ⭐ 必须有：supervisor 不注入 PYTHONPATH，
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "zero_sdk"))  # 漏此行 ModuleNotFoundError: zero_tool（qqbot 实证连崩 7 次，2026-09-20）
if __name__ == "__main__":
    sock = os.environ.get("ZERO_MOD_SOCKET")
    serve_socket(sock) if sock else serve()
```

## 四、工具函数编写规约

1. **类型注解必写**（str/int/float/bool→JSON Schema 自动生成；无注解按 string）。
2. **docstring 必写**——即工具 description（LLM 据此决定何时调用；写清用途与参数含义）。
3. **返回值 JSON 可序列化**（dict/list/str/num；复杂对象自行 `default=str` 已兜底）。
4. **错误=抛异常**——shim 自动转 MCP `isError` 文本回 LLM（勿吞异常返回假成功）。
5. **长任务（>60s）用 start/poll 模式**：`xxx_start(...)->job_id` + `xxx_status(job_id)` 两个工具（绕开单次调用超时，M0）。
6. **配置经 `get_config()` 读**，勿硬编码用户偏好；无 configSchema 时返回 `{}`。
7. **常驻状态存文件**（如 `$PREFIX/zero/data/<模块名>/`）——模块进程可能被重启，内存态会丢。
8. **勿阻塞启动路径**：模块入口 `serve()` 前只做轻初始化；重初始化放首个工具调用内。

## 五、命名规范

- 模块名：`snake_case` 英文（如 `weather_query`、`bili_download`）→ FC 前缀 `dyn_weather_query_*`。
- 工具名：动词开头（`lookup_word`/`send_message`/`start_download`）。
- 同名重新 `develop_module` = **更新**该模块（旧进程停→新进程起）；改 configSchema 后配置页字段随之增减（旧值保留、新字段取 default）。

## 六、生命周期与调试

- **状态**：`ACTIVE`（运行中）/ `DISABLED`（用户停用）/ `CRASHED`（进程崩，自动停 + Zero 告警；用户可再启用）。
- **重启恢复**：Zero 重启后非 CRASHED 模块经 supervisor 自动重挂（D51）。
- **调试**：① Zero→运行日志 查 `code_dev`/`module` trace（compile/test/mount 全链）；② 模块 `print` 到 stdout 由 supervisor 收集，`develop_module` 失败时 stderr/traceback 回灌 LLM 自修复（≤3 轮）；③ 手写模块可直接在 Termux `python <模块>.py` 手跑验证。
- **权限（D67）**：挂载即 AUTO 零弹窗；最大授权风险自担——**生成代码前 LLM 应在回复中说明模块用途与将访问的资源（网络/文件），用户知情**。

## 七、完整模板（带配置的示例模块）

```python
# ZERO_MANIFEST: {"deps": ["requests"], "configSchema": [{"key": "city", "label": "查询城市", "type": "text", "default": "邵阳", "description": "天气查询默认城市"}, {"key": "units", "label": "单位", "type": "text", "default": "metric", "description": "metric=摄氏 / imperial=华氏"}]}
"""天气查询模块（示例）——按配置页城市查天气。"""
from zero_tool import tool, serve, serve_file, get_config
import os

@tool
def query_weather(city: str = "", units: str = "") -> dict:
    """查询指定城市当前天气。不传参时用配置页默认城市/单位。

    Args:
        city: 城市名（如 邵阳）；缺省用配置
        units: metric/imperial；缺省用配置
    Returns:
        dict: {city, temp, description}
    """
    import requests  # 最大授权（D67）：可自由联网
    cfg = get_config()
    city = city or cfg.get("city", "邵阳")
    units = units or cfg.get("units", "metric")
    r = requests.get("https://api.open-meteo.com/v1/forecast",
                     params={"latitude": 27.24, "longitude": 111.47, **({"units": units} if units else {})},
                     timeout=15)
    r.raise_for_status()
    d = r.json()
    return {"city": city, "temp": d.get("current", {}).get("temperature"), "raw": d}

if __name__ == "__main__":
    sock = os.environ.get("ZERO_MOD_SOCKET")
    serve_socket(sock) if sock else serve()
```

---

## 八、工作站模式（D69 · 与模块模式并存的两条开发路径）

除"模块化"（本指引主体——成熟工具挂载为 FC）外，Agent 还有**工作站模式**（`linux_exec` FC，全 AUTO）：Debian 工作站内直接 shell 循环（ls/grep/试跑/看报错/改）——适合**探索性/一次性**任务（"查一下这个 API 返回什么"）。**路径选择约定**：临时探索用 `linux_exec`；会复用的能力写成模块（develop_module）获得配置页（D68）与 FC 稳定契约；大型项目在工作站开发（git/多文件），其中稳定功能再包装为模块。工作区约定 `/root/work`；长任务用 `nohup` + `/service` 登记（重启自动恢复）；大文件经 `linux_upload/linux_download` 与手机 SAF 互通。

## 附：与 Zero 的接缝一览（开发者视角）

| 接缝 | 机制 | 落点 |
|---|---|---|
| 工具注册 | MCP `tools/list` → `dyn_<module>_<tool>` FC（AUTO） | McpClientModule（M2.2） |
| 传输 | HTTP loopback 主 / unix-socket 兜底 | D59/M2.3 |
| 配置 | configSchema → Zero 配置页 → env `ZERO_MOD_CONFIG` 注入 | D68/M2.4 |
| 依赖 | manifest deps → supervisor pip install | M3.1 |
| 大文件 | `serve_file` → `GET /file/<name>` Range 流 | A5 |
| 崩溃隔离 | 模块=supervisor 子进程，崩溃不波及 Zero | D65 |

*冲突裁决：《ZeroV2-模块化重构方案.md》接口契约优先于本指引。*
