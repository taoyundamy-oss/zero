# Zero V2 收束开发设计（S 轨 · 2026-09-06 用户裁定立项）

> 背景：C0-C4 云服轨 + N0-N6 规范化轨完成后，用户提出 6 项问题 + 11 项收束意见，进入收束开发阶段。
> 本文档 = 设计 + 实施计划合一；**纯设计，未编译未开发**；执行前逐轨经用户确认。
> 参照研究（2026-09-07 补，用户指定 D:\Alife.Backup\Alife.Client.zip）：Alife.Client = Electron 壳 + ASP.NET Core Blazor(AntDesign) 内核；app.asar 仅主进程无业务 UI；DLL 字符串层提取到 UI 概念：「思考过程/展开思考」（思维链折叠）、「消息前缀」（消息身份以**前缀标注**而非独立流——S2 徽标方案与此吻合）、「对话看板」「系统管理中心」。组件级渲染细节需 ILSpy 反编译（超出本轮工具，记录在案）；GitHub 获取受网络策略限制。S2 设计按用户四要素描述 + 上述可提取概念定稿。

---

## 〇、问题与意见 → 轨映射总表

| # | 用户问题/意见 | 落轨 |
|---|---|---|
| 问1 | LLM baseUrl/apiKey 填入分离；视觉/搜索/TTS 无各自设置页管理端点 | S1 |
| 问2 | 部分数值 max 过低（如每日通知上限 10） | S1（数值 sweep） |
| 问3 | 自驱动/云服消息注入黑盒，无观测界面 | S2 |
| 问4 | 提醒功能不稳定、无 UI、状态黑盒 | S3 |
| 问5 | LLM 调工具黑箱 | S2 |
| 问6 | MCP 推荐服务器无用 | S1（清理） |
| 意1 | 各模块独立管理端点；协议统一（OpenAI 兼容）便于换模型 | S1 |
| 意2 | 包体庞大原因排查；本地模块提供线上 API 方案 | S4 |
| 意3 | 模块功能独立、注册函数统一通信、基建稳定 | S5 |
| 意4 | 可调数值 max 设足够大（不破坏稳定前提） | S1 |
| 意5 | 系统消息实时注入对话界面、系统身份区别用户、LLM 回复预期显示 | S2 |
| 意6 | 系统消息注入参考 Alife | S2（参照受限记录） |
| 意7 | 提醒独立稳定存活、关机跳过、LLM 可增删改、设置页 UI | S3 |
| 意8 | 云服视频影音处理（剪辑/格式转换） | S6 |
| 意9 | 云服整体迁移能力（腾讯云等） | S7 |
| 意10 | 工具调用可视化（调用/返回信息暴露） | S2 |
| 意11 | 正式 UI 设计（图标/设置落地/清理无效项） | S8 |

---

## 一、S1：设置体系统构（模块自治 + 协议统一 + 数值 sweep + 清理）

### 1.1 模型服务区块（问1 + 意1）

**现状**：LlmProfileStore（N2.1）已集中 5 个 profile（deepseek/qwen/mimo-tts/qwen-embedding/qwen-vl），但消费模块（TTS/Embedding/Vision/Search）的设置页只暴露 `apiKeyRef`，baseUrl 藏在 profile 里——用户视角"分离"。

**方案（D115 · 用户修订 2026-09-07：模块全自治）**：
- **删除全局「模型管理」页（LlmProfilesScreen）**——url/模型名/Key 的填入**全部下沉到各消费模块自己的设置页**，无跨模块共享 UI
- 每个消费模块（对话 deepseek/qwen、TTS、ASR、视觉、Embedding、搜索）设置页内自管三件套：**baseUrl + apiKeyRef（API Keys 卡片）+ modelId**——协议统一 OpenAI 兼容不变（换厂商 = 改 baseUrl/modelId，零代码）
- 底层：每模块绑定**专属 profile**（LlmProfileStore 保留为存储层，预置各模块默认 profile；无全局 UI 入口；迁移 = 现有 5 profile 按消费方拆分归属）
- 模块内参数覆盖（温度/思考等）维持各模块 ConfigField
- 涉及：各模块 Config + 设置页；导航清理（模型管理入口删除）

### 1.2 数值上限 sweep（问2 + 意4）

