# Zero v2 本地 Termux 运维与验收矩阵

> 适用范围：Android 本地 Termux 工作站、`zero_supervisor.py` 和动态模块链路。
> `zero-cloud` 已 retired，不属于本手册的部署或验收范围。

## 1. 当前边界与默认值

| 项目 | 当前实现事实 |
|---|---|
| Termux 前缀 | `/data/data/com.termux/files/usr` |
| 工作区 | Linux 工作站默认 `/root/work`；运行时目录 `/root/zero` 不得直接修改 |
| supervisor | `zero_supervisor.py`，由 `TermuxBridge.ensureSupervisor()` 幂等启动 |
| HTTP 控制面 | `127.0.0.1:7878`，端口可在 TermuxBridge 配置页改为 1024–65535 |
| 健康检查 | `GET /health`；HTTP 失败后尝试抽象 Unix socket `zero-supervisor` |
| 命令超时 | `linux_exec` 默认 120 秒；配置上限默认 600 秒，允许范围 10–3600 秒 |
| HTTP 余量 | 普通 supervisor 请求 15 秒；`linux_exec` 请求为命令超时加 15 秒 |
| 依赖安装 | 默认 300 秒，允许范围 30–1800 秒；与探活超时分离 |
| 输出与文件 | `linux_exec` 输出超过 64 KB 截尾；单文件 SAF 往返上限 512 MB；小文本直写上限 256 KB |
| 动态模块 | `/module/<id>/start`、`/module/<id>/stop`；模块 id 为小写字母开头，仅 `a-z0-9_`，最长 40 字符 |

## 2. 启动、健康检查与停止

1. 确认 Termux 已安装并允许 Zero 使用 RUN_COMMAND；若 Android 侧提示 Termux 不可达，先打开 Termux 一次并检查权限。
2. 在 Zero 的 TermuxBridge 配置中确认端口，默认 `7878`；首次引导保持“启动自动引导”关闭，确认用户已准备好网络和存储后再手动开启。
3. Zero 启动后由 `ensureSupervisor()` 检查 `/health`。健康返回必须包含 `alive=true`，并记录 `pid`、`uptime_s`、`rootfs`。
4. HTTP loopback 失败时，代码自动尝试抽象 socket `zero-supervisor`；两路均失败才记录 supervisor 不可用。
5. supervisor 死亡后由低频 watchdog 每 60 秒探活；10 分钟内最多自动重启 3 次，超过后暂停本窗口内的自动重启并留 trace。

不使用 Android shell 直接绕过 `TermuxBridge` 调 supervisor。所有控制面请求都应经过该模块，以保持超时、trace 和失败语义一致。

## 3. 动态模块生命周期

1. 通过动态模块页面导入或创建模块，先校验 manifest、模块 id 和依赖。
2. 编译链把源码写入 supervisor 约定的模块目录，执行依赖安装，再调用 `/module/<id>/start` 做探活。
3. 探活无论成功或失败都调用 `/module/<id>/stop`，避免留下半启动进程。
4. 启用模块时由 `mountDynamicModule()` 启动、发现端点并注册 `dyn_*` 工具；停用时先注销工具，再调用 supervisor stop 并更新数据库状态。
5. 模块跨会话崩溃计数达到配置的 `crashAutoDisableK`（默认 5）后自动停用并告警；人工重新启用才清零该计数。

## 4. SAF 上传、下载与超时

- `linux_upload`：从 Zero 工作目录读取 SAF 流，PUT 到 supervisor `/file/upload?path=...`；确认目标路径在模块或工作区约定目录内。
- `linux_download`：从 supervisor 读取文件，使用净化后的相对文件名写回 SAF `linux/` 目录；路径不存在、响应为空或超过上限都应记录失败。
- `linux_write_text`：只用于小文本覆盖写，超过 256 KB 应改用 `linux_upload`。
- 长任务使用 `nohup` 或 supervisor service 后台运行，随后用状态/日志接口轮询；不要把分钟级任务同步占用一次 FC 调用。
- 超时只代表本次等待结束，不代表远端进程一定停止；需要停止时使用 supervisor 的模块或服务控制接口。

## 5. L1/L2/L3 验收矩阵

| 层级 | 项目 | 命令/证据 | 当前结果 |
|---|---|---|---|
| L1 | TermuxExecutionPolicy 超时裁剪 | `:services:testDebugUnitTest` | 已通过，随质量门禁复验 |
| L1 | TermuxCommandPolicy 文件名净化 | `:services:testDebugUnitTest` | 已通过，随质量门禁复验 |
| L1 | 动态模块 id、依赖和 start/stop 行为 | `:services:testDebugUnitTest` | 已有纯逻辑覆盖；真实 supervisor 仍需 L3 |
| L2 | Android 工程、Room、lint、Detekt、Ktlint、APK | `qualityCheck --no-parallel --no-daemon --max-workers=1 --console=plain` | 本次收口执行 |
| L2 | Macrobenchmark 基建可编译 | `:benchmark:compileDebugKotlin` | 已通过 |
| L3 | Termux 安装、RUN_COMMAND、bootstrap、`/health` | 真机操作；记录 Android/Termux 版本、端口和 trace | 待用户设备证据 |
| L3 | `linux_exec` 超时、取消和进程恢复 | 真机 Termux；短命令、超时命令、取消、重启后复测 | 待用户设备证据 |
| L3 | SAF 文件往返 | 真机上传/下载文本和二进制，核对字节数/hash | 待用户设备证据 |
| L3 | 动态模块启停和崩溃停用 | 真机导入模块、启停、探活失败、自动停用 | 待用户设备证据 |
| L3 | 性能基线 | LG V35 已取得导航+滚动帧数据；冷启动因 production build 无法 `adb root` 未测 | 已有部分证据，不阻塞文档收口 |

## 6. 记录要求与停止条件

每次 L3 记录日期、设备型号/API、Android 与 Termux 版本、Zero APK 版本、配置端口、执行命令、stdout/stderr、退出码和 traceId。无法执行的项保持“待设备证据”，不得以构建成功替代。

遇到第三方制品来源/许可证不明、需要改变数据库兼容语义、需要新增 zero-cloud 部署或需要伪造设备数据时，停止该项并记录阻塞原因。
