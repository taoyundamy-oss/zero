# Zero V2 后续路线设计（P5–P8，v4.0）

> 依据：2026-08-21 阶段性评审（实测反馈）+ GPT 意见文件（QQ/OneBot、Realtime Agent、M3 Expressive）
> + 用户四项核心决策（D33-D36）。
> 本文档为 DEVELOPMENT_DESIGN.md 的路线扩展，遵循现有规范（决策记录 D 编号 / Phase 表 / Step 文件路径 + 验证标准）。
> **权威层级**：DEVELOPMENT_DESIGN（架构/决策）> 本文档（Phase/Step）> IMPLEMENTATION_PLAN（细节）。冲突按此序裁决。
> v5.0（2026-08-27）：**D57 Phase 顺序重排——P8 MCP 生态前置，P7 Telegram 顺延**（用户决策）；P8 扩容为全案：自研轻量 MCP 客户端（Streamable HTTP）/ 高德官方 MCP 直连 / 办公文件纯本地方案（POI）/ WebFetch 双引擎（jsoup + Jina Reader）；浏览器操控改判为"下载 + 爬取"直接方案（Playwright/PC 远程移出计划）。
> v4.0（2026-08-23）：**D48-D50 语音引擎重设计**——sherpa-onnx 全本地（流式 Zipformer ASR + Kokoro TTS + Silero VAD），模型 APK 内置，R0 级联废弃，6.8 云端降为可选。
> v3.5（2026-08-22）：D44-D47 修订并入（UserGraph 权威/侧载定位/Realtime 分层/ScreenSnapshot）；分发定位 = 开源个人侧载（D45，Play 政策不适用）。
> 2026-08-21

---

## 〇、用户核心决策（本轮确认）

| # | 决策 | 选择 |
|---|------|------|
| D33 | 实时语音路线 | **分阶段**：第一级 系统STT+现有LLM+系统TTS 级联（零新增成本快速可用）；第二级 云端流式 Realtime（DashScope CosyVoice/qwen-omni，真 Barge-in）。两级并存用户可切换 |
| D34 | 屏幕实时阅读 | **混合**：无障碍节点树文本为主通道（零成本毫秒级）；按需（用户提问含"看这个页面"/文本不可用）MediaProjection 截图走 Vision 模型 |
| D35 | 社交平台 | **Telegram 优先**（官方 Bot API，零封号风险）；QQ（OneBot 11 + 远端 NapCat）作为后续 Adapter 保留设计；微信个人号本轮不做（无官方 API） |
| D36 | 文件写入 | **SAF**：用户经系统选择器选定「Zero 工作目录」，持久化 Uri 权限后免权限写入；PDF 用 Android PdfDocument 生成 |
| D37 | 平台接入架构 | **Platform Gateway + 统一消息模型 PlatformMessage**（GPT 方案采纳）——Agent Core 不知道消息来自哪个平台 |
| D38 | Live 权限模型 | **会话制**：麦克风/屏幕/摄像头仅在用户主动开启 Live Session 时申请，会话结束全部释放；不设计"永久后台偷听/偷看" |

**新 Phase 映射**（相对旧计划）：
- P5 打磨+用户主权（现有）+ **扩容**：权限中心强化 + M3 Expressive UI 翻新
- **P6 Realtime Agent**（用户预期 P0 实时语音对话 + P3 屏幕识别，合并）
- **P8 MCP 生态（前置，D57）**：MCP 客户端 + 高德直连 + 办公文件本地编辑 + 网络资源自主获取
- **P7 Platform Gateway + Telegram**（顺延至 P8 后）
- P9 多 Agent（原 Phase 7，远期保留）

---

## 一、全量权限矩阵（权限中心数据源）

| 权限 | 用途 | 授予方式 | 引入 Phase |
|---|---|---|---|
| INTERNET / ACCESS_NETWORK_STATE | 网络 | 安装 | P0 |
| RECEIVE_BOOT_COMPLETED | 开机恢复 | 安装 | P0 |
| BIND_ACCESSIBILITY_SERVICE | Agent Runtime 报点 + **P6 屏幕文本树**（需升级 canRetrieveWindowContent，见 D39） | 特殊授权（设置页） | P3 / P6 |
| BIND_NOTIFICATION_LISTENER_SERVICE | 通知监听 | 特殊授权（设置页） | 预留 |
| POST_NOTIFICATIONS | 通知推送 | 运行时弹窗 | P2 |
| PACKAGE_USAGE_STATS | 使用统计 | 特殊授权（使用情况访问页） | P2 |
| RECORD_AUDIO + FOREGROUND_SERVICE_MICROPHONE | 实时语音 | 运行时弹窗 + 前台服务类型 | P6 |
| MediaProjection（+ FOREGROUND_SERVICE_MEDIA_PROJECTION） | 按需截图 | 每次会话系统弹窗 | P6 |
| SYSTEM_ALERT_WINDOW（悬浮窗） | Live 后台右侧快捷按钮组 | 特殊授权（设置页，权限中心直达） | P6 |
| CAMERA + FOREGROUND_SERVICE_CAMERA（可选） | 摄像头 Live | 运行时弹窗 | P6 选做 |
| SAF 目录授权 | 写 txt/md/pdf | 系统选择器（无 Manifest 权限） | P6 |
| 无（纯网络） | Telegram / MCP | — | P7/P8 |

