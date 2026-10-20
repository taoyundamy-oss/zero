# Claude Code 模块化轨开发提示词（M 轨）

> 用途：跨会话流畅执行 `ZeroV2-模块化重构方案.md`（D59–D69 · §4.4 Profile v1/§15 工作站三通道/§十六 封板契约表）与 `ZeroV2-模块化落地实施计划.md`（M0–M4 共 14 Step · §九 参数决策表 · Gate 0–6 对照）。
> 核心原则：**契约封板不动摇**（§十六 16 条）、**不破坏已有功能与基线**、**只做授权修改**。
> 记录日期：2026-08-30 初版 · 2026-09-15 全面重写（D67 最大授权/D68 配置协议/D69 Linux 工作站三决策后，旧版 D62 沙箱模型作废）。
> 结构沿三先例轨（音乐 MU/知识库 KB）模板。

---

## 版本 A：完整启动（首次会话 / 中断后恢复）

```
你正在开发 Zero V2 模块化轨（M 轨），为 Android AI Agent 应用 Zero 增设三大能力：
① Shizuku 系统操作层；② Termux→Debian Linux 工作站（D69 全权托付 Agent 自主开发）；
③ 动态模块热插拔 + LLM 自编译（D67 最大授权）。项目位于 D:\ZeroV2。

## 第一步：加载上下文（只读，不修改任何文件）

请依次读取以下文件，了解本轨全貌：

1. `D:\ZeroV2\docs\refined\ZeroV2-模块化落地实施计划.md` — 实施计划与进度追踪
   （§七进度表 = 当前位置权威；§九参数决策表 = ConfigField 契约；Gate 0–6 = 真机验证硬门）
2. `D:\ZeroV2\docs\refined\ZeroV2-模块化重构方案.md` — 设计/决策权威
   （D59–D69 决策表 · §4.4 MCP Profile v1 · §15 工作站三通道 · §十六 封板契约表——**开工前必读**）
3. `D:\ZeroV2\docs\refined\ZeroV2-模块化开发提示词.md` — 本提示词（行为边界）
4. `D:\ZeroV2\docs\refined\ZeroV2-LLM开发指南.md` — 跨轨通用规范（R1–R8 + 地雷清单 + 安全红线）

根据落地计划 §七进度表中第一个 ⏳ 状态的 Step，精读该 Step 的完整定义
（文件清单/验证标准/关联 D 编号锚点）。

## 第二步：执行当前 Step

严格按落地计划中该 Step 的定义执行：
- 只创建/修改该 Step 明确列出的文件（Android ≤3 文件/Step；Room 迁移组例外同 commit）
- 不改动 Step 范围外的任何文件与功能
- 每个 Step 自带验证标准——逐条对照，不达标的验证项如实标注"待实测/待用户"

## 修改权限边界（硬性，违反即回滚）

只允许三类修改：
1. 实现已批准设计（重构方案 D59–D69 及落地计划对应 Step，含 §4.4/§15/§十六 契约）
2. 修复落地计划/方案审计表（§十二/§十三）明确列出的问题
3. 修复实现过程中发现的真实 bug（有错误日志/复现证据）

不允许三类修改：
1. 未授权架构重构（模块边界/通信机制/数据层的结构性变更）
2. 将已裁定的方案重新设计——**尤其：恢复 D62 沙箱/allowlist/禁网/逐级放权 = 直接违令**
  （D67 用户裁定最大授权；D69 工作站全权托付；"更安全"不是恢复理由，仅 backlog 提案）
3. 因"实现更简单"而修改模块边界、通信机制或 UI 基线（三 Tab 固定、chatHistory 纯净等）

遇到边界拿不准时：记录问题 + 向用户提问，不自作主张。

## 行为规则

1. **每次只做一个 Step**。完成后停下，构建验证，等用户确认再推进。
2. **Android ≤3 文件/Step**（Room 迁移组例外：Entity+Dao+DB版本+Migration 同 commit，X9）。
3. **构建验证**：代码改动后运行 `cmd //c "D:\ZeroV2\_build_apk.bat"`，验 APK 时间戳更新；
   构建失败读编译输出、自诊断、自修复后重试（不得带着失败标记完成）。
