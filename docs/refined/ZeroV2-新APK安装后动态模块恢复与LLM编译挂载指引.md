# ZeroV2 新 APK 安装后动态模块恢复与 LLM 编译挂载指引

> 版本：1.0 ｜ 日期：2026-10-01 ｜ 适用：新 Zero APK 已安装，Termux 动态模块未挂载或需要由 LLM 新建/更新模块
> 依据：当前 `CodeDevModule`、`ReleaseManager`、`TermuxBridge`、`McpClientModule`、`zero_release.py` 与《Zero 迭代总纲 v2》

## 1. 先分清两种“模块”

Zero 有两套不同的发布物：

| 内容 | 运行位置 | 如何更新/恢复 |
|---|---|---|
| Android 宿主（Hilt、Room、系统能力、Termux supervisor/SDK） | Zero APK | 修改 Android 工程、构建 APK、安装新 APK；这不是动态挂载 |
| 动态工具模块（Python、`@tool`、`serve()`） | Termux 内 Debian proot | 由 Zero 对话中的 `develop_module` 生成、校验、发布并注册为 `dyn_<模块>_<工具>` |

安装新 APK 本身不会把 Python 动态模块编进 APK。正常情况下，APK 更新保留应用数据；模块记录在 Android Room，发布 journal、release 内容和活动指针由 Termux supervisor 管理。若 Termux/Debian 被重置、应用数据被清除、Termux 存储迁移，Room 记录和运行时发布物可能不一致。

## 2. 新 APK 安装后，先做只读检查

1. 打开 Zero，进入「设置 → 动态模块」，查看模块状态：`ACTIVE`、`DISABLED` 或 `CRASHED`。记下失败模块 ID 和最近错误。
2. 在 Zero 对话中让 LLM 查询：

   > 用 `zero_status` 查询 `termux`、`modules`、`runtime` 状态，并用 `list_modules` 列出动态模块。只报告观察到的状态和不确定项，先不要修改或重建模块。

3. 查看「设置 → 运行日志」，筛选 `mcp_client`、`termux`、`code_dev`。重点辨认：`dyn_restore_start`、`dyn_restore_failed`、`supervisor_health`、`dyn_mount_failed`、`dyn_mount_start_failed`、`dyn_mount_init_failed`。
4. 确认 Termux 和 Termux:Boot 仍安装、Termux 首次启动初始化已完成、Debian proot 仍存在。不要清除 Zero 或 Termux 的应用数据，也不要重置 Debian。

注意：`zero_status` 当前暴露的是 Termux supervisor 健康投影，并不保证列出每个模块的 release journal；模块明细用 `list_modules`、动态模块页和运行日志交叉核对。

## 3. 按症状恢复

### A. 模块显示 ACTIVE，但工具暂不可用

Zero 启动恢复会对 Room 中 `ACTIVE` 的模块执行 supervisor `start`、重新发现端点、MCP `tools/list`，然后重新注册 `dyn_*` 工具；不会信任数据库里上次的端口。等待启动恢复完成，再查询 `list_modules`。不要把旧端点手动写回数据库。

若 supervisor 不可达，先确认 Termux 可启动、权限/电池限制没有阻断，并在前台打开 Termux 一次。Zero 的 `ensureSupervisor()` 会部署 APK 内置的 release/supervisor/SDK 脚本并尝试启动兼容协议 supervisor；之后回到 Zero 动态模块页重试启用。

### B. 模块为 DISABLED

调用 `enable_module(id)` 或在动态模块页点击启用。该动作会让 supervisor 重新启动模块、重新发现端点并检查工具列表。成功后应出现 `dyn_<id>_<tool>` 工具；失败时保留返回错误并查运行日志。

### C. 模块为 CRASHED

先读 `list_modules` 与运行日志中的崩溃原因，再调用 `enable_module(id)` 进行一次明确重试。不要反复自动重试崩溃模块。若 Android Room 有记录但 Termux journal/发布文件已不存在，重建模块源码后再用同名 `develop_module` 发布；如果需要原版本，应从你自己的备份恢复源码/数据。

### D. 所有模块都消失或 supervisor 协议无法升级

先确认 Termux Debian rootfs 和 `/root/zero` 工作目录是否还在。升级 supervisor 时，Zero 会通过 Termux API 将 APK 内的 `zero_release.py`、`zero_supervisor.py`、`zero_tool.py` 部署到 `$PREFIX/zero/zero_sdk/`，并按需安装 Debian `python3-venv`。若安装命令或 rootfs 不可达，查看 `supervisor_launch_failed`、Termux 命令 stderr 和权限状态；修复 Termux 基础环境后重启 Zero，再用 `list_modules` 核对。

如果 Room 模块记录仍在而 release journal 丢失，运行时没有从 Room 源码自动重建历史 release 的保证。对可恢复的模块，用备份源码调用 `develop_module` 重新发布；不要直接删库或清空模块目录来“修复”。

