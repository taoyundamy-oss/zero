# Zero V2 收束开发落地实施计划（S 轨 · 交叉验证版 v1）

> 目标：在 D:\ZeroV2 实现 `ZeroV2-收束开发设计.md`（S1-S11 · D115-D126 · C1-C12 审核版）。
> **文档权威层级**：收束开发设计（决策）> 本文档（实施细节）。
> 纪律沿用：Android ≤3 文件/Step 构建验证；云服 ≤1 逻辑单元/Step；Room 迁移同 commit（X9）；方向性变更先问后做。
> 记录日期：2026-09-07 · 零编译纯文档

---

## 〇、交叉验证（落地细节排查 · SR 表）

### 实现级事实（本次勘察钉死）

| # | 事实 | 影响 |
|---|---|---|
| F1 | **UI 投影层已存在**：`ChatViewModel.projectToUiMessages()`（:372，vision 剥离同源先例）——显示流与 LLM 发送视图已分层 | S2 无需架构改造，扩投影即可（SR1 降级） |
| F2 | **提醒已在 Room**：`ReminderEntity` + `systemStateDao.loadReminders/saveReminder`（SystemEventModule:133/290）+ `exactAlarmScheduler.scheduleReminder` + recurringMs 周期 | S3 = 增强重构而非从零建表；但**扩列仍触发 DB 迁移**（SR2） |
| F3 | app_name=`ZeroV2`、versionName=`2.0.0`、versionCode=1 | S8 改名 Zero + 1.0.0（SR10 版本号注意） |
| F4 | 自主回合 prompt 经 `chatAndCollect→sendMessage(source=AUTONOMOUS)` 入 chatHistory（USER role） | S2 以 **source 重映射**渲染系统气泡（零 ChatBotModule 改动），需防双显（SR1） |

### 风险表

| # | 级别 | 发现 | 落地消解（已写入 Steps） |
|---|------|------|------|
| SR1 | 🟠 | 系统消息/执行轨迹若写入 `chatHistory` 将污染 N6.6 发送视图、世代压缩、持久化——**必须只进投影层**；`source=AUTONOMOUS` 的 USER 消息现按用户气泡渲染，重映射后需防双显 | S2.1：`projectToUiMessages` 扩展——source 重映射（AUTONOMOUS→系统气泡）+ VM 侧 `systemEvents` 独立缓冲合流；chatHistory 零改动 |
| SR2 | 🔴 | ReminderEntity 扩列（status/created_by/repeat_rule/title）→ **DB v14 migration**——`fallbackToDestructiveMigration` 开启（X9 地雷），漏写/失败=全量对话抹除 | S3.1 迁移步：Entity/Dao/DB version/MIGRATION_13_14 **同 commit**；老库升级 = L3 必测 |
| SR3 | 🔴 | **Android 12+ `SCHEDULE_EXACT_ALARM` 特殊权限**未授权时 `setExact` 静默不响——现有提醒"不稳定"的头号嫌疑 | S3.2：启动检测 `canScheduleExactAlarms` → 引导授权（`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`）+ 未授权降级 `setAndAllowWhileIdle` 窗口闹钟 + trace 埋点 |
| SR4 | 🟠 | 线上 ASR 与本地流式 partial 的**粒度差异**（逐段 vs 连续麦克风流）——G 表修订：线上同样 SSE 流式出文（C2 已统一端点），非批处理 | S4.1：行为说明文案="线上模式：按语句分段流式识别（非逐字实时）"；**G16：单段 wav Base64 >10MB（MiMo 官方限）→ 段内二分或本地兜底** |
| SR5 | 🟠 | 系统事件实时注入依赖 ChatViewModel 存活；后台/息屏触发时 UI 不在——事件丢失窗口 | S2.1：SystemBus 侧环形缓冲（50，通知模块先例）+ 进屏重放；进程死亡窗口 RunLog 兜底（可接受） |
| SR6 | 🟠 | ModelSelector ← OpenAiChatModule 全量 profile 注册——模块自治后**对话模块承担多 profile 管理**（其他模块单 profile） | S1.1：对话模块详情页承载多模型管理（增删改 baseUrl/key/modelId）；ModelSelector 链路不动；其余模块 Config 增 baseUrl/modelId 字段 |
| SR7 | 🟡 | SAF 文件夹语义：`renameDocument` 仅同目录；`deleteDocument` 要求空目录；跨目录移动=复制+删除；list 树为逐级递归 | S9.1：move=同目录 rename 快路径 + 跨目录 copy/delete 兜底；delete_folder 自走树递归删（force 参数控制非空）；list 树深度上限 5 层防性能坑 |
| SR8 | 🟡 | ZeroCloudTrust 常量三处消费（McpTransport / WebFetch pinnedHttp / TriggerHub sseClient）——配置化需统一 + 双指纹过渡 | S7.2：TrustConfig 单例（SecureKeyStore 读 + 内存缓存 + 默认值=现常量）；pin 校验支持指纹列表（旧/新并存）；三消费点改引 |
| SR9 | 🟡 | Vision 模块为 DashScope 原生多模态（N2.5 偏差）——不强行 OpenAI 化 | S1.2：Vision 页三件套经其 adapter 管理端点；"协议统一"声明范围=chat/tts/asr/embedding 家族（vision adapter 例外，文档明示） |
| SR10 | 🟡 | versionName 2.0.0→**1.0.0** 为降版（用户裁定版本归零）；versionCode 保持单调（1→2）防升级安装被拒 | S8.1：applicationId 不动；versionCode=2 / versionName=1.0.0；app_name→"Zero" |
| SR11 | 🟡 | reminder FC 新旧并存窗口：SystemEventModule 现有 `set_reminder/list_reminders/cancel_reminder`，S3 新增 modify——若新建模块并行注册同名 FC 撞 FunctionRegistry | S3.3：**原地增强 SystemEventModule**（不新建并行模块），FC 逐个改造无并存窗口 |
| SR12 | 🟢 | 世代压缩/发送视图与系统注入的隔离 | SR1 方案（纯投影层）天然零影响——chatHistory 不变，N6.6 逻辑无感知 |

**阻塞项：无**（🔴 SR2/SR3 均已在步骤内消解——同 commit 迁移 + 权限检测降级）。

### 2026-09-07 交叉审核修订（C1-C12 · 设计文档已同步）