**实测修复项（三星 Fold3 韩版）**：权限中心必须提供 `Settings.ACTION_USAGE_ACCESS_SETTINGS` 等 Intent 直跳（韩版 ROM 不在应用列表展示第三方入口，用户无从找到授权页）。

---

## 二、Phase 5 修订（打磨 + 权限中心 + UI 翻新）

原 Step 5.1-5.7 保持，两处扩容：

### 5.3 扩容：PermissionCenterScreen（原 ModulePermissionScreen 升级）
- 权限分组列表（参照 §一矩阵）：每项 = 图标 + 名称 + 用途说明 + **状态徽标**（已授权/未授权/不支持）+ **一键直跳**对应系统授权页
- Intent 直跳清单：无障碍、通知访问、使用情况访问（`ACTION_USAGE_ACCESS_SETTINGS`）、通知运行时请求（MainActivity 触发）、SAF 目录选择（P6 接入后）
- 数据源：各模块 `requiredPermissions` + 本地授权状态轮询（`AppOpsManager`/`checkSelfPermission`/`NotificationManagerCompat.areNotificationsEnabled`）
- 埋点：`ui_permission: action=jump target=...`

### 5.4 扩容：M3 Expressive UI 翻新（GPT 规范采纳）
- **布局不动**（用户已认可），统一升级视觉层：
  - `enableEdgeToEdge()` 强制 + WindowInsets 全量适配（手势/三键/刘海/键盘/横屏/折叠屏）
  - Dynamic Color（Android 12+ `dynamicLightColorScheme`，低版本回退品牌色板）
  - 暗色主题（原有内容）与 Dynamic Color 合流
  - 反"到处 Card"：列表页转 tonal surface / ListItem 分组；形状系统统一（M3E 柔和大圆角）
  - 动效：`animateContentSize`/共享元素过渡自然短促
- 落地方式：主题层（`ZeroTheme`）+ 逐屏翻新（Chat → 模块中心 → 自动化 → 设置族），每屏一个 Step 内子任务

---

## 三、Phase 6：Realtime Agent（语音 + 屏幕阅读，6.1–6.10）

> 目标场景（用户原始诉求）：阅读英文文献时，Zero 通过屏幕阅读与我实时对话——翻译、解析生词、
> 按要求存入记事本或写入文件（txt/md/pdf）。对标 Gemini Live 的"边看边聊"。

### 〇、Live UI 设计规范（2026-08-22 用户确认，参考 Gemini Live 实际交互）

**三层 UI 结构**：

| 层 | 设计 | 说明 |
|---|---|---|
| **L1 入口** | ChatScreen 输入栏右侧**圆形 Live 按钮**（🎙 图标，primaryContainer 底色） | 独立于附件/发送键；Live 进行中呈呼吸态（脉动边框），点击回 Live 页 |
| **L2 全屏 Live** | LiveSessionScreen：中央**动态状态大圆**（LISTENING 蓝色脉动 / THINKING 渐变 / SPEAKING 波形光圈，RealtimeState 驱动）+ 下方实时转写滚动 + 底部功能条（屏幕阅读开关 / 静音 / 挂断） | 挂断才结束会话；返回键=退后台不结束 |
| **L3 后台悬浮** | **LiveOverlayService**（SYSTEM_ALERT_WINDOW）：右侧边缘**半隐藏圆钮**（Zero 波纹头像），点击展开纵向功能按钮组：回到应用 / 静音 / 结束 Live / **屏幕共享** | 应用在后台时承载；常驻通知（静音/结束）为锁屏兜底 |

**后台处理链**：LiveSessionService（FOREGROUND_SERVICE_MICROPHONE）持麦克风+STT/TTS，退后台/锁屏语音不中断（D38 同一会话）；悬浮按钮+通知双入口控制。

**屏幕阅读权限流（双通道，D34/D39）**：
- 主通道（默认）：无障碍文本树——零新增弹窗（已授权 + 包名白名单）
- 副通道（按需）：悬浮组/功能条点「屏幕共享」→ 透明 **ConsentActivity**（系统要求授权必经 Activity）→ MediaProjection 系统弹窗 → Service 持 token **单帧按需**抓取 → 结束共享即释放；每会话重新授权

**权限矩阵新增**：`SYSTEM_ALERT_WINDOW`（悬浮按钮，特殊授权，权限中心直达 `ACTION_MANAGE_OVERLAY_PERMISSION`）、`RECORD_AUDIO`（进入 Live 运行时请求）、MediaProjection（每会话系统弹窗）。

### 〇.5、落地审查修订（2026-08-22 二次评审）

