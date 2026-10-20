# Zero V2 模块化重构落地方案（热插拔 + 自编译 + 操作层）

> 记录日期：2026-08-30 · **2026-09-15 重大修订（用户四项决策）**：①Termux 基建照计划构建；②**自编译取消权限限制——Termux 环境最大授权（D62 沙箱模型/D63 权限分级废止，D67 替代）**；③新增《ZeroV2-模块构建指引.md》规范文档（§十四）；④Termux 动态模块须有 Zero 侧配置页（D68 配置协议）· **2026-09-15 二次修订（D69 Linux 工作站）**：Termux 内跑 proot Debian 发行版，**整个环境托付 Agent 自主开发——Zero 只搭三条基建（shell 会话通道/文件双向通道/进程与模块管理，§十五）**，实施路径按工作站形态重排（§六）
> 依据：`ZeroV2-模块化架构设计.md`（构想）+ 开源能力边界调研 + 用户三项决策
> **文档定位**：本文件是构想的"落地工程方案"——把"模块热插拔 / LLM 自编译 / 视觉→决策→操作"从概念翻译为可在现有代码库上增量实施的 Step 清单与接口契约。
> **权威层级**：DEVELOPMENT_DESIGN（架构/决策）> ROADMAP_P5_P8（Phase/Step）> 本文档（模块化重构实施细节）。本文件不修改既有 D1–D58 决策，新决策从 D59 起编号。
> **核心立场**：内核（Kotlin 常驻核心）不动；热插拔/自编译/操作层均为**增量**新增模块与基础设施，遵循既有"先新建→验证通过→再删除"渐进原则。

---

## 〇、目标重述与原则

三大目标（来自构想）：

1. **模块热插拔**——功能模块运行中动态加载/卸载，独立启停、崩溃隔离，无需重建 APK。
2. **LLM 自编译**——LLM 编写/编译新模块并挂载，系统具备"自我迭代"能力。
3. **全流程闭环**——视觉（感知）→ 决策（理解规划）→ 操作（系统执行）。

落地原则（沿用既有工程纪律）：

- **内核稳定优先**：LLM/记忆/对话/FC/调度/无障碍/MCP 客户端/SecureKeyStore/AgentTrace/WorkDirRepository 全部保留，不重写。
- **增量 + 复用**：新能力以新 `IZeroModule` 形式接入；**最大化复用既有 MCP 管道**（`McpClientModule` 已是热插拔注册表的 80%）。
- **编译质量门 + 探活 + 可观测护栏（D67 · 2026-09-15 替代原"沙箱+逐级放权"）**：自编译代码无沙箱（Termux/Debian 即授权边界）、不限 import/联网/pip、挂载即 AUTO；护栏为非权限性三道——py_compile 质量门 / 试跑探活防挂死 / 全链 trace + 随时 disable + CRASHED 自动停（沿用 D17 用户主权：用户是最高授权方，D67 即其裁定）。
- **谋定而后动**：每 Step ≤3 文件或 ≤1 逻辑单元，用户 Build 验证后再推进。

---

## 一、研究结论：落地能力边界

### 1.1 Shizuku —— 操作层钥匙（无需 root 的 ADB 级能力）

- **机制**：Shizuku 启动一个以 shell-uid 运行的常驻服务（经 ADB / 无线调试激活）。应用通过 Binder 绑定：定义 AIDL `IUserService` → 实现 → `Shizuku.bindUserService(...)`，UserService 跑在 Shizuku 的特权进程内，**比 spawn shell 快**。
- **能做**（shell-uid）：
  - `input tap <x> <y>` / `input swipe` / `input text` / `input keyevent` —— 系统级手势注入，不依赖无障碍对目标 App 的可见性。
  - `am start -n/-a/-d` —— 启动任意 Activity/组件（含无障碍无法触达的系统页）。
  - `pm grant <pkg> <perm>` / `pm install` / `pm list` —— 程序化授权/安装（**无障碍做不到**）。
  - `settings put/get` —— 系统设置读写。
  - `dumpsys notification` / 反射直调 `INotificationManager` —— 通知栏读取（补无障碍之短）。
  - `content call/query` —— 以 shell 身份查 ContentProvider（日历/联系人等，绕过部分运行时权限）。
  - 反射直调隐藏 binder（`IPackageManager`/`IActivityManager`/`IWindowManager`），更快且能力更全。
- **不能（无 root）**：内核 namespace、`su`、cgroups、Seccomp 严格过滤。
- **生命周期风险**：Shizuku 服务重启即销毁，需重新激活（无线调试/ADB）；**2026-07 Google 提案拟限制设备端 ADB**，可能威胁 Shizuku 的激活路径——记为战略风险（§八）。
- **与既有无障碍的分工**（关键，避免重复造轮子）：
  - **感知**：无障碍节点树（结构化、毫秒级、零额外权限）+ 截图 VL（D34 副通道）。**不动**。
  - **操作**：`tap/swipe/input_text` 走 **Shizuku `input`** 为主（系统级、统一机制、不受目标 App 无障碍可见性限制）；**Shizuku 未激活时回退无障碍 `dispatchGesture`**（需 `canPerformGestures=true`，权限中心提示重开无障碍，R8 先例）。
  - **系统动作**（`am/pm/settings/content/通知栏`）：**仅 Shizuku** 能做。