| # | 级别 | 冲突/缺漏 | 修订 |
|---|------|------|------|
| C1 | 🔴 | 设计"新建 RemindersModule" vs SR11"原地增强"（并行模块 FC 撞名） | 统一**原地增强 SystemEventModule** |
| C2 | 🔴 | S4 端点自相矛盾（transcriptions vs chat/completions——首轮检索误记） | 统一 **/v1/chat/completions + 音频 content + stream**；transcriptions 降为 adapter 兼容位 |
| C3 | 🔴 | D116"增 role 入 chatHistory" vs SR1"纯投影"（前者污染 N6.6/持久化） | 统一**零 chatHistory 改动**（VM 缓冲 + source 投影重映射）；系统气泡=会话级（C10 明示） |
| C4 | 🟠 | D125 落 tts/ 子目录——openReadStream 只查 root/downloads，**读不到** | 改落 **downloads/** |
| C5 | 🟠 | S8 B批 IA 重构 vs 基线"不大改" | 撤 IA 重构，改模块详情页区块 |
| C6 | 🟠 | versionCode 1 vs 2 | 定 **2**（单调） |
| C7 | 🟠 | gateway 连 WS——**httpx 不支持 WS** | requirements + `websockets`（S10.1 入） |
| C8 | 🟡 | 波次表缺 S9/S10/S11/S4.2 | 补 wave5 + S4.2（本表为权威顺序） |
| C9 | 🟡 | S3 新 FC 名 vs 既有名 | 保留 set_reminder 等 + 新增 modify_reminder |
| C10 | 🟡 | 系统气泡生命周期未声明 | 会话级，历史=RunLog（已明示） |
| C11 | 🟡 | D124 每 fire 探测=多一次 MCP 往返 | TriggerHub 缓存 5 分钟 |
| C12 | 🟡 | BOOT 重排缺 RECEIVE_BOOT_COMPLETED | S3.2 清单补权限声明 |

### 2026-09-07 GPT 外审最小收口（G 表 17 项全落地 · 架构不变 · 阻塞清零）

| # | 级别 | 发现 | 落点 |
|---|------|------|------|
| G1 | 🔴P0 | S11 缺 worker/openclaw 共享卷契约（零拷贝链断） | S11.1 宿主 bind /data/zero 双挂+双向可见验证 |
| G2 | 🔴P0 | OpenClaw WS 协议细节未落（会猜协议） | S11.2 独立探针步（握手/auth/订阅/单条收发；受阻切备选 C） |
| G3 | 🔴P0 | QQ 缺游标/去重/重连契约（Lagrange #685 断线丢消息） | S10.1 Redis List+message_id 去重+持久 cursor；WS 仅 wake-up |
| G4 | 🔴P0 | SR4 残留批处理旧结论 | 已重写为 SSE 流式+10MB 段限（G16 并入） |
| G5 | 🟠P1 | digest 不可还原全量（点击展开无路径） | S2.3 toolCallId+traceId→RunLog 检索 BottomSheet |
| G6 | 🟠P1 | missed"非常规"无机器定义 | 一次性→missed/周期→repeat_rule 下期（永不 missed） |
| G7 | 🟠P1 | 权限恢复无重调度 | PERMISSION_STATE_CHANGED→active 升回 exact |
| G8 | 🟠P1 | 迁移跨存储不一致 | S7.1 quiesce→BGSAVE→tar→manifest+checksum |
| G9 | 🟠P1 | S10 一期只落私聊文本 | 补群聊/图片/表情/列表/戳一戳+验收 |
| G10 | 🟠P1 | 灰度回退与立即退役矛盾 | S11 拆五步，退役独立 S11.5（观察期+legacy 拉起） |
| G11 | 🟠P1 | @latest 版本漂移 | S11.1 npm 精确版本锁定 |
| G12 | 🟠P1 | S1.2 漏 Search | 补入+独立切换验证 |
| G13 | 🟠P1 | S8 无定稿实现/验收步 | 增 S8.3/S8.4 |
| G14 | 🟠P1 | D124 未落 Step | S2.2 文件+验收全链 |
| G15 | 🟡P2 | force 删除无审计 | 解析→校验→RunLog 清单→返回数/大小 |
| G16 | 🟡P2 | MiMo 10MB 段限 | SR4 段内二分或本地兜底 |
| G17 | 🟡P2 | save_as 穿越面 | basename 强制净化 |

**G 表后：P0 阻塞全清（G1-G3 契约入 Step），可按 wave1→5 开工。**

### 2026-09-07 GPT 终审封板（F 表 · P0×4/P1×7/P2×3/特别裁定 · 本轮全部落地）

| # | 级别 | 终审裁定 | 落点 |
|---|------|------|------|
| F1 | 🔴P0 | S6 ffmpeg 载体钉死 worker（不进 sandbox） | S6.1 设计+计划 |
| F2 | 🔴P0 | S6 工作空间：输入×3 预估/并发=1/失败清理临时文件 | S6.1 |
| F3 | 🔴P0 | QQ `ingest_seq`（Zero 自增）=游标，message_id 只做去重 | S10.1 |
| F4 | 🔴P0 | Redis 500=常态容量非可靠性上限——按 cursor 回收，溢出显式 overflow 标记不静默删 | S10.1 |
| F5 | 🔴P0 | S11 共享=同一存储实例（非强制 host bind）——四验含双重启 | S11.1 |
| F6 | 🟠P1 | S11.2 补能力探针（channel/peer/send 错误格式/媒体 RPC 可用性） | S11.2 |
| F7 | 🟠P1 | S2 `triggerId` 事件↔回合唯一关联（禁时间/文本/顺序关联） | S2.2 |
| F8 | 🟠P1 | **同类聚合仅限 UI DisplayEvent**——业务链逐条 Trigger，禁止收集合并式实现 | 设计 D116 |
| F9 | 🟠P1 | QQ 收文件双路径（URL 快路径/无 URL 走 Lagrange API） | S10.1 |
| F10 | 🟠P1 | quiesce 真静止=拒新任务+active jobs=0 后才 BGSAVE | S7.1 |
| F11 | 🟠P1 | ASR segmentId 状态机（仅 COMMITTED 进最终文本防双显） | S4.1 |
| F12 | 🟠P1 | profile 迁移三段式（绑定随模块/引用推断/legacy 保留认领——禁静默删） | S1.2 |
| F13 | 🟡P2 | TTS 产物 TTL 清理（用户明确保存的不清）——后置 S4.2 观察期后 | backlog |
| F14 | 🟡P2 | 每日备份（Redis+/data/zero 快照保留 N 份）——复用 S7 机制，后置 | backlog |
| F15 | 🟡P2 | Reminder 逻辑单测（repeat/missed/状态机）+实机矩阵（Alarm/BOOT/Doze） | S3.3 验证分层 |
| F16 | 特别 | **消息接收保持逐条实时语义；聚合的只是 UI 显示，不是 Zero 处理的东西** | D116 终审裁定 |
| F17 | 🟠（L3 实测触发 09-07） | **双重实现 bug 致"8 条已过提醒挤同刻顺延"**：① set/modify 无过去时间守卫——LLM 重排"今日已过时刻"（07:00/11:00…）立即入队触发；② v6.7 周期推进 `tickMs+ms` 相位丢失——21:15 补触发后全部顺延成 21:15+1d，原时段全丢且 recurring 永不 missed（现象完美对应） | set/modify 增过去时间拒绝守卫（引导未来时刻）+ 周期推进改**原相位保持**（`+=ms` + while 越过 now——与 S3.2 恢复期同语义）；日志佐证 `state_restored active=12 missed=0`（恢复时均未来，重排发生在后） |
| F18 | 🟠（L3 实测触发 09-07 · **用户裁定**） | **微信集中推送破坏体验与页面纯度**：① `wechatSinceTs` 内存态——App 重启归零，整段历史重读（实测 `since=0.0 → n=32` 全为过时消息集中倒回）；② 批量 payload 拼接 ≤10 条旧消息一次回复——破坏对话节奏（没在听此刻）、上下文（新旧混杂指代乱）、页面纯度（积压处理日志感）。用户裁定：**"微信域废除集中推送——实时回复为唯一目标，SSE 可靠性为保障，页面纯度纳入裁定"**；(b) 上下文附带、(c) 新鲜度门槛随集中推送废除一并撤销 | 游标持久化（SharedPreferences `zero_wechat_cursor`，跨重启增量）+ **仅取最新一条**（ts 最大者，payload 单条；trace `latest_only=true` 保留 n 可观测）；SSE 链路（心跳/退避重连/kick）已有不动 |

**F 表后：终审 P0 全封、P1 全落 Step、P2 入 backlog——文档封板，进入 wave1 开发。**

---

## 一、S3：提醒重构（波 1 · 3 Step）

### Step S3.1：存储扩展 + MIGRATION_13_14（⚠️ 地雷步）
**文件**（4 文件属迁移例外，同 commit）：`core/data/entity/ReminderEntity.kt`（+title/note/status[active|missed|done]/created_by[user|llm]/repeat_rule；旧字段 remark/triggerTimeMs/recurringMs 兼容映射）+ `SystemStateDao`（+updateStatus/updateAll 列）+ `ZeroDatabase`（version 13→14）+ `DatabaseModule`（MIGRATION_13_14 逐列 ALTER）
**验证**：Build + 老库升级实测（12 提醒全保留）+ 全新安装零回归
### Step S3.2：调度稳定化（SR3 权限 + 开机重排 + missed 语义）
**文件**：`SystemEventModule.kt`（启动 `canScheduleExactAlarms` 检测+trace `alarm_permission_state`；未授权→引导意图广播 + setAndAllowWhileIdle 降级；到期处理 missed 判定 **G6 机器化定义**：`repeat_rule=null（一次性）且 trigger_at<now → status=missed`；`repeat_rule≠null（周期）且 trigger_at<now → 不标 missed，按 repeat_rule 计算下一期重排`——删除"非常规"歧义词）+ 新建 `BootRescheduleReceiver`（BOOT_COMPLETED/REBOOT→重排全部 active）+ `AndroidManifest.xml`（**C12：receiver 注册 + `RECEIVE_BOOT_COMPLETED` 权限**——漏权限=开机重排静默失效）
**G7 权限恢复闭环**：动态注册 `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`→授权恢复时全部 active 项从 inexact 重排为 exact
**验证**：重启存活 / 一次性跨期 missed、周期跨期下期重排 / Doze 准点 / 权限关闭降级 trace / **权限恢复自动升回 exact（trace 实证）**
### Step S3.3：FC 全集 + 提醒管理 UI
**文件**：`SystemEventModule.kt`（+`reminder_modify`；现有 set/cancel/list 描述更新含状态；created_by 标记 llm）+ `TriggerSettingsScreen` 或模块详情页新提醒列表区（时间/标题/状态徽标/来源/手动增删改；missed 高亮一键重排）
**验证**：LLM 对话"5 分钟后提醒我 X，每小时重复"→Room 落库+闹钟即时排；UI 列表实时刷新

## 二、S2：系统消息 + 执行轨迹（波 1 · 3 Step，依赖 S3.1 无关可并行）

### Step S2.1：注入通道 + 投影扩展（SR1/SR5）
**文件**：`core/bus/ChatBus(.Impl)`（+`onSystemEventInjected: SharedFlow<SystemEventDisplay>`）+ `TriggerHub.kt`（fire 处旁路 emit 摘要事件；环形缓冲 50）+ `ChatViewModel.kt`（`projectToUiMessages` 扩展：source=AUTONOMOUS 的 USER 消息→系统气泡重映射（防双显）；VM 合流 systemEvents 缓冲 + 进屏重放）
**验证**：微信发消息→对话界面实时出现右侧系统气泡（`💬 微信消息`）+ Zero 回复左侧正常；重启进屏重放；**chatHistory 与 LLM 发送视图零变化**（N6.6 世代压缩 trace 不回归）
### Step S2.2：系统事件全源接入 + 自主回复徽标
**文件**：`TriggerHub.kt`（NOTIFICATION/BATTERY/CLOUD_TASK/WECHAT 等 fire 统一 emit 摘要）+ `AutonomousThinkingJob.kt`（回复入流时带 trigger 来源；**P1-2：事件↔回合以 `triggerId` 唯一关联**（TriggerEvent.id 既有字段贯通至 SystemEventDisplay 与回复徽标——禁止时间相近/文本相同/数组顺序关联））
**文件补（G14 · D124 落地）**：TriggerHub fire payload 附通道状态（wechat/qq logged_in，缓存 5min）+ AutonomousThinkingJob prompt 统一尾段（通道状态+memory 偏好提示+失败降级链 wechat_send 失败→autonomous_notify）
**验证**：云任务完成→气泡→回复带来源徽标；**D124 全链：提醒按偏好送达微信 / wechat 未登录 LLM 开局即知不盲试 / 发送失败自动降级通知栏**
### Step S2.3：执行轨迹卡（D117 合并裁定）
**文件**：`core/bus/ChatBus`（+`onToolCallEvent`）+ `ChatBotModule.kt`（FC 循环 tool_execute_start/done 处 emit：工具名/参数摘要≤80 字/结果摘要/耗时/轮次）+ `MessageBubble`/消息渲染（**单一折叠轨迹卡**：默认一行 `🔧 N 工具 · 思考 Xs`；展开时间序穿插思考段/工具段；**G5：事件携带 `toolCallId+traceId`——点击"查看完整"经 RunLog 按 id 检索全参数/全结果（BottomSheet），摘要不可还原全量故以 id 关联审计层**；thinking 既有渲染归并入卡）
**验证**：多工具对话→折叠一行→展开完整叙事；纯文本对话零变化

## 三、S1：设置体系统构（波 2 · 3 Step）

### Step S1.1：对话模块模型自治（SR6）
**文件**：对话模块详情页（承载原 LlmProfilesScreen 的多 profile 管理：baseUrl/Key(API卡)/modelId/参数——迁入模块页区块）+ `ZeroNavHost/SettingsScreen`（删"模型管理"入口 + LlmProfilesScreen 退役）+ `OpenAiChatModule`（profile 注册链路不变）
**验证**：模块页增删改 profile→ModelSelector 即时可选；全局入口消失
### Step S1.2：TTS/ASR/Embedding/Vision 各页三件套（SR9）
**文件**：各模块 Config（+baseUrl/modelId 字段）——**G12：含 Search 模块（设计六模块勿漏）**+ 各设置页"模型服务"区块（三件套 + API Keys 卡片；Vision adapter 注明协议）+ `LlmProfileStore`（预置 5 按模块拆分；**P1-7：用户自建 profile 迁移三段式——有绑定随模块 / 无绑定按 consumer 引用推断 / 仍无法判断标记 legacy 保留待用户认领——禁止静默删除**；无全局 UI）
**验证**：各模块独立换 baseUrl/modelId 即时生效；**搜索端点独立切换不影响对话/TTS/ASR/Vision（G12）**；现有 5 预置 profile 迁移归属正确
### Step S1.3：数值 sweep + 清理
**文件**：`TriggerHubConfig/AgentRuntimeConfig/MemoryModuleConfig/AutomationModuleConfig`（按 S1.2 表放宽 + fromJson 钳位同步）+ `McpServersScreen`（删推荐目录）
**验证**：dailyNotifyCap 可调至 200；报点 base 至 3600；MCP 页无推荐区；Build 零回归

## 四、S4：ASR 双通道（波 2 · 1 Step，S1.2 后）
**文件**：`OpenAiCompatibleClient`（+`chatAudioStream(profile, wavBytes)`——C2：**/v1/chat/completions 音频 content part + stream（MiMo 实测端点，非 multipart）**）+ `SpeechRecognitionModule`（引擎切换 本地/线上；线上走模块 profile（预置 mimo-asr）；VAD 断句整段上传（**P1-6：segmentId 状态机 PROCESSING→ONLINE_PARTIAL→ONLINE_FINAL/LOCAL_FALLBACK→COMMITTED——仅 COMMITTED 进最终文本，防线上 partial+本地 fallback 双显**）；失败回退本地 + trace）+ SpeechRec 设置页（引擎选择 + 行为说明文案 SR4）
**验证**：线上模式语音→MiMo 转写正确；断网→自动回退本地；本地模式零变化