4. **先读后写**。修改现有文件前必须 Read 确认当前内容；McpClientModule/TermuxBridge 相关段
   改动前精读（D64 复用主体）。
5. **参考标杆**：FC 注册 → WebSearchModule/WebFetchModule；MCP 动态注册 → McpClientModule
   （Step 8.3 实现）；Room → MemoryModule/MIGRATION 先例（音乐/KB 轨 v15+）；模块结构 →
   MusicModule（IZeroModule 惯例）；shim/协议 → 方案 §4.3/§4.4 逐行对齐。
6. **埋 AgentTrace**：每个关键集成点 record 一句；静默失败路径必须留 trace（E28 铁律）——
   埋点名清单见落地计划 §6.2（shizuku_action/termux_command/supervisor_health/module_transport/
   code_dev/termux_restart）。
7. **契约优先**：§十六 封板契约表 16 条为不可协商实现边界（双通道/进程组/Profile v1/
   runtime endpoint/session cwd/…）——实现与契约冲突时停下来报告，不改契约。
8. **真实偏差入档**：发现设计与现实不符，先取证（日志/复现），记入方案 §十二/§十三 或
   落地计划对应表，再继续——不静默偏航。

## 第三步：输出标准格式

### Step Mx.y 完成：[名称]

**改动文件**：
- 新增: path/to/File（N 行）
- 修改: path/to/File（变化简述）

**验证结果**：
- [x] Build 通过 / APK 时间戳
- [x] 该 Step 验证项（逐条，实测数据）
- [ ] 待用户 L3 实测项（如有，写明操作——Shizuku/Termux/模块端到端均需真机）

**执行偏差**（如有）：与计划的差异 + 原因 + 已入档位置

## 第四步：更新状态

修改 `ZeroV2-模块化落地实施计划.md` §七进度表：
- 该 Step ⏳→✅ + 验证摘要
- 发现的新问题 → 追加到方案 §十二/§十三 审计表（新编号）
- 用 git commit 留痕（信息含 Step 号与关键结论，尾行 Co-Authored-By: Claude Code）

---

不确定的实现选择：先查方案对应 D 决策 → 再查落地计划/审计表 → 都没有 → 向我提问。
```

---

## 版本 B：精简步进（连续开发 · 与版本 D 二选一）

```
继续 Zero V2 模块化轨（M 轨）开发。项目在 D:\ZeroV2。

1. 读 `docs/refined/ZeroV2-模块化落地实施计划.md` §七进度表确认当前 Step（首个 ⏳）
2. 读该 Step 定义 + 关联设计决策（ZeroV2-模块化重构方案.md 对应 D 编号/§4.4/§15/§十六）
3. 执行（Android ≤3 文件/Step；迁移组同 commit；构建验证 _build_apk.bat + 自修复）
4. 输出标准格式（改动文件/验证结果/执行偏差）
5. 更新进度表 + git commit

行为约束（详版见 ZeroV2-模块化开发提示词.md）：
- 只做三类授权修改（实现已批准设计/修复表中列出问题/修复真实 bug）
- 不做三类禁止修改（未授权重构/重设计封板决策/因简化改边界与基线）
- 先读后写；标杆对齐；关键路径埋 trace；静默失败必留痕（E28）
- 契约铁律：§十六封板表 16 条（supervisor 双通道/进程组 pgid/Profile v1/runtime endpoint/
  session cwd/目录契约/…）不可协商；恢复 D62 沙箱即违令（D67）
- 真机项（Shizuku/Termux/Debian/模块端到端）如实标 L3 待用户，不伪造通过
- 不确定时提问，不自作主张
```

---

## 版本 C：修复模式（Build 失败 / 真机反馈后）

```
Zero V2 模块化轨测试反馈。项目在 D:\ZeroV2。

信息：
[粘贴完整编译错误 / 运行日志 / trace / 真机实测反馈（含 supervisor/exec/模块的 stdout/stderr）]