原则：**用户主权上限 = 防失控而非防用户**——与 BudgetGuard/Token 预算相关的保持紧（有独立熔断），体验类上限放开：

| 字段 | 现界 | 新界 | 理由 |
|---|---|---|---|
| TriggerHubConfig.dailyNotifyCap | [1,10] 默认4 | **[1,200] 默认20** | 用户明示 10 过低 |
| dailyTriggerCap | [5,200] 默认30 | [5,1000] 默认100 | 意4 |
| AgentRuntime 报点四参 | [30,600]/[5,120]/[1.5,5]/[1,10] | base [30,3600] / offset [0,600] / mult [1,10] / doubling [0,8] | 空闲退避可更长省电；mult 上限放开 |
| MemoryModule 各项 | 已 N4.2 定界 | graphExtractionThreshold [10,2000]、archiveAfterDays [7,3650] | 意4 |
| Automation maxSteps/maxDurationMin | [3,30]/[1,30] | [3,100]/[1,240] | 长任务支持（BudgetGuard 兜底不变） |
| 其余 | — | 逐字段复审：凡"防 UI 溢出"性质的上限全部复核 | 验收=全部 @ConfigField 重新过表 |

安全边界不动摇：BudgetGuard（日 Token/月费）、MAX_FILE_SIZE、SSRF、TriggerCap 的存在性（值可调大，机制不删）。

### 1.3 清理（问6）
- **MCP 推荐服务器目录删除**（McpServersScreen 预设列表）——保留"手动添加"主流程；zero-cloud 添加引导以文档/对话内引导替代
- 设置项落地审计（并入 S8）：逐设置页核对"每个可见项真实生效"，孤儿项清除

---

## 二、S2：系统消息注入与工具可视化（问3/5 + 意5/6/10）

### 2.1 系统消息身份（意5/6）

**D116**：对话流增加 **SYSTEM_EVENT 消息身份**，与 USER/ASSISTANT/TOOL 区分：
- 数据层（C3 修订 2026-09-07 交叉审核）：**不入 chatHistory / 不新增持久化 role**——系统事件只存 ChatViewModel 显示缓冲（环形 50 + 进屏重放）；chatHistory 内既有 `source=AUTONOMOUS` 的 USER 消息经投影层重映射为系统气泡（F4 路径，零 ChatBotModule 改动）。系统气泡为**会话级生命周期**（重启即失，历史查 RunLog——C10 明示不持久化）
- 注入点（实时、事件驱动）：
  - TriggerEvent fire（自驱动：通知/电量/微信/云任务/BATTERY…）→ ChatBus 新事件 `onSystemEventInjected` → ChatViewModel 追加 SYSTEM_EVENT 消息到对话流
  - 自主回合 LLM 回复 → 以 ASSISTANT 消息 + `source=AUTONOMOUS` 徽标入流（用户可见"Zero 自主发了什么"）
  - 云服消息送达（wechat 收发/任务完成摘要）同通道
- **UI 渲染（用户修订 2026-09-07 · 参照 Alife）**：系统消息以**系统身份在右侧渲染对话气泡**（与用户消息同侧、同布局），气泡头部带系统徽标/图标与来源（`⚡ 云任务完成 · cloud_download`、`💬 微信消息 ×2`；**P1-3 终审特别裁定：同类聚合仅限 UI DisplayEvent 层**——业务消息逐条接收逐条 Trigger，`💬 ×N` 为纯显示聚合，**绝不参与 TriggerEvent/AutonomousThinkingJob/chatHistory/RunLog/发送决策**；禁止"等待收集后统一 Trigger"式实现——聚合的是眼睛看到的，不是 Zero 处理的）——右侧=入站方（用户/系统），左侧=Zero 出站（对系统消息的自主回复按 ASSISTANT 正常左侧渲染）；可折叠 payload；**不入 LLM 发送视图**（仅 UI 投影，不污染上下文缓存）。
  **Fallback（用户裁定）**：若 role 渲染架构改造伤及现有消息流功能 → 导航单设"系统留痕"页渲染（主方案先行验证，受阻即切换，不硬改）
- LLM 回复预期显示：自主回合回复卡片显示触发来源徽标（"因：微信消息"）——满足"后续 LLM 回复预期显示"

### 2.2 工具调用可视化（问5 + 意10）