### Step S4.2：text_to_speech FC（D125 · 波 3）
**文件**：`SpeechSynthesisModule.kt`（+`synthesizeToFile(text, fileName)`：speakMimo 音频流截获写 WAV 至工作目录 **downloads/**（C4）；registerFunctions 结束零 FC 历史）+ FC `text_to_speech(text, save_as?, cloud?, play?)`（默认自动 upload_file 得 file_id；**G17：save_as 强制 basename 净化**（sanitizeName 复用）剥路径穿越）
**验证**：对话"生成语音"→WAV 落盘可播 + file_id 可下载；play=true 外放；QQ 语音链待 S10、微信语音待 S11

## 五、S9：工作目录全操作（波 2 · 1 Step）
**文件**：`WorkDirRepository`（+renameInPlace/moveCrossDir/copyStream/createFolder/deleteTree(递归)/listTree(≤5 层)）+ `FilesModule`（+`move_file`/`create_folder`/`delete_folder(force)` FC，AUTO；delete_work_file AUTO 化；list 增目录树标注）
**验证**：对话"新建归档文件夹，把报告移进去"全链；越界名拒绝；非空文件夹无 force 拒绝；SAF 边界外不可达

## 六、S8：UI 正式化（波 3 · 图纸 4 批 + 实现 2 Step）
- **S8.A-D 图纸批**（纯文档，逐屏 ASCII + ⚠️ 决策点 + 文案修正标注 SR-文案审查）：A=对话主界面/系统气泡/轨迹卡 → B=设置首页(结构不变)/对话模块详情/语音模块详情（C5） → C=触发自主/提醒/MCP → D=图标 3 稿/余页
- **S8.1 身份落地**：`strings.xml`（app_name→Zero）+ `build.gradle.kts`（versionCode 2/versionName 1.0.0，SR10）+ 关于页版本显示
- **S8.2 图标与清理**：adaptive icon 三套（前景/背景/单色）+ 《设置项-生效性对照表》终审清理
- **S8.3 定稿实现（G13）**：批 A-D 图纸经用户逐页批注定稿后——系统气泡/执行轨迹卡/模块详情页区块/提醒管理/文案修正逐屏落地 Compose（图纸↔代码一一对应，每屏一验证）
- **S8.4 全量验收（G13）**：全页面文案/交互/主题一致性走查 + 设置项生效性终审（对照表全绿）+ Build 收官

## 七、云服三步（波 4）
- **S6.1 cloud_media（P0-1 封板）**：`worker/tasks/media.py`（**ffmpeg 在 worker 镜像直跑，不进 sandbox**；cut/concat/transcode/extract_audio/probe；ffmpeg 模板白名单构造禁 shell；file_id 进出；≤512MB=输入配额）+ **工作空间守护**：执行前可用空间检查（输入×3）+ **媒体任务并发=1**（隔离通道稳定性，不做线程级过早优化）+ **失败 finally 清理临时文件**（防磁盘耗尽）+ gateway 工具（15 个）——验证：mp4 切 30s 片段拉回可播；avi→mp4 转码；**人为 kill 任务→临时文件已清（磁盘零残留）**；**ffmpeg 高负载期间微信/QQ 事件仍秒级进入（CPU 观测）**；回归 9/9
- **S7.1 migrate.sh（G8 一致性切片）**：export = **quiesce（gateway 拒新任务 → 轮询 celery active jobs=0（P1-5 真静止：确认 worker/ffmpeg/download 无写入）→ BGSAVE 完成 → tar /data/zero → manifest+checksum → 恢复**；import = manifest 校验→compose up→RDB 载入→tar 解包→.env 填充——验证：模拟导入 15 工具全绿；**迁移中任务要么在快照内要么被 quiesce 挡外（跨存储一致性切片）**
- **S7.2 Android 参数化**（SR8）：`TrustConfig` 单例（指纹列表双过渡）+ 三消费点改引 + McpServerStore 增指纹字段——验证：改设置指纹→MCP/下载/SSE 全链重连
- **S5.1 基建收敛**：`tasks/lib/files.py` 归位 + ssrf 单源 + MIME/组装统一——回归 9/9 + 全工具抽查

## 八、通道域收官（波 5 · S10 → S11 顺序执行 · 设计 §八C/§八F）

### Step S10.1：QQ 通道 · 云服侧（Lagrange 定选 · D114 管道复用）
**文件**：`docker-compose.yml`（+`lagrange` 容器：GHCR `ghcr.io/lagrangedev/lagrange.onebot` 经国内加速站拉取，E14 预警——失败换 LLBot 实测；正向 WS :3001 仅内网 expose；config 卷持久化扫码会话；mem_limit 200m）+ `gateway/requirements.txt`（**+websockets——C7：httpx 不支持 WS**）+ 新 `gateway/tools/qq.py`（`qq_status` 连通/登录态；`qq_read(since)` 拉新消息；`qq_send(text[, qq号])` send_private_msg）+ 新 `gateway/qq_client.py`（WS 常驻客户端：连 Lagrange :3001 → publish `zero:events(type=qq)` 仅作 **wake-up**；**G3+P0-2 游标契约**：事件入 Redis List `zero:qq:msgs`，每条 `{ingest_seq, message_id, timestamp, type, payload}`——**`ingest_seq`（Zero 自生成 INCR 单调序）= 顺序游标；`message_id` = 去重键**（二者职责分离，OneBot message_id 不保证跨会话单调）；`qq_read(since_ingest_seq)` 以持久游标 `zero:qq:cursor` 增量读取并推进——**P0-3 保留语义**：500=常态缓存容量**非可靠性上限**；游标已消费段才允许 LTRIM 回收（按 cursor 推进修剪而非盲目 LTRIM）；**未消费数据溢出时不静默删——List 超 2000 条置 `zero:qq:overflow=1` 显式标记，qq_read 响应附 degraded 提示**（Android 长期离线场景诚实降级）；Lagrange #685 由缓冲层吸收；
        **收文件（P1-4 双路径）**：事件含可用 URL→直接 httpx 下载；无 URL→经 Lagrange `get_image/get_record/get_file` API 取→下载→`_register_file` 登记 file_id（file_id 体系不变）；断线重连指数退避）
**G9 一期全量**（设计 §八C 对齐）：`qq_client` 订阅 `message.private` **+ message.group**；`qq_send` 支持 `send_private_msg` **+ send_group_msg**；图片/表情 CQ 段透传；`qq_status` 含好友/群列表（get_friend_list/get_group_list）；戳一戳事件透传
**验证**：扫码→私聊/群聊消息 SSE 秒达；qq_send 私聊+群文本送达；图片/表情段收发；好友/群列表返回；收文件→file_id 可下载；**游标实证**：断开 Android 30s 期间连发 3 条→重连 qq_read 恰好 3 条不重不漏；回归 9/9；内存 free -h
### Step S10.2：QQ 通道 · 手机侧（source 泛化）
**文件**：`TriggerHub.kt`（SSE 事件 type=qq → kick + qqCheckPending 检查器（镜像 wechat：qq_read→新消息 fire `QQ_PRIVATE`））+ `AutonomousThinkingJob.kt`（buildQqPrompt：D107 不可信定界 + D124 送达通道尾段（含 qq））+ gateway `qq.py` 描述同步
**验证**：QQ 发消息→秒级 kick→`trigger_fired: source=QQ_PRIVATE`→Zero 自主回复送达 QQ；D107 定界包裹实证
**二期（实测后决定）**：发文件（≤50KB 直发/大文件降级 file_id 链接）；语音 record 段（text_to_speech→qq_send record）

### Step S11 状态机纪律（终审）：S11.2 FAIL→不进 S11.3；S11.3 FAIL→不进 S11.4；S11.4 FAIL→保留旧通道；S11.5 仅稳定后执行——整链可回退

### Step S11.1：openclaw 容器 · 固定版本 · 同一存储实例 · 扫码（G1/G11/P0-4）
**文件**：新 `openclaw/`（Dockerfile：node:20-slim + OpenClaw Gateway + 插件——**G11：固定已验证版本**（npm 精确版本号锁定，禁 @latest；升级=显式动作+重新核对 RPC reference），npm 走 npmmirror）+ `docker-compose.yml`（+openclaw：mem 250m；**G1+P0-4 存储契约：openclaw 与 worker 挂载**同一存储实例**（现架构为宿主 bind `/data/zero`——若未来改 named volume 则两者同步改；契约=同一文件系统对象，非强制挂载类型）**——发送媒体只传共享卷内绝对路径，禁止假设宿主路径天然可见；状态目录 volume 持久化扫码会话）
**验证**：容器 Up + 版本锁定复核（重建镜像=同版本）；扫码通；重启会话保留；存储实例四验：既有文件双见 / worker 新文件→openclaw 可见 / **重启 worker 文件仍在 / 重启 openclaw 文件仍在**（P0-4）
### Step S11.2：协议探针（G2——先通协议再写 Provider）
**文件**：`gateway/wechat_official.py` 仅探针段（external-apps WS：**connect 首帧→握手→auth→protocol version→scopes→hello-ok→事件订阅→单条文本收发**——逐帧 trace 埋点；以官方 RPC reference 为准，禁止猜协议）
**P1-1 能力探针补充**：除握手链外同 Session 核验 **Provider 最终所需能力**——channel/account 寻址、target/peer 指定、send RPC 错误格式、**媒体能力探测**（file/image/voice 发送 RPC 是否在 scope 内可用）——"文本通"≠"媒体链可成"，缺口记录并反馈 S11.4 设计
**验证**：探针脚本全链通（订阅微信事件+收 1 条+发 1 条）；失败即止——协议受阻则启备选 C（fork WeClawBot-API 加媒体端点），不带病推进
### Step S11.3：OfficialWechatProvider · 双通道灰度（G10——退役后移）
**文件**：`gateway/wechat_official.py` 完整 Provider（D97 缝：消息事件 publish `type=wechat`；sendMessage 文本/媒体）+ `gateway/tools/wechat.py`（`WECHAT_PROVIDER=official|weclawbot` env 开关）——**weclawbot+tail 本步保留运行（灰度回退位）**
**验证**：official 主用：微信消息 SSE 秒达+文本送达；开关切回 weclawbot 即通；回归 9/9
### Step S11.4：微信全媒体链路（G1 零拷贝兑现）
**文件**：`wechat_official.py`（+收文件：插件 CDN 解密落盘→登记 file_id；+发文件/图片：file_id→共享卷路径→对应消息类型）+ `worker/requirements.txt`（+pilk WAV→Silk）+ `wechat_send` +可选 `file_id` + 手机 FC 描述（text_to_speech+wechat_send(file_id) 语音链）
**验证**：收文件→file_id 可读；发文件送达；语音链送达可播；工具数 18 定格；free -h
### Step S11.5：稳定观察期后正式退役（G10——独立步，非随切随删）
**操作**：official 稳定运行观察期（≥48h 无回退事件）后——compose 移除 weclawbot/wechat-tail 服务；**回退保障：镜像与配置留存 git，一键 `docker compose --profile legacy up` 可拉起**（rollback 路径不丢）
**验证**：退役后官方链全绿；legacy profile 拉起演练一次通过

---

## 九、进度追踪表

> 实施顺序权威表：设计文档 §九（wave1-5）；本表为全 Step 状态追踪。

| Step | 内容 | 状态 | 验证 |
|---|---|:---:|---|
| S3.1 | ReminderEntity 扩列 + MIGRATION_13_14 | ✅ | Build ✅ APK 09-07 20:28（5 列逐条 ALTER+DEFAULT 对齐；DAO +loadActive/updateStatus/updateSchedule）；**老库升级 12 提醒保留 = L3 必测待用户** |
| S3.2 | 调度稳定化（权限/BOOT/missed） | ✅ | Build ✅ APK 09-07 20:44（+SCHEDULE_EXACT_ALARM 声明/G6 恢复期判定/BootReceiver 接线/G7 watcher+引导通知）；**SR3 四态矩阵 = L3 待用户** |
| S3.3 | FC 全集 + 提醒 UI | ✅ | Build ✅ APK 09-07 21:03（modify_reminder FC/missed 复活/set 落全字段+llm 标记/list 状态化 + TriggerSettings 提醒管理区/状态徽标/missed 高亮重排）；**S3 轨收官，L3 全链待用户** |
| S2.1 | 注入通道 + 投影扩展 | ✅ | Build ✅ APK 09-07 21:47（onSystemEventInjected DROP_OLDEST(50)+SystemEventDisplay；TriggerHub WECHAT/CLOUD_TASK 旁路 emit F8；VM 会话级 systemBubbles+merge 时间戳穿插+AUTONOMOUS 重映射防双显 C3）；**chatHistory 零改动**——微信气泡实时显示 L3 待用户 |
| S2.2 | 全源接入 + 回复徽标 | ✅ | Build ✅ APK 09-07 22:08（8 源 fireTraced 统一气泡+F7 triggerId 贯通；回应气泡"因：来源"；D124 尾段+payload 通道状态 5min 缓存）；**全链 L3 待用户** |
| S2.3 | 执行轨迹卡 | ✅ | Build ✅ APK 09-09 19:46（onToolCallEvent 流+FC 循环 emit G5 双 id；ExecutionTraceCard 单一折叠卡合并 thinking/toolCalls——ThinkingBubble 退役；消息自身字段渲染，流式实时更新，chatHistory/VM 零改动）；**折叠/展开 L3 待用户** |
| S1.1 | 对话模块模型自治 | ✅ | Build ✅ APK 09-09 21:35（**fix2 用户裁定收口：openai_chat 既有 API Keys 卡片退役**——编辑子页 apiKey 为唯一入口，沿用卡片保存风格=眼睛切换+字段级 ✓ 立即保存（存后清空+反馈），保存全部保留兜底；同 ref 零迁移；fix-rev 移除尾部 TOOL 补位（审查发现有害），孤儿守卫独立有效；rev：编辑改子页+apiKey 同地+版式松化；**S1.1-fix 孤儿 TOOL 400 守卫** buildMsgsForLLM 单点收口）；**模块页管 profile + 400 消除 L3 待用户** |
| S1.2 | 四模块三件套 | ✅ | Build ✅ APK 09-09 22:12（a-d 四子步各构建：**a 存储层** Kind+SEARCH、qwen-search 预置（G12）、qwen-vl 预置修正为 DashScope 原生端点+模块现行 ref（SR9）、未编辑存量预置值升级（P1-7 用户改过保留）、maskedKey 回退链；**b** TTS/Embedding 接线 Store 即时生效（原读静态 defaults 用户改不生效）+ TTS 音色一次性迁移 profile；**c** Vision/Search 接线（协议不动仅数据源换 profile）+ ConfigField 存量迁移+退役；**d** speech_tts/memory/vision_analyze/web_search 详情页嵌 ModuleProfilesSection + 三卡片分支退役（单入口裁定，同 ref 零迁移）；ASR 三件套随 S4.1（偏差已录））；**独立换端点生效 + G12 搜索独立切换 + 存量迁移归属 L3 待用户** |
| S1.3 | 数值 sweep + MCP 清理 | ✅ | Build ✅ APK 09-09 22:36（sweep 按 S1.2 表全落地：dailyNotifyCap [1,200]默认20 / dailyTriggerCap [5,1000]默认100 / 报点四参 base[30,3600]·offset[0,600]·mult[1,10]·doubling[0,8] / graphExtractionThreshold[10,2000]·archiveAfterDays[7,3650] / maxSteps[3,100]·maxDurationMin[1,240]——fromJson 钳位全部同步；MCP 推荐目录删除（预设列表+Key 对话框+添加框内高德快捷按钮，手动添加主流程保留）；全库 @ConfigField 数值字段过表复审：其余均物理域/安全边界，零额外改动）；**S1 轨收官**；上限可调实测 L3 待用户 |
| S4.1 | ASR 双通道 | ✅ | Build ✅ APK 09-09 23:05（a：chatAudioStream C2-G4 终稿端点（/v1/chat/completions+input_audio content part+stream，G16 10MB 段限抛错走兜底，usage 经 BudgetGuard 咽喉）+ Kind.ASR + mimo-asr 预置（asr:mimo→tts:mimo 回退，一个 Key 管 TTS+ASR）+ kindLabel；b：**本地 sherpa 降级为断句器+兜底转写**（端点=VAD 断句，线上 partial 不外显防 P1-6 双显）+ 段 PCM→WAV 编码整段上传 + SSE 流式 ONLINE_PARTIAL→ONLINE_FINAL/LOCAL_FALLBACK→COMMITTED 仅终态进回合 + epoch 守卫丢弃过期段 + 引擎 DROPDOWN（SR4 文案：按语句分段流式非逐字实时，断网自动回退）+ LocalVoiceProvider 双引擎分支/回流接线 + speech_asr 详情页嵌模型管理（S1.2 顺延 ASR 三件套落点）；本地模式零变化）；**线上转写正确性/断网回退/本地零回归 L3 待用户（需 MiMo Key）** |
| S4.2 | text_to_speech FC（D125） | ✅ | Build ✅ APK 09-09 23:25（TTS 结束零 FC 历史：`text_to_speech(text, save_as?, cloud?, play?)` CONFIRM 对齐 save_to_file；synthesizeToWav 截获合成流写 WAV——MiMo 优先（复用 ttsStreamPcm 逐句）/本地 Melo 同理（系统 TTS 不支持）；**TtsWavSink 缝**（ChatEngineBridge 先例：services 接口+app WorkDirRepository 实现，G17 净化在 app 侧收口强制 .wav+basename 剥穿越）落盘 downloads/（C4）；默认经 registry.execute 程序化通道自动 upload_file 得 file_id（cloud=false 关）；play=true 走既有 speak 完整管线异步外放；tts_file_* trace 全链）；**S4.2-fix（L3 实测触发 23:48）：FC 注册须 onAwake 自调**——TTS onAwake 漏调 registerFunctions 致工具列表缺失（LLM 如实报告无此工具）；同类审计修复 **TriggerHub autonomous_notify 同病**（日志实证零注册——D124 降级链兜底一直静默缺失）；**对话生成语音全链（落盘可播/file_id 可下载/play 外放）L3 复测待用户（需 MiMo Key 或 Melo 模型）**；QQ 语音链待 S10 |
| S9.1 | 工作目录全操作 | ✅ | Build ✅ APK 09-10 00:21（D123 完全暴露+SAF 护栏：**FC×3 新增** move_file（SR7 同目录 rename 快路径/跨目录 copy+delete 兜底/目标已存在拒覆盖）/create_folder（嵌套逐级）/delete_folder（**G15 审计**：非空无 force 拒绝并返回统计；force=后序递归删+已删清单/数量/大小双载体 RunLog+返回）；delete_work_file 及新增三项 **AUTO 让渡**；list_work_files 升目录树（📁/📄 标注+缩进，SR7 ≤5 层截断）；resolvePath 拒 ".."/"." 段（D57 穿越防护）；writeMutex 全变更串行沿用）；**fix（Zero agent 实测 09-11 · S10/S11 暂停插修 · 20:20）**：delete/read 族接入**统一文件引用解析** `resolveFileRef`（①含'/'路径=resolvePath 精确 ②纯名=根→downloads 两级 ③全树唯一名匹配+多命中歧义清单）——deleteFile 原仅根目录整串匹配（downloads/ 子目录文件永远删不掉）+文件夹守卫（目录引导 delete_folder）；readText 原根目录精确（子目录文本不可读）；readBinary/openReadStream 原剥路径仅两级（深层子目录不可达）——四函数全量修复，writeText 根写入语义不变；**fix2（用户裁定 20:38）**：**写族全面路径化**（save_to_file/create_document/edit_document/format=pdf——`ensureParentDir` 逐级建父目录+拒穿越；根因=原以整串在根目录 createDocument，provider 将'/'压成'_'落根+撞名"(1)"）；**回执真实落点**（`WriteOutcome.finalRelPath`=目录前缀+SAF 改名后实名——消灭"回显请求路径"失真回执）；save_to_file 描述注明子路径支持；10 函数子文件夹全通、不设目录限制（writeBinaryStream/TtsWavSink 仍落 downloads 子目录语义不变） |
| S8.A-D | 逐屏图纸 4 批 | ✅ | 图纸已出并**定稿**（`ZeroV2-UI图纸-S8定稿.md`；2026-09-10 用户逐项裁定 1-13 全部落档）：历史按钮保留文字/署名 You→「我」/系统卡按事件源固定图标+payload 默认折叠/思考段保持现状/关于卡副标语=常驻自驱陪伴·数据仅存本机/入口名保留「事件触发」/语音保持两页/空 FC 区隐藏/**通道状态统一归权限中心（新增区块，D4 修订图纸）**/TriggerHub 数值留模块详情页/图标选稿一「零语」/启动画面=splash 自然时长；A3/B2/C2/C3/D3=已落地定稿确认零改动；生效性候审 4 项预登记 S8.2；**可直接进 S8.1-S8.3** |
| S8.1 | Zero v1.0 身份落地 | ✅ | Build ✅ APK 09-10 10:09（strings.xml app_name ZeroV2→**Zero**；build.gradle.kts versionCode 1→**2** 单调（C6/SR10）/versionName 2.0.0→**1.0.0** 归零 + buildConfig 启用；关于卡=Zero + v1.0.0 (build 2)（BuildConfig 读取）+ 副标语「个人 AI Agent · 常驻自驱陪伴 · 数据仅存本机」（裁定 6，"Phase 0 MVP"/技术黑话行删）；applicationId 不动）；**fix（L3 实测触发 10:18）：application label 原硬编码 "ZeroV2" 压过 @string/app_name——桌面图标名不生效；改 @string/app_name 单一来源（三级合并 Manifest 校验 ✅）**；**装机实测：桌面图标名 Zero / 降版覆盖安装成功 / 关于页版本显示 L3 复测待用户（若启动器缓存旧名：先卸载重装）** |
| S8.2 | 图标 + 设置终审 | ✅ | Build ✅ APK 09-10 10:32（**零语图标从零建**——原 Manifest 无 android:icon/res 零资产（系统默认图标实锤）：adaptive 三套=前景（渐变环+气泡尾，安全区校验）/深空底 #0B1020/单色版 + Manifest icon/roundIcon 接线；**通知小图标** ic_stat_zero 线形版（app+services 双模块）替换全库 5 处 android.R.drawable.ic_dialog_info（applicationInfo.icon 分支一并收口——adaptive XML 作小图标渲染不良）；**《设置项-生效性对照表》**出表：候审#1 已收口=非 CHAT 单 profile 隐藏 Switch/设为主力（可见即生效）、#2 PASS=AgentBehavior 直连同 config 实例同源实证、#3 ✅S8.1、#4 🔜S8.3；维持组六页留 S8.4 走查）；**图标显示/主题图标/通知小图标 L3 待用户**；splash 画面随 S8.3 落地 |
| S8.3 | 定稿图纸实现（逐屏落地） | ✅ | Build ✅ APK 09-10 10:45（四子步各构建：**A** 顶栏仅角色名（基线 2 兑现）+ You→我（裁定 2）+ 系统卡样式化=右对称卡/头部 title 自带事件源 emoji（裁定 3 fireTraced 统一）/payload 默认折叠 40 字+展开（裁定 4）；**B** MCP 副标题删例举+图标 Folder→Cloud+行尾箭头 ChevronRight 右向；空 FC 区隐藏=既有 isNotEmpty 守卫已满足（零改动核验）；**D2** splash=core-splashscreen 深空底+零语 glyph 自然时长（裁定 13；词标文字 splash API 图标位不支持——偏差已记）；**D4** 权限中心通道状态区块（裁定 10）：TriggerHub 探测结构化（Boolean? 缓存共用 C11）+wechatChannelStatus() 公开 + 权限中心「通道状态」卡（已登录/未登录/探测中三态+送达降级说明+刷新联动）；chatHistory/VM 零改动——A2 纯渲染层）；**逐屏 L3 待用户** |
| S8.4 | 全量验收走查 | ✅ | Build ✅ APK 09-10 11:0x（走查结论：**全库用户可见文案零 "Zero v2/Phase/MVP" 残留**（唯一命中=对话顶栏，S8.3 已修）；返回钮 contentDescription 全库统一、动作图标描述合法；工作目录页文案与行为无矛盾；暗色基底资源系统解析一致；**G5 审计链闭合**——RunLog 增检索框（Trace ID/工具调用 id/内容过滤+检索态自动展开命中卡片，兑现 A3 卡「按 id 检索」承诺，候审#5 收口）；《设置项-生效性对照表》**全绿封板**（候审 5 项全闭+维持组六页走查翻绿）；Build 收官零回归）；**全页面实机走查 L3 待用户**；**S8 轨收官（wave3 体验轨完）** |
| S6.1 | cloud_media | ✅ | **已部署并全链实测 ✅（09-10）**：上传 5 文件→rebuild 三容器→worker-media 就绪（media 专队 concurrency=1）→**回归 9/9 ALL_PASS**；E2E：cut 2s 起 5s 段→产物 **5.012993s 拉回 ffprobe 实证可播**；transcode→avi ✓；extract_audio→m4a ✓；probe 元数据 ✓；**kill 清扫闭环实证**（SIGKILL 容器→.tmp 残留 1→下任务启动 swept_stale_tmp=1→FINAL_TMP=0 零残留；注：docker kill=手动停止不自愈——真实 OOM 崩溃走 restart 策略自愈）；内存 1.8Gi available / 11 容器 ✓；ffmpeg 高负载微信事件隔离=专队+独立容器架构性保障（真实流量观察待用户）；代码：五动作/argv 白名单禁 shell/df≥输入×3/输入≤512MB/finally+清扫双层清理（b7e273f）；**fix（Zero agent 实测 F 节 244e837 已部署实证）**：cut/transcode 白名单纳入音频源（.mp3/.m4a/.wav——m4a→mp3 实测走通 ffprobe=mp3 8.05s）；音频源仅音频目标/分辨率守卫/码率 -b:a；工具描述补 action+file_id+params{} 结构+probe 异步幂等语义 |
| S7.1 | migrate.sh | ✅ | **已部署并真实演练 ✅（09-10）**：export/verify/import 三段（G8 切片：quiesce 拒新任务=redis 标志 submit.py 消费→**active jobs=0 真静止轮询（--json 求和）**→BGSAVE 完成确认→/data/zero tar 109M+weclawbot 扫码会话包（D120 清单实测补充）+脱敏 env 模板+SHA256SUMS/MANIFEST；import=fresh redis 卷注入 RDB（清 AOF 保 RDB 优先）→数据解包→起栈→**清快照残留 quiesce**；trap 兜底）；**演练实证**：真实导出 109M 包/checksum 全过/**RDB 钻孔**（临时容器载入 DBSIZE=54·zero:file:*×12）/quiesce 解除 EXISTS=0/回归 9/9 ALL_PASS；**演练抓出两 bug 即修**：①active 文本检测 '\->worker:OK' 头行恒计 2 永不归零→JSON 求和 ②快照必含 quiesce→导入后永久拒任务→import 起栈即 DEL（AFTER_DEL=0 实证）；**全栈 import 钻孔（15 工具）+Android 切换=真实迁移时新机执行（2C4G 无法双栈并行）** |
| S7.2 | Trust 参数化 | ✅ | Build ✅ APK 09-10 21:36（SR8/D120：**ZeroCloudTrust 常量态→可配置态**——pinnedHosts/acceptedCerts/primaryHost 三 @Volatile + applyPins；**HOST 改动态 getter** ⇒ TriggerHub SSE/WebFetch 上传 URL 拼接零改动自动跟随新服务器；checkServerTrusted **接受集命中（双指纹并存）**；**TrustConfig 单例**（新）=内置默认 ∪ SecureKeyStore 全局新指纹（zero_cloud:cert_sha256_extra 备份通道）∪ McpServerStore 条目（host+certSha256）——collect store 流自刷新+McpClientModule.onAwake 实例化锚；McpServerConfig **+certSha256 字段**（JSON 往返）；MCP 添加对话框**指纹输入**（64 hex 归一，公共 CA 留空）；pins_refreshed trace）；**rev（用户裁定 21:45）**：MCP 条目**编辑 UI+功能**——添加/编辑共用对话框（预填；Key 恒空显示留空=不改，填写即覆盖写回**既有引用**——改名不动存储 Key 永不丢）；卡片「编辑」入口；指纹改动经 TrustConfig 流即时生效）；**换服务器全链重连（MCP/下载/上传/SSE）实测待用户——迁移场景随 S7.1 包实操联测** |
| S5.1 | 云服基建收敛 | ✅ | **已部署验证 ✅（09-10）**：①`tasks/lib/files.py` 归位——文件五助手（meta/resolve/register/result/sha256）自 file.py 公开化迁出 + **EXT_MIME 双表合并**（download EXT_MIME ∪ media MIME_OUT 键值核对一致）统一 `guess_mime()` 出口；file.py 瘦身、4 消费方（file/media/bili_login/download）别名导入**零行为**；②**ssrf 单源**——common/ssrf.py 经 build context 根化（compose 三服务 `context:.`）COPY 进双镜像，消费方 `from ssrf import` 不变；**实测双副本已漂移**（worker 缺 CGNAT 100.64/10 显式阻断）→ 收敛取更严 gateway 版，**部署后 worker 侧 Ali metadata IP 拦截实证生效**（`100.100.100.200 → IP 不允许`）；③sync/async 声明式标注**核验已存在**（main.py:247 双态调用）零改动；验证：三镜像 rebuild Up（gateway healthy）+ **回归 9/9 ALL_PASS** + lib 链端到端抽查（media probe→tasks.lib.files→登记 3.000000s）+ 服务器残留文件清理 |
| S10.1 | QQ 云服侧（Lagrange+qq 三件套） | ⏳ | SSE event:qq 秒达 + 收文件 file_id |
| S10.2 | QQ 手机侧（QQ_PRIVATE 泛化） | ⏳ | QQ 消息→秒级自主回复 |
| S11.1 | openclaw 容器（固定版本+共享卷+扫码） | ⏳ | 版本锁定 + /data/zero 双向可见 |
| S11.2 | 协议探针（握手/订阅/单条收发） | ⏳ | 全帧 trace 通，受阻即切备选 C |
| S11.3 | OfficialProvider 双通道灰度 | ⏳ | official 主用 + 开关回退通 |
| S11.4 | 微信全媒体 + 语音 Silk | ⏳ | 文件/语音双向 + 工具数 18 |
| S11.5 | 观察期后退役 weclawbot/tail | ⏳ | legacy profile 一键拉起演练 |
