# Zero V2 模块化重构 Claude Code 落地实施计划

> 目标：由 Claude Code 在 `D:\ZeroV2` 现有代码库上逐步实现 `ZeroV2-模块化重构方案.md`（D59–D69 + §四–§十五）。
> 核心约束（2026-09-15 更新）：Claude Code **已具备自构建能力**（`_build_apk.bat` 自动构建+自修复，项目现行模式）——验证 = L1 代码审查 + L2 自动构建（APK 时间戳）+ L3 用户真机实测；真机项（Shizuku/Termux/模块端到端）仍待用户。
> **文档权威层级**：模块化重构方案（架构/决策）> 本文档（实施细节）。冲突按此序裁决。
> 2026-08-30 · **2026-09-15 重大修订（用户四项决策）**：Termux 基建照计划；**自编译取消权限限制（D67 最大授权——D62 沙箱/D63 模块权限部分废止，M3 大幅简化）**；新增《ZeroV2-模块构建指引.md》（M3.2 prompt 注入）；**动态模块 Zero 侧配置页（D68，M2.4 扩展）**。· **二次修订（D69 Linux 工作站）**：Termux 内 proot Debian 托付 Agent 自主开发——M1.4 装 Debian、M2.0 扩三通道（exec/文件双向/进程表）、新 M3.0 工作站 FC。

---

## 〇、Claude Code 开发约束与对策

| 约束 | 影响 | 对策 |
|------|------|------|
| ~~无法编译~~（2026-09-15 起已可自构建） | —— | `_build_apk.bat` 自动构建+读输出自修复（现行模式）；仍保持 ≤3 文件或 ≤1 逻辑单元/Step |
| 无法运行 | 运行时崩溃不可见 | 关键路径 `AgentTrace` 埋点；RunLogScreen 可查 |
| 无 Android 设备 | UI/IPC/Shizuku/Termux 不可测 | 标杆模块对照（WebFetchModule/McpClientModule）；真机验证项明确标注"待用户实测" |
| LLM 输出不可靠 | 自编译 JSON/代码不确定 | `JsonExtractor` 4 层降级 + 规则兜底（§7.6）；有界修复 ≤3 轮 |
| 跨会话状态丢失 | 改到哪了 | 进度追踪表（§七）+ 每 Step git commit |

**协作模式**：Claude Code 完成一个 Step → 用户 git commit → 用户 Build → 通过则继续 / 失败贴错误则修复。

---

## 一、前置定案（落码前必决，消阻 B-II-8）

> 唯一需动**真代码**的开放项。本节定案，后续 Step 据此执行，无遗留阻塞。

### Step M0：McpClient per-call timeout + 长时 FC 策略定案（B-II-8）

**事实**：`McpClient.kt:30` `CALL_TIMEOUT_MS = 60_000L`；`McpClient.kt:112` `withTimeout(CALL_TIMEOUT_MS)` 硬编码，无 per-call override。下载/长计算/WeChat long-poll >60s 必超时。

**定案（三策并用）**：
1. **`McpClient.toolsCall` 加 `timeoutMs: Long = CALL_TIMEOUT_MS` 参数**——受信长时模块可传更大值（如 600_000）。`initialize`/`toolsList` 不变（仍 15s）。
2. **长时模块用异步 FC 模式**：`start_job(...)→job_id`（立刻返回）+ `poll_status(job_id)`（轮询）——结果不回单次 MCP call，绕开 60s。**（GPT 审计补）语义门**：`timeoutMs` 只是"受信模块同步调用延长上限"（如 3 分钟内转码），**不是长任务方案**——超过分钟级（如 10 分钟 ffmpeg）必须走 start_job/poll 异步模式，防 Agent 回合被同步长调用占死。
3. **下载类不经 MCP call**：由 Kotlin 编排器（如 BiliMusicModule）OkHttp 拉 `GET /file/<name>` 流 → `WorkDirRepository.writeBinaryStream` 入 SAF（复刻 `WebFetchModule.downloadFile` 同构）。

**修改**：`services/.../mcp/McpClient.kt`（`toolsCall` 签名加 `timeoutMs`，`withTimeout(timeoutMs)`）。
**验证**：Build 通过；现有 `mcp_*` 调用走默认 60s 不变（兼容）；dex 验证签名。
**前置**：本 Step 先行，否则 M3 自编译长时模块会撞超时。

---

## 二、阶段一：Termux 环境 + Shizuku 操作层（M1.1–M1.4）

> 标杆对照：FC 注册见 `WebSearchModule`/`WebFetchModule`；AIDL/Service 见既有；Room 见 `MemoryModule`。

### Step M1.1：ShizukuBridge 骨架 + UserService AIDL

**创建**：
- `services/src/main/aidl/com/zero/v2/services/shizuku/ISystemActionService.aidl`（**实测勘误 M1.1**：AGP 规范 AIDL 源集非 `src/main/java`；且 AGP 8.x 默认关闭 AIDL——services 须 `buildFeatures { aidl = true }`）
```kotlin
package com.zero.v2.services.shizuku;
interface ISystemActionService {
    String runShell(String cmd);          // shell-uid 执行 input/am/pm/settings/content
    void tap(int x, int y);
    void swipe(int x1, int y1, int x2, int y2, int ms);
    void inputText(String text);
    void pressKey(String name);           // KEYCODE_* 名
    boolean startActivity(String component, String action, String data); // am start
    boolean grantPermission(String pkg, String perm);                    // pm grant
    boolean setSetting(String namespace, String key, String value);      // settings put
}
```
- `services/.../shizuku/SystemActionUserService.kt`（`IZeroModule`UserService`，跑 Shizuku 特权进程；`runShell` 经 `Runtime.exec`/`Process` 执行，读 stdout+exit）
- `services/.../shizuku/ShizukuBridge.kt`（`@Singleton` `IZeroModule id=system_action`，INSTRUMENT；`Shizuku.bindUserService` 绑定 + `onBinderReceived`/`onBinderDead` 重连 + 激活态检测 `Shizuku.pingUid()`）

**修改**：`services/build.gradle.kts`（**实测勘误 M1.1**：依赖在代码所在模块非 app——`dev.rikka.shizuku:api:13.1.5` + `provider:13.1.5`（provider=接收 server 推送 binder，app manifest 配套声明 ShizukuProvider）；`hidden-api-stub`/`refine` 留 M1.2 B9 binder 直调时再引）、`app/AndroidManifest.xml`（`<queries>` `rikka.shizuku.provider` + ShizukuProvider 声明）
**验证**：Build 通过；Shizuku 已激活时 `runShell("echo ok")` 返回 `ok`（dex + 实测——**待 M1.3 AppBindingModule 注册后可测**）

### Step M1.2：Shizuku 操作 FC + dispatchGesture 兜底 + 权限中心

**修改**：`ShizukuBridge.kt`（`registerFunctions()`，权限按 D63）：
```kotlin
functionRegistry.register(FunctionDefinition("tap",
    "点击屏幕坐标。Shizuku 未激活时回退无障碍 dispatchGesture。",
    mapOf("type" to "object","properties" to mapOf(
        "x" to mapOf("type" to "integer"),"y" to mapOf("type" to "integer")),
        "required" to listOf("x","y"))),
    metadata.id, permission = ActionPermission.CONFIRM) { args -> tap(args) }