请读取相关文件，分析根因，只做最小必要修复（授权三类之三：真实 bug）。
若根因指向设计层问题（契约/决策层，非实现 bug），停下来向我报告，不自行改设计。
修复后构建验证（_build_apk.bat）+ 更新进度表 + commit。
分诊提示：问题定位先分层——Android(Zero) / Termux / Supervisor / Debian / MCP / Module / CodeDev
（Gate 分层即分诊表，§十六尾注）。
```

---

## 版本 D：逐步提示词（每 Step 一段，直接粘贴执行）

> 顺序执行，不跳步；Gate 0（文档封板）已完成（§十六落档）。每段自带文件边界/契约锚点/验证标准。

### Step M0 — McpClient per-call timeout + 长时 FC 定案（1 文件）

```
执行 Zero V2 模块化轨 Step M0（前置定案消阻 B-II-8）。项目在 D:\ZeroV2。
先读落地计划 M0 定义 + McpClient.kt 现状（CALL_TIMEOUT_MS 硬编码 :30/:112），再动手。

文件（1 文件）：services/.../mcp/McpClient.kt——toolsCall 加 timeoutMs: Long = CALL_TIMEOUT_MS
参数，withTimeout(timeoutMs)；initialize/toolsList 不变（15s）。
语义门（§十六）：timeoutMs 只是受信同步延长上限；分钟级以上长任务必须 start_job→poll_status
异步模式——此语义写进 FC description。

验证：Build ✅；既有 mcp_* 调用走默认 60s 不变（兼容）。R8 输出 + 进度表 + commit。
```

### Step M1.1 — ShizukuBridge 骨架 + AIDL（3 文件+gradle/manifest 同批）

```
执行 Zero V2 模块化轨 Step M1.1（Gate 1 起点：Shizuku 操作层，不依赖 Termux）。
先读方案 §1.1（Shizuku 边界研究）+ D63（操作层权限——注意 D63 仅辖 Shizuku FC，动态模块
见 D67），再动手。

文件：services/.../shizuku/ 三件（ISystemActionService.aidl 八方法 / SystemActionUserService
（跑 Shizuku 特权进程，runShell 经 Process 执行）/ ShizukuBridge（IZeroModule id=system_action
INSTRUMENT，bindUserService+重连+激活态））+ app/build.gradle.kts（shizuku api 13.1.0）+
AndroidManifest（<queries>）。

验证：Build ✅；Shizuku 激活时 runShell("echo ok") 返回 ok = L3 待用户。
R8 输出 + 进度表 + commit。
```

### Step M1.2 — 操作 FC + dispatchGesture 兜底 + 权限中心（3 文件）

```
执行 Zero V2 模块化轨 Step M1.2。先读方案 D63 权限矩阵 + 落地计划 M1.2（含 D38 会话预授权）。

文件：ShizukuBridge（FC×9：tap/swipe/input_text/press_key/start_app/open_url AUTO-or-CONFIRM 按 D63；
shell_run 恒 CONFIRM；高危 FORBIDDEN）+ agent_runtime_service_config.xml（canPerformGestures）+
PermissionCenter（Shizuku 激活态条目）。
**B9 落位**：UserService 直调 UiAutomation/InputManager binder 注入手势（非 spawn input），
目标 <50ms/动作 + trace 打点；实测超标记 G 类再迭代。
FC description 域边界（P1-1）：注明 shell_run=Android shell ≠ 未来 linux_exec=Debian 工作站。

验证：Live 中"点击 (x,y)"真实点击；Shizuku 未激活回退 dispatchGesture = L3 待用户。
R8 输出 + 进度表 + commit。
```

### Step M1.3 — TermuxBridge + RUN_COMMAND（3 文件）

```
执行 Zero V2 模块化轨 Step M1.3（Gate 2：Termux IPC 最小闭环）。先读方案 §1.2（Termux 边界）+
落地计划 M1.3（sendCommand 签名含 PendingIntent 回调）。