**D117**：FC 循环事件流 → 对话内工具卡片：
- ChatBotModule FC 循环已有 trace 事件（tool_execute_start/done）——新增 `ChatBus.onToolCallEvent(toolName, argsDigest, resultDigest, ok, durationMs, round)` 
- MessageBubble 内嵌**工具卡片**：图标+工具名+参数摘要（≤80 字）+ 结果摘要（成功/失败+大小/耗时）；点击展开全参数/全结果（BottomSheet）
- **对话清洁裁定（用户修订 2026-09-07）：思维链与工具调用合并为单一「执行轨迹」卡片**——默认折叠一行摘要（如 `🔧 2 个工具 · 思考 3.2s · 完成`），点击按时间序展开（思考段/工具段穿插，工具段含调用名+参数摘要+结果摘要+耗时；**G5：事件带 toolCallId+traceId，"查看完整"经 RunLog 按 id 检索全参数/全结果**）；**不做双轨展示，不另行展开 trace**
- RunLog 保持为终极审计层，折叠卡片为一线观测层

---

## 三、S3：提醒模块重构（问4 + 意7）

**D118：提醒重构（原地增强 SystemEventModule——SR11/C1：不新建并行模块防 FC 同名撞 FunctionRegistry）**：

- **存储**：Room 表 `reminders(id, title, note, trigger_at, repeat_rule, status[active/missed/done], created_by[user/llm], created_at)`——重启/换机不丢
- **调度**：ExactAlarmScheduler（既有）+ `setExactAndAllowWhileIdle`；**BOOT_COMPLETED/REBOOT** 广播重排全部 active 项（Manifest 注册，独立于无障碍）
- **到期语义**（意7 明示）：到期 → fire SystemBus → TriggerHub → autonomous_notify + S2 系统消息入对话流；**关机错过 = 不补发**——开机重排判定（G6 机器化）：`repeat_rule=null 且 trigger_at<now → missed`；`repeat_rule≠null 且 trigger_at<now → 按 repeat_rule 算下一期重排（永不 missed）`；"等待后续提醒"= 周期项自然继续
- **LLM 接口**（FC 注册，C9 修订：**保留既有名** `set_reminder`/`list_reminders`/`cancel_reminder` 防工具名漂移，新增 `modify_reminder(id, ...)`；描述补 repeat/status 语义）——增删改均落 Room 即时重排闹钟
- **设置页 UI**：模块详情页提醒列表（时间/标题/状态/来源徽标，用户可增删改）；"missed" 项高亮可一键重排
- 稳定性验证矩阵：重启存活 / 关机跨期跳过 / Doze 准点（allow-while-idle）/ LLM 增删改即时生效

---

## 四、S4：包体瘦身（意2 · 用户修订 2026-09-07：仅 ASR，其余功能稳定不做调整）

**实测构成**（app-debug.apk 132MB）：ASR 本地模型 57MB（sherpa encoder 43 + decoder 14）+ onnxruntime/sherpa 双 ABI ~49MB + debug dex ~67MB。
**范围裁定**：仅动 ASR；abiFilters/R8 等构建项**列为可选项单独请示**，不默认执行。