| # | 级别 | 问题 | 修订 |
|---|---|---|---|
| R1 | P0 | **API 34+ 时序**：`getMediaProjection()` 前服务必须已含 `MEDIA_PROJECTION` FGS 类型，否则 SecurityException | ConsentActivity 授权后 → Service 升级 `startForeground(MICROPHONE \| MEDIA_PROJECTION)` → 再建 projection；Manifest 服务声明**双类型** |
| R2 | P0 | **级联回声未定义**：TTS 播报时 STT 开启会自听误触发"打断"死循环 | **v1 半双工**：TTS 播报期间暂停 STT（无语音打断，保留手动"停止说话"按钮）；真 Barge-in 属 6.8 第二级（云端 + VAD）。与 D33 一致，写明防实现歧义 |
| R3 | P1 | Live 与文本对话并发竞争 ChatBotModule 互斥锁 | Live 激活期间 Chat 输入栏禁用 + "🎙 Live 进行中"提示 |
| R4 | P1 | D39 包名白名单无管理 UI | 6.5a Live 设置区含**白名单 App 选择器**（默认仅文献/阅读类 App 勾选引导） |
| R5 | P1 | 双服务（Session+Overlay）生命周期同步复杂度无收益 | overlay 窗口**由 LiveSessionService 直接管理**（会话开始挂视图/结束移除）；删除独立 LiveOverlayService |
| R6 | P2 | 后台启动 ConsentActivity 依赖 SYSTEM_ALERT_WINDOW 豁免 | 备用路径：常驻通知 PendingIntent（同样可启 Activity）——overlay 未授权时屏幕共享仍可达 |
| R7 | P2 | SpeechRecognizer 在三星国行/无 GMS 设备质量不可控 | D33 降级链注明：第一级 STT 质量 OEM 相关，第二级云端 Realtime 为终极兜底 |
| R8 | P2 | 无障碍 XML 变更（D39）需重新开关服务生效 | 升级后首次启动权限中心提示"重新开启无障碍以启用屏幕阅读" |
| R9 | P2 | Android 14 while-in-use：系统重建 FGS 后台恢复时麦克风被拒 | Live 被杀后不静默复活，通知引导用户回前台重开（与 D38 会话制一致） |

```
              LiveSessionService（前台服务，RealtimeState 状态流）
              ┌────────────┬──────────────┬─────────────┐
              │ 🎙 语音通道 │ 🖥 屏幕通道   │ 🔊 输出通道 │
              │ 级联STT/LLM/│ 无障碍文本树  │ TTS 流式 +  │
              │ (第二级云端  │ (主) + 截图VL │ Barge-in   │
              │  Realtime)  │ (按需, D34)   │ 打断       │
              └────────────┴──────────────┴─────────────┘
                     │ 统一进 ChatBus / FunctionRegistry
                     ▼
              Agent Core（Character/Memory/UserGraph/FC/文件FC）
```

### Step 6.1：SAF DocumentRepository（文件写入地基）
**创建**：`app/src/main/java/com/zero/v2/app/data/WorkDirRepository.kt`
- `pickDirectory()`（ACTION_OPEN_DOCUMENT_TREE + persistUriPermission）、`writeText(name, content, append)`（txt/md）、`writePdf(name, textPages)`（PdfDocument）
- 验证：选择目录 → 写入 test.md → 文件管理器可见内容

### Step 6.2：ScreenTextReader（无障碍文本主通道；D47 结构化）
**创建**：`services/src/main/java/com/zero/v2/services/realtime/ScreenTextReader.kt`（含 `ScreenSnapshot` 数据类）
**修改**：`app/src/main/res/xml/agent_runtime_service_config.xml` —— **canRetrieveWindowContent="true"（D39：隐私边界变更，权限中心需明示"读取屏幕内容"）**、`AgentRuntimeService` 转发 `TYPE_WINDOW_CONTENT_CHANGED` 节流事件
- `rootInActiveWindow` 递归提取可见文本（去重/排序/上限 8K 字符）、包名过滤（仅白名单 App，6.5a 管理）
- **ScreenSnapshot（D47）**：packageName/windowId/timestamp/contentHash/text/nodes/bounds——使用方消费前校验时间戳与哈希（Android 节点树可能过时，防"上一帧当当前页"）；无障碍（结构化）+ 截图（视觉，6.6）合并为 ScreenContext
- FC：`read_screen_text()`（AUTO）——LLM 可主动读屏，返回含时间戳与陈旧度提示
- 验证：阅读 App 内文献 → FC 调用返回当前页面文本 + 时间戳；页面切换后旧快照被识别为过期

### Step 6.3：LocalVoiceProvider（D48/D50 sherpa-onnx 全本地引擎，v4.0 重写）
**创建**：
- `services/src/main/java/com/zero/v2/services/realtime/RealtimeVoiceProvider.kt`（**D46 统一接口**：start/stop/isActive/interrupt/events）
- `services/src/main/java/com/zero/v2/services/realtime/LocalVoiceProvider.kt`（D48 核心实现）
- `services/src/main/java/com/zero/v2/services/realtime/VadDetector.kt`（Silero VAD + 分段）
- `services/src/main/java/com/zero/v2/services/realtime/LocalTtsPlayer.kt`（Kokoro 合成 + AudioTrack 播放）