文件：services/.../termux/TermuxBridge.kt（IZeroModule id=termux；存在性/启动引导/
sendCommand/healthCheck/wake-lock）+ AndroidManifest（<queries> com.termux + RUN_COMMAND 权限）+
AppBindingModule（bindShizuku/bindTermux）。

验证：Build ✅；python3 -c "print(42)" → PendingIntent 回 42 = L3 待用户。
R8 输出 + 进度表 + commit。
```

### Step M1.4 — bootstrap + Debian 工作站 + shim（2-3 文件）

```
执行 Zero V2 模块化轨 Step M1.4（D69 工作站装载）。先读方案 §15.4（环境分层）+ 落地计划 M1.4。

文件：TermuxBridge（ensureTermuxAlive 鸡蛋-蛋前置 + bootstrap 三段脚本：Termux pkg /
proot-distro install debian / Debian apt 基础包 + /root/work、/root/zero 目录）+
assets/zero_sdk/zero_tool.py（方案 §4.3 逐行实现：_dispatch/do_GET 文件回传/serve/serve_socket/
serve_file/get_config）。

验证：Build ✅；Debian 内 py_compile shim 通过；import urllib.request 可用（stdlib 验证——
零依赖精神）；/root/work、/root/zero 存在 = L3 待用户。R8 输出 + 进度表 + commit。
```

### Step M2.0 — supervisor 双通道 + 三通道 + 进程组（2 文件 · Gate 3 核心）

```
执行 Zero V2 模块化轨 Step M2.0。先读方案 §15 全节 + §十六契约表——本步是封板契约的主要
实现载体，逐条对照。

文件：assets/zero_sdk/zero_supervisor.py（**双通道监听** HTTP 7878+\0zero-supervisor；
/health；/module start|stop——**setsid 进程组 + TERM→3s→KILL + moduleId/cmdline/pgid 三校验**
（P0-2）；**端点 runtime 回报**（P0-4：ZERO_MOD_LISTENING 经 stdout 读，回报 Zero）；
/exec **session_id cwd 隔离**+64KB 截断；/file/upload|/ls|/read；/procs pgid 维度；
/service 登记表 D51 恢复；崩溃就地重启 3/5min + 全局计数上报）
+ TermuxBridge（ensureSupervisor 固定端口+socket 探测 HTTP→socket→DEAD；心跳挂 D18 tick；
退避重启序列）。

验证：Build ✅；health=alive；起停 demo 探活；kill supervisor 自动重启 = L3 待用户。
R8 输出 + 进度表 + commit。
```

### Step M2.1 — DynamicModuleStore（迁移组同 commit）

```
执行 Zero V2 模块化轨 Step M2.1。先读方案 §5.2（字段——lastEndpoint 仅调试/installedDeps
归属记录/无 perm/capability）+ 落地计划 M2.1。

文件（迁移组 4 文件同 commit X9）：DynamicModuleEntity + DynamicModuleDao +
ZeroDatabase（**version = 当前+1**（KB 轨后应 v19——以实际为准））+ DatabaseModule
（MIGRATION 显式建表+注册，防 fallback 清库 B12）。

验证：Build ✅；Room schema 生成；老库升级不丢 = L3 待用户。R8 输出 + 进度表 + commit。
```

### Step M2.1b — Profile v1 契约测试（前置门 · 集成验证不改码）

```
执行 Zero V2 模块化轨 Step M2.1b。先读方案 §4.4（Profile v1 封板——本步检验标准）。

验证五项（demo shim ↔ McpClient）：①initialize（sessionId null-safe+protocolVersion 回显）
②tools/list schema ③tools/call 单响应路由+isError 格式 ④socket 帧（4B 大端+JSON）
⑤超时（连接方控制）。任一不通 → 修 shim 至契约（不是修 McpClient）。mcp_profile_v1 埋点。
= L3 待用户（需 Termux 环境）。结果入进度表后才可进 M2.2。
```

### Step M2.2 — ModuleRegistry per-endpoint 连接（1 文件）

```
执行 Zero V2 模块化轨 Step M2.2。先读方案 D64 + 落地计划 M2.2（P0-4 语义已注）。