## 4. 让 LLM 新建或更新动态模块

在 Zero 的普通对话中清楚描述工具目标、输入/输出、数据来源、是否联网、是否会读写文件，以及示例输入和预期结果。请求 LLM 先说明计划访问的资源，再用 `develop_module`。模块名使用小写 `snake_case`，例如 `weather_query`。同名再次开发表示更新。

当前宿主的工具闭环为：

```text
develop_module
  → 解析 ZERO_MANIFEST
  → supervisor prepare（写独立 release）
  → validate（语法、依赖环境、启动与 tools/list 探活）
  → activate（切换活动 release 并启动）
  → Android MCP 重新连接并注册 dyn_<module>_<tool>
```

成功回执会列出确切工具名；随后让 LLM 用该工具做一个无副作用样例，并核对结果。失败时错误文本会返回模型，模型可据此修复后再次调用；默认连续修复轮上限为 3，达到上限后需检查错误并保存代码开发模块配置以开始新一轮。

### LLM 模块源码契约

- Python 单文件；需要 manifest 时将一行 `# ZERO_MANIFEST: {...}` 放在源码前部。
- `runtime` 只能为 `python`。`deps` 必须精确固定版本，例如 `requests==2.32.3`；当前 release runner 为每个版本建独立 venv，不调用全局 pip。
- 使用 Termux Zero SDK：`from zero_tool import tool, serve, get_config`；用 `@tool` 声明工具，函数参数必须有 `str/int/float/bool` 类型注解，docstring 要解释工具用途和参数。
- 入口应遵循当前 SDK 模板，并在 `serve()` 前完成最少初始化：

  ```python
  import os, sys
  sys.path.insert(0, os.path.join(
      os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "zero_sdk"))
  from zero_tool import tool, serve, get_config

  @tool
  def ping(text: str = "hello") -> dict:
      """返回输入文本，用于验证动态模块挂载。"""
      return {"ok": True, "text": text}

  if __name__ == "__main__":
      sock = os.environ.get("ZERO_MOD_SOCKET")
      serve_socket(sock) if sock else serve()
  ```

  若使用 `serve_socket`，需从 SDK 显式导入它：`from zero_tool import tool, serve, serve_socket`。
- 配置经 manifest `configSchema` 声明并通过 `get_config()` 读取；不要把密钥写入源码、manifest、配置 JSON 或日志。
- 网络请求设置超时；超过约一分钟的工作采用 `start_job`/`poll_status` 两阶段工具；返回值必须可 JSON 序列化。
- 发布成功后，确认回执中的工具确实出现；再调用 `dyn_<模块名>_<工具名>` 验证行为。更新失败应保留旧活动版本并检查 rollback 报告。

manifest 示例：

```python
# ZERO_MANIFEST: {"deps": ["requests==2.32.3"], "termuxDeps": [], "runtime": "python", "configSchema": [{"key":"city","label":"城市","type":"text","default":"上海"}]}
```

## 5. 当前实现边界与安全说明

- Room 是 Zero UI/模块状态镜像；Termux supervisor journal 才拥有 release 活动指针。两侧状态不一致时先查询并对账，不要手改数据库状态。
- 当前 APK 内 `CodeDevModule` 发布路径会自动 validate 后 activate；现有目标总纲中的可信低风险 runner、完整风险提案门和自动 canary 决策不能仅凭总纲文字认定已落地。源码在 Termux 用户权限下运行，venv 只隔离 Python 依赖，不隔离文件、shell 或网络权限。
- 因此不要让 LLM 自动生成并挂载具有删除/覆盖数据、发送消息、凭据访问、任意 shell、任意网络副作用的模块。此类需求先要求模型列出行为和副作用，按当前产品授权边界由用户决定是否开发与启用。
- APK、Android 权限、Room schema、Hilt、supervisor/SDK 基建改动属于宿主工程变更：不能通过 `develop_module` 完成，需修改 Zero Android 工程、构建 APK并由用户安装。

## 6. 故障反馈模板

若按上述步骤仍未挂载，把以下信息一起提供给开发者，避免只凭“失去挂载”猜原因：

```text
Zero APK version/build:
Termux 与 Debian 是否保留：
zero_status 的 termux/modules 摘要：
list_modules 输出：
失败模块 ID 与状态：
运行日志（mcp_client / termux / code_dev 对应时间段）：
是否近期清理/迁移过 Zero 或 Termux 数据：
目标：恢复旧模块 / 重新生成模块 / 修改 Android 宿主：
```

**证据边界**：本指引依据当前仓库源码和设计文档编写；没有连接用户手机读取实际安装版本、Termux 文件或运行日志。设备上的具体丢失原因需用第 2 节的状态与日志确认。