**修改**：
- `services/build.gradle.kts` — 新增 `implementation("com.k2fsa.sherpa:onnx:1.12.28")`（AAR ~20MB）
- `app/build.gradle.kts` — aaptOptions noCompress for model assets
- 模型资产（`services/src/main/assets/models/`）：
  - `asr/` streaming-zipformer-small-bilingual-zh-en int8（encoder ~45MB + decoder ~3MB + joiner ~2MB + tokens.txt）
  - `tts/` kokoro-multi-lang-v1.1 int8（model ~170MB + voices.bin + tokens.txt + espeak-ng-data）
  - `vad/` silero_vad.onnx（~1MB）

**核心链路（D50）**：
```
AudioRecord(16kHz PCM) → OnlineRecognizer(Zipformer).acceptWaveform()
    → partial 结果 → events.emit(UserTranscript partial)
    → endpoint detected → final 结果 → events.emit(UserTranscript final)
        → ChatEngineBridge → LLM 回复
        → OfflineTts(Kokoro).generate() → WAV bytes
        → AudioTrack 播放（分段流式）

// Barge-in（真打断，非 R2 半双工）：
AudioRecord(同时) → VadDetector(Silero).isSpeech()
    → SPEAKING 状态下检测到用户声音 → interrupt() → 停止 AudioTrack → 回 LISTENING
```

- **流式 ASR**：OnlineRecognizer 逐字出稿（非 SenseVoice 的整句模式）——用户说话时实时看到转写
- **真 Barge-in**：VAD 与 AudioRecord 并行——TTS 播报中检测到用户说话 → 立即停播（延迟 <100ms）
- **分段 TTS**：长回复按句切分 → Kokoro 逐段生成 → AudioTrack 顺序播放（段间无缝）
- 删除 VoiceCascadeModule + CascadeVoiceProvider（D48 完全替代）；ChatEngineBridgeImpl 保留（桥接 LLM 不变）

**验证**：语音输入 → 流式逐字转写 → LLM 回复 → Kokoro 语音播报；播报中说话 → <100ms 打断；全程飞行模式可用（ASR+TTS 本地，LLM 需网络）

### Step 6.4：LiveSessionService + RealtimeState（R1/R5 修订）
**创建**：`app/src/main/java/com/zero/v2/app/background/LiveSessionService.kt`（**FGS 双类型：MICROPHONE | MEDIA_PROJECTION** + 常驻通知静音/结束快捷操作）、`services/src/main/java/com/zero/v2/services/realtime/RealtimeState.kt`（IDLE/LISTENING/THINKING/SPEAKING/INTERRUPTED/OBSERVING_SCREEN）
**修改**：`AndroidManifest.xml`（RECORD_AUDIO + 双 FGS 类型声明）
- 会话生命周期（D38：开启才申请，结束全释放）；后台保持语音（通知可见）
- **overlay 悬浮按钮由本服务直接管理**（R5：会话开始挂右侧边缘圆钮视图，结束移除——不设独立 OverlayService）
- 屏幕共享授权（R1）：收到 Consent 结果 → 升级 `startForeground(MICROPHONE | MEDIA_PROJECTION)` → 再建 projection；被杀不静默复活（R9，通知引导重开）
- 验证：开启 Live → 切后台 → 语音仍可唤醒；悬浮钮随会话出现/消失

### Step 6.5a：LiveSessionScreen（L2 全屏 Live UI）
**创建**：`app/src/main/java/com/zero/v2/app/ui/live/LiveSessionScreen.kt` + `LiveSessionViewModel.kt`
- 中央动态状态大圆（RealtimeState 驱动：脉动/渐变/波形三态动画）、实时转写滚动、底部功能条（屏幕阅读开关/静音/停止说话（R2 半双工）/挂断）
- **Live 设置区（R4）**：屏幕阅读包名白名单选择器（默认引导勾选文献/阅读类）
- **ChatScreen 入口圆钮（L1）+ 并发策略（R3）**：输入栏右侧圆形按钮，Live 中呼吸态；Live 期间输入栏禁用+"🎙 Live 进行中"提示
- 验证：状态流转可视化，挂断结束会话、返回键退后台不结束（转 L3）；Live 中 Chat 输入被禁用

### Step 6.6：ScreenCapture（按需截图 VL，D34 副通道；R1/R6 修订）
**创建**：`services/src/main/java/com/zero/v2/services/realtime/ScreenCaptureModule.kt` + `app/.../background/ProjectionConsentActivity.kt`（透明授权页）
- 授权流（R1 严格时序）：悬浮组/功能条「屏幕共享」（或通知 PendingIntent——R6 overlay 未授权备用路径）→ ConsentActivity 系统弹窗 → **Service 先升级双类型 FGS** → `getMediaProjection()` → token 持有
- MediaProjection + ImageReader **单帧按需**抓取（不持续投屏）；复用 VisionModule VL 分析；结束共享释放 token
- FC：`capture_screen_analyze(question)`（CONFIRM——截屏属敏感操作）
- 验证：游戏/图片页面 → FC 返回画面描述；停止共享后再调用 → 引导重新授权；Android 14+ 设备不抛 SecurityException