文件：McpClientModule.kt（扩 activeClients：本地模块经 supervisor 启停→**runtime 回报端点连接**
（DB lastEndpoint 仅调试）→tools/list→注册 dyn_<id>_<tool> FC **perm 恒 AUTO（D67）**；
CRASHED 告警；**D51 恢复=supervisor 重启→重新发现→更新→mount，不直连旧端点**）。

验证：demo 模块 enable → FC 清单出现 dyn_demo_* → LLM 调用（端到端热插拔）= L3 待用户。
R8 输出 + 进度表 + commit。
```

### Step M2.3 — McpSocketTransport（unix-socket 兜底，2 文件）

```
执行 Zero V2 模块化轨 Step M2.3。先读方案 §4.1（帧格式与 serve_socket 对称）+ D59。

文件：McpSocketTransport.kt（McpTransport 实现，AF_UNIX 抽象名 + 4B 大端长度前缀）
+ McpClient.kt（增 transport 注入构造）。

验证：Build ✅；模拟 loopback 不可用 → 自动切 socket → 模块仍可调 = L3 待用户（B13 矩阵）。
R8 输出 + 进度表 + commit。
```

### Step M2.4 — 动态模块 UI + 配置页 + 权限中心（3 文件）

```
执行 Zero V2 模块化轨 Step M2.4（D68 配置页）。先读方案 §5.3（配置协议四则）+ 落地计划 M2.4。

文件：DynamicModulesScreen+VM（列表/状态徽标 ACTIVE|DISABLED|CRASHED/origin/启停/源码查看）
+ DynModuleDetailScreen（**configSchema 动态渲染三型控件**+保存→ModuleConfigStore→supervisor
重启注入 env+「保存后模块将重启」明示）+ PermissionCenter（Termux 四项：电池优化/Termux:Boot/
自启动白名单/wake-lock）。

验证：UI 启停→FC 增减；改配置→模块重启→get_config() 回读正确 = L3 待用户。
R8 输出 + 进度表 + commit。
```

### Step M3.0 — 工作站 FC（1 文件 · Gate 4 收口）

```
执行 Zero V2 模块化轨 Step M3.0（D69：Agent 获得一台电脑）。先读方案 §15 全节。

文件：TermuxBridge（FC 全 AUTO：linux_exec(cmd,cwd?,timeout_s?,session_id?)——**description
域边界注明 Debian≠Android shell（P1-1）+ 工作区规范 /root/work 勿动 /root/zero（P1-3）**；
linux_ls/read/write/upload/download——大文件 Kotlin 编排器推拉流）。

验证：对话"看看工作站装了什么"→ linux_exec 真实回传；upload→ffmpeg→download 端到端
= L3 待用户。R8 输出 + 进度表 + commit。
```

### Step M3.1 — CompileManager（2 文件）

```
执行 Zero V2 模块化轨 Step M3.1。先读方案 §5.1（D67 六步管线）+ 指引 §二（manifest 注释行）。

文件：services/.../termux/CompileManager.kt（写码→py_compile 质量门→manifest 解析（deps 非空
触发 supervisor pip install，**installTimeoutS 计时，与 probeTimeoutS 分离**）→普通进程探活
（**只计进程启动→endpoint ready**，超时进程组 kill）→stderr 回传）
+ manifest 解析器（# ZERO_MANIFEST: <json> 单行）。

验证：语法错→行号回传；合规代码（含 import requests）pass；带 deps 模块装后 pass
= L3 待用户。R8 输出 + 进度表 + commit。
```

### Step M3.2 — CodeDevModule（2 文件）

```
执行 Zero V2 模块化轨 Step M3.2。先读指引全文（prompt 注入源）+ 落地计划 M3.2。

文件：services/.../module/CodeDevModule.kt（FC 全 AUTO：develop_module(name,description,code,
config_schema?)→CompileManager→挂载+返回工具清单；list/enable/disable_module。
**FC description 内嵌《模块构建指引》精简规范**：manifest 行格式/shim API/命名/长任务模式/
configSchema 声明）+ AppBindingModule（bindCodeDev）。