### MiMo ASR 调研结论（2026-09-07）
- **官方 OpenAI 兼容**（模型 **MiMo-V2.5-ASR**），域名 `api.xiaomimimo.com/v1`——**与现有 mimo-tts profile 同域同 Key**（一个 Key 管 TTS+ASR）；**端点终稿（C2/G4）**：`/v1/chat/completions` + 音频 content part + `stream:true`（SSE 流式出文）——`/audio/transcriptions` 为首轮调研误记，已撤回
- 能力：普通话/英语/多种中文方言/中英混说/歌词；格式 mp3/wav
- 来源：[MiMo ASR 官方文档](https://mimo.mi.com/docs/zh-CN/api/audio/Speech-Recognition) · [使用指南](https://mimo.mi.com/docs/zh-CN/usage-guide/Speech-Recognition) · [ModelScope 模型页](https://modelscope.cn/models/XiaomiMiMo/MiMo-V2.5-ASR) · [OpenAI 兼容说明](https://mimo.mi.com/docs/zh-CN/api/chat/openai-api)

### 方案（D121：ASR 双通道 + 标准接口设置项）
1. **SpeechRec 设置页新增「模型服务」区块**（S1 样板首落地）：
   - ASR 引擎选择：`本地（离线）` / `线上 API`
   - 线上时：profile 选择器（预置 mimo-asr profile：api.xiaomimimo.com/v1 + MiMo-V2.5-ASR）+ **模型名可改**（换任何 OpenAI 兼容端点零代码）
   - **端点统一（C2/G4 终稿）**：MiMo 走 **`/v1/chat/completions` + 音频 content part + `stream:true`**（SSE 流式出文——实测实锤；`/audio/transcriptions` 首轮误记已撤回）——OpenAiCompatibleClient 增 **`chatAudioStream(profile, wavBytes)`**（多模态 content 消息族；**G16：单段 wav Base64 >10MB → 段内二分或本地兜底**）；transcriptions 型厂商走 adapter 兼容
2. **流式裁定（用户纠正 2026-09-07）**：MiMo-V2.5-ASR **支持流式**——`/v1/chat/completions` + `stream:true`（音频为 content part，识别文本 **SSE 流式返回**，OpenAI 兼容）；线上引擎 = VAD 分段上传 + **SSE 流式出文**（识别中即显部分文本，体感接近实时；非 WebSocket 连续麦克风流，逐段粒度）。本地 sherpa 保留离线通道；线上失败自动回退本地（fail-safe）
3. APK 瘦身（可选，单独请示）：ASR 模型改首次启动按需下载 → APK -57MB

---
## 五、S5：模块标准化与基建收敛（意3）

- FC 注册通信已统一（FunctionRegistry 唯一通道）——审计补漏：确认无模块旁路 LLM
- **基建收敛清单**（此前架构审计遗留，本轮兑现）：`tasks/lib/files.py`（register/meta/resolve 归位，file.py 瘦身）+ ssrf 单源化 + MIME/结果组装统一 + 工具 sync/async 声明式标注
- 验收：回归 9 用例 + 全工具抽查零行为变化

---

## 六、S6：云服媒体处理（意8）

**D119：cloud_media 工具**（**P0-1 钉死：ffmpeg 在 worker 镜像直跑，不进 sandbox Dockerfile**；链路 = file_id→worker→worker 内 ffmpeg→/data/zero→登记 file_id）：
- action：`cut`（-ss/-to 片段）/ `concat`（多 file_id 合并）/ `transcode`（mp4/webm/mkv/mov/avi/gif 互转 + 分辨率/码率参数）/ `extract_audio`（视频→mp3/m4a）/ `probe`（ffprobe 元数据）
- 协议完全复用既有：输入 `input_file_ids`（owner 断言/总量 ≤512MB——**仅输入配额，非工作空间上限**）→ /data/zero 处理 → 产物登记 file_id（download_file 拉回）；超时 600s；download 队列隔离
- **工作空间守护（P0-1）**：执行前 `df` 可用空间检查（输入×3 预估中转+产物）+ **活动媒体任务并发=1**（与通道稳定性隔离——不做激进线程优化，实测竞争再调）+ 失败/超时 **finally 清理临时文件**（临时输入/输出/中间产物，防磁盘慢性耗尽）
- 安全：无网络（本地文件处理）；ffmpeg 参数白名单化构造（模板拼接，禁 shell）；配额同文件体系
- 工具数 14→15

## 七、S7：云服迁移能力（意9）

**D120：迁移 runbook + 参数化**：
- 状态清单（已达成）：全部状态 = Redis AOF 卷 + /data/zero 卷 + .env——compose 服务本身无状态
- **export/import 脚本**（scripts/migrate.sh · G8 一致性切片）：`export` = **quiesce（拒新任务+等 active jobs=0 真静止）→ BGSAVE → tar → manifest+checksum → 恢复**（单一时间切片防跨存储不一致）→ 新机 `import`（compose up + redis RDB 载入 + tar 解包 + .env 填充）
- **Android 参数化**：ZeroCloudTrust.HOST/CERT_SHA256 常量 → McpServerStore 可配置（换服务器 = 设置页改 URL+指纹，不改代码）；pin 指纹支持双指纹过渡（旧/新并存）
- 腾讯云预判：同样大陆 ICP 限制 → IP+自签方案平移；compose 无改动；DNS/profile 全在 .env
- 演练验收：新实例（模拟）export→import→14 工具全绿 + Android 切换连通

## 八、S8：应用 UI 正式设计（意11 · 用户修订 2026-09-07：逐页输出布局供决策修订）

**用户裁定基线（2026-09-07，逐屏图纸不得违背）**：
1. **底部导航固定三 tab**：对话 / 自动化 / 设置——不增不减
2. **对话界面**：左上角 = 当前角色名（**不含 "ZeroV2" 字样**，仅角色名）；右上角 = 历史 + 刷新图标（**刷新实证：现为"重新生成"，功能已落地 ✓ 保留**）；其余保持既有设计
3. **自动化界面**：不调整
4. **设置界面**：不大改保持既有结构；**去除「模型管理」入口**（意见4 下沉各模块）
5. **软件名 = Zero，版本机制启用：当前 1.0**（versionName 1.0.0 / **versionCode 2（C6：保持单调）**；设置-关于页显示版本；后续语义化版本 + 版本日志随发版记录）
6. **全量文案审查**：所有 UI 显示文字逐条核对正确性（错字/过时描述/技术黑话/与实际行为不符）——随逐屏图纸同步标注修正

**交付形式（D122）**：**逐屏 ASCII 布局图**——每屏一稿，含布局分区/组件类型/交互标注/**决策点标记**（`⚠️` 需用户拍板的选项），以文档批次输出（每批 3-5 屏），用户逐页批注后修订定稿，然后才进入实现。

**屏幕清单（首批优先对话主链路，后续设置群组）**：
| 批 | 屏 | 内容要点 |
|---|---|---|
| A | 对话主界面 | 消息流（用户/AI/系统事件卡/执行轨迹卡折叠态+展开态）、输入区、语音入口、导航 |
| A | 执行轨迹卡（折叠/展开两态） | S2 合并裁定后的样式 |
| A | 系统事件卡 | S2 SYSTEM_EVENT 渲染 |
| B | 设置·首页（**既有结构不变**，C5 修订：撤 IA 重构，与基线 4"不大改"对齐；仅核入口去留） | S1.3 联动 |
| B | 对话模块详情页（模型区块：多 profile 自管） | S1.1 |
| B | 语音模块详情页（ASR/TTS 引擎切换 + 三件套区块） | S4/S1 样板 |
| C | 触发与自主页（TriggerHub 数值区/白名单/微信状态） | S1.2 sweep 后表单 |
| C | 提醒管理页 | S3 列表 UI（状态徽标/missed 高亮） |
| C | MCP 服务器页（清理后） | 问6 |
| D | 图标 3 稿 + 启动画面、模块管理页、记忆/数据页 | 意11 |

- **图标方案**（概念"零号·归零即起始"）：深空底 adaptive icon，前景 = 几何化 "0" 与对话气泡融合（"0"下缘气泡开口），单色版/通知小图标同步；3 稿候选交用户选
- **设置落地审计**：逐页核对"可见即生效"，与 S1.3 清理联动产出《设置项-生效性对照表》
- 主题/暗色一致性 + 组件规范（与既有 Compose 风格延续）

---

## 八B、S9：工作目录文件系统全操作（用户意见 2026-09-07 第 8 条 · D123）

**现状**（FilesModule 7 FC）：save_to_file/read_work_file/list_work_files/read_office_file/create_document/edit_document/delete_work_file——读类 AUTO、写删类 CONFIRM；**缺**：移动/重命名、新建文件夹、删除文件夹。

**方案（D123：完全暴露 + SAF 天然护栏）**：
- **新增 FC ×3**：
  - `move_file(file_name, new_name_or_path)`——文件移动/重命名（工作目录内任意路径，SAF DocumentsContract.rename/move；跨子目录 = copy+delete 兜底）
  - `create_folder(folder_name)`——新建文件夹（含嵌套路径）
  - `delete_folder(folder_name, force?)`——非空需 force；SAF 递归删除；**G15 审计**：解析路径→SAF 边界校验→RunLog 记已删清单→返回删除数/总大小（保留 AUTO 增可审计）
- **权限放开**：既有 delete_work_file 及新增三项**默认 AUTO**（用户明示主权让渡——"正常所有文件夹操作权限"）；D57 硬护栏不变：**SAF 工作目录边界**（越界物理不可能）+ 危险扩展名拦截（apk/so/dex/jar 下载侧既有规则不回退）+ LLM 无路径穿越（fileName 净化既有）
- `list_work_files` 增强：返回**目录树**（含文件夹，标注类型 dir/file）——LLM 空间感知前提
- 验证：建目录→移动文件→删目录全链；越界/非法名拒绝；**force 删除返回已删清单+数量/大小（G15 审计实证）**；对话"把报告移到归档文件夹"直达

---

## 八C、S10：QQ 机器人（OneBot v11 · 2026-09-07 立项 · 摸底完成）

### 摸底结论（2026-09-07 调研，实现端三选一、协议同源零差异）

| 实现端 | 形态 | 体积/内存 | 文件能力 | 备注 |
|---|---|---|---|---|
| **Lagrange.OneBot（✅ 用户裁定 2026-09-07 固定选型）** | 纯 C# NTQQ 逆向 | 镜像 ~77-96MB / 内存几十 MB | ⚠️ 发文件 <50KB 可靠、>1MB 常败（Issue #503）；收文件 ✓ | [官方文档](https://lagrangedev.github.io/Lagrange.Doc/v1/Lagrange.OneBot/)；GHCR 镜像（需国内加速站拉取，E14 同类预警） |
| **LuckyLilliaBot/LLBot**（用户推荐） | NTQQ 客户端转平台 | 中 | 同类 NTQQ 限制 | 支持 OneBot 11/Satori/Milky；WebUI 管理；[GitHub](https://github.com/LLOneBot/LuckyLilliaBot) |
| **NapCat** | QQNT 无头 Node | 重（数百 MB） | ⚠️ Docker 下 upload 偶败 | WebUI :6099；镜像 DaoCloud 白名单待实测 |

共性事实：① 完整 OneBot v11 协议（[标准](https://github.com/botuniverse/onebot-11)）② 均占 PC 端登录位（不可与 PC QQ 同号同登）③ 扫码登录 ④ 腾讯封号风险自担 ⑤ **文件外发是共性弱项（NTQQ 协议限制）**。

### 可实现功能清单（经 OneBot v11）
- ✅ **一期（可靠，随立项实施）**：私聊/群聊文本双向（message 事件 + send_private_msg/send_group_msg）；图片/表情消息段收发；**收文件**（P1-4 双路径：事件 URL 快路径 / 无 URL 经 Lagrange 文件 API → file_id 体系）；好友/群列表；戳一戳事件；SSE 事件接入（type=qq → kick → 既有检查器链路零新管道）
- ⚠️ **二期（视稳定性，实测后决定）**：**发文件**（≤50KB 直发 / 大文件降级为 file_id URL 链接发文本——LLM 引导说明）；语音 record 段（can_send_record 探测后启用）
- ❌ 不做：与 PC QQ 同号并行（协议位冲突）

### 范围分期与落轨
- **S10.1（云服）**：Lagrange 容器（GHCR 加速站拉取，失败换 LLBot 实测）+ gateway WS 客户端连 :3001 → `qq_read/qq_send/qq_status` 三件套 + 消息事件 publish(type=qq)——**C7：+`websockets` 库**；**G3+P0-2 游标契约**：事件入 Redis List `{ingest_seq(Zero 自增=游标), message_id(=去重键), timestamp, payload}`——职责分离；持久 cursor；WS 仅 wake-up（**P0-3：缓存满溢出显式暴露 overflow 标记，不静默丢——见计划 S10.1**）；**G9 一期全量**：群聊/图片表情/好友群列表/戳一戳（详见落地计划 S10.1）
- **S10.2（手机）**：TriggerEvent source 泛化（QQ_PRIVATE 同微信 prompt 路 + D107 不可信定界 + D124 送达通道清单加 qq）
- 工具数 15→18（含 cloud_media 后）

## 八E、TTS 语音生成 FC（D125 · 用户需求 2026-09-07：LLM 生成语音用于微信/QQ 聊天）

**现状事实**：`SpeechSynthesisModule.registerFunctions()` 为空——**TTS 零 FC 暴露**，LLM 当前无法生成语音（仅 Live 语音管线内部消费 speak/speakStreaming）。

**D125：新增 `text_to_speech` FC**：
- 入参：`text`（文本）、`save_as`（文件名，缺省自动命名；**G17：basename 强制净化**防穿越，仅落 downloads/）——经 mimo TTS 管线合成 → **WAV 落工作目录 `downloads/` 子目录**（C4 修订：upload_file 的 openReadStream 只检索 root/downloads 两层，tts/ 新子目录读不到——复用 downloads/ 零改动）（`synthesizeToFile` 新通道：复用 speakMimo 音频流，截获写文件不外放；本地 Melo 引擎同理）
- 产物同时自动 `upload_file` 上云得 file_id（可选参数 `cloud=false` 关闭）——直接为聊天通道备好
- **通道能力矩阵（诚实边界）**：
  | 通道 | 语音消息 | 链路 |
  |---|---|---|
  | **QQ（S10 后）** | ✅ | `text_to_speech` → file_id → `qq_send` record 段（OneBot 语音消息，Lagrange 下载转码） |
  | **微信（WeClawBot v1.0）** | ❌ **协议仅文本**（/messages?text=） | 语音需 D113 官方插件升级（iLink type 3 Silk 语音段）后解锁——记录不硬做 |
  | 本地播放 | ✅ 既有 speak 不变；FC 可选 `play=true` 立即外放 |
- 落轨：S4 轨扩为 S4.2 步（与 ASR 同域语音收口）；验证=QQ 对话"用语音回复我"→合成→file_id→QQ 收到语音消息

## 八F、S11：微信官方插件升级（D113 兑现 · D126 · 用户裁定 2026-09-07：S10 落地后执行）

**目标**：替换 WeClawBot v1.0（仅文本）→ 官方 `@tencent-weixin/openclaw-weixin` 全媒体（文本/图片/语音 Silk/文件/视频双向），兑现文件传输。

**调研结论（2026-09-07）**：
- 官方插件**必须宿主 OpenClaw Gateway**（不能独立运行）；安装 = 宿主内 `npx -y @tencent-weixin/openclaw-weixin-cli install`（安装语法示例；**生产固定版本，见选型 G11**）（[官方文档](https://docs.openclaw.ai/zh-CN/channels/wechat)）
- **接入缝**：OpenClaw [外部应用集成协议](https://docs.openclaw.ai/zh-CN/gateway/external-apps)（WebSocket + RPC）——我们的 gateway 作为外部应用连其 WS 收事件/发消息，**无需 fork 任何代码**
- WeClawBot-API 本质 = 同栈黑盒 HTTP 化包装（v1.0 只放出文本端点）

**选型（D126）**：**方案 B——自建 openclaw 容器 + 官方插件 + gateway 经 external-apps WS 直连**（备选 C：fork WeClawBot-API 加媒体端点，仅当 B 的 WS 集成协议实测受限时启用）。**G11：插件与 Gateway 固定已验证版本**（npm 精确版本号，禁 @latest——上文 @latest 仅为安装语法示例）；**G1+P0-4：openclaw 与 worker 挂载同一存储实例**（现=宿主 bind /data/zero；契约=同一文件系统对象，非强制挂载类型——四验：双见/新文件互见/双重启文件仍在）。

**架构**：
- 云服 `openclaw` 容器（Node + OpenClaw Gateway + 官方插件；npm 走 npmmirror 国内镜像；状态目录 volume 持久化扫码会话）——**替换 weclawbot 容器**
- gateway 新 `OfficialWechatProvider implements WechatProvider`（D97 缝兑现）：
  - 收：WS 客户端连 Gateway → 消息事件（媒体由插件 CDN 解密落盘）→ publish `zero:events(type=wechat)`——**wechat-tail sidecar 退役**（stdout 黑盒通道被官方事件取代，D114 事件源①升级）
  - 发：sendMessage 全类型——文件/媒体传**宿主本地路径**（file_id → worker 落盘同机路径直传，零拷贝）
- **媒体链路**：收文件=插件解密落盘→登记 file_id（对话内可直接读/下载）✓；发文件=file_id→宿主路径→file 消息 ✓；语音=`text_to_speech`（D125）WAV→**silk 转码**（pilk）→voice 消息 ✓；图片直发 ✓
- 登录：OpenClaw 插件自身凭据（**新扫码一次**，WeClawBot auth.json 不通用——同 QQ 模式）
- 手机侧：wechat_read/wechat_send schema 平移（send 增 media 参数：`file_id` 可选——文本/媒体同工具）

**资源账（2C4G）**：当前 available ~1.8Gi；+openclaw(估 250MB) +Lagrange(~100MB, S10) −weclawbot 退役(~40MB) −wechat-tail 退役(~30MB) → 净剩 ~1.5Gi ✓（上线时 free -h 实测）

**风险**：灰度协议变动由腾讯插件层吸收（优于黑盒）；OpenClaw Gateway 子进程管理 bug（issue #68451 类）——容器 restart 兜底；扫码会话丢失=重扫（低频）

**落轨（S10 后执行 · G 表五步 · 终审状态机纪律：任一步 FAIL 不进下一步，S11.4 FAIL 保留旧通道，S11.5 仅稳定后执行）**：S11.1 容器（固定版本 G11+共享卷契约 G1+扫码）→ S11.2 **协议探针**（G2；受阻切备选 C）→ S11.3 OfficialProvider 双通道灰度（G10：weclawbot 保留）→ S11.4 媒体全链（file/image/voice+silk+D125）→ S11.5 观察期≥48h 后正式退役（G10：legacy 一键拉起）。验证：微信发文件→对话内 file_id 可读；"把这份报告发我微信"→送达；语音回复链。

## 八D、送达通道统一引导（Q5 分析落档 · D124）

**现状**：管道全通（trigger→自主回合→prompt→wechat_send），但 prompt 层引导不全——WECHAT_PRIVATE/CLOUD_TASK 已引导，**REMINDER（现直发本地通知不经自主回合）、BACKLOG/NOTIFICATION 未引导微信**；LLM 不知微信登录态（盲选失败才知）；用户偏好无固化。

**D124：自主 prompt 通用「送达通道」尾段**：
- 动态状态：fire payload 附通道探测摘要（wechat_status logged_in / 未来 qq_status）——LLM 开局即知可用通道；**C11：TriggerHub 缓存探测结果 5 分钟**（防每 fire 一次 MCP 往返）
- 偏好固化：用户一句话（"提醒类送微信"）经 memory_remember 长期生效——prompt 尾段提示"遵循用户既有送达偏好（查 memory_recall）"
- 降级链：首选通道失败（wechat_send 错误）→ autonomous_notify 通知栏兜底；prompt 明示
- 落点：S3.3（reminder 事件化后入 TriggerEvent）+ S2.2（各 source prompt 统一尾段）+ TriggerHub fire payload 增通道状态

**判定：链路骨架通畅，补 prompt 统一引导即全通**（小改，随波 1 落地）。

## 九、实施顺序（收束 = 稳定优先，逐轨经用户确认后开工）

> **终审封板（2026-09-07 GPT 终审 F 表 · P0×4/P1×7/P2×3）已全量落地**：ffmpeg worker 钉死/工作空间守护（D119）、QQ ingest_seq 游标+溢出显式（§八C）、S11 同一存储实例（§八F）、能力探针/triggerId/UI-only 聚合（D116）/quiesce 真静止（D120）/ASR segmentId/profile 迁移保自建——逐条见各节 **P0-x/P1-x** 标注；完整裁定表在落地实施计划 §〇 F 表。**文档封板，进入开发。**

| 波次 | 轨 | 依赖 | 预估 Step 数 |
|---|---|---|---|
| 1（稳定与可见性） | **S3 提醒重构**、**S2 系统消息+工具可视化**（含 D124） | 无 | 3 + 3 |
| 2（设置体系） | S1 模块自治+sweep+清理、**S9 工作目录全操作** | 可并行 | 3 + 1 |
| 3（体验） | S8 图纸→实现、S4.1 ASR 双通道 + **S4.2 text_to_speech（D125）** | S8 图纸定调 S2 卡片 | 2+2批 + 2 |
| 4（云服） | S6 媒体、S7 迁移、S5 基建收敛 | 无相互依赖 | 1 + 2 + 1 |
| 5（通道域） | **S10 QQ** → **S11 微信官方插件升级** | 顺序执行 | 2 + 3 |

> 全轨执行仍守既有纪律：Android ≤3 文件/Step、云服 ≤1 逻辑单元/Step、每步构建验证、E 教训持续入档、方向性决策先问后做。