### Step 6.7：VocabularyFlow（文献场景闭环 FC）
**修改**：`SystemEventModule`（notepad 增强 `add_vocabulary(word, meaning, context)`）+ 新 FC `save_to_file(fileName, content, format)`（CONFIRM，走 WorkDirRepository）
- 验证（**Phase 6 判据场景**）：阅读英文文献 + Live 开启 → 口头"翻译这段" → "把 decadent 存进生词本" → 记事本/工作目录 `vocabulary.md` 均可查

### Step 6.8：云端 Realtime Provider（可选升级位，D46 第二级；v4.0 降级为可选）
> D48 本地方案已覆盖 Barge-in + 流式，云端 Realtime 降级为**可选增强**（用户经 ConfigField 切换）：
> - 更自然的对话韵律（端到端语音 token，无文本中间态）
> - 更低端到端延迟（ASR→LLM→TTS 单连接）
> - 但依赖网络 + 按量付费

**创建**（可后置）：`services/src/main/java/com/zero/v2/services/realtime/CloudRealtimeProvider.kt`（实现 RealtimeVoiceProvider）
- DashScope 流式语音 API（CosyVoice TTS + paraformer-realtime STT / qwen-omni 端到端，随当时可用 API 定）
- LocalVoiceProvider 仍为默认（离线 + 零成本优先）；ConfigField 切换（D17/D46）

### Step 6.9-6.10：权限中心 Live 项接入 + Phase 6 集成验证（v3.5 清单扩充）
- 权限中心增加：**麦克风 / SYSTEM_ALERT_WINDOW（悬浮按钮，可选增强——通知为主控制，overlay 未授权优雅降级）/ SAF 目录**（状态 + 直跳）；MediaProjection 为会话制弹窗不设常驻项
- **Kill/恢复契约（R9 升格）**：Live 被杀 → 不静默复活 → 通知引导用户回前台重开
- 端到端：完整文献阅读场景（入口圆钮 → Live → 后台 → 屏幕共享授权 → 词汇入库/写入文件）+ 打断 + 记忆联动
- **Live 场景矩阵**：锁屏/后台/旋转/折叠/蓝牙耳机/拔出耳机/来电话/他 App 播放声音/断网/模型超时/麦克风被占
- **屏幕场景矩阵**：Compose 常规页/WebView/PDF 阅读器/游戏(Canvas)/视频/图片/密码输入页/系统设置（无障碍树不可用页验证 VL 副通道与降级提示）
- **记忆场景矩阵**：中文/英文/混合/同义词/新旧事实冲突/否定句/疑问句

### Phase 6.5：全双工语音改造（D53，2026-08-26 立项——v6.10-v6.13 四轮打断横跳教训）

> 根因裁定：OEM `VOICE_COMMUNICATION` 处理链是黑盒（实测同一机型既压制说话期用户语音又漏回声），
> 在被污染信号上做 VAD/ASR/文本启发式判别必然横跳。业界正解（WebRTC 通话 / LiveKit / Pipecat / Wyoming）：
> **回声在音频层用 far-end 参考信号 AEC 根治**——我们写入扬声器的每块 PCM 就是完美参考。

#### Step 6.11：参考信号 AEC（Phase 1，根治打断 + 精度）
**创建** `services/realtime/SoftwareAec.kt`；**修改** `SpeechSynthesisModule` + `LocalVoiceProvider`
- `SoftwareAec`：纯 Kotlin NLMS 自适应滤波器（尾长 240ms/3840 taps，μ=0.3 保守步长 + 远端静默期冻结更新）
  + NLP 残余抑制（远端能量占比软门限）+ 任意采样率线性重采样至 16k（MiMo 24k/Melo 44.1k 通吃）
- far-end 接线：`SpeechSynthesisModule.farEndListener` 回调——MiMo/Kokoro/Melo **每块写入 AudioTrack 的 PCM 同步喂参考**
  （系统 TTS 无 PCM 访问 → 该引擎下退回 v6.13 文本判别，文档化限制）
- 音源 **`VOICE_COMMUNICATION → MIC`**（绕开 OEM 有损链，ASR 精度同步受益；`mic_source_fallback` 机制废止）
- 处理链：`MIC(原始) + far参考 → AEC → 干净信号 → VAD + ASR 同源`
- 打断规则回归经典简单判定：AEC 后 **Silero VAD 连续 3 帧**（SPEAKING 期间）即触发；
  v6.13 ASR partial + 播报文本回声判别**降级为二道防线**（防 AEC 残余，保留）