- **研究来源**：[Shizuku 官网](https://shizuku.rikka.app/) · [Shizuku-API README](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md) · [Shizuku 用户手册](https://shizuku.rikka.app/guide/setup/) · [XDA 集成教程](https://www.xda-developers.com/implementing-shizuku/)

### 1.2 Termux —— 运行时 + 沙箱 + 编译链

- **调用**：`com.termux.RUN_COMMAND` Intent → `com.termux.app.TermuxService`。Intent extra（确认自 `TermuxConstants.java`）：
  - `com.termux.RUN_COMMAND_PATH`（String，必填，绝对路径，如 `/data/data/com.termux/files/usr/bin/python`）
  - `com.termux.RUN_COMMAND_ARGUMENTS`（String[]）
  - `com.termux.RUN_COMMAND_WORKDIR`（String，缺省 Termux home）
  - `com.termux.RUN_COMMAND_BACKGROUND`（Boolean，后台跑不起 UI）
  - `com.termux.RUN_COMMAND_BACKGROUND_MIN_LOG_LEVEL`（int）
  - **`com.termux.RUN_COMMAND_PENDINGIntent`**（PendingIntent，拿回 stdout/stderr/exit——一次性脚本与编译校验的关键回路）
- **权限/前置**：调用方声明 `com.termux.permission.RUN_COMMAND`（自定义权限，需授予）+ Manifest `<queries>` 声明 `com.termux`；Termux 需"显示在其他应用上层"；**Termux 宜已在前台/常驻**（实测后台 RUN_COMMAND 不稳定，需 `termux-wake-lock`）。
- **IPC app↔Termux**（关键边界）：
  - **loopback 127.0.0.1**：Android ≤15 **app↔app 可达**（浏览器作为另一 App 能访问 Termux localhost 即证）；**Android 16 可能阻断后台应用的 loopback**（[termux#4824](https://github.com/termux/termux-app/issues/4824)）——**真实边界，必须规避**。
  - **PendingIntent**：适合一次性"发命令+取结果"（编译校验、ping）。
  - **unix 抽象命名空间 socket**（`\0zero-...`，无文件路径）：内核级、跨 App 共享、**不受网络策略限制**——规避 Android 16 loopback 风险的兜底通道。
  - **共享文件系统**：app 私有存储 Termux 不可达；既有 SAF 工作目录（WorkDirRepository）可作受控文件交换面，但 Termux 写 SAF 树不便，故**主通道走 socket**。
- **沙箱（无 root）现实边界**：
  - `proot-distro --isolated` → **文件系统隔离**（ptrace 拦截，隐藏 Android/Termux 目录）；**无真 network namespace**。（**2026-09-15 D67 后仅作研究记录——实施不再使用 proot 沙箱**）
  - `bubblewrap`（namespace+seccomp，更深隔离）→ 多需内核未启用的 unprivileged user namespaces，**存量 Android 不可靠**。
  - cgroups（CPU/mem 限额）→ 需 root，**不可用**。
  - → ~~**务实沙箱 = proot `--isolated` + ast 允许名单 + 禁网**~~ **【历史废弃（D67），不得实现】**；supervisor 超时/kill 资源护栏保留（进程组语义 P0-2）。
- **工具链**：Termux Python 3.11+/Node 18+ 成熟；纯 Python pip 直装，原生扩展需编译（提供 build deps 但**限制 LLM 生成模块只用纯 Python + 允许名单**避免原生构建痛点）。编译校验：`python -m py_compile` + `python -c "import ast,ast_validate"` + 自定义 ast walker；Node `node --check`。常驻模块进程冷启动可接受（保活）。
- **研究来源**：[Termux RUN_COMMAND Wiki](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent) · [TermuxConstants.java](https://raw.githubusercontent.com/termux/termux-app/master/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java) · [PRoot Wiki](https://wiki.termux.com/wiki/PRoot) · [bubblewrap/LWN](https://lwn.net/Articles/686113/)

### 1.3 自编译回路 —— OpenHands 范式 = Zero 已有

- **OpenHands 架构**：无状态 Agent 发 Actions → Conversation 跑循环 → append-only EventLog → Runtime+Sandbox 执行 → Observation 回灌 LLM → 重复。
- **Zero 已具备**：`ChatBotModule` FC 循环（最多 5 轮）+ **REACT 模式（D41，类 Claude Code agent loop）** + `AgentTrace`（append-only，持久化）+ `FunctionRegistry`。
- **结论**：自编译**不必新建 agent 框架**——给现有 FC 回路加一个**元工具 `develop_module`** 即可。沙箱执行体 = Termux + proot + supervisor。结果/错误经既有 `JsonExtractor`（4 层降级）+ `AgentTrace` 回灌。
- **研究来源**：[OpenHands 架构概览](https://docs.openhands.dev/sdk/arch/overview) · [OpenHands Agent](https://docs.openhands.dev/sdk/arch/agent) · [arXiv 2511.03690](https://arxiv.org/html/2511.03690v2)

### 1.4 MCP-in-Termux —— McpClientModule 已是 80% 注册表

- `FastMCP @mcp.tool` 装饰器自动从类型注解+docstring 生成工具 schema；Streamable HTTP 绑 127.0.0.1:port；**Zero 现有 `McpClient` 已是 Streamable HTTP 客户端**（Step 8.2，处理 application/json 单响应 + SSE 流 + `Mcp-Session-Id`）。
- **`McpClientModule`（Step 8.3）已实现**：连 MCP 服务器 → `tools/list` → 注册为 `mcp_<server>_<tool>` FC 工具 + 服务器级 `ActionPermission` + `McpServersScreen` UI + `SecureKeyStore` Key + **D51 启动恢复**。
- **结论**：热插拔框架**大半已建**。新工作 = 让 `McpClientModule` 还能"**在 Termux 内启动本地模块进程 → 连接 → 注册**"（区别于连远程 URL）。"LLM 写模块 = LLM 写一个 MCP 兼容 server"可行，且**复用全部现有 MCP 管道**。
- **边界**：stdio 传输不可行（Android 无子进程能力，且 Termux 二进制需其环境）；**Streamable HTTP over loopback 为本机唯一标准传输**，配 unix-socket 兜底。
- **研究来源**：[MCP Python SDK](https://github.com/modelcontextprotocol/python-sdk) · [FastMCP Tools](https://gofastmcp.com/servers/tools) · [FastMCP 传输](https://medium.com/@anil.goyal0057/building-and-exposing-mcp-servers-with-fastmcp-stdio-http-and-sse-ace0f1d996dd)

### 1.5 移动动作回路 —— AppAgent 范式

- AppAgent/Mobile-Agent = 截图/无障碍 → LLM（computer-use）→ `tap/swipe` → 重观察 → 循环。
- Zero 已有：截图 VL（qwen-vl）+ 无障碍读屏（D34）+ REACT 回路。**缺口仅是动作 FC 工具**（`tap/swipe/input_text/start_app/grant_perm/set_setting`），由 Shizuku 支撑 → 即成完整 mobile computer-use。
- **研究来源**：[AppAgent](https://github.com/TencentQQGYLab/AppAgent) · [AppAgent v2](https://arxiv.org/html/2408.11824v1) · [AccessibilityService dispatchGesture](https://developer.android.com/guide/topics/ui/accessibility/service)

---

## 二、架构总览：内核不动 + 增量插槽

```
┌──────────────────────────── Kotlin 常驻内核（不动）────────────────────────────┐
│ LLM(OpenAI兼容) · Memory/UserGraph · 对话+FC循环+REACT(D41) · FunctionRegistry  │
│ /ActionPermission(D9) · AgentRuntime/Scheduler/AgentJob · AccessibilityService │
│ (读屏+dispatchGesture) · McpClient+McpClientModule · SecureKeyStore ·            │
│ ModuleConfigStore · AgentTrace · WorkDirRepository(SAF) · 权限中心               │
└───────────┬───────────────────────────────────┬───────────────────────────┬─────┘
            │                                   │                           │
   ╔════════▼═════════╗            ╔════════════▼════════════╗   ╔══════════▼══════════╗
   ║  ShizukuBridge    ║            ║  TermuxBridge            ║   ║  CompileManager      ║
   ║ (操作层/系统动作) ║            ║ (运行时/Debian 工作站)║   ║ (自编译管线·D67)     ║
   ║ UserService+AIDL  ║            ║ RUN_COMMAND+PendingIntent║   ║ py_compile 质量门   ║
   ║ tap/swipe/am/pm/  ║            ║ 健康检查/wake-lock      ║   ║ 探活+trace 护栏     ║
   ║ settings/content  ║            ║ 启停本地模块进程        ║   ╚══════════╤══════════╝
   ╚════════╤══════════╝            ╚══════════╤════════════╝              │
            │                                   │                           │
            └───────────┬───────────────────────┴───────────────────────────┘
                        │  传输：MCP Streamable HTTP over 127.0.0.1（主）
                        │       + unix 抽象 socket（Android16 兜底）
                        ▼
   ┌────────────────────────────────────────────────────────────────────────┐
   │ ModuleRegistry = 扩展 McpClientModule：连本地 Termux 模块 → 注册 dyn_* FC │
   │  动态模块（Zero 轻量 SDK shim / FastMCP server）跑在 Termux（最大授权 D67）│
   │  状态机：ACTIVE / DISABLED / CRASHED（perm 恒 AUTO——D67）                │
   └────────────────────────────────────────────────────────────────────────┘
                        │
                        ▼  自编译回路（复用现有 FC 循环）
   LLM 调 develop_module → CompileManager 写+编译+探活（失败回传 stderr→LLM 修复）
        → 通过 → ModuleRegistry 挂载（Termux 起 MCP server，McpClient 连，注册 dyn_ FC，
          默认 CONFIRM）→ LLM 调用 → 稳定后提 AUTO（用户主权）
```

### 内核不动清单（零重写）
LLM 模块 · 记忆层/UserGraph · 系统权限调度 · Agent 运行时 · 任务调度器 · 对话引擎 · 消息过滤器 · 上下文构建器 · 函数注册中心 · AccessibilityService · McpClient · SecureKeyStore/ModuleConfigStore · AgentTrace · WorkDirRepository · 权限中心

### 新增模块（增量）
| 模块 | id | 层 | 职责 |
|---|---|---|---|
| ShizukuBridge | `system_action` | 框架核心层（常驻，常依赖） | 操作层：UI 手势 + 系统动作，Shizuku UserService + 无障碍 dispatchGesture 兜底 |
| TermuxBridge | `termux` | 框架核心层（常驻） | Termux 存在/启动/健康、RUN_COMMAND+PendingIntent、wake-lock、启动本地模块进程 |
| ModuleRegistry | 扩展 `mcp_client` | 框架核心层 | 动态模块注册表：连本地/远程模块 → 注册 `dyn_*`/`mcp_*` FC + 信任态机 |
| CompileManager | （TermuxBridge 内/新 `code_dev` 模块，原 SandboxManager 更名） | 自编译 | 写码→py_compile 质量门→普通进程探活→挂载（D67 无沙箱） |
| CodeDevTools | `code_dev` | LLM 自迭代入口 | FC: develop_module / list_modules / enable_module / disable_module（全 AUTO，D67） |

---

## 三、关键决策记录

| # | 决策 | 结论 |
|---|------|------|
| **D59** | **动态模块传输** | **MCP Streamable HTTP over 127.0.0.1 为主 + unix 抽象命名空间 socket 兜底**。主路径复用 FastMCP/现有 McpClient、LLM 生成目标最简、立即可用；unix-socket 传输变体在 Android 16+ 后台 loopback 受限时切换（McpSocketTransport，MCP 语义不变、传输为长度前缀 JSON-RPC over AF_UNIX 抽象名）。stdio 不做（Android 无子进程）。**（2026-09-15 GPT 审计 P0-1 补）supervisor 控制面同律双通道**：自身也监听 HTTP 7878 + AF_UNIX `\0zero-supervisor`——否则 loopback 受限时模块数据面有兜底而**控制面失联**（health/exec/file/procs 全断，D69 工作站整体不可达）；TermuxBridge 探测策略 HTTP→socket→DEAD。 |
| **D60** | **LLM 生成模块编写目标** | **Zero 轻量 SDK 为主 + FastMCP 兼容**。LLM 生成目标 = Zero 提供的**零依赖 stdlib Python shim**（`@zero_tool` 装饰器 → 自动出 schema + `serve()` 起 stdlib http.server 说 MCP 兼容 JSON-RPC），generate→compile→mount 最快、沙箱依赖最小、零安装；手写富模块仍可走完整 FastMCP server。两路都汇入同一 FunctionRegistry（`dyn_*`）。 |
| **D61** | **重构起步顺序** | **按构想文档顺序：阶段一 Termux 环境（运行时+编译链+沙箱）→ 阶段二 模块注册表+热插拔框架（现有功能模块化）→ 阶段三 LLM 生成→编译→挂载闭环**。ShizukuBridge 为独立基础 infra，**并入阶段一并行子轨**（不依赖 Termux、低风险、早出动作能力价值），不扰动 Termux 主线。 |
| **D62** | ~~**沙箱模型**~~ **已废止（2026-09-15 D67 替代）** | 原案：proot+allowlist+禁网+默认 CONFIRM——按用户裁定"取消权限限制、Termux 最大授权"废止；proot 保留为可选隔离工具不再强制。 |
| **D63** | **操作层权限** | Shizuku 写/副作用动作（tap/swipe/input_text/start_app/grant_perm/set_setting/shell_run 变更类）默认 **CONFIRM**；只读 shell（dumpsys/settings get/读通知）可 **AUTO**；`shell_run` 任意命令恒 **CONFIRM**。`am force-stop`/`pm uninstall` 等高危恒 **FORBIDDEN**（仅用户手动）。沿用 D9/D17。**注（2026-09-15）：本条仅辖 Shizuku 操作层 FC；动态模块权限见 D67（恒 AUTO）**。 |
| **D67** | **自编译最大授权（2026-09-15 用户裁定，替代 D62 沙箱模型）** | **Termux 编译运行环境即授权边界——模块在 Termux 内最大授权**：生成代码不限 import（socket/subprocess/文件 IO/联网全开）、不进 proot 沙箱、不禁网、不禁 pip；**模块挂载即 `perm=AUTO`**（LLM 直接调用零弹窗——自编译体验不断裂）。保留三道非权限性护栏（可观测/可控，非限制）：① 编译校验（`py_compile` 语法门——质量门非权限门）；② 试跑 smoke（普通进程跑非 proot，超时 kill 防挂死）；③ 全链 trace + 用户随时 disable + CRASHED 自动停。**风险接受声明**：无沙箱+AUTO=LLM 生成代码全权运行于 Termux 用户态（仍受 Android 应用沙箱与 Termux uid 边界约束，不触 Zero 进程/系统分区）——用户知情裁定。 |
| **D68** | **动态模块配置协议（2026-09-15 用户裁定）** | Termux 中建有模块，**须在 Zero App 有模块配置设置页**：① 模块 manifest 声明 `configSchema`（字段：key/label/type(text|number|toggle)/default/min/max/description——@ConfigField 精神）；② 配置持久化**复用 ModuleConfigStore**（module_configs 表，moduleId=动态模块 id，JSON——零新表零迁移）；③ 注入机制：supervisor 启动模块时经 **env `ZERO_MOD_CONFIG`（JSON）** 注入，模块 `os.environ` 读取（改配置→supervisor 重启模块生效，配置页明示）；④ Zero 侧 UI：动态模块详情页按 schema 动态渲染（Text/Number/Toggle 三型控件——AutoForm 同款精神，动态 schema 手写渲染不经反射）。 |
| **D69** | **Linux 工作站托付形态（2026-09-15 用户裁定，重排主轴）** | **Termux 内跑 proot-distro Debian 发行版 = Zero Linux 工作站，整个环境托付 Agent 自主开发**——从"写插件"跃迁为"拥有工作站"（多文件项目/apt 自举/任意运行时/自建服务/探索式 shell 循环）。**Zero 只搭三条基建（§十五）**：① shell 会话通道（exec + cwd 粘性）；② 文件双向通道（SAF↔Linux 推拉浏览）；③ 进程与模块管理（进程表 + MCP 挂载桥——M2.x 保留）。**环境分层**：supervisor 跑 Termux 原生层（管理者），Agent 命令与模块进程默认在 **Debian 内**执行（supervisor 经 `proot-distro login` 包装）；proot 由"被 D67 废止的沙箱"**转世为环境载体**（非隔离用途）。**诚实边界**：非 root 用户态 Linux——无 systemd/docker（服务=裸进程经 supervisor 管）；proot 开销 5–20%；物理边界不变（Android 沙箱 + Termux uid，D67 风险接受声明同源适用且扩大至 apt 装包）。执行策略：工作站 FC 全 AUTO（D67 精神），主权闸门=trace + 一键熔断（停 supervisor=停整个工作站）。 |
| **D64** | **MCP 复用为注册表** | **`McpClientModule` 即热插拔注册表主体**——扩展为支持"本地 Termux 模块"服务器类型（启停 + 连接 + 注册 + 信任态 + D51 恢复），不另起平行注册表。`dyn_<module>_<tool>` 与既有 `mcp_<server>_<tool>` 同构。 |
| **D65** | **崩溃隔离边界** | 动态模块在 Termux 独立进程跑（非 Zero 进程内）；模块崩溃不波及 Zero 内核。Zero↔模块仅经传输通道，超时/断连降级为"工具不可用"文案回 LLM（沿用既有 FC 失败降级 §7.6）。 |
| **D66** | **微信接入裁定** | **iLink（OpenClaw/ClawBot）官方个人微信 Bot API 已开（2025-26），做成 Phase 7 的 WeChat 热插拔模块**（Termux Python，同下载型受信模块：`dyn_wechat_*` FC 出站 + `MessagePlatform` 入站，一模块两缝）。**纠正早期 D35"微信无官方 API"判断——前提已过时**。**热插拔可行性**：iLink 纯 HTTP，天然 Termux 热插拔料；唯一约束=实时收消息可达性（拉模型→全手机端热插拔；推模型/webhook→收消息段挂 §15 云中继，手机用户能力仍热插拔）。**待核实**：iLink 收消息推/拉模型 + 主动发起限制 + bot vs 本人身份 + OpenClaw 准入。**on-device 无障碍因微信 8.0.52+ 混淆失效**（§11.6）降为兜底。详见 §十一。 |

---

## 四、传输与模块协议设计

### 4.1 传输层（D59）

```
主路径：Termux 模块进程 ──(MCP Streamable HTTP, JSON-RPC)──> 127.0.0.1:port ──> McpClient(现有)
兜底：  Termux 模块进程 ──(长度前缀 JSON-RPC over AF_UNIX 抽象名 \0zero-mod-<id>)──> McpSocketTransport(新)
```

- **McpSocketTransport**：实现与 `McpTransport` 同接口（`initialize`/`toolsList`/`toolsCall`），底层 `SocketChannel` 连 AF_UNIX 抽象名，帧格式 = 4 字节大端长度 + JSON-RPC 文本。Android 16 检测：先试 loopback HTTP，失败/超时则切 unix-socket（运行时探测，`module_transport: path=socket` 埋点）。
- **端口分配**：每个本地模块动态分配 127.0.0.1 空闲端口（Termux 侧 `serve("127.0.0.1", 0)` 自分配后经 stdout/PendingIntent 回报端口）；unix-socket 兜底用固定抽象名 `zero-mod-<module-id>`。

### 4.2 模块生命周期状态机

```
（D67 简化版）
SOURCE(written) → COMPILED(py_compile 语法门) → PROBED(supervisor 普通进程探活 pass) → ACTIVE(挂载，perm=AUTO)
任意阶段失败 → FAILED(stderr/traceback 回 LLM 修复 ≤3 轮) ；DISABLED(用户关) ；CRASHED(运行时崩, 自动 DISABLED+告警)
```

### 4.3 Zero 轻量 SDK（D60，LLM 生成目标）

零依赖 stdlib shim（`services/src/main/assets/zero_sdk/zero_tool.py`，首跑拷入 Termux）：

```python
# zero_tool.py —— 零依赖，仅 stdlib，MCP 兼容子集
import ast, inspect, json, os, typing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

_TOOLS = {}
_FILES = {}  # name -> abs path（serve_file 注册，供 GET /file/<name> 流式回传，A5）

def tool(fn):
    """装饰器：从类型注解+docstring 自动生成 JSON schema，注册工具。"""
    sig = inspect.signature(fn)
    props, req = {}, []
    for name, p in sig.parameters.items():
        t = p.annotation if p.annotation is not typing.Parameter.empty else str
        props[name] = _py_to_jsonschema(t)
        if p.default is inspect.Parameter.empty: req.append(name)
    _TOOLS[fn.__name__] = {
        "name": fn.__name__,
        "description": (fn.__doc__ or "").strip(),
        "inputSchema": {"type": "object", "properties": props, "required": req},
        "fn": fn,
    }
    return fn

def _py_to_jsonschema(t):  # str/int/float/bool/list/dict → JSON Schema
    m = {str: "string", int: "integer", float: "number", bool: "boolean"}
    return {"type": m.get(t, "string")}

def _dispatch(req):
    """核心 JSON-RPC 分发（initialize/tools/list/tools/call）—— HTTP 与 socket 传输共用。"""
    method, rid = req.get("method"), req.get("id")
    if method == "initialize":
        res = {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}}, "serverInfo": {"name": "zero-mod", "version": "1"}}
    elif method == "tools/list":
        res = {"tools": [{k: v for k, v in t.items() if k != "fn"} for t in _TOOLS.values()]}
    elif method == "tools/call":
        t = _TOOLS[req["params"]["name"]]
        try:
            out = t["fn"](**req["params"].get("arguments", {}))
            res = {"content": [{"type": "text", "text": json.dumps(out, ensure_ascii=False, default=str)}]}
        except Exception as e:
            res = {"isError": True, "content": [{"type": "text", "text": f"{type(e).__name__}: {e}"}]}
    return {"jsonrpc": "2.0", "id": rid, "result": res}

class _Handler(BaseHTTPRequestHandler):
    def do_POST(self):  # MCP 兼容（application/json 单响应；Mcp-Session-Id 可忽略——McpTransport null-safe，A3）
        req = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self._respond(_dispatch(req))

    def do_GET(self):  # 文件回传通道（A5）：下载型模块把大二进制交还 app 入 SAF，支持 Range
        name = self.path.replace("/file/", "").split("?")[0]
        path = _FILES.get(name)
        if not path or not os.path.exists(path):
            self.send_error(404); return
        size = os.path.getsize(path); start, end = 0, size - 1
        rng = self.headers.get("Range")
        if rng and rng.startswith("bytes="):
            s = rng[6:].split("-")[0]; start = int(s) if s else 0
        self.send_response(206 if rng else 200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start); remaining = end - start + 1
            while remaining > 0:
                buf = f.read(min(65536, remaining))
                if not buf: break
                self.wfile.write(buf); remaining -= len(buf)

    def _respond(self, obj):
        b = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers(); self.wfile.write(b)

def serve_file(abs_path):
    """注册文件供 GET /file/<name> 流式回传（Range），返回文件名。下载型模块用（A5）。"""
    name = os.path.basename(abs_path); _FILES[name] = abs_path; return name

def serve(host="127.0.0.1", port=0):
    # HTTP 传输（主，D59）——端口自分配经 stdout 回报供 app 连接
    srv = ThreadingHTTPServer((host, port), _Handler)
    print(f"ZERO_MOD_LISTENING {srv.server_address[1]}", flush=True)
    srv.serve_forever()

def serve_socket(abstract_name):
    """unix 抽象命名空间 socket 传输（A4 兜底，Android 16 loopback 受限时）。
    MCP 语义不变，传输=4 字节大端长度前缀 + JSON-RPC over AF_UNIX（与 McpSocketTransport 对称）。
    抽象名（\\0 前缀）跨 app 共享、不受网络策略限制。"""
    import socket, struct
    srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    srv.bind("\0" + abstract_name)  # 抽象名（\0 前缀，无文件路径，跨 app 共享）
    srv.listen(8)
    print(f"ZERO_MOD_SOCKET {abstract_name}", flush=True)
    def _recvn(c, n):
        d = b""
        while len(d) < n:
            b = c.recv(n - len(d))
            if not b: return None
            d += b
        return d
    while True:
        conn, _ = srv.accept()
        try:
            while True:
                hdr = _recvn(conn, 4)
                if not hdr: break
                req = json.loads(_recvn(conn, struct.unpack(">I", hdr)[0]))
                resp = json.dumps(_dispatch(req)).encode()
                conn.sendall(struct.pack(">I", len(resp)) + resp)
        except Exception: pass
        finally: conn.close()
```

LLM 生成模板：
```python
from zero_tool import tool, serve, serve_socket, serve_file
import os

@tool
def lookup_word(word: str) -> str:
    """查询单词释义并返回。"""
    ...  # 纯 Python，仅用允许名单库
    return result

if __name__ == "__main__":
    # supervisor 按 transport 字段设 ZERO_MOD_SOCKET env：有值→socket 兜底（A4），无值→HTTP 主（D59）
    sock = os.environ.get("ZERO_MOD_SOCKET")
    serve_socket(sock) if sock else serve()
```

> 兼容路径：手写富模块可走完整 FastMCP（`from fastmcp import FastMCP; mcp = FastMCP(); @mcp.tool`），同一 `McpClient` 连接，同注册为 `dyn_*`。
> **双传输 + 文件回传**（A4/A5 落地）：shim 同时支持 `serve()`（HTTP 主）/`serve_socket()`（unix-socket 兜底，supervisor 经 env 切换）/`serve_file()`（下载型模块经 `GET /file/<name>` Range 流式交还 app 入 SAF）。McpSocketTransport（§4.1）与 `serve_socket` 帧格式对称（4B 长度前缀 + JSON-RPC）。

### 4.4 Zero MCP Compatibility Profile v1（P0-3 封板 · 2026-09-15 GPT 审计）

> shim 是**Zero 自定义的 MCP 兼容子集**（非完整 MCP）——本节把边界封板为命名契约，M2.1b 据此做**契约测试**（非"感觉能连"）：

| 项 | Profile v1 固定值 |
|---|---|
| 方法集 | `initialize` / `tools/list` / `tools/call`（仅此三） |
| protocolVersion | 恒 `"2025-06-18"`（握手回显请求值亦可） |
| initialize 响应 | `{protocolVersion, capabilities:{tools:{}}, serverInfo:{name,version}}`；不维护会话——`Mcp-Session-Id` 可忽略（`McpTransport` null-safe） |
| tools/list 响应 | `{tools:[{name,description,inputSchema}]}` |
| tools/call 响应 | `{content:[{type:"text",text}]}`；异常=`{isError:true, content:[...]}` |
| HTTP 形态 | 单 `application/json` 响应（非 SSE 流）——与 `McpTransport` 双形态路由对齐 |
| socket 帧 | 4 字节**大端**长度前缀 + UTF-8 JSON-RPC 文本 |
| 超时 | 连接方（McpClient）控制；服务端无超时语义 |
| 错误格式 | JSON-RPC 2.0 error 对象或 HTTP 4xx/5xx；Profile 不定义重试 |

**超出 Profile 的 MCP 特性（resources/prompts/notifications/流式）不实现不支持**——需要时升 Profile v2 另行封板。

---

## 五、自编译管线（D67 最大授权版）+ 模块配置协议（D68）

### 5.1 自编译回路（复用现有 FC 循环 · 2026-09-15 D67 简化版）

```
LLM 在 REACT 循环中调用 develop_module(name, description, code, config_schema?)
  → CodeDevModule → CompileManager（原 SandboxManager 更名，无沙箱）
     ① 写码：经 TermuxBridge RUN_COMMAND 写入 $PREFIX/zero/modules/<name>.py（+ 零依赖 shim）
     ② 编译校验（质量门非权限门）：python -m py_compile <name>.py——语法错误行号回 LLM
     ③ manifest 处理：解析 code 头部 manifest 注释块或 develop_module 参数（deps/termuxDeps/
        runtime/configSchema）→ 存 DynamicModuleStore；deps 非空 → supervisor bootstrap 装 pip 依赖
     ④ 试跑 smoke（防挂死非防越权）：supervisor 以普通进程起模块 → health 探活（端口/抽象名回报）
        超时 15s 未起 → 失败；**不进 proot、不限 import、不禁网（D67）**
     ⑤ 失败 → stderr/traceback 经 JsonExtractor 回 LLM → 有界修复（≤3 轮，复用既有降级 §7.6）
     ⑥ 通过 → 挂载：McpClient 连端点 → tools/list → 注册 dyn_<name>_<tool> FC，
        **perm=AUTO（D67——LLM 直接调用零弹窗）**；configSchema 非空 → 模块配置页就绪（D68）
  → LLM 即可调用新工具 → Observations 正常回灌
```

**护栏（非权限性，D67）**：全链 AgentTrace（code_dev: generate/compile/test/mount/repair）+ 用户随时 disable（动态模块页）+ CRASHED 自动停 + 崩溃计数告警。风险接受声明见 D67——Termux 用户态边界（Android 应用沙箱 + Termux uid）即物理边界。

### 5.2 模块状态库（`DynamicModuleStore`，Room · D67 简化版）

每模块持久化：`id / origin(HANDWRITTEN|GENERATED) / source(code) / schema / transport(http|socket) / **lastEndpoint**（仅调试信息，见下） / deps(List<pip>) / **installedDeps(List<pip>)** / termuxDeps(List<pkg>) / runtime(python|node) / configSchema(JSON，D68) / state(ACTIVE|DISABLED|CRASHED) / createdAt / lastCrashAt`。

- **P0-4（2026-09-15 审计）endpoint 语义**：HTTP 端口 `serve(port=0)` **每次启动动态分配**——DB 的 `lastEndpoint` 仅作"上次运行"调试信息；**真实连接地址一律以 supervisor 启动回报（ZERO_MOD_LISTENING/抽象名）为准**，ModuleRegistry 收到回报后 `updateRuntimeEndpoint()` 更新内存连接与 DB lastEndpoint。**D51 启动恢复同律：不信任 DB endpoint 直连——必经"supervisor 重启模块 → 重新发现端点 → 更新 → mount"**。
- **P1-5 依赖归属记录**：`installedDeps` 记录"本模块实际装过哪些 pip 包"（supervisor 安装成功后回写）——依赖装在 Debian 全局（D67 不隔离），此记录供未来依赖治理/卸载参考，v1 不做治理。
- **perm 恒 AUTO、无 capability 维度**（D67 废止双维放权）。
- manifest 驱动 bootstrap（B-II-4 保留）：`deps`/`termuxDeps` 供 supervisor 装依赖。
- D51 启动恢复保留：非 CRASHED 模块经 supervisor 重挂；CRASHED 不自动重挂 + 告警。
- **P1-6 状态转换补全**：`CRASHED --用户 enable--> DISABLED --自动 PROBED(重试起进程探活)--> ACTIVE`（用户手动 enable=明确重试语义；探活失败回 DISABLED+告警，不回 CRASHED——CRASHED 特指运行中崩溃）。

### 5.3 模块配置协议（D68 · Termux 模块 ↔ Zero 配置页）

**① manifest `configSchema` 声明**（模块作者/LLM 写模块时给出，JSON 数组）：
```json
[{"key": "city", "label": "城市", "type": "text", "default": "邵阳", "description": "天气查询城市"},
 {"key": "interval_min", "label": "刷新间隔(分)", "type": "number", "default": 30, "min": 5, "max": 720},
 {"key": "enabled", "label": "启用推送", "type": "toggle", "default": true}]
```

**② 持久化**：复用 `ModuleConfigStore`（module_configs 表，moduleId=动态模块 id，JSON）——零新表零迁移（B12 消除一半面）。写回走既有 300ms 防抖管线。

**③ 注入**：supervisor `POST /module/{id}/start` 携带 `config`（Zero 侧读 ModuleConfigStore 传入）→ supervisor 以 **env `ZERO_MOD_CONFIG=<json>`** 启动模块子进程；模块内 `json.loads(os.environ.get("ZERO_MOD_CONFIG", "{}"))` 读取。**配置变更 → 配置页保存 → Zero 调 supervisor 重启该模块生效**（配置页明示"保存后模块将重启"）。

**④ Zero 侧 UI**：动态模块详情页（DynamicModulesScreen 扩展）按 configSchema **动态渲染**三型控件（OutlinedTextField/Slider+数值/Switch）——AutoForm 同款精神；无 configSchema 的模块不渲染配置区。模块信息暴露：id/origin/状态/端点/transport/依赖清单/源码查看（只读）。

---

## 六、实施路径（按文档顺序，D61）

> 每 Step ≤3 文件或 ≤1 逻辑单元；用户 Build 验证后推进（沿用 §7 协作模式）。
> 标杆模块参考：FC 注册见 `WebSearchModule`；AIDL/Service 见既有；MCP 动态注册见 `McpClientModule`；Room 见 `MemoryModule`。

### 阶段一：Termux 环境就绪（运行时 + 编译链 + 沙箱）+ Shizuku 基础设施

#### Step M1.1：ShizukuBridge 骨架 + UserService AIDL（操作层基础infra，并行）
**创建**：
- `services/src/main/java/com/zero/v2/services/shizuku/ISystemActionService.aidl`（`tap`/`swipe`/`inputText`/`pressKey`/`runShell`/`startActivity`/`grantPermission`/`setSetting`）
- `services/.../shizuku/SystemActionUserService.kt`（实现，跑在 Shizuku 特权进程，shell-uid 执行 `input`/`am`/`pm`/`settings`/`content`）
- `services/.../shizuku/ShizukuBridge.kt`（IZeroModule `id=system_action`，INSTRUMENT；`Shizuku.bindUserService` 绑定 + 重连 + 激活态检测）
**修改**：`app/build.gradle.kts`（Shizuku API 依赖 `dev.rikka.shizuku:api` + `hidden-api-stub`/`refine`）、`AndroidManifest.xml`（`<queries>` Shizuku）
**验证**：Build 通过；Shizuku 已激活时 `runShell("echo ok")` 返回 `ok`（dex + 实测）

#### Step M1.2：Shizuku 操作 FC + 无障碍 dispatchGesture 兜底 + 权限中心
**修改**：`ShizukuBridge.kt`（`registerFunctions()`：`tap(x,y)`/`swipe`/`input_text`/`press_key`/`start_app`/`open_url`/`grant_perm`/`set_setting`/`shell_run`，权限按 D63；Shizuku 未激活 → tap/swipe 回退 `AccessibilityService.dispatchGesture`）
**修改**：`res/xml/agent_runtime_service_config.xml`（`canPerformGestures="true"`，R8 提示重开无障碍）、`PermissionCenterViewModel`（+ Shizuku 激活态条目 + 直跳无线调试）
**验证**：Live 中"点击屏幕 (x,y)"→ 触发 tap FC → 真实点击；Shizuku 未激活时回退 dispatchGesture 仍可点击
**B9 延迟优化落位（2026-09-15 审计）**：`runShell("input tap")` spawn 延迟 ~100-300ms/次——M1.2 实现 UserService 直调 `UiAutomation`/InputManager binder 注入手势（非 spawn shell），trace 打点目标 <50ms/动作；实测超标再迭代（批量 gesture 次选）——非阻塞（慢但可用），列 M1.2 验证项。

#### Step M1.3：TermuxBridge 骨架 + RUN_COMMAND + 健康检查
**创建**：`services/.../termux/TermuxBridge.kt`（IZeroModule `id=termux`；存在性检测/启动引导/`sendCommand(path,args,workdir,background)` → `startService(RUN_COMMAND)` + PendingIntent 结果回调；`healthCheck()` ping `python --version`；`termux-wake-lock` 启停）
**修改**：`AndroidManifest.xml`（`<queries>` com.termux + `com.termux.permission.RUN_COMMAND` 声明）、`AppBindingModule`（注册 ShizukuBridge + TermuxBridge）
**验证**：Build 通过；发 `python3 -c "print(42)"` → PendingIntent 回 `42`（dex + 实测）

#### Step M1.4：Termux 环境 bootstrap + **Debian 工作站就绪**（D69）
**修改**：`TermuxBridge.kt`（`bootstrap()`：首跑**前置 `ensureTermuxAlive()`**（§12.6.3 序列：Termux 不在则 Shizuku `am` 拉起→wake-lock，B-II-10，否则 RUN_COMMAND 鸡蛋-蛋失败）→ 经 RUN_COMMAND 执行引导脚本——`pkg install python proot-distro` / **`proot-distro install debian`（D69 工作站载体）** / Debian 内 `apt install python3 python3-pip git build-essential curl ffmpeg` / 建 `/root/work` 与 `/root/zero/modules`、`zero_sdk/` 目录；幂等 + `bootstrap_done/skipped` 埋点）
**创建**：`services/src/main/assets/zero_sdk/zero_tool.py`（§4.3 shim，含 `get_config()` 辅助读 ZERO_MOD_CONFIG）。（~~PythonAllowlist.kt~~——D67 废止静态校验）
**验证**：bootstrap 后 Debian 内 `python3 -m py_compile zero_tool.py` 通过；`proot-distro login debian -- python3 -c "import requests; print('net ok')"` 返回 ok（工作站+联网自由）

> 阶段一里程碑：Shizuku 动作能力可用（早出价值）+ Termux 沙箱/编译链就绪（自编译地基）。

### 阶段二：模块注册表 + 热插拔框架（现有功能模块化 + 动态挂载）

#### Step M2.0：zero_supervisor + 控制面 + health 探活 + **工作站三通道**（B-II-9，§12.6 A1 展开 · D69）
**创建**：`services/src/main/assets/zero_sdk/zero_supervisor.py`（控制面：`GET /health → {alive, wakelock, modules:[{id,state,pid,endpoint}]}` + `POST /module/{id}/start|stop`；子进程管理：按 manifest 装 `deps`/`termuxDeps`、按 `transport` 起 `serve()`/`serve_socket()`（**Debian 内**，经 proot-distro login 包装，§15.4）、回报端点；**有界就地重启**瞬态崩 max 3 次/5min（A-II-3 局部层）；PID+cmdline 校验后 `kill -9`（解 A2）；常驻骑 Termux FGS via wake-lock；**+ §15 三条工作站通道**：`POST /exec`（shell 会话 cwd 粘性/Debian 包装/超时 kill/64KB 截断）、`/file/upload|/ls|/read`（双向文件）、`/procs|/proc/kill|/service/start|stop`（进程表+自建服务登记））
**修改**：`TermuxBridge.kt`（`ensureSupervisor()`：起 supervisor（**固定端口 127.0.0.1:7878** `@ConfigField`——长驻进程不经 stdout/PendingIntent 回报端口，故固定端口直连探活；与落地计划 M2.0 一致）；`health()` 按需探活 + 低频心跳挂 AgentRuntime tick（D18）；死亡判定→§12.6.3 重启序列 + 指数退避上限；跨会话崩数计超 K→auto-DISABLE+告警（A-II-3 全局层））
**验证**：起 supervisor → `health` 返回 alive → 起/停 demo 模块 → PID 可查 → kill 后 health 反映 stopped；supervisor 被杀后 TermuxBridge 自动重启（`termux_restart` 埋点）

#### Step M2.1：DynamicModuleStore（Room 注册表 + 信任态机）
**创建**：`core/data/.../entity/DynamicModuleEntity.kt`、`core/data/.../dao/DynamicModuleDao.kt`（§5.3 字段）；DB v→ +1 + `MIGRATION`（防 fallback 清库）；`AppBindingModule` provider
**验证**：Build 通过；Room schema 生成

#### Step M2.1b：shim ↔ McpClient 协议握手验证（前置门，B-II-12 / A3）
**验证**（不改码，集成验证）：用 §4.3 shim 起一个 demo MCP server → `McpClient` 连 → `initialize`（验 sessionId=null 路径 OK，`McpTransport.kt:68` null-safe）/`tools/list`/`tools/call`（验单 `application/json` 响应正确路由、非期待 SSE）全通 → M2.2 可放心 per-endpoint 连；不通 → 补 shim（session-id echo + 单响应确认）。`mcp_handshake` 埋点。

#### Step M2.2：ModuleRegistry 本地模块连接 + FC 注册（per-endpoint，D64）
**修改**：`services/.../mcp/McpClientModule.kt`（扩展 `activeClients`：本地 Termux 模块经 M2.0 supervisor 启停后回报端点 → `McpClient(name, "127.0.0.1:port" 或 abstract, null, trace)` 连接 → `tools/list` → 注册 `dyn_<id>_<tool>` FC + 服务器级 ActionPermission（按 origin：HANDWRITTEN=CONFIRM/AUTO、GENERATED=CONFIRM，D63）；CRASHED 检测→告警；D51 启动恢复非 CRASHED 模块经 supervisor 重挂）。**per-endpoint**：N 模块=N McpClient+N 连接（`McpClientModule.kt:71` 现模型）——A-II-1 两层架构：**supervisor=控制面**（启停/探活/重启，自己端点），**模块=数据面**（各自 MCP server 端点，McpClientModule per-module 连）
**验证**：手写一个 `zero_tool` 示例模块 → enable（经 supervisor）→ 模块详情 FC 清单出现 `dyn_demo_*` → LLM 可调用（端到端热插拔闭环）

#### Step M2.3：McpSocketTransport（unix-socket 兜底传输，D59）
**创建**：`services/.../mcp/McpSocketTransport.kt`（实现 `McpTransport`，AF_UNIX 抽象名 + 长度前缀 JSON-RPC；`McpClientModule` 连接策略：先 loopback HTTP，超时/失败切 socket，`module_transport` 埋点）
**验证**：模拟 loopback 不可用（断网/切 Android16 行为）→ 自动切 unix-socket → 模块仍可调用

#### Step M2.4：动态模块管理 UI（含**模块配置页** D68）+ 权限中心 Termux 四项（B-II-11）
**创建**：`app/.../ui/settings/DynamicModulesScreen.kt`（模块列表卡：状态徽标 ACTIVE/DISABLED/CRASHED + origin 标签 + 启停 + 源码只读查看 + 端点/transport/依赖信息）、`DynamicModulesViewModel`；**模块详情配置页**（D68：按 configSchema 动态渲染 text/number/toggle 三型控件 + 模块信息卡 + "保存后模块将重启"明示 + 保存→ModuleConfigStore→supervisor 重启模块）；`SettingsScreen` 入口 + `dynamic_modules` 路由
**修改**：`PermissionCenterViewModel`/`PermissionCenterScreen`（Termux 区四项：① 关电池优化 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for `com.termux` + 状态徽标；② Termux:Boot 已装检测（PackageManager）+ 缺失引导；③ OEM 自启动白名单直跳（厂商 Intent，韩版等不可达→文案兜底，§5.3 先例）；④ wake-lock 持有状态徽标 + 释放按钮）+ Shizuku 区（M1.2 已建）
**验证**：UI 启停模块 → FC 清单实时增减；带 configSchema 的模块改配置保存 → 模块重启 → env 注入生效（模块回读值正确）；权限中心 Termux 四项 + ON_RESUME 刷新

> 阶段二里程碑：任意 `zero_tool`/FastMCP 模块可热插拔（启停独立、崩溃隔离、D65），复用 McpClientModule 全套管道。

### 阶段三：Agent 工作站 + 自编译闭环（D67/D69）

#### Step M3.0：工作站 FC（D69 通信基建的 Zero 侧暴露——Agent 直接获得"一台电脑"）
**修改**：`services/.../termux/TermuxBridge.kt`（`registerFunctions()` 暴露 §15 通道为 FC，全 **AUTO**——D67/D69）：
- `linux_exec(cmd, cwd?, timeout_s?)` → stdout/stderr/exit/cwd（**探索式开发核心原语**——Agent 的 ls/grep/试跑/看报错循环；cwd 粘性经 supervisor）
- `linux_ls(path)` / `linux_read_file(path, offset?)` / `linux_write_file(path, content)`（小文本）
- `linux_upload(from_saf, to_linux)` / `linux_download(from_linux, to_saf)`（大文件双向——Kotlin 编排器拉推流，剪辑/下载场景）
- FC description 内嵌**工作站使用规范**（工作区 /root/work、长任务 nohup+/service 登记、输出截断约定、模块化时机建议——临时探索用 exec、成熟工具走 develop_module）
**验证**：对话"看看工作站里装了什么"→ LLM 调 `linux_exec("apt list --installed | head")` → 真实回传；"把音乐库第一首转码成 m4a"→ upload→exec(ffmpeg)→download 端到端

#### Step M3.1：CompileManager 编译管线（原 SandboxManager 更名，无沙箱）
**创建**：`services/.../termux/CompileManager.kt`（§5.1 ①–⑥：写码→py_compile 语法门→manifest 解析（deps 非空触发 supervisor bootstrap 装 pip）→supervisor 普通进程试跑探活→抓 stderr/traceback→pass/fail；试跑超时 15s + PID kill（`pkill -f`，A2——非 `am force-stop` 包级误杀）；`compile/test/pass/fail` 埋点。**无 allowlist、无 proot、无禁网（D67）**）
**验证**：喂一段有语法错的代码 → 返回行号错误；喂合规代码 → 起进程探活 pass；带 `import requests` 的代码照常通过（联网自由）

#### Step M3.2：CodeDevModule（LLM 自迭代元工具 · 全 AUTO）
**创建**：`services/.../module/CodeDevModule.kt`（IZeroModule `id=code_dev`；FC `develop_module(name, description, code, config_schema?)`（**AUTO——D67 取消权限限制**）→ CompileManager → 通过则挂载 + 返回新工具清单；`list_modules`（AUTO）/ `enable_module`/`disable_module`（AUTO——启停非破坏性，D67 精神；用户也可经 UI）——**动态模块挂载后 dyn_* FC 恒 AUTO**；develop_module 的 FC description 内嵌《模块构建指引》精简规范（命名/manifest/shim API/配置声明——引导 LLM 产出规范模块）；元工具排除自动化递归自引用——§3.5 先例）
**修改**：`AppBindingModule` 注册
**验证**：对话中"写一个工具把大段文本去重"→ LLM 调 develop_module → 编译通过 → `dyn_dedup_*` 出现 → LLM 直接用之（零弹窗端到端闭环）

#### Step M3.3：失败修复回路 + 可观测
**修改**：`CodeDevModule.kt`/`CompileManager.kt`（失败时 `stderr/traceback` 经 `JsonExtractor` 回 LLM；≤3 轮有界修复，超出则"模块未挂载：原因"文案；`AgentTrace` 全链路 `code_dev` 埋点：generate/compile/test/mount/repair）
**验证**：故意让 LLM 产出有缺陷代码 → 见其据 stderr 自修复 → 最终挂载；RunLog 可查自编译 trace

> 阶段三里程碑：LLM 自我扩展能力闭环——"我需要一个工具"→ 写→编译→挂载→直接用（零弹窗零沙箱，D67），无需重建 APK。

### 阶段四：收官（D67 后无放权维度——简化）

- **M4.1 状态与可控收口**：CRASHED 自动 DISABLED + 权限中心告警；用户 UI 启停/disable（唯一控制面）；配置页（D68）收口验证。~~放权状态机~~（D67 废止 perm/capability 双维）。
- **M4.2 全流程闭环验证**：视觉→决策→操作——Live 中读屏/截图 → LLM 决策 → Shizuku tap/start_app 操作 → 重观察；自编译——LLM 写工具→挂载→用于该操作任务（全 AUTO 零弹窗）。
- **M4.3 可控性审计（原安全审计改向）**：Termux 用户态边界确认（模块不触 Zero 进程/系统分区）、CRASHED 计数告警、trace 覆盖度（code_dev 全链/module lifecycle）、disable 即时性。
- **M4.4 文档同步**：DEVELOPMENT_DESIGN 能力矩阵 + STATUS 收官 + 本方案实测结论回填 + 《模块构建指引》定稿。

---

## 七、新增模块/文件清单

```
services/shizuku/        ISystemActionService.aidl, SystemActionUserService.kt, ShizukuBridge.kt
services/termux/         TermuxBridge.kt, CompileManager.kt（原 SandboxManager，无沙箱无 allowlist——D67）
services/mcp/            McpSocketTransport.kt（新）, McpClientModule.kt（扩）
services/module/         CodeDevModule.kt（新）
services/assets/zero_sdk/ zero_tool.py（LLM 生成目标 shim，含 serve_socket/serve_file + ZERO_MOD_CONFIG env 读取辅助）, zero_supervisor.py（控制面，§12.6/M2.0 + config 注入 D68）
core/data/entity/        DynamicModuleEntity.kt（简化版 §5.2，+configSchema 字段）
core/data/dao/           DynamicModuleDao.kt
app/ui/settings/         DynamicModulesScreen.kt（列表+状态+源码查看）, DynamicModulesViewModel.kt, DynModuleDetailScreen.kt（D68 配置页）
app/res/xml/             agent_runtime_service_config.xml（canPerformGestures=true）
AndroidManifest.xml      <queries> com.termux + Shizuku + RUN_COMMAND 权限
docs/refined/            ZeroV2-模块构建指引.md（§十四，LLM/用户模块开发规范）
```

---

## 八、风险登记

| 风险 | 级别 | 缓解 |
|---|---|---|
| Android 16 后台 loopback 受限 | P0 | D59 unix-socket 兜底 + 模块进程 `termux-wake-lock`/FGS 保活 + 运行时探测切换 |
| Shizuku 重启失效 / Google 限设备端 ADB | P1（战略） | Shizuku 未激活时 tap/swipe 回退 dispatchGesture（M1.2）；系统动作降级为"需激活"文案；持续关注 ADB 限制提案 |
| ~~proot 非硬安全边界~~ | ~~P1~~ | **【已废 D67】**沙箱/allowlist/禁网/禁 pip 全撤；proot 转世为 Debian 工作站载体（D69，非隔离用途） |
| Termux 后台被 OEM 杀 | P1 | wake-lock + 权限中心引导关电池优化 + CRASHED 自动告警 + 重连 |
| 自编译无限循环/资源耗尽 | P1 | supervisor 超时 kill（15s/试跑，全局上限）+ FC 回路步数/Token 守护（D41 复用） |
| LLM 产出恶意/越界代码 | P1 | 静态禁名单 + 沙箱态默认 CONFIRM + 联网须二次放权 + `shell_run` 恒 CONFIRM |
| Termux 未安装/未授权 | P2 | TermuxBridge 引导文案 + 权限中心 RUN_COMMAND 授权直跳 + 降级为"内核模块可用、动态模块不可用" |
| 模块进程端口冲突 | P2 | 端口自分配（serve port=0）+ 回报 + unix-socket 兜底 |
| iLink 新协议不确定性（微信，D66） | P1 | 落码前必查官方文档：收消息推/拉模型、主动发起限制、bot vs 本人身份、OpenClaw 准入；按推/拉决定手机端热插拔 or §15 云中继；on-device 无障碍因微信 8.0.52+ 混淆已降为兜底（§11.6） |

---

## 九、与现有 Phase 的关系

- 本方案为 **Phase 9 之后的并行新轨**（构想文档自定"阶段一/二/三"），不与 ROADMAP_P5_P8 的 P5–P8、P9(已完成)、P10(多 Agent 远期) 冲突——命名沿用构想的"阶段一/二/三/四"，避免占用既有 P 编号。
- 现有 MCP 生态（P8，Step 8.1–8.6，已完成 v6.21–v6.28）是本方案的**直接地基**：`McpTransport`/`McpClient`/`McpClientModule`/`McpServersScreen`/SecureKeyStore 全部复用（D64）。
- 现有 REACT 执行模式（D41）+ AgentTrace + ActionPermission（D9）+ 用户主权（D17）是自编译回路与放权模型的基础设施，零新增。
- 本方案**不重写任何既有 Kotlin 模块**（D61 增量原则）；既有 ~30 个 IZeroModule 继续以常驻/编译期模块运行，仅"新模块 + LLM 生成模块"走 Termux 动态加载。

---

## 十、待决策 / Backlog

- **云服务落地**：构想 §十五的轻量云（2c2G，承载 MCP 服务器 + 企业微信通信 7×24）与 LG root 设备（备份/同步节点）的接入时序——本方案聚焦本机能力，云/LG 作独立后续轨。
- **微信/企业微信接入**（D66，更新 D35）：个人微信 = **iLink（OpenClaw/ClawBot）官方 API**（2025-26 已开，D35"无官方 API"前提过时），做成 Phase 7 的 **WeChat 热插拔模块**（Termux Python，`dyn_wechat_*` FC 出站 + `MessagePlatform` 入站，一模块两缝，详见 §11）。实时收视 iLink 推/拉模型定（待核实）：拉→全手机端热插拔；推→收消息段挂 §15 云中继，手机用户能力仍热插拔。企业微信·微信客服（48h/5条）作 CRM 补充 Adapter。on-device 无障碍因微信 8.0.52+ 混淆降为兜底（§11.6）。
- **多 Agent 协作（原 P10）**：自编译产出"专业 Agent"后，可作为多 Agent 轨的 Worker 来源——远期接合点。
- **Termux:API**（设备能力 shell 命令：摄像头/传感器/短信等）是否作为动态模块的快捷能力底座，留阶段二后评估。
- **Node.js 模块支持**：本方案以 Python 为主（D60）；Node 作为 FastMCP 兼容路径的次要运行时，待有真实 Node 模块需求时启用 `node --check` + 沙箱适配。

---

## 十一、应用实例与能力边界厘清：微信接入（D66）

> 本节把"微信打通"作为热插拔框架的**首个平台类应用实例**，并厘清一条能力边界：对抗无障碍的 App 走云 API，不混淆的 App 走 Shizuku。仅设计，不落码。

### 11.1 能力边界矩阵（2026 现状）

| 路径 | 官方? | 跑在哪 | 读消息 | 写消息 | 主动发起 | 封号风险 | 判定 |
|---|---|---|---|---|---|---|---|
| **iLink / ClawBot** | ✅官方 | 云端/手机 HTTP | ✅回调/拉 | ✅HTTP | 待核实 | 低 | **主推荐** |
| **企业微信·微信客服** | ✅官方 | 云端 HTTP | ✅回调 | ✅（48h 内 5 条） | ❌48h 后不可 | 低 | CRM 补充 |
| 无障碍/Shizuku UI 自动化 | ❌ToS 灰 | 手机端 | ⚠️8.0.52+ 被混淆 | ✅ input/tap | ✅ | 高 | **不推荐主路径** |
| PadLocal/WeChatFerry 逆向 | ❌逆向 | PC/云 | ✅ | ✅ | ✅ | 高 | padplus 已 deprecated，废弃 |

### 11.2 iLink 官方路径（主，更新 D35）

- **官方凭据**：[微信开放社区 ClawBot 接口文档](https://developers.weixin.qq.com/doc/aispeech/knowledge/openapi/Clawbotrelated.html)（域名 `ilinkai.weixin.qq.com`：`POST /api/v1/wechat/qrcode` + `/qrcode/status` + 收发 HTTP 端点）。非野鸡——腾讯官方域名。
- **机制**：请求二维码 → 用户手机微信扫码授权 → 轮询登录 → HTTP 收发。无 PadLocal token、无 PC hook、声明零封号（[AllClaw 解析](https://allclaw.org/blog/what-is-ilink) · [腾讯云接入指南](https://cloud.tencent.com/developer/article/2651968) · [知乎开发指南](https://zhuanlan.zhihu.com/p/2019677743126704371)）。
- **定位**：框在"AI 助手/Agent"语义（OpenClaw 网关、ClawBot 插件）——与 Zero 定位重合。
- **待核实（落码前必查官方文档）**：① bot 独立身份 vs 本人身份；② 主动发起窗口/频次/内容审核；③ OpenClaw 框架绑定与准入门槛；④ 新协议稳定性/SLA。

### 11.3 热插拔可行性结论（核心）

**微信能力完全可以、且应做成热插拔模块**——iLink 是纯 HTTP，与下载型热插拔模块同属"手写受信 Termux Python 模块"：

- `WeChatModule`（Zero SDK shim）跑 Termux，ModuleRegistry 注册为 `dyn_wechat_*` FC：`wechat_login`（返回二维码供 UI 扫）/ `wechat_send_message(to, text)` / `wechat_read_unread(since)` / `wechat_login_status`。
- 运行时启停、崩溃隔离、免重建 APK（D59/D60/D64 全义）；需联网 → Debian 内自由联网（D67 最大授权），挂载即 AUTO。
- LLM/Live 语音直接经 FC 调它发微信（"回老王说我晚点到"→ `wechat_send_message`）。

**唯一真约束 = 实时收消息的可达性**（不是"能否热插拔"）：

| iLink 收消息模型 | 手机端热插拔？ | 说明 |
|---|---|---|
| **拉/长轮询**（类 Telegram getUpdates） | ✅ 完全可以 | Termux 模块 long-poll（挂 tick/AgentJob），免公网；Doze 约束非 7×24，D40 双层调度兜底 |
| **推/webhook**（需公网回调 URL） | ⚠️ 收消息段需云 | webhook 接收器跰 §15 云（7×24 公网可达）；**手机端用户能力仍是热插拔模块**（经云中继收发），本地模块照常启停 |

→ 即便 iLink 是 webhook-only，**手机端 WeChat 用户能力依然是热插拔模块**——只是"收"委托云端中继；云是基础设施，不是用户能力。收消息模型（推/拉）**待核实官方文档**，但它**不改变"能热插拔"结论，只改变"收消息段跑在哪"**。

### 11.4 架构形态：一个模块占两条接缝

WeChat 热插拔模块同时实现（皆运行时可插拔）：

- **入站**：实现 `MessagePlatform` → 微信来消息 → `MessageRouter` → Conversation → ChatBotModule（LLM 应答）。被动应答。
- **出站**：注册 `dyn_wechat_*` FC → LLM/Live 主动发起发消息。主动发送。

```
个人微信用户 ⇄ 微信(腾讯)
               ↕ iLink HTTP（QR 授权 + 收 + 发）
         [WeChat 热插拔模块 : MessagePlatform + dyn_wechat_* FC]
           ├─ 拉模型：全在手机 Termux（tick long-poll）
           └─ 推模型：收消息段经 §15 云中继（7×24 webhook），手机能力仍热插拔
               ↕ MessageRouter → Conversation → ChatBotModule → 回复回流
         手机端 Zero（Live 语音/对话直接经 FC 调）
```

> 与 TelegramAdapter（D37）同构；鉴权（iLink token / 企业微信 access_token）走 SecureKeyStore。

### 11.5 企业微信·微信客服（CRM 补充）

- 官方 [微信客服 API](https://kf.weixin.qq.com/api/doc/path/94745)：回调接收 + [发送消息](https://developer.work.weixin.qq.com/document/path/94677)（文本/图/语音/视频/文件/小程序/菜单）。
- **硬限制**：客户主动发消息后 **48h 窗口内、最多 5 条**（[服务条款](https://work.weixin.qq.com/kf/eula) · 错误码 95002）；**不可未经咨询主动发起**。
- 适合被动咨询/客服话术；**不适合**个人全天候代理。作 `WeComKfAdapter`（MessagePlatform）补充，同 §15 云。

### 11.6 能力边界厘清：Shizuku 操作层对"反无障碍 App"走云 API

微信 8.0.52+（2025）**主动混淆无障碍节点信息**（[CSDN：控件混淆](https://blog.csdn.net/A622828/article/details/147897330) · [知乎：无障碍混淆探究](https://zhuanlan.zhihu.com/p/1907372254662198397)），专反 Auto.js/AutoX.js 读消息。**影响本方案 §一.1 / §六的 Shizuku 操作层**：

- **读侧**：无障碍树在微信失效 → 走 iLink 云 API（拉/回调），**不走手机端无障碍树**。
- **写侧**：Shizuku `input text`+`tap`（shell 级）仍可用，但需截图+VL(qwen-vl) 定位坐标——慢、需亮屏、ToS 灰 → 仅作 iLink 做不到的零星 UI 动作兜底。
- **边界结论**：Shizuku 操作层面向**不反制无障碍的 App**（系统设置/常规 App 自动化）；**对抗无障碍的 App（微信）走官方云 API**。两通道在 Platform Gateway 汇合，按目标 App 反制强度选——与下载型模块（不反制无障碍、需 Python 工具链 → Termux 热插拔）形成互补：不同 App 按其约束选通道。

---

## 十二、落地阻塞与高危审计（2026-08-30）

> 交叉核对代码库（`ZeroDatabase`/`McpTransport`/`McpClient`）与外部约束后，对 §一–§十一 的落地风险审计。§八 之外的发现，按严重度排序。

### 12.0 已核实的代码事实（审计依据）

- `ZeroDatabase.kt:42` `version = 12` + `DatabaseModule.kt:180` **`fallbackToDestructiveMigration()` 开启** → 任何迁移写错/漏注册 = Room 清库（对话/记忆/自动化全丢）。
- `McpTransport.kt:32/45/68/77` 管理 `Mcp-Session-Id`（init 响应捕获、后续请求携带，null-safe）；`McpClient.kt:52/76/112` init/list/call **全有 `withTimeout` 硬上限**。

### 12.1 阻塞项（A — 不解决则热插拔/自编译跑不起来）

| # | 阻塞 | 证据 | 解法 |
|---|---|---|---|
| **A1** | **Termux 模块进程常驻不可靠（头号）**：M2.2 `RUN_COMMAND BACKGROUND` 起 `python serve()`，后台进程绑 Termux 前台态；OEM/Doze 杀后台（D40 实测）。Termux 不前台=模块被杀=dyn_ FC 全失效 | §1.2/§六 M2.2；D40 | ① Termux 单常驻 supervisor + `termux-wake-lock`（非每模块独立 spawn）；② "Termux 须保活"作硬前置，权限中心强引导+状态自检+降级文案；③ §15 LG root 设备做稳定宿主（root 强保活） |
| **A2** | per-module 精准 kill 不可靠：M2.2 写 `am force-stop`，但 `am force-stop com.termux` 杀整个 Termux 包（所有模块+本体全死），非单模块 | §六 M2.2 文案；am force-stop 语义 | 启动记录 PID（serve 经 stdout/PendingIntent 回报），disable/crash 按 PID `kill -9`（ShizukuBridge.runShell 或 Termux `pkill -f <module>.py`）；改 M2.2 |
| **A3** | zero_tool.py shim ↔ McpClient 协议兼容未验证：shim 无状态、不发 Mcp-Session-Id、硬编码 protocolVersion；McpTransport 双形态响应解析是否对 tools/call 单 application/json 正确路由未验 | §4.3 shim；`McpTransport.kt:32/45/68/77`；`McpClient.kt:52/76/112` | M2.2 落码前先做 shim↔McpClient 握手验证（initialize/list/call 全通）；不通过则补 shim（session-id echo + 单响应确认） |
| **A4** | unix-socket 兜底（D59）只有客户端、无服务端：§4.3 shim `serve()` 只起 stdlib http.server，无 socket-mode serve → Android 16 切 socket 时模块连不上 | §4.3 ↔ D59 矛盾 | shim 增 `serve_socket(abstract_name)`（stdlib AF_UNIX abstract）对接 McpSocketTransport；或诚实降级"兜底待 M2.3 后补、HTTP 先行" |
| **A5** | 下载型模块缺文件回传通道：shim 只有 MCP JSON-RPC，无 `GET /file/<name>` 流式路由 → 下载类模块无法把大二进制交还 app 入 SAF | §4.3 shim | shim 增通用 `serve_file(path)`/GET Range 路由（并入 §4.3 规范，非某模块专属） |
| **A6** | 长时模块 FC 撞 McpClient `CALL_TIMEOUT_MS` 硬上限（§8.2 约 60s）：下载/长计算/WeChat long-poll >60s 即超时。WebFetchModule.downloadFile 不走 MCP（无硬上限）故能长跑，dyn_ 模块无此豁免 | `McpClient.kt:112` withTimeout；§8.2 | ① 长时模块用 start_job→poll_status 分块 FC；② 受信长时模块单独放宽 CALL_TIMEOUT；③ 下载类由 Kotlin 编排器拉文件流（绕开 MCP call） |

### 12.2 高危点（B — 能跑但重伤可靠性/能力/安全）

| # | 高危 | 证据 | 解法 |
|---|---|---|---|
| ~~**B7**~~ | ~~自编译能力天花板~~ **已撤销（2026-09-15 D67 用户裁定）**——沙箱/allowlist/禁网/双维放权全废止，Termux 最大授权；本风险转化为 D67 风险接受声明（护栏=trace/disable/CRASHED 停） | §5.2 ↔ §5.3（旧版） | 用户知情裁定；护栏非权限性 |
| **B8** | Shizuku 重激活摩擦 + Google 限 ADB 提案（战略双风险）：Android 14+ 每重启需无线调试重新配对；2026-07 提案限设备端 ADB 可能让激活路径失效 | §1.1/§八 | tap/swipe 有 dispatchGesture 兜底；系统动作降级文案；LG root 用 Sui 免重激活；权限中心一键配对+状态徽标；预案=无障碍-only 操作子集 |
| **B9** | Shizuku `input tap` 每次 spawn shell 延迟 ~100-300ms/次 + 无批量 → 移动 computer-use 流畅度远逊 AppAgent（§1.5 对标但未提延迟） | §1.1 `input tap` | Shizuku 直调 `UiAutomation`/InputManager binder 注入手势（非 spawn）；或批量 gesture；trace 打点目标 <50ms/动作 |
| **B10** | McpClientModule"80% 复用"高估：现连远程 URL + McpServerStore(SharedPreferences)；动态本地模块需新写进程生命周期/本地传输/DynamicModuleStore(Room)/bootstrap/信任态机 | `McpClientModule.kt:91`；D64 | 重估为"新建 DynamicModuleService（复用 McpClient 协议+FC 注册，新写生命周期/存储）"，避免低估工时；改 D64/§六 |
| **B11** | Termux 重 onboarding：动态模块全轨依赖另装 Termux + bootstrap（pkg/pip/proot），个人侧载重摩擦；bootstrap 失败即全轨不可用 | M1.4；§八 | bootstrap 幂等+分步重试+进度可见；预置最小 pip 白名单；LG root 稳定宿主；**动态模块轨与内核解耦**——Termux 不可用时主功能（对话/记忆/语音/既有 FC）不受影响（降级非崩溃） |
| **B12** | DB 迁移破坏性 fallback：`fallbackToDestructiveMigration()` 开启 → DynamicModuleEntity(v13) 迁移写错=清库 | `ZeroDatabase.kt:42`；`DatabaseModule.kt:180` | M2.1 严格 MIGRATION_12_13 显式建表+注册 addMigrations；本地验证 v12→v13 升级不丢数据再合并 |
| **B13** | Android 16 loopback + unix-socket 真机均未验：D59 主张 HTTP loopback+socket 兜底，两者均未在目标机型实测 | §1.2/§八；termux#4824 | M1.4/M2.3 加真机验证矩阵（loopback 前台/后台、unix-socket 跨 app）；不通过则需 loopback 的模块挂 §15 云 |

### 12.3 文档内部矛盾（C — 动工前修订，即 A/B 的文档落点）

- **C1**：§4.3 shim 仅 HTTP ↔ D59 unix-socket 兜底缺服务端（→ A4）
- **C2**：§六 M2.2 "am force-stop" 包级误用（→ A2）
- **C3**：§5.3 放权状态机无能力维度 ↔ §5.2 "受信+允许 socket 态"未建模（→ B7）
- **C4**：§1.5 对标 AppAgent 流畅度 ↔ §1.1 `input tap` spawn 延迟未提（→ B9）

### 12.4 已合理覆盖（D，不再展开）

iLink 推/拉待核实（D66，Phase 7 后置非本轨阻塞）；自编译无限循环（supervisor kill）；LLM 恶意代码（静态禁名单+CONFIRM）；端口冲突（自分配）。

### 12.5 优先解决清单（按落地顺序）

1. **阶段一前**：A1（Termux 保活策略定调）、A3（shim↔McpClient 握手验证）、B12（迁移验证流程）——三条不定，阶段二即空中楼阁。
2. **阶段二动工即修**：A2（PID kill）、A4（socket serve 或诚实降级）、A5（file 路由）、A6（长时 FC 策略）、B10（复用工时重估）。
3. **贯穿**：B7（能力放权维度）、B11（onboarding+降级解耦）、B13（真机验证矩阵）。
4. **战略观察**：B8（Shizuku/ADB 限制）——不阻塞但需预案。

### 12.6 Termux 保活与协同（A1 展开：安全且正确的处理）

> 头号阻塞 A1 的展开解法。核心：非 root 下**无法"永不死"**——把"保活"重定义为"**死亡检测 + 按需重启 + 优雅降级**"，目标是死了能恢复，不是永不死。

#### 12.6.0 核心重构：单常驻 supervisor（非每模块独立 spawn）

M2.2 现状错在每模块各发一次 `RUN_COMMAND BACKGROUND` 起 `serve()`——N 个独立进程、N 条独立生命周期、无法集中跟踪。改为：

- Termux 内起**一个常驻 `zero_supervisor.py`**（骑 Termux 前台服务，见 12.6.1），由它管理所有模块进程：start/stop/PID/health/崩溃就地重启。
- `TermuxBridge` **只与 supervisor 一个端点对话**（loopback HTTP / unix-socket）。
- 模块仍是 supervisor 的**子进程**（崩溃隔离 D65 不丢），PID 集中在 supervisor 内跟踪（同时解 A2 的精准 kill）。

#### 12.6.1 保活栈（降低死亡概率，非消除）

按效力从强到弱，全部叠加：

| 杠杆 | 操作 | 谁做 | 效力 |
|---|---|---|---|
| `termux-wake-lock`（主） | = Termux FGS + PowerManager wakelock；首模块 enable 时 `RUN_COMMAND termux-wake-lock`，全 disable 时 `termux-wake-unlock` | TermuxBridge 自动 | 中高（FGS 级） |
| 关电池优化 | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for `com.termux` + 状态自检徽标 | 用户（权限中心引导） | 高（Doze 豁免） |
| Termux:Boot | 装 addon，开机自动起 supervisor | 用户 | 中（重启存活） |
| OEM 自启动白名单 | 权限中心直跳厂商自启动页（不可达则文案） | 用户（无法自动化） | 中高（OEM 冻结豁免） |

> wakelock 纪律：按需 acquire / 全禁用时 release，不常驻；trace 计费 + 持有时长可观测。

#### 12.6.2 协调协议（Zero ↔ supervisor）

- supervisor 控制端点：`GET /health → {alive, wakelock, modules:[{id,state,pid,endpoint}]}`；`POST /module/{id}/start|stop`。
- 探活策略（不紧轮询，省电）：
  - **按需探活**：每次模块 FC 调用前轻探（结果缓存 N 秒）。
  - **低频心跳**：挂 AgentRuntime tick（D18 自适应 60s~2.7h），只探活不重启。

#### 12.6.3 重启序列（按需，带退避 + 上限）

```
死亡判定（health 超时）
  → ① 查 Termux 进程（Shizuku pgrep -f com.termux 或 ActivityManager）
  → ② Termux 不在 → 拉起：Shizuku am start（隐蔽 flag，最坏短暂前台）
       （RUN_COMMAND 需 Termux 已运行 = 鸡蛋问题，故先 am 拉起再 RUN_COMMAND）
  → ③ re-acquire wake-lock + re-start supervisor + D51 式重挂启用模块（DynamicModuleStore）
  → ④ 退避：指数退避，max M 次/分钟；超限 → CRASHED + 告警 + 停 respawn
  → ⑤ 全程幂等：重启中重复请求合并；trace termux_restart: reason/stage/retries
```

#### 12.6.4 优雅降级（B11 解耦）

重启失败 → `dyn_*` FC 返回"Termux 不可用，见权限中心"文案；**Zero 内核（对话/记忆/语音/既有 Kotlin FC）完全不受影响**。需要 7×24 的模块（WeChat webhook 收消息）**不放手机 Termux，放 §15 云**——手机 Termux 只承载按需能力。

#### 12.6.5 安全护栏

- supervisor **只管进程，不直接跑模块逻辑**（模块=独立子进程，隔离 D65）。
- kill 前校验 **PID + cmdline 匹配**（防误杀异进程）。
- **崩溃计数**：单模块 K 次崩 → auto-DISABLE(CRASHED) + 告警 + 停 respawn（防 fork-bomb / 死循环烧电）。
- wakelock 按需 acquire/release，trace 计费 + 持有时长可观测。
- 重启序列幂等 + 重试上限。
- Zero↔supervisor 仅经传输通道，超时/断连降级文案（沿用 §7.6）。

#### 12.6.6 与既有基建衔接（不新造轮子）

- **AgentRuntimeService（无障碍保活 D15）= Zero 自身稳定锚点** → `TermuxBridge` 在 Zero 内始终活 → 能执行重启协调。即 **Zero 稳、Termux 浮，Zero 重新锚定 Termux**——保活靠 Zero（无障碍级）兜底协调，Termux 作可恢复的易失工作端。
- **D40 AlarmManager 准点闹钟** = 唤醒 Zero 做低频心跳/定时重启检查（不直接唤 Termux，Zero 作中继）。
- **D51 启动恢复** = supervisor 重启后的模块重挂清单（DynamicModuleStore）。
- **§15 LG root 设备** = 需 7×24 模块的稳定宿主（root 可 init.d / Magisk 常驻，免 OEM 杀）。

#### 12.6.7 诚实边界

- **非硬保活**：激进 OEM（华为 EMUI 历史杀 FGS）即便全套保活仍可能杀 Termux → 靠"检测+重启"而非"永不死"，这是非 root 的物理上限。
- `am` 拉起 Termux 可能短暂闪到前台（UX 代价）；后台启动服务在部分 ROM 受限。
- 用户**必须**做的设置（关电池优化 + 自启动白名单 + 装 Termux:Boot）无法自动化——个人侧载的必然摩擦（B11）。

> 一句话：**Zero（无障碍级稳定）作锚，Termux（易失）作可恢复工作端，单 supervisor 统管模块进程，wake-lock+关电池优化+Termux:Boot 降死亡概率，health 探活+按需重启+退避上限+优雅降级兜死亡恢复；需 7×24 的能力不碰手机 Termux，上云。**

---

## 十三、第二轮整文档审查（2026-08-30，设计深化）

> 第一轮 §十二 之外的发现：supervisor 引入后浮现的一致性问题、设计缺陷与缺失细节。带「已回填」的已在本次修订落回正文；「仍开放」为待办。

### 13.1 代码事实确认（审计依据）

| 事实 | 证据 | 影响 |
|---|---|---|
| `CALL_TIMEOUT_MS = 60_000L` | `McpClient.kt:30` | 长时 dyn_ FC 必超时 → B-II-8 |
| McpClientModule **per-endpoint** | `McpClientModule.kt:71/185/191`（`activeClients` 每 server 一 McpClient 连其 URL） | N 模块=N McpClient+N 连接 → A-II-1 两层架构 |

### 13.2 设计缺陷 / 不一致（已修订）

| # | 缺陷 | 修订落点 |
|---|---|---|
| A-II-1 | supervisor 与 per-module `serve()` 关系未定义 → 定调**两层架构**：supervisor=控制面（启停/探活/重启，自己端点）；模块=数据面（各自 MCP server 端点，McpClientModule per-module 连） | §六 M2.2 已注 |
| A-II-2 | §5.1 ⑥ 挂载流仍写"TermuxBridge 直起 serve()" → 改经 supervisor `POST /module/{id}/start` | §5.1 ⑥ 已改 |
| A-II-3 | 崩溃重启两层边界未清 → 局部（supervisor 有界就地重启 max 3/5min）+ 全局（Zero 跨会话计崩数超 K→auto-DISABLE） | §六 M2.0 已注 |

### 13.3 缺失细节（已回填）

| # | 缺失 | 回填落点 |
|---|---|---|
| B-II-4 | manifest `deps`/`termuxDeps`/`runtime` | §5.3 字段 + M2.0 按 manifest 装依赖 |
| B-II-5 | ~~`origin` 决定初始态（HANDWRITTEN≠SANDBOXED）~~ **已废止（D67）**——origin 仅作展示标签 | §5.3 初始态规则 |
| B-II-6 | ~~`capability` 放权维度（与 `perm` 解耦）~~ **已废止（D67）**——perm 恒 AUTO 无 capability | §5.3 放权双维 |
| B-II-7 | smoke 输入来源（防 LLM 伪通过） | §5.1 ④ |
| B-II-9 | supervisor 自身 Step | §六 M2.0 |
| B-II-10 | bootstrap 鸡蛋-蛋（RUN_COMMAND 需 Termux 已运行） | §六 M1.4 `ensureTermuxAlive` |
| B-II-11 | 权限中心 Termux 四项 | §六 M2.4 |
| B-II-12 | shim↔McpClient 握手验证 Step | §六 M2.1b |
| C-II-13 | §4.3 shim 缺 `serve_socket`/`serve_file` | §4.3 已补（do_GET + serve_file + serve_socket + _dispatch 重构） |

### 13.4 仍开放（待办）

| # | 项 | 待办 |
|---|---|---|
| B-II-8 | 长时 dyn_ FC 撞 60s + McpClient 无 per-call timeout | ① `McpClient.toolsCall` 加 `timeoutMs` 参数（现硬编码 `CALL_TIMEOUT_MS=60s`，`McpClient.kt:112`）；② 长时模块用 `start_job`→`poll_status` 异步 FC；③ 下载类由 Kotlin 编排器拉文件流绕开 MCP call。**已由落地计划 M0 定案** |
| A4/A5 | `serve_socket`/`serve_file` 真机验证 | shim 已补代码，待 M2.1b/M2.3 真机验 unix-socket 跨 app + `/file` 大文件流 |
| B13 | Android 16 loopback + unix-socket 真机 | M2.3 验证矩阵 |

### 13.5 优先级（本次修订后）

- **已回填正文（动工即可依 Step 执行）**：A-II-1/2/3、B-II-4/5/6/7/9/10/11/12、C-II-13。
- **已定案**：B-II-8 由落地计划 M0 定案（`McpClient` per-call `timeoutMs` + 异步 `start_job→poll_status` + 下载类 Kotlin 编排器拉流绕开 MCP call）。
- **真机验证**：A4/A5/B13（M2.1b/M2.3）。

---

## 十四、模块构建指引（D68 决策③ · 独立文档）

> 规范全文见 **`docs/refined/ZeroV2-模块构建指引.md`**（2026-09-15 新建）——面向 LLM（develop_module prompt 注入精简版）与用户手写模块的开发规范：zero_tool shim API / manifest 字段 / configSchema 声明 / 命名与依赖规范 / 生命周期与调试 / 完整模板。本方案 §四/§五 与该指引冲突时以本方案为准（接口契约）。

---

## 十五、Linux 工作站三条基建（D69 接口契约 · 全部实现在 supervisor 侧 Python）

> Agent 的开发能力全部骑在这三条通道上；Android 侧仅消费 HTTP/FC（TermuxBridge 扩 FC），改动面极小。

### 15.1 Shell 会话通道（探索式开发核心原语）

```
POST /exec {cmd, cwd?, timeout_s?=120, session_id?="default"}
  → {stdout, stderr, exit, cwd}        # cwd 回传=会话粘性（同 session 下次缺省沿用）
```
- **P1-2（2026-09-15 审计）session 隔离**：supervisor 维护 `session_id → cwd` 映射（默认 `"default"`）——多 Agent 任务/并发 FC 不互污 cwd（任务 A 在 /root/work/projA、任务 B 在 /root/work/projB 互不干扰）；Zero 侧 FC `linux_exec(cmd, cwd?, timeout_s?, session_id?)` 同参透传。
- 命令默认在 **Debian 内**执行（`proot-distro login zero -- bash -c <cmd>` 包装）；超时按**进程组** kill（§15.3）。
- stdout/stderr 截断保护（各 ~64KB 尾部保留，防刷爆 LLM 上下文）；exit 非 0 原样回传（LLM 自诊断）。
- Zero 侧 FC：`linux_exec`（**AUTO**，D67/D69）；**FC description 域边界注明（P1-1）：`linux_exec`=Debian 工作站用户态 ≠ `shell_run`=Android shell（Shizuku，D63 CONFIRM）——两个不同的 shell，防 LLM 混用**。

### 15.2 文件双向通道（缺口 B 完全体）

```
POST /file/upload?path=/root/work/x.mp4   # body=流（app 读 SAF 推入 Debian）
GET  /file/<name>                          # 下行（A5 已有，Range 流式）
GET  /ls?path=/root/work                   # {entries:[{name,size,mtime,is_dir}]}
GET  /read?path=...&offset=&len=           # 文本读（截断保护同上）
```
- Zero 侧 FC：`linux_ls(path)` / `linux_read_file(path, offset?)` / `linux_write_file(path, content)`（小文本直写）/ `linux_download(from_linux, to_saf)`（复用 Kotlin 编排器拉 A5 流入 SAF）/ `linux_upload(from_saf, to_linux)`（全 **AUTO**）。

### 15.3 进程与模块管理（supervisor 控制面扩展 · **P0-2 进程组契约**）

```
GET  /procs                    → {procs:[{pgid, pid, cmd, ports, module_id?}]}
POST /proc/kill {pgid}         # 进程组 TERM→等 3s→KILL；kill 前校验 moduleId+cmdline+pgid
POST /service/start|stop       # Agent 自建常驻服务登记（cmd+cwd+name+pgid；D51 恢复）
（模块启停/挂载 = 既有 M2.0/M2.2 接口，不变）
```
- **P0-2（2026-09-15 审计）进程组管理**：`proot-distro login` 是 wrapper——实际进程树 `supervisor→proot→bash→python`，裸 PID kill 可能孤儿化真实模块进程（状态误判/双实例）。supervisor 内模块抽象为 `ModuleProcess{moduleId, logicalPid, launcherPid, pgid, endpoint, startedAt, state}`，**以 `setsid` 起独立进程组、按 pgid 管理**（TERM→宽限→KILL；校验 cmdline 属管理域）。/exec 超时 kill 同律。
- Agent 可在工作站内起任意常驻服务（HTTP/定时器/数据库），经 `/service` 登记入进程表——supervisor 统一探活/重启/D51 恢复；成熟工具仍可包装为 MCP 模块挂载（dyn_* FC——两层能力并存）。

### 15.4 环境分层与 bootstrap

```
Termux 原生层：supervisor（管理者——exec/login 包装、文件路由、进程表、模块控制面；HTTP 7878 + \0zero-supervisor 双监听，D59 补）
  └─ proot Debian（被管理者=Zero Linux 工作站）：apt/git/python/node/gcc……Agent 全权
     - 工作区约定（P1-3 目录契约）：/root/work = Agent 工作区（git 项目/临时文件/构建产物）；
       /root/zero = Zero 运行时（modules/shim/supervisor 元数据）——**Agent 的 git clean/rm -rf/构建
       不得波及 /root/zero**（无权限限制，靠规范防自毁运行时——FC description 与《模块构建指引》注明）
     - bootstrap（M1.4 扩）：proot-distro install debian + apt 基础包（python3/python3-pip/git/build-essential/curl/ffmpeg）
       + pip 源配置 + 建目录（幂等，bootstrap_done 埋点）
```
- MCP 模块进程同样在 Debian 内起（shim 兼容——proot 网络共享，loopback/unix-socket 均可达）——**统一环境**，Agent 开发与工具运行同一坐标系。
- **P1-4（协议预留）**：D68 的 `ZERO_MOD_CONFIG` 仅承载**非敏感配置**；Secret（API key/Cookie/Token）**不入 manifest/DB source/env/trace**——预留 shim `get_secret(key)` → 未来经 Zero `SecureKeyStore` 注入（本版不实现，接口先封：见《模块构建指引》§三表）。

### 15.4 环境分层与 bootstrap

```
Termux 原生层：supervisor（管理者——exec/login 包装、文件路由、进程表、模块控制面）
  └─ proot Debian（被管理者=Zero Linux 工作站）：apt/git/python/node/gcc……Agent 全权
     - 工作区约定：/root/work/（Agent 项目默认根）；模块源码 /root/zero/modules/（shim 随装）
     - bootstrap（M1.4 扩）：proot-distro install debian + apt 基础包（python3/git/build-essential/curl）
       + pip 源配置 + 建目录（幂等，bootstrap_done 埋点）
```
- MCP 模块进程同样在 Debian 内起（shim 兼容——proot 网络共享，loopback/unix-socket 均可达）——**统一环境**，Agent 开发与工具运行同一坐标系。

---

## 十六、封板契约表（2026-09-15 GPT 审计落档 · M1 开工前 Gate 0 门）

| 契约 | 固定内容 | 落点 |
|---|---|---|
| Supervisor IPC | **HTTP 7878 + AF_UNIX `\0zero-supervisor` 双通道**（HTTP→socket→DEAD 探测） | D59 补/§15.4/M2.0 |
| Supervisor endpoint | runtime discovery——不永久信任任何缓存地址 | §15.4 |
| Module endpoint | **每次启动重新发现**；DB 只存 `lastEndpoint`（调试）；D51 恢复=重启→发现→更新→mount | §5.2 P0-4 |
| Module PID | **进程组（pgid）管理**：setsid 起组、TERM→宽限→KILL、moduleId+cmdline+pgid 三校验 | §15.3 P0-2 |
| MCP 协议 | **Zero MCP Compatibility Profile v1**（三方法/单响应/4B BE 帧/无会话）——M2.1b=契约测试 | §4.4 |
| Socket 帧 | 4 字节大端长度前缀 + UTF-8 JSON-RPC | §4.4 |
| 模块状态 | ACTIVE / DISABLED / CRASHED；转换含 PROBED（enable→探活→ACTIVE） | §5.2 P1-6 |
| D68 配置 | 普通配置→env `ZERO_MOD_CONFIG`；**Secret 预留 `get_secret()`→SecureKeyStore**（不入 manifest/DB/env/trace） | §15.4 P1-4 |
| D69 cwd | 按 `session_id` 隔离（默认 default） | §15.1 P1-2 |
| linux_exec 域 | =Debian 工作站用户态；≠`shell_run`（Android shell，D63）——FC description 注明 | §15.1 P1-1 |
| 目录契约 | `/root/work`=Agent 工作区 ≠ `/root/zero`=Zero 运行时（勿自毁） | §15.4 P1-3 |
| D67 | 不恢复 sandbox/allowlist/禁网/逐级放权（"更好方案"仅 backlog） | D67/审计十七 |
| 依赖归属 | `installedDeps` 记录（v1 记录不治理） | §5.2 P1-5 |
| 长任务 | `timeoutMs` 非长任务方案——同步延长上限；真长任务必须 `start_job→poll_status` 异步模式 | M0 补 |
| timeout 语义 | `installTimeoutS`（pip/apt）与 `probeTimeoutS`（进程启动→endpoint ready）分离，互不包含 | 参数表 §9.1 |
| maxModules 语义 | =DynamicModuleStore **模块总数**（installed≠active 不区分，v1） | 参数表 §9.1 |

> 开发顺序 Gate 对照（GPT 审计二十）：Gate0 文档封板（本表）→ Gate1 Shizuku（M1.1–M1.2）→ Gate2 Termux IPC（M1.3）→ Gate3 Supervisor 双通道+进程组（M2.0）→ Gate4 Debian 工作站（M1.4+M3.0）→ Gate5 MCP 模块（M2.1–M2.4）→ Gate6 自编译（M3.1–M3.3）——与既有步骤序一致，Gate 即真机验证硬门。

---

## 附：研究来源汇总

- Shizuku：[官网](https://shizuku.rikka.app/) · [Shizuku-API](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md) · [用户手册](https://shizuku.rikka.app/guide/setup/) · [XDA 集成](https://www.xda-developers.com/implementing-shizuku/)
- Termux：[RUN_COMMAND Wiki](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent) · [TermuxConstants.java](https://raw.githubusercontent.com/termux/termux-app/master/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java) · [loopback issue#4824](https://github.com/termux/termux-app/issues/4824) · [PRoot Wiki](https://wiki.termux.com/wiki/PRoot) · [bubblewrap/LWN](https://lwn.net/Articles/686113/)
- 自编译/代码执行：[OpenHands 架构](https://docs.openhands.dev/sdk/arch/overview) · [OpenHands Agent](https://docs.openhands.dev/sdk/arch/agent) · [arXiv 2511.03690](https://arxiv.org/html/2511.03690v2)
- MCP：[Python SDK](https://github.com/modelcontextprotocol/python-sdk) · [FastMCP Tools](https://gofastmcp.com/servers/tools) · [FastMCP 传输](https://medium.com/@anil.goyal0057/building-and-exposing-mcp-servers-with-fastmcp-stdio-http-and-sse-ace0f1d996dd)
- 移动动作：[AppAgent](https://github.com/TencentQQGYLab/AppAgent) · [AppAgent v2](https://arxiv.org/html/2408.11824v1) · [AccessibilityService](https://developer.android.com/guide/topics/ui/accessibility/service)
- 微信：[iLink/ClawBot 官方接口](https://developers.weixin.qq.com/doc/aispeech/knowledge/openapi/Clawbotrelated.html) · [AllClaw 解析](https://allclaw.org/blog/what-is-ilink) · [腾讯云接入指南](https://cloud.tencent.com/developer/article/2651968) · [微信客服 API](https://kf.weixin.qq.com/api/doc/path/94745) · [企业微信发送消息](https://developer.work.weixin.qq.com/document/path/94677) · [无障碍混淆(CSDN)](https://blog.csdn.net/A622828/article/details/147897330) · [无障碍混淆探究(知乎)](https://zhuanlan.zhihu.com/p/1907372254662198397) · [Wechaty puppet-padplus deprecated](https://github.com/wechaty/wechaty-puppet-padplus)