// swipe/input_text/press_key/start_app/open_url = CONFIRM；set_setting/shell_run = CONFIRM
// grant_perm = CONFIRM；只读 shell（dumpsys/settings get）= AUTO；am force-stop/pm uninstall = FORBIDDEN
```
**修改**：`res/xml/agent_runtime_service_config.xml`（`canPerformGestures="true"`，R8 提示重开无障碍）、`PermissionCenterViewModel`（+ Shizuku 激活态条目 + 直跳无线调试 `Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS` 或无障碍页）
> **D38 会话制预授权**：Live 会话内动作 FC（tap/swipe/start_app）可提 AUTO（用户开 Live 即预授权 agent 操作），会话外 CONFIRM——避免 agent 连续 tap 每次弹窗；会话开关控（D17）。
**验证**：Live 中"点击 (x,y)"→ tap FC → 真实点击；Shizuku 未激活回退 dispatchGesture

### Step M1.3：TermuxBridge 骨架 + RUN_COMMAND

**创建**：`services/.../termux/TermuxBridge.kt`（`@Singleton` `IZeroModule id=termux`）
```kotlin
suspend fun sendCommand(path: String, args: List<String>, workdir: String? = null,
        background: Boolean = true, onResult: ((String, Int) -> Unit)? = null): Boolean {
    val intent = Intent().apply {
        setClassName("com.termux", "com.termux.app.TermuxService")
        action = "com.termux.RUN_COMMAND"
        putExtra("com.termux.RUN_COMMAND_PATH", path)
        putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args.toTypedArray())
        workdir?.let { putExtra("com.termux.RUN_COMMAND_WORKDIR", it) }
        putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
        onResult?.let { putExtra("com.termux.RUN_COMMAND_PENDINGIntent", ...PendingIntent...) }
    }
    return ctx.startService(intent) != null  // 需 Termux 已运行（见 ensureTermuxAlive）
}
```
**修改**：`AndroidManifest.xml`（`<queries>` `com.termux` + `<uses-permission android:name="com.termux.permission.RUN_COMMAND"/>`）、`AppBindingModule`（+ `bindShizuku`/`bindTermux`）
**验证**：Build 通过；`python3 -c "print(42)"` → PendingIntent 回 `42`

### Step M1.4：bootstrap + ensureTermuxAlive + **Debian 工作站**（D69）+ shim

**修改**：`TermuxBridge.kt`
```kotlin
suspend fun ensureTermuxAlive(): Boolean {  // B-II-10：RUN_COMMAND 鸡蛋-蛋前置
    if (isTermuxRunning()) return true
    // Shizuku am start 拉起（隐蔽 flag）→ wake-lock
    shizukuBridge.runShell("am start -n com.termux/.app.TermuxActivity --activity-no-history --activity-no-anim")
    delay(1500); return sendCommandWakeLock()
}
suspend fun bootstrap(): Boolean {  // 首跑幂等引导（D69：装 Debian 工作站）
    if (!ensureTermuxAlive()) return false
    // ① Termux 原生层：supervisor 运行时
    val t1 = "pkg install -y python proot-distro && mkdir -p \$PREFIX/zero/zero_sdk"
    // ② 工作站层（§15.4）：Debian + 基础包 + 工作区
    val t2 = "proot-distro install debian || true"  // 已装幂等
    val t3 = "proot-distro login debian -- bash -c 'apt update && apt install -y python3 python3-pip git build-essential curl ffmpeg && mkdir -p /root/work /root/zero/modules'"
    sendCommand(bash, listOf("-c", "$t1 && $t2 && $t3"), background=true, onResult=::onBootstrap)
    // bootstrap_done/skipped 埋点
}
```
**创建**：`services/src/main/assets/zero_sdk/zero_tool.py`（§4.3 完整 shim + `get_config()`）。（~~PythonAllowlist.kt~~——D67 废止）
**验证**：bootstrap 后 `proot-distro login debian -- python3 -m py_compile <shim>` 通过；Debian 内 `import urllib.request` 可用（联网原语——GPT 审计修正：requests 非 bootstrap 基础包，验证用 stdlib 对齐 shim 零依赖精神）；`/root/work`、`/root/zero` 存在

> 阶段一里程碑：Shizuku 动作可用（早出价值）+ Termux 沙箱/编译链就绪。

---

## 三、阶段二：热插拔框架（M2.0–M2.4 + M2.1b）

### Step M2.0：zero_supervisor + 控制面 + health 探活 + **工作站三通道**（A1 核心 · D69 §15 · P0-1/2）

**创建**：`services/src/main/assets/zero_sdk/zero_supervisor.py`
```python
# 控制面（自己端口，非 MCP——与模块数据面分离，A-II-1 两层架构）
# **双通道监听（P0-1）**：HTTP 127.0.0.1:7878（@ConfigField）+ AF_UNIX '\0zero-supervisor'
#   ——loopback 受限时控制面不失联（TermuxBridge 探测 HTTP→socket→DEAD）
# GET /health → {alive,wakelock,modules:[{id,state,pgid,endpoint}]}  ← 端点 runtime 回报（P0-4）
# POST /module/{id}/start {transport, manifest} → setsid 起独立进程组（Debian 内经 proot-distro login）
#   → 读子进程 stdout 拿 ZERO_MOD_LISTENING/抽象名 → 回报端点（P0-4：连接以此为准，DB 仅 lastEndpoint）
# POST /module/{id}/stop → 进程组 TERM→3s→KILL（P0-2：pgid+cmdline+moduleId 三校验，防 wrapper 孤儿化）
# 崩溃就地重启 max 3 次/5min（A-II-3 局部层）；崩溃数上报 Zero 全局计
# ── D69 工作站三通道（§15）──
# POST /exec {cmd, cwd?, timeout_s?, session_id?} → {stdout, stderr, exit, cwd}  # session 隔离 cwd（P1-2）+64KB 截断
# POST /file/upload?path= ｜ GET /file/<name>（A5）｜ GET /ls?path= ｜ GET /read?path=&offset=&len=
# GET /procs（pgid 维度）｜ POST /proc/kill {pgid} ｜ POST /service/start|stop     # 进程组管理（P0-2）
```
**修改**：`TermuxBridge.kt`
```kotlin
suspend fun ensureSupervisor(): Boolean {  // 起 zero_supervisor.py（固定端口）；先 ensureTermuxAlive(M1.4)
    if (health() != null) return true        // 已在
    sendCommand(python, listOf(zero_supervisor_path), background=true)  // 不等退出
    delay(800); return health() != null
}
suspend fun health(): SupervisorHealth? { /* OkHttp GET http://127.0.0.1:7878/health，超时→死亡判定 */ }
// health 挂 AgentRuntime tick（D18 自适应）；死亡→§12.6.3 重启序列 + 指数退避 max M/分钟
// 跨会话崩数超 K→auto-DISABLE 模块 + 权限中心告警（A-II-3 全局层）
```
**修改**：`TermuxBridge.bootstrap()`（M1.4 扩）：拷 `zero_supervisor.py` → `$PREFIX/zero/zero_sdk/`（assets→filesDir→RUN_COMMAND cp，或 bootstrap 脚本追加 `cp $ASSET zero_supervisor.py $PREFIX/zero/zero_sdk/`）
**验证**：supervisor `health`=alive（固定端口）；起/停 demo 模块 PID 可查、端点经 `/health` 回报；supervisor 被杀→TermuxBridge 自动重启（`termux_restart` 埋点）

### Step M2.1：DynamicModuleStore（Room + 信任态机，§5.3 字段）

**创建**：
- `core/data/.../entity/DynamicModuleEntity.kt`（**D67/D68 简化字段（§5.2）+ P0-4/P1-5**：`id/origin/source/schema/transport/**lastEndpoint**（仅调试——真实端点 runtime 发现）/deps/**installedDeps**（归属记录）/termuxDeps/runtime/configSchema(JSON)/state(ACTIVE|DISABLED|CRASHED)/createdAt/lastCrashAt`——~~capability/confirmCount/perm 已废~~）
- `core/data/.../dao/DynamicModuleDao.kt`（`@Upsert`/`observeAll(): Flow`/`getById`/`getEnabled`）
**修改**：`ZeroDatabase.kt`（**`version = 19`**（本计划写于 DB v12 时代，KB 轨已推至 v18——2026-09-15 审计勘误，实施时以**当时实际版本 +1** 为准）+ `entities += DynamicModuleEntity::class`）、`DatabaseModule.kt`（`MIGRATION_18_19` 显式建表 + 注册 `addMigrations`，**防 fallback 清库**——B12）
**验证**：Build 通过；Room schema 生成；本地 v18→v19 升级不丢数据（用户实测）

### Step M2.1b：shim ↔ McpClient **Profile v1 契约测试**（前置门，A3/B-II-12/P0-3）

**验证**（不改码，按 §4.4 封板契约逐项）：起一个 `zero_tool` demo MCP server → `McpClient(name, "127.0.0.1:port", null, trace)` 连 → ①`initialize`（验 sessionId=null 路径 OK + protocolVersion 回显）②`tools/list`（schema 结构符合 Profile）③`tools/call`（单 `application/json` 响应正确路由、非期待 SSE；异常→isError 格式）④socket 传输帧（4B 大端+JSON，serve_socket 路径）⑤超时行为（连接方控制）——**五项契约全过** → M2.2 可放心 per-endpoint 连；任一不通 → 修 shim 至契约（不是修 McpClient）。`mcp_profile_v1` 埋点。

### Step M2.2：ModuleRegistry 本地模块连接 + FC 注册（per-endpoint，D64）

**修改**：`services/.../mcp/McpClientModule.kt`（扩 `activeClients`：本地模块经 M2.0 supervisor 启停→**runtime 回报端点（P0-4——连接以回报为准，DB lastEndpoint 仅调试）**→`McpClient(name, endpoint, null, trace)` 连→`tools/list`→注册 `dyn_<id>_<tool> FC` **（perm 恒 AUTO——D67；原 origin 分权表述已废）**；CRASHED→告警；**D51 启动恢复（P0-4）：非 CRASHED 模块经 supervisor 重启→重新发现端点→更新→mount，不直连 DB 旧端点**）。**两层架构**（A-II-1）：supervisor=控制面，模块=数据面各自端点。
**验证**：demo 模块 enable（经 supervisor）→ FC 清单出现 `dyn_demo_*` → LLM 可调用（端到端热插拔闭环）

### Step M2.3：McpSocketTransport（unix-socket 兜底，D59）

**创建**：`services/.../mcp/McpSocketTransport.kt`（实现 `McpTransport`，`AF_UNIX` 抽象名 `\0zero-mod-<id>` + 4B 长度前缀 JSON-RPC；`McpClientModule` 连接策略：先 loopback HTTP，超时/失败切 socket，`module_transport: path=` 埋点；与 `serve_socket` 帧格式对称）
**修改**：`services/.../mcp/McpClient.kt`（增 `McpClient(name, transport: McpTransport, trace)` 构造——socket 兜底用 transport 注入，不走路由 url 构造内部建 transport；HTTP 路仍用既有 `(name, url, apiKey, trace)` 构造）
**验证**：模拟 loopback 不可用→自动切 unix-socket→模块仍可调用（真机待验，B13）

### Step M2.4：动态模块管理 UI + 权限中心 Termux 四项（B-II-11）

**创建**：`app/.../ui/settings/DynamicModulesScreen.kt` + `DynamicModulesViewModel`（列表卡：状态徽标 ACTIVE/DISABLED/CRASHED + origin 标签 + 启停 + 端点/transport/依赖信息 + 源码只读查看）；**`DynModuleDetailScreen.kt` 模块配置页（D68）**——按 configSchema 动态渲染三型控件（OutlinedTextField/Slider/Switch）+ 模块信息卡 + "保存后模块将重启"明示 + 保存→`ModuleConfigStore`（复用，moduleId=模块 id）→ supervisor 重启模块注入 env `ZERO_MOD_CONFIG`；`SettingsScreen` 入口 + `dynamic_modules` 路由
**修改**：`PermissionCenterViewModel`/`PermissionCenterScreen`（Termux 四项：① 关电池优化 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for `com.termux` + 徽标；② Termux:Boot 已装检测 + 缺失引导；③ OEM 自启动白名单直跳（厂商 Intent，韩版兜底）；④ wake-lock 持有状态 + 释放按钮）+ Shizuku 区（M1.2 已建）
**验证**：UI 启停模块→FC 清单实时增减；带 configSchema 模块改配置保存→模块重启→env 注入生效（模块 get_config() 回读正确）；无 configSchema 不渲染配置区；权限中心 Termux 四项 + Shizuku 状态自检 + ON_RESUME 刷新

> 阶段二里程碑：任意 `zero_tool`/FastMCP 模块可热插拔（启停独立、崩溃隔离 D65）+ **配置页就绪（D68）**，复用 McpClientModule 全套管道。

---

## 四、阶段三：Agent 工作站 + 自编译闭环（M3.0–M3.3 · D67/D69）

### Step M3.0：工作站 FC（D69 通信基建 Zero 侧暴露——Agent 直接获得"一台电脑"）

**修改**：`services/.../termux/TermuxBridge.kt`（`registerFunctions()` 暴露 §15 通道，全 **AUTO**）：
```kotlin
// linux_exec(cmd, cwd?, timeout_s?, session_id?) → {stdout, stderr, exit, cwd}  ← 探索式开发核心原语
//   （session_id 隔离 cwd——P1-2；FC description 域边界注明：linux_exec=Debian 工作站 ≠ shell_run=Android shell——P1-1）
// linux_ls / linux_read_file(path, offset?) / linux_write_file(path, content)（小文本）
// linux_upload(from_saf, to_linux) / linux_download(from_linux, to_saf)  ← Kotlin 编排器推拉流
// FC description 内嵌工作站使用规范（/root/work 工作区、勿动 /root/zero 运行时——P1-3、
// 长任务 nohup+/service、输出截断、成熟工具走 develop_module 的时机建议）
```
**验证**：对话"看看工作站装了什么"→ `linux_exec("apt list --installed | head")` 真实回传；"把音乐库第一首转 m4a"→ upload→exec(ffmpeg)→download 端到端

### Step M3.1：CompileManager 编译管线（原 SandboxManager 更名 · D67 无沙箱）

**创建**：`services/.../termux/CompileManager.kt`
```kotlin
// ① writeCode(name, code) → TermuxBridge 写 $PREFIX/zero/modules/<name>.py
// ② compile: python -m py_compile（stderr→行号——质量门非权限门）
// ③ manifest 解析：# ZERO_MANIFEST: <json> 注释行（deps/termuxDeps/runtime/configSchema，
//    《模块构建指引》§二规范）→ 存 DynamicModuleStore；deps 非空→supervisor bootstrap pip install
// ④ 试跑探活：supervisor 普通进程起模块（无 proot、不限 import、不禁网——D67）→ 15s 内端点回报=pass
//    超时 PID kill（pkill -f，A2 非 am force-stop）
// ⑤ 失败→stderr/traceback 回 CodeDevModule；⑥ 通过→ModuleRegistry.mount(name)
```
**验证**：语法错→返回行号；合规代码（含 `import requests`）→起进程探活 pass；带 deps 模块→pip 装后 pass

### Step M3.2：CodeDevModule（LLM 自迭代元工具 · D67 全 AUTO）

**创建**：`services/.../module/CodeDevModule.kt`（`IZeroModule id=code_dev`）
- FC `develop_module(name, description, code, config_schema?)`（**AUTO——D67**）→ CompileManager→通过则挂载 + 返回新工具清单；**FC description 内嵌《模块构建指引》精简规范**（manifest 行/shim API/configSchema/命名/长任务模式——引导 LLM 产出规范模块）
- FC `list_modules` / `enable_module` / `disable_module`（全 AUTO——启停非破坏性；用户主路径经 UI 配置页）
- **挂载的 dyn_* FC 恒 AUTO（D67）**；元工具排除自动化递归自引用（§3.5 先例）；长时模块走 M0 异步 FC 模式
**修改**：`AppBindingModule`（+ `bindCodeDev`）
**验证**："写一个工具把大段文本去重"→ develop_module → `dyn_dedup_*` 出现 → LLM **直接**用之（零弹窗端到端闭环）；再验带 config_schema 模块→配置页出现可改

### Step M3.3：失败修复回路 + 可观测

**修改**：`CodeDevModule.kt`/`CompileManager.kt`（失败 `stderr/traceback` 经 `JsonExtractor` 回 LLM；≤3 轮有界修复，超出"模块未挂载：原因"文案；`AgentTrace` 全链路 `code_dev`：generate/compile/test/mount/repair）
**验证**：故意产出缺陷代码→见其据 stderr 自修复→最终挂载；RunLog 可查 trace

> 阶段三里程碑：LLM 自我扩展闭环——"我需要一个工具"→写→编译→挂载→**直接用（零弹窗零沙箱，D67）**，无需重建 APK。

---

## 五、阶段四：收官（D67 后无放权维度——简化）

- **M4.1 状态与可控收口**：CRASHED→auto-DISABLE+告警；用户 UI 启停/disable（唯一控制面）；配置页（D68）收口验证。~~放权状态机~~（D67 废止 perm/capability 双维）。
- **M4.2 全流程闭环验证**：视觉→决策→操作（Live 读屏/截图→LLM→Shizuku tap/start_app→重观察）；自编译（写工具→挂载→用于操作任务，全 AUTO 零弹窗）；**工作站（D69）**——`linux_exec` 探索循环（ls→读→改→试跑）、多文件项目（git clone→改→构建）、素材加工（upload→ffmpeg→download）、自建服务（`/service` 登记+重启恢复）。
- **M4.3 可控性审计（原安全审计改向）**：Termux 用户态边界确认、CRASHED 计数告警、trace 覆盖度、disable 即时性。
- **M4.4 文档同步**：DEVELOPMENT_DESIGN 能力矩阵 + STATUS 收官 + 实测结论回填 + 《模块构建指引》定稿。

---

## 六、保证机制（沿用 CLAUDE_CODE_IMPLEMENTATION_PLAN §八）

- **6.1 三层验证**：L1 静态自查（import/DI/接口）→ L2 用户 Build（compile/lint/Room/Hilt）→ L3 APK 实测。任一失败→修复→重验。
- **6.2 关键路径埋点**：每集成点 `AgentTraceManager.record`——`shizuku_action`/`termux_command`/`supervisor_health`/`module_transport`/`sandbox_*`/`code_dev`/`termux_restart`。
- **6.3 变更边界=Git Commit**：每 Step 完成即 commit；出错 `git reset --hard HEAD~1` 回滚。
- **6.4 标杆模块对照**：FC 注册→`WebFetchModule`；MCP 动态注册→`McpClientModule`（Step 8.3）；Room→`MemoryModule`；SAF→`WorkDirRepository`。
- **6.5 渐进迁移**：先新建→验证→再启用；既有 ~30 个 Kotlin IZeroModule 不动（D61），仅新模块+LLM 生成模块走 Termux。
- **6.6 降级策略**：Termux/Shizuku 不可用→dyn_/action FC 返回引导文案，**内核（对话/记忆/语音/既有 FC）不受影响**（B11）；LLM 输出不可靠→`JsonExtractor`+规则兜底（§7.6）。
- **6.7 真机验证矩阵**（待用户实测）：A4 unix-socket 跨 app / A5 `/file` 大文件流 / B13 Android 16 loopback / A1 Termux 保活重启 / Shizuku 重激活。
- **6.8 模拟器自测基建**（2026-09-16 建立）：AS 模拟器（adb root）+ Room trace 直读 + 对话 UI 自动化——方法/装配链/经验见 `docs/refined/ZeroV2-模块化模拟器自测手册.md`；工具 `_test_trace.py`/`_test_send.py` 在库。M1.1–M1.3 已用其完成 L3 级自测（11 项过 + onAwake 漏调 registerFunctions 真 bug 修复）。

---

## 七、进度追踪表

> ✅ 已完成 | ⏳ 待开始 | 🔧 进行中 | ⚠️ 有阻塞

| 阶段 | Step | 内容 | 状态 | 验证 |
|---|---|---|:---:|---|
| 前置 | M0 | McpClient per-call timeout + 长时 FC 定案 | ✅ 2026-09-15 | Build ✅ APK 时间戳 ✅；唯一调用方 McpClientModule:257 默认 60s 兼容；语义门落 toolsCall KDoc（FC description 侧 M2.2/M3.2 内嵌） |
| 一 | M1.1 | ShizukuBridge + AIDL | ✅ 2026-09-15 | Build ✅；AIDL 八方法+destroy 事务码 16777115 ✅；**模拟器 L3 实测 ✅ 2026-09-16**：绑定链全通（binder_received→bind_requested→user_service_connected）+ 特权进程 uid=0 + runShell exit=0 + binder 死亡自动重连 |
| 一 | M1.2 | 操作 FC + dispatchGesture 兜底 + 权限中心 | ✅ 2026-09-15 | Build ✅×2；**模拟器 L3 实测 ✅ 2026-09-16**：FC 79→88；tap B9=16ms/15ms（<50ms 达标，binder 直调实证）；swipe/start_app/press_key 真实生效；无障碍兜底 via=a11y_fallback ✅；高危拦截描述层 LLM 拒调×2（代码层未触发——LLM 不肯调）；**修复真 bug：onAwake 漏调 registerFunctions** |
| 一 | M1.3 | TermuxBridge + RUN_COMMAND | ✅ 2026-09-15 | Build ✅；组件=RunCommandService（官方 wiki 勘误）；**模拟器 L3 实测 ✅ 2026-09-16**：awake installed=true；RUN_COMMAND 契约冒烟（extra 键/路由/后台执行——touch 落盘 Termux home）；权限+allow-external-apps 前置就绪；PendingIntent 回传 = M1.4 bootstrap 触发时验 |
| 一 | M1.4 | bootstrap + ensureTermuxAlive + **Debian 工作站（D69）** + shim | ✅ 2026-09-16 | Build ✅；**模拟器 L3 全链实测 ✅**：bootstrap 四轮收敛（t0–t3 真跑：pkg python3.14+proot-distro 5.8、Debian 473 包含 ffmpeg/gcc）；py_compile ✅、urllib NET_OK 200 ✅、/root/work+/root/zero+shim 双侧落位 ✅；**PendingIntent 回传 NOT_EXPORTED 疑点解除**（API 35 四轮 result trace 全通）；实修 3 真 bug：stdin 挂死+sources.list 自愈、proot-distro 5.8 布局探测（containers/ vs install-rootfs/）、（M1.2 遗留 onAwake 已在前 commit） |
| 二 | M2.0 | zero_supervisor + 控制面 + health + **工作站三通道（D69）** | ✅ 2026-09-16 | Build ✅；**模拟器 L3 实测 ✅**：双通道监听（HTTP 7878 alive + `\0zero-supervisor` 帧协议同 §4.4）；/exec Debian 真跑（session 粘性 A=projA/B=/root/work 隔离 + 超时组 kill timed_out + 64KB 截断）；/ls//read 通道；**kill supervisor→60s 守护自动重启**（termux_restart ok=1/3@10min 有界退避）；模块起停端点回报留 M2.1b/M2.2 验；socket 兜底通道代码在位（HTTP 恒通未触发场景，留 B13 矩阵） |
| 二 | M2.1 | DynamicModuleStore + MIGRATION_18_19（以实施时实际版本 +1） | ✅ 2026-09-16 | Build ✅；**模拟器实测迁移零丢失 ✅**：v18(2891 trace)→v19(2926) user_version=19、dynamic_modules 14 列（D67 简化字段+lastEndpoint 调试语义+installedDeps P1-5）、零 fallback 清库日志；@Upsert/observeAll/getById/getEnabled DAO |
| 二 | M2.1b | shim↔McpClient 握手验证 | ✅ 2026-09-16 | **模拟器 L3 五项契约全过 ✅**（§4.4 Profile v1）：①initialize sessionId=null-safe+pv=2025-06-18 ②tools/list schema 生成 ③tools/call 单响应路由+isError（echo 回执/ValueError→⚠️ 失败路径）④socket 4B 大端帧（serve_socket：INIT/LIST/CALL/未知工具 -32602）⑤无服务端超时语义（即时响应+M0 客户端控制）；全部经既有 FC 真链路（mcp_add_server/LLM 调 mcp_*）；**实修 3 真 bug**：supervisor /file/upload RAW body 被误 JSON 解析恒 400、proot-distro 不透传父 env（--env K=V 且须在 -- 前）、shim typing.Parameter→inspect.Parameter（设计稿原生 bug 首执行暴露） |
| 二 | M2.2 | ModuleRegistry per-endpoint + FC 注册 | ✅ 2026-09-16 | Build ✅；**模拟器 L3 端到端热插拔全通 ✅**：D51 恢复（dyn_restore_start→mount，不信任 DB 旧端点）；runtime 端点连接（supervisor 回报 35657）；dyn_demo_v1_echo FC（AUTO D67）→ LLM 调用回执 `echo:m222_end_to_end`；CRASHED 治理（连杀×3→supervisor 超限→Zero 120s 守卫→FC 注销+DB CRASHED+告警 trace）；M0 timeoutMs 消费位落地（moduleCallTimeoutS §9.1）；socket 端点挂载留 M2.3（mount 返回明确 deferred 文案）；TermuxBridge +supervisorPort 访问器 |
| 二 | M2.3 | McpSocketTransport | ✅ 2026-09-16 | Build ✅×3；**模拟器 L3 两路全通 ✅**：直连（demo_sock transport=socket → `path=socket \0zero-mod-<id>` → McpSocket 注入构造 → init_ok sessionId=null（Profile v1）→ dyn_mounted → LLM 调用回执 `via:sock_direct`）；**运行时探测兜底**（demo_fb http → iptables owner 规则模拟 loopback 受限 → `path=loopback_failed` → 切 socket → 挂载 → LLM 回执 `via:fb_after_fallback`）；**实修 3 真 bug**：LocalSocket 双参 connect API35 抛 UnsupportedOperationException（→单参）、serve_socket 串行 accept 并发饿死（→thread-per-conn 对齐 supervisor）、config String 透传二次包裹（→边界归一化 JSONObject）；**新发现环境约束（B13 关联）**：SELinux Enforcing 拒 app→他 app 抽象 socket connectto（avc 实证）——socket 兜底在 Enforcing 不可用，验于 permissive；真机矩阵跟踪 |
| 二 | M2.4 | 动态模块 UI + **模块配置页（D68）** + 权限中心 Termux 四项 | ✅ 2026-09-16 | Build ✅×2；**模拟器 L3 UI 全链实测 ✅**：列表三态徽标（CRASHED/ACTIVE/DISABLED 四卡齐）+ origin/传输/依赖/上次端点（P0-4 调试语义文案）；UI toggle 挂载（DISABLED→ACTIVE + 工具计数 0→1）与卸载（1→0）双向；**D68 配置页端到端**（demo_cfg 三型控件 text/Slider1-120/Switch 渲染 → 改值保存 → `dyn_config_saved fields=3` → unmount+mount 重启（端点 37883→41133）→ env 注入 → 模块 get_config() 回读 `{"city":"Shanghaiy","interval_min":90.73,"verbose":true}` → LLM 回执一致）；无 configSchema 详情页不渲染配置区（明示文案）；源码只读查看；权限中心 Termux 四项（电池优化⚠️受限/Boot✅已装/OEM 手动项/wake-lock 未知态降级——Shizuku server 后起 binder 未重绑，正路径留重激活后验，代码与 M1.x 已证 runShell 同通道）；Shizuku 区 M1.2 已验 + ON_RESUME 刷新沿既有 |
| 三 | M3.0 | **工作站 FC（linux_exec/文件双向，D69）** | ✅ 2026-09-17 | Build ✅×3；**模拟器 L3 主体实测 ✅**：linux_exec（uname/apt 真回执 Debian 内核特征；session 粘性 proj1 三连验——失败 cd 不位移 cwd、stderr/exit 原样回传自诊断；timeout ceiling 接 §9.1）；linux_write_file（21B 落盘 + cat 回读）；linux_read_file（内容+size/offset 元信息）；linux_ls（3 项含目录标记/大小）；**实修 1 真 bug：/file/upload 客户端误用 PUT（supervisor 仅 do_POST→501 HTML 解析失败）改 POST**；FC description 内嵌 P1-1 域边界/P1-3 目录契约/长任务规范（develop_module 时机）；冷启动自愈链顺带全验（fresh boot→ensureTermuxAlive app 侧兜底直启→supervisor 拉起 alive）；**linux_upload/download：B11 降级分支实测**（"工作目录服务未连接"引导文案），SAF 正路径被模拟器 DocumentsUI ALLOW 框拒注入输入卡壳（手册坑 21）——**留用户真机 2 击配置工作目录后补验**（ffmpeg 素材加工链） |
| 三 | M3.1 | CompileManager（D67 无沙箱）+ manifest 解析 | ✅ 2026-09-17 | Build ✅×2；**L3 通道级三项全过 ✅**（CompileManager 消费的 supervisor 路由逐条真跑；Kotlin 全链 E2E 归 M3.2 develop_module）：①语法错→行号（`File "…", line 5 SyntaxError` 原样回传）；②合规模块探活 pass（start→端点回报 <1s→stop）；③带 deps 模块（import requests + manifest）pip 装后 pass（未装时同模块 CRASHED 对照）；**实修 1 环境真 bug：Debian 12 PEP 668 拦系统级 pip（--user 亦拦）→ `--break-system-packages` 落法（坑 23）**；TermuxBridge 增 supervisor 公开面（supGet/supPost/supPostRaw + installTimeoutS——§十六 单一权威）；manifest 单行注释解析（无行=全默认/坏 JSON=失败二态）+ 模块名安全门（防穿越） |
| 三 | M3.2 | CodeDevModule（全 AUTO + 指引 prompt 注入） | ✅ 2026-09-17 | Build ✅×3；**L3 自迭代闭环全链实测 ✅**：对话"写去重工具"→ LLM 调 develop_module（code 内嵌 manifest+shim 规范照抄）→ compile_ok → probe_ok（端点 46661）→ **mounted tools=[dedup, dedup_report] → LLM 零弹窗直调 `dyn_dedup_dedup` → "apple banana cherry" 去重正确**；DB GENERATED/ACTIVE 落库；**LLM 自主修复提前现形**（首版 probe CRASHED → 依失败文本改码 1227ch 二次通过——M3.3 回路雏形）；带 config_schema 模块（greeter）参数路径落库 ACTIVE（LLM 自补中文 label/description——指引内嵌引导生效；配置页渲染 M2.4 已证）；tools 88→98（+6 linux_* +4 code_dev 族）；**实修 1 真 bug：py_compile 必写 __pycache__ 遇属主污染假语法错 → 改 `compile()` 纯编译门零落盘（坑 24）**；元工具递归自引用排除=描述层禁令+maxModules 硬顶（框架无元标志位，M4.3 审计复核） |
| 三 | M3.3 | 修复回路 + 可观测 | ✅ 2026-09-17 | Build ✅；**L3 三项全过 ✅**：①故意缺陷→自修复→挂载（fixme：`repair round=1/3 编译` → LLM 修正 → `mounted tools=[ping]`——LLM 还先交好版再故意交坏版执行"先错后修"指令，行为可信）；②有界 3 轮（fail4：round 1 试跑/2-3 编译，**"第 3/3 轮"文案引导 LLM 自行停手**转 list_modules——设计意图等效达成）；③**超限拒收**（forced 第 4 次 → `repair_exceeded: fail4 rounds=4>3` + "模块未挂载：已连续 4 轮"终局文案）；**traceback 直达 LLM**（probe 失败经 RUN_COMMAND cat Termux 侧 `zero/logs/module_<id>.log` 尾部 3000B——不动 supervisor 契约）；code_dev 全链 trace 齐套（generate/compile/test/probe/mount/repair/repair_exceeded）+ RunLog UI 可查；连续失败计数成功清零（fixme 两轮成功挂载实证） |
| 四 | M4.1 | 状态与可控收口（crashAutoDisableK 消费闭环） | ✅ 2026-09-17 | Build ✅；**L3 全链实测 ✅**：迁移 v19→v20 零丢失（crash_count 列 + 8 行数据全在）；崩溃计数（杀 demo_fb×N → supervisor CRASHED → 守卫 `计数 1/K=2` + DB CRASHED,1）；**再启用计数保持**（ACTIVE,1——低于 K 不清，跨会话累计语义）；**K 触顶 auto-DISABLE**（周期 2 → `dyn_auto_disabled_alert: 2≥K=2 自动停用` + DB DISABLED,2）；**权限中心告警行**（"🔴 崩溃自动停：demo_fb"+处置引导——UI dump 实证）；**告警后 re-enable 清零**（ACTIVE,0——人工介入重新计数）；UI 启停唯一控制面 + D68 配置页收口引 M2.4/M3.2 既证（demo_cfg E2E + greeter schema 落库）；TermuxBridgeConfig +crashAutoDisableK（§9.1 5/2..20）+ 访问器；DAO +getAutoDisabled |
| 四 | M4.2–M4.4 | 全流程闭环验证/可控审计/文档同步 | ✅ 2026-09-17 | **M4.2 L3 五链 ✅**：多文件构建（gcc+Makefile→make→运行，LLM 11s 全链）/git clone（exit=0 外网可达）/自建服务 D51（杀 supervisor→service_restore→sleeper 复活）/自编译用于任务（urltool 开发→挂载→即刻编码 `hello%20world%2042`）/决策→操作→重观察（start_app settings + dumpsys 前台实证；capture_screen_analyze 需用户 Live 屏幕共享授权=真机项）；素材加工 Linux 侧 ✅、SAF 终点待用户配置工作目录（模拟器 DocumentsUI 拒注入，M3.0 遗留）；**M4.3 审计**见 §十；**M4.4**：STATUS 收官 + DESIGN 能力矩阵/决策行更新 + 指引定稿（pip PEP 668 勘误入册） |

---

## 八、每个 Step 的标准输出格式

```
### Step Mx.y 完成
**改动文件**：新增/修改（path，变化）
**验证要求**：[ ] Build [ ] 实测项
**如 Build 失败**：贴完整错误，我立即修复
```

---

## 十、M4.3 可控性审计结论（2026-09-17 收官）

| 审计项 | 结论 | 证据/落点 |
|---|---|---|
| Termux 用户态边界 | ✅ 确认 | 模块经 proot 跑于 Termux uid（u0_a209）内；Zero（u0_a213）与 Termux 私有存储互不可见；supervisor 仅监听 loopback 7878 + 抽象名；无任何提权路径（代码审 + ps/dumpsys 实证） |
| CRASHED 计数告警 | ✅ | M4.1 全链实测（计数→K 触顶 auto-DISABLE→权限中心告警行→人工再启用清零） |
| trace 覆盖度（§6.2 清单） | ✅ | shizuku_action/termux_command/supervisor_health/module_transport/code_dev（generate/compile/test/mount/repair/repair_exceeded）/termux_restart——M 轨战史全部真实出现（RunLog 可查） |
| disable 即时性 | ✅ | unmountDynamicModule 同步 supervisor stop + FC 注销（M2.2/M2.4 toggle 双向实测） |
| 元工具递归自引用 | ⚠️ 软防 | 框架无元标志位（不加字段=不动封板接口）——落描述层禁令 + maxModules 硬顶（50）+ repairMaxRounds 界修复轮；审计结论：当前可接受（FC 循环 maxRounds=16 另有全局界），真机长期观察后如现自激励再议硬隔离 |
| Supervisor 控制面契约 | ✅ 不变量保持 | M 轨全程未改 zero_supervisor.py 路由（仅 M2.1b 修 RAW 分流真 bug）；双通道/pgid 三校验/Profile v1/session cwd/目录契约逐条在案（§十六） |

**遗留真机矩阵（B13 及相关——不阻塞收官，待用户实测）**：
① Android 16 后台 loopback 受限实测（HTTP 主路 + socket 兜底切换）；② **SELinux Enforcing 下 app→Termux 抽象 socket connectto 被拒**（模拟器 avc 实证——socket 兜底在 stock Enforcing 不可用，permissive/root 可用；D59 兜底语义需真机复核）；③ SAF 工作目录配置两击手动步（模拟器 DocumentsUI ALLOW 框拒注入输入）→ linux_upload/download 正路径；④ Shizuku 无线调试重激活 + One UI 保活（Termux:Boot/电池优化/OEM 白名单——权限中心四项已就位）；⑤ capture_screen_analyze 需 Live 屏幕共享授权（视觉→决策→操作全链的真机验收）。

---

## 九、参数决策表（2026-09-15 审计落档 · M15 律：进 Config 数值显式 min/max / TOGGLE / 按需 TEXT；常数写死+注释不进 Config）

### 9.1 可暴露调节参数（ConfigField）

**TermuxBridge（id=termux）**

| 字段 | 类型 | 默认 | min/max | 说明 |
|---|---|---|---|---|
| supervisorPort | Int | 7878 | 1024/65535 | supervisor 控制面端口（固定端口直连探活，M2.0） |
| maxExecTimeoutS | Int | 600 | 10/3600 | `linux_exec` 超时上限——FC 参数 `timeout_s`（默认 120，§15.1）的天花板，防 LLM 传超长挂死回合 |
| installTimeoutS | Int | 300 | 30/1800 | manifest deps 的 pip/apt 安装超时（大包保护——探活前安装不占 probeTimeout） |
| autoBootstrapOnStart | TOGGLE | false | — | 启动自动 bootstrap（首跑装 Debian 流量大，默认手动触发） |

**CodeDevModule（id=code_dev）**

| 字段 | 类型 | 默认 | min/max | 说明 |
|---|---|---|---|---|
| repairMaxRounds | Int | 3 | 1/5 | 自编译失败修复轮上限（**token 成本相关→可暴露**；§5.1） |
| probeTimeoutS | Int | 15 | 5/120 | 模块试跑探活超时——**只计"进程启动→endpoint ready"，不含 pip/apt 安装**（P1-7：安装归 installTimeoutS） |
| maxModules | Int | 50 | 5/200 | 动态模块数上限——**=DynamicModuleStore 模块总数**（installed 与 active 不区分，P1-8；超出拒挂载+trace） |

**McpClientModule（dyn 调用域）**

| 字段 | 类型 | 默认 | min/max | 说明 |
|---|---|---|---|---|
| moduleCallTimeoutS | Int | 60 | 10/3600 | dyn_* FC 调用默认超时（M0 全局默认；模块 manifest 可选 `callTimeoutS` 覆盖——长任务模块声明式放宽，无需改全局） |

**supervisor 崩溃治理（TermuxBridge 域内）**

| 字段 | 类型 | 默认 | min/max | 说明 |
|---|---|---|---|---|
| crashAutoDisableK | Int | 5 | 2/20 | 跨会话崩溃计数超 K → auto-DISABLE + 告警（A-II-3 全局层） |

### 9.2 常数（不进 Config——写死+代码注释，M15 律）

exec 输出截断 64KB（上下文保护）· serviceRestartMax 3 次/5min（A-II-3 局部层）· 心跳间隔（挂 D18 AgentRuntime 自适应 tick，非独立参数）· supervisor 抽象名格式 `\0zero-mod-<id>` · 工作区约定 `/root/work` · exec 默认 timeout_s=120（FC 参数级非配置）· FC 循环轮数（既有全局 `maxFcRounds` ConfigField 已覆盖，不重复设）。

### 9.3 非参数决策（权限/路由类，勿做成可调参数）

操作层权限矩阵（D63：CONFIRM/AUTO/FORBIDDEN 分级）· 动态模块 perm 恒 AUTO（D67）· exec 默认 Debian 内执行（D69 环境分层）· develop_module FC description 内嵌指引（非配置）。

---

## 附：核心决策沿用清单（确保统一，审查对照）

| 决策 | 来源 | 本计划落点 |
|---|---|---|
| D9/D63 ActionPermission | DEVELOPMENT_DESIGN | M1.2（操作 FC 权限）、M2.2（模块按 origin）、M4.1 |
| D17 用户主权 | DEVELOPMENT_DESIGN | M4.1（放权 CONFIRM）、M1.4（@ConfigField）、M2.4 |
| D40 双层调度 | DEVELOPMENT_DESIGN | M2.0（health 挂 tick + AlarmManager 唤 Zero 重启 Termux） |
| D51 启动恢复 | DEVELOPMENT_DESIGN | M2.2（非 CRASHED 模块经 supervisor 重挂）、M2.1 |
| D57 文件主权 | DEVELOPMENT_DESIGN | shim `serve_file`/`GET /file`、M0 下载类 SAF 落盘 |
| D59 传输 | 重构方案 | M2.3（HTTP+unix-socket）、M1.4 shim `serve`/`serve_socket` |
| D60 生成目标 | 重构方案 | M1.4 zero_tool.py、M3.1 shim 复用 |
| ~~D62 沙箱~~ | 重构方案 | **已废止（D67 替代）**——M3.1 无沙箱无 allowlist |
| **D67 最大授权** | 重构方案（2026-09-15） | M3.1（无沙箱）、M3.2（develop_module/enable/disable/dyn_* 全 AUTO） |
| **D68 配置协议** | 重构方案（2026-09-15） | M2.4（配置页+env 注入）、M2.0（supervisor config 传递）、M2.1（configSchema 字段） |
| **D69 Linux 工作站** | 重构方案（2026-09-15） | M1.4（Debian bootstrap）、M2.0（三通道）、M3.0（工作站 FC）、M4.2（工作站闭环验证） |
| D64 注册表 | 重构方案 | M2.2（McpClientModule per-endpoint） |
| D65 崩溃隔离 | 重构方案 | M2.0（模块=supervisor 子进程）、M2.0 告警 |
| D66 微信 | 重构方案 | Phase 7 后置（§十一），本轨不实施 |
| A1 Termux 保活 | §12.6 | M2.0 supervisor + health/restart + §6.7 真机 |
| A2 PID kill | §十二 | M2.0/M3.1（非 am force-stop） |
| A-II-1 两层架构 | §十三 | M2.2 注 |
| B-II-4 manifest | §十三 | M2.1/M3.1 字段（~~5/6 origin 初始态/capability 已废 D67~~） |
| B-II-8 长时 FC | §十三 | M0 定案（消阻） |