- 埋点：`aec_erle: XdB`（回声消除量，每 ~5s 采样）——设备实测 AEC 质量的量化指标
- 验证：播报期安静不自打断；说话秒打断（trace `barge_in_triggered: vad`）；`aec_erle ≥ 10dB`；空闲识别精度对比提升

#### Step 6.12：分句流式管线（Phase 2，Gemini 级首响延迟）
**修改** `ChatEngineBridgeImpl` + `SpeechSynthesisModule` + `LocalVoiceProvider`
- 现状：LLM 全文生成完 → TTS（首音延迟 = 全文生成 3-10s）
- 改造（Pipecat 模式）：**LLM token 流 → 标点断句缓冲 → 句级合成入播放队列**；
  `speak` 增量接口（首句即播，后续句边合成边接续）；打断只 flush 未播句
- 验证：首音延迟 ≤2s（trace `tts_first` vs `turn_start` 差值）；长回复播报连续无句间空洞；打断丢弃未播句

#### Step 6.13：可选增强（Phase 3，按实测数据决定）
- 语义判停（LiveKit turn detector 模式小模型，替代固定静默端点）
- 蓝牙耳机检测 → 物理无回声场景自动关 AEC/文本判别（省电省算）
- 蓝牙/扬声器双场景自动切换策略

---

## 四、Phase 8：MCP 生态与自主能力（8.1–8.8，D57 前置扩容）

> 依据：D57（2026-08-27 用户决策）——P8 前置、P7 顺延；MCP 客户端**自研轻量**（OkHttp + org.json，零新依赖栈）；
> 办公文件**纯本地**（POI，含 +30MB 代价与内存边界）；浏览器操控改判："文件自主下载 + 网络资源爬取"的本质是
> HTTP + HTML 解析，无需浏览器自动化——Playwright/Edge/PC 远程操控**移出计划**。
> 探索结论（能力边界）：Android 无 stdio（不能跑 Node 服务器）→ **Streamable HTTP 为唯一本机传输**；
> 高德官方 MCP 托管端点支持 Streamable HTTP 可直连；Jina Reader 免费层（匿名 20 RPM / 免费 Key 200 RPM）
> 服务端渲染 JS 动态页，取代离屏 WebView 方案。

#### Step 8.1：WebFetchModule（网络资源自主获取——最易，独立零协议风险）
**创建** `services/module/WebFetchModule.kt`（IZeroModule id=web_fetch，INSTRUMENT）；**依赖** +jsoup（~600KB）
- FC `download_file(url, file_name)`（AUTO）：OkHttp 流式下载 → **固定落盘工作目录 downloads/ 子目录**
  （fileName 仅相对名，净化路径穿越/绝对路径/非法字符——LLM 无路径自由度）；单文件上限 ConfigField（默认 500MB）；
  危险类型（.apk/.so/.dex）CONFIRM；重名自动序号；未设工作目录 → 引导文案拒绝；`fetch_download_done` 埋点
- FC `fetch_webpage(url)`（AUTO）**双引擎降级**：① 本地 jsoup（快/隐私/静态页）→ ② 正文空/过短（<200 字，疑似
  JS 渲染页）→ Jina Reader（`https://r.jina.ai/<url>`，Key 走 SecureKeyStore 可选）→ ③ 失败返回文案，LLM 降级
  web_search。返回结构化：正文 Markdown / 链接清单 / 图片媒体清单（供 download_file 联动）
- Config：下载上限 + Jina Key（SECRET）；仅 HTTPS（targetSdk 35 明文限制）
- 验证：说"把这个 PDF 下载到工作目录"→ 文件出现在 WorkFilesScreen；"读一下这个网页"→ 正文 + 链接返回；
  JS 动态页走 Jina（trace `fetch_engine: jina`）

#### Step 8.2：MCP 协议层（自研轻量客户端）
**创建** `services/mcp/McpTransport.kt` + `services/mcp/McpClient.kt`
- Streamable HTTP 传输：POST JSON-RPC 到单端点；响应两种形态兼容（application-json 单响应 / text-event-stream
  SSE 流逐事件解析——**MimoTtsClient/DeepSeek 适配器已验证同模式**）；`Mcp-Session-Id` 会话头管理
- 协议三调用：`initialize`（握手/能力协商）→ `tools/list`（发现）→ `tools/call`（执行，参数 JSON Schema 透传）
- OkHttp + org.json（现有依赖，零新增）；withTimeout 超时 + 错误文案化；`mcp_proto` 埋点
- 边界声明：不做 stdio（Android 无子进程）；不做客户端初始化的 GET SSE 通道（仅客户端发起调用）
- 验证：单测式验证页/调试 FC 对高德官方端点完成 initialize + tools/list（返回工具清单）

#### Step 8.3：McpClientModule（模块化 + 动态 FC + 安全）
**创建** `services/mcp/McpClientModule.kt`（IZeroModule id=mcp_client）+ `app/ui/settings/McpServersScreen.kt`
- 服务器注册表：专用存储（服务器列表 JSON：name/url/enabled/权限策略）——**不扭曲扁平 ConfigField 体系**；
  鉴权 Key 走 SecureKeyStore（buildApiKeyFields 同模式）