验证："写一个工具把大段文本去重"→ develop_module → dyn_dedup_* 出现 → LLM 直接用
（零弹窗端到端）= L3 待用户。R8 输出 + 进度表 + commit。
```

### Step M3.3 — 修复回路 + 可观测（1 文件）

```
执行 Zero V2 模块化轨 Step M3.3。先读落地计划 M3.3。

文件：CodeDevModule/CompileManager（失败 stderr 经 JsonExtractor 回 LLM；≤repairMaxRounds
（参数表默认 3）有界修复；AgentTrace 全链 code_dev: generate/compile/test/mount/repair）。

验证：故意缺陷代码→自修复→挂载；RunLog 可查 = L3 待用户。R8 输出 + 进度表 + commit。
```

### Step M4.1–M4.4 — 收官（放权已废 · 状态收口/闭环/审计/文档）

```
执行 Zero V2 模块化轨阶段四收官（D67 后无放权维度）。
M4.1 状态收口（CRASHED auto-DISABLE+告警；**enable→PROBED→ACTIVE 转换** P1-6；配置页收口）
M4.2 四闭环验证：视觉→决策→操作；自编译全 AUTO；**工作站四场景**（exec 探索循环/多文件项目/
素材加工 upload→ffmpeg→download/自建服务 D51 恢复）
M4.3 可控性审计（Termux 用户态边界/CRASHED 计数/trace 覆盖/disable 即时性）
M4.4 文档同步（能力矩阵/STATUS/《模块构建指引》定稿）
真机项全部 L3 待用户；R8 输出 + 进度表 + commit；本轨收官输出全轨验收清单。
```

---

## 模块化轨防漂移锚点（每会话自检 · 摘自方案 §十六 + 决策表）

- **封板契约 16 条（§十六）**：supervisor 双通道 / 进程组 pgid / Profile v1 / runtime endpoint /
  session cwd / 目录契约 / secret 预留 / D67 不恢复沙箱——**任何一条被"优化"掉即漂移**。
- **D67 最大授权**：无沙箱/无 allowlist/不禁网/dyn_* 恒 AUTO——"恢复 ast 校验/CONFIRM 默认"
  即违令（用户裁定，风险接受声明在案）。
- **D69 工作站分层**：supervisor=Termux 原生管理者，模块+exec=Debian 内执行；proot 是环境
  载体**不是沙箱**——"用 proot --isolated 隔离模块"即概念倒退。
- **D68 配置**：复用 ModuleConfigStore（零新表）；env 注入；secret 不入 manifest/DB/env/trace。
- **D63 仅辖 Shizuku**：操作层 FC 权限矩阵与动态模块权限（D67 AUTO）是两个域——勿混。
- **endpoint 语义**：DB lastEndpoint 仅调试——直连 DB 端点做 D51 恢复即 P0-4 违令。
- **依赖方向红线**：services 不依赖 app（X12）；shim 零依赖 stdlib（Node/FastMCP 兼容路径除外）。
- **通用**：迁移同 commit（X9，version=当前+1）；chatHistory 纯净；≤3 文件/Step；
  onAwake 自调 registerFunctions；E28 静默失败留痕；真机项如实标 L3 不伪造。

---

## 相关文档

- 设计：`ZeroV2-模块化重构方案.md`（D59–D69 + §4.4/§15/§十六——权威）
- 实施计划：`ZeroV2-模块化落地实施计划.md`（14 Step + §九参数表 + Gate 对照——进度权威）
- 模块构建指引：`ZeroV2-模块构建指引.md`（LLM/用户模块开发规范——M3.2 prompt 注入源）
- 开发指南：`ZeroV2-LLM开发指南.md`（跨轨 R1–R8 + 地雷 + 红线）
- 结构范式参照：`ZeroV2-音乐模块开发提示词.md`/`ZeroV2-知识库开发提示词.md`（本档模板母本）