- **工具动态注册**：tools/list 结果 → JSON Schema 透传 `FunctionDefinition.parameters`（Map 结构直通，
  WebSearchModule 已验证）→ FunctionRegistryModule 注册为 `mcp_<server>_<tool>`
- 权限（D9/D24）：默认 CONFIRM，服务器级可配 AUTO 白名单；域名白名单校验
- McpServersScreen：添加/启停/工具清单/权限标签（复用 ModuleDetail FC 卡片交互模式）+ 路由 + 设置入口
- 生命周期：onAwake 后台发现（网络不可用优雅跳过）；D51 启动恢复；工具清单刷新 FC
- 验证：添加服务器 → 工具自动出现在 FC 清单（模块详情可见）→ LLM 可调用

#### Step 8.4：高德地图接入（首个真实 MCP 消费者）
- 配置：官方托管 Streamable HTTP 端点 + 高德 Key（[lbs.amap.com](https://lbs.amap.com/api/mcp-server/gettingstarted) 申请）
- 12 项工具自动入 FC：地理编码/逆地理/POI 搜索/驾车步行公交路线规划/天气/静态地图等
- 手机端零 Node 依赖；端点失败 → 错误文案，LLM 降级 web_search
- 验证：说"帮我规划从 A 到 B 的驾车路线"→ `mcp_amap_*` 工具调用 → 路线文本回复（Live 语音播报）

#### Step 8.5：POI 依赖 Spike（风险前置验证，半天）
- 空引入 poi-ooxml-lite + exclusions 配方（xmlbeans ART 验证/javax 冲突）→ 编译 → 真机读一个 docx
- **通过判据**：build 成功 + 真机 extract 文本正确 + 无运行时类验证崩溃
- **失败退路**：docx/xlsx 手写 zip+xml 直改（保真度受限零依赖）——8.6/8.7 按退路降级
- APK 体积记录（预期 +30MB，128→160MB 级）

#### Step 8.6：OfficeFilesModule 读 + 生成（轻能力，Spike 通过后）
**创建** `services/module/OfficeFilesModule.kt`（IZeroModule id=office_files）
- FC `read_office_file(file_name)`（AUTO）：工作目录内 docx/xlsx/pptx 文本抽取（POI XWPF/XSSF/XSLF），
  ≤5MB/≤5000 行边界；PDF 复用 PdfBox-Android（读/合并/拆分，新依赖 ~5MB 成熟移植）
- FC `create_document(file_name, content, format)`（CONFIRM）：docx（标题/段落/列表）/xlsx（表头+行数据）
  从零生成 → 工作目录；PDF 已有 PdfDocument 路径（6.1）
- 验证：读取真实 docx 返回文本；"生成一份会议纪要 docx"→ 文件出现在工作目录可应用外打开

#### Step 8.7：Office 编辑三阶段（重能力，风险递增）
- 7a docx 段落/文本替换（find&replace 语义，CONFIRM）
- 7b xlsx 单元格读写（`excel_get/set(file, sheet, cell)`，CONFIRM；公式保留边界声明）
- 7c pptx：读 + 从零生成（标题页/内容页/项目符号）——**保真编辑视 8.5 Spike 结果决定，不承诺**
- 边界：编辑前后文件哈希记录（trace 可审计）；大文件拒绝（沿用 5MB 边界）
- 验证："把报告里的'2025'全部替换为'2026'"→ docx 保真编辑；"在表格 B3 写入合计"→ xlsx 单元格更新

#### Step 8.8：P8 集成验证 + 收官
- 场景闭环：搜索 → fetch_webpage 读页 → download_file 落盘 → read_office_file 解析 → 编辑/生成 → LLM 总结播报（全语音链路）
- 高德 12 工具实测；MCP 服务器管理 UI 实测；权限策略审计（CONFIRM/AUTO 白名单）；Key 管理审计
- APK 体积/内存/耗电记录；文档更新（DESIGN 能力矩阵 + STATUS 收官）

---

## 五、Phase 7：Platform Gateway + Telegram（7.1–7.9，D57 顺延至 P8 后）

> GPT 方案采纳（D37）：Agent Core 不知道消息来自哪个平台。

```
Agent Core ←→ ChatBus/Conversation ←→ MessageRouter ←→ PlatformGateway
                                                        ├─ TelegramAdapter（本轮）
                                                        └─ OneBot11Adapter（QQ，后续）
```

### 统一消息模型（7.1）
**创建**：`core/common/src/main/java/com/zero/v2/core/common/PlatformMessage.kt`
- `PlatformMessage`（platform/accountId/conversationId/senderId/content segments/timestamp/replyTo）+ `OutgoingMessage` + `Platform` 枚举（INTERNAL/TELEGRAM/QQ）+ `MessagePlatform` 接口（`sendMessage`）

### Steps 7.2-7.9
- 7.2 `MessageRouter`（framework/chat）：PlatformMessage → ConversationResolver（平台会话 ↔ Zero Conversation 映射，沿用现有持久化）→ ChatBotModule；回复回流 OutgoingMessage
- 7.3 `TelegramAdapter`（services/platform/）：官方 Bot API long polling（getUpdates）、sendMessage；Token 走 SecureKeyStore（SECRET ConfigField）
- 7.4 附件映射：Telegram photo/document → `Attachment`（复用 Vision/文件链路）
- 7.5 白名单与触发（用户主权：仅白名单 chatId 可用；群聊仅 @触发或关键词唤醒）
- 7.6 后台保活：接入现有 Accessibility tick 体系的轮询 Job（AgentJob）+ 网络变化重连
- 7.7 PlatformSettingsScreen（平台账号管理 UI）
- 7.8 权限中心无新增（纯网络）；通知推送（收到消息 → 系统通知，可选）
- 7.9 集成验证：Telegram 发消息 → Zero 人格回复 → 记忆/图谱联动 → 长稳 48h

### 附录：QQ OneBot 11 Adapter（后续启用，不在本轮）
按 GPT 文件方案保留：Android 仅 OneBot 11 WS Client（Token 鉴权/心跳/重连），QQ 登录层跑用户自备电脑/NAS/VPS（NapCat/LLBot）；私聊/群聊/@触发/媒体映射与 Telegram Adapter 同构（同实现 MessagePlatform）。

---

## 六、Phase 9：系统级感知与自主运行（9.1-9.5，2026-08-29 立项 D58）

> 审计裁定：6 项中 1 项已通（时间注入）、5 项需实施。依赖关系：自主运行(⑤)需要通知/位置/日历数据源。

#### Step 9.1：通知读取打通（P0，~40 行）
**修改** `services/module/NotificationModule.kt`
- init 中收集 SystemBus.onNotificationPosted → 环形缓冲（最近 50 条，去重）
- read_notifications FC 从 buffer 读取（支持 count / filter_app 参数）
- 验证：收到真实通知后 LLM 可通过 FC 读取到内容

#### Step 9.2：位置获取（P1，~80 行）
**创建** `services/module/LocationModule.kt`（IZeroModule id=location）
- LocationManager.getLastKnownLocation()（零功耗缓存）→ 降级 requestLocationUpdates（GPS/网络，10s 超时）
- FC `get_location()`（AUTO）→ 返回 lat/lng/精度；与高德 MCP 逆地理编码协同
- Manifest + ACCESS_COARSE/FINE_LOCATION + 权限中心入口

#### Step 9.3：日历集成（P2，~120 行）
**创建** `services/module/CalendarModule.kt`（IZeroModule id=calendar）
- ContentResolver 操作 content://com.android.calendar/events
- FC calendar_list_events(AUTO) / calendar_create_event(CONFIRM) / calendar_delete_event(CONFIRM)
- Manifest + READ/WRITE_CALENDAR 运行时权限

#### Step 9.4：系统闹钟（P2，~50 行）
**修改** `services/module/SystemEventModule.kt`（或新 AlarmModule）
- AlarmClock.ACTION_SET_ALARM Intent（标准 API，兼容三星/Google/小米）
- FC `set_alarm(hour, minute, message)`（CONFIRM）
- Manifest + SET_ALARM 权限

#### Step 9.5：Agent 自主运行（P1，~100 行）
**创建** `services/agent/AutonomousThinkingJob.kt`（实现 AgentJob）
- tick 触发 → 构造 context-aware prompt（时间 + 最近通知 + 日程 + 记忆摘要）
- LLM 通过 FC 自主决策（读通知/查日程/推送提醒/调用工具）
- AgentBehaviorScreen ALWAYS_ON 模式控制启停
- 验证：ALWAYS_ON 开启后 Zero 定期自主思考并可能推送通知

---

## 七、P10 多 Agent（原 P9 顺延）

AgentOrchestrator / Manager-Worker / 跨 Agent Memory —— 远期保留。

---

## 七、风险与缓解

| 风险 | 缓解 |
|---|---|
| canRetrieveWindowContent=true 隐私边界扩大 | D39：权限中心明示 + 包名白名单（6.5a 设置区管理，R4）+ 仅 Live/FC 调用时读取；**XML 变更需重开无障碍（R8 提示）** |
| 韩版/国产 ROM 特殊授权页不可达 | 权限中心 Intent 直跳 + 状态自检兜底文案 |
| 级联语音体验不达预期（回声/OEM STT 质量，R2/R7） | v1 半双工防自听；D33 分阶段，第二级云端 Realtime 为终极兜底 |
| Telegram 国内可达性 | Adapter 层代理配置（host/port 走 ConfigField） |
| MediaProjection 每次授权繁琐 | 仅按需单帧（6.6），不做持续投屏，多数文献场景走无障碍文本无需投屏 |
| Android 14+ FGS/权限时序（R1/R9） | 双类型 FGS 先升级后建 projection；被杀不静默复活，通知引导重开 |
