# Zero v2 — Assistant 化与语音唤醒探索报告

> **来源存档** ｜ 2026-09-13 ｜ 状态：⚠ **已并入 KB 轨（裁-5 / 2026-09-14）**——设计权威 = `ZeroV2-知识库设计.md` §十八；步骤 = `ZeroV2-知识库落地实施计划.md` Phase KB3（KB3.1–KB3.4）。本文保留作底稿（市场链接/AB 审计细节/裁定 A–D 原始记录），与 KB 两文档冲突时**以 KB 文档为准**。
> 问题：将 Zero 设计为 Android **assistant 应用**（系统级数字助手）并提供 **Gemini 式语音唤醒**的可行性。
> 方法：Android 平台 API/权限约束实证 + Zero 基座代码盘点 + 生态方案调研（sherpa-onnx KWS/SoundTrigger）。

---

## 一、结论先行

| 路径 | 可行性 | 一句话结论 |
|---|---|---|
| **A. Assistant 应用化**（手势/电源键全局唤起，成为系统默认数字助手） | ✅ **高** | Android 标准 API（`VoiceInteractionService` + 默认助手角色），Zero 语音会话全链现成，壳层 2–3 文件增量；主要不确定性 = ROM 对默认助手替换的支持度（裁-D：三星外版，前景良好——R1 四点 L3 实测） |
| **B. 语音唤醒（默认"你好，大帝"，可自定义）** | ⚠ **分层** | **亮屏/前台常驻唤醒：完全可行**（前台服务 + sherpa-onnx KWS 本地识别，与 Zero 现有 sherpa 生态同栈）；**息屏 always-on：第三方 app 不可达**（平台特权墙，非工程问题） |

**核心判断**：Gemini/小爱的"息屏随叫随应"靠**系统特权**（DSP 低功耗唤醒 + `CAPTURE_AUDIO_HOTWORD` signature 级权限），第三方 app 在任何 Android 版本上都拿不到。第三方能做到的天花板 = ChatGPT app 的形态：**前台/亮屏常驻唤醒 + 系统手势/电源键唤起**。Zero 按此天花板设计即为最优解，且 Zero 的本地栈（sherpa 全家 + 用户 llama.cpp 节点）反而比 Gemini 更隐私。

---

## 二、对标能力矩阵（Gemini vs Zero 可达）

| 能力 | Gemini（系统特权） | Zero 可达 | 路径/说明 |
|---|---|---|---|
| 手势（对角线上滑）/电源键长按唤起 | ✅ | ✅ | `VoiceInteractionService` + 默认助手角色（Android 10+ 设置项 / Android 12+ 电源键 handoff） |
| **息屏 always-on 唤醒** | ✅ DSP 硬件路 | ❌ **不可达** | `CAPTURE_AUDIO_HOTWORD` = signature\|privileged；且 Android 9+ 屏闭 idle 后系统对非特权 app 停供麦克风（喂静音） |
| 亮屏/使用中唤醒 | ✅ | ✅ | 常驻前台服务（通知可见）+ 软件 KWS——屏亮期间不受限 |
| 锁屏唤起 | ✅ | ⚠ ROM 相关 | Android 12 起锁屏手势触发收紧；国产 ROM 各异——L3 实测，不承诺 |
| Overlay 半屏 UI | ✅ | ✅ | `VoiceInteractionSession` 自定义布局（或主题化 Activity） |
| 持续多轮语音对话 | ✅ | ✅ **已有** | LiveSessionService 全链（VAD/ASR/sherpa TTS/RealtimeState） |
| 后台自主感知 | ✅ | ✅ **已有** | TriggerHub 8 源 + AutonomousThinkingJob——assistant "大脑"已存在 |
| 多模态（看屏/识图） | ✅ | ✅ 已有 | VisionModule + ScreenCapture |
| 本地推理 | 部分 | ✅ **优势项** | 用户 llama.cpp 节点（:8080/v1 经 LlmProfile 零代码）+ sherpa 全本地 |

---

## 三、平台约束实证（本探索的地基事实）

### 3.1 Assistant 角色是开放标准（无特权墙）

- 成为系统数字助手 = 实现 [`android.service.voice.VoiceInteractionService`](https://source.android.com/docs/automotive/voice/voice_interaction_guide)（manifest service 声明 + intent-filter + 组件属性 `android:permission="android.permission.BIND_VOICE_INTERACTION"`——该权限约束**绑定方**（系统），app 自身无需申请任何权限）+ `VoiceInteractionSessionService`（会话 UI）。
- 用户在 **设置 → 应用 → 默认应用 → 数字助手应用** 选择 Zero（Android 10+ 通用入口；app 侧可用 `RoleManager`/`ACTION_ASSIST` 引导跳转）。**无需任何特殊权限**。
- 手势/电源键触发 → 系统绑定当前默认助手 → `onPrepareSession` → Session `show()`——与 Gemini 同一入口机制（Google 自家 app 也走此路，只是多了特权唤醒词）。
- **入口层无 BAL 阻塞（AB-E）**：Session 内容展示于 `VoiceInteractionSession` 自带**系统窗口**——免 Android 10+ 后台启动 Activity 限制；语音链启动经 `LiveSessionService`（服务启动本就不受限）。

### 3.2 息屏常驻唤醒的特权墙（不可绕越）

- DSP 低功耗唤醒 = SoundTrigger 通道，需 [`CAPTURE_AUDIO_HOTWORD`](https://source.android.com/docs/core/audio/sound-trigger)——**signature|privileged**，仅系统/特权预装可得（[车载 VIA 文档](https://source.android.com/docs/automotive/voice/voice_interaction_guide/app_development)明示 VIA 须持此权限+`HOTWORD` 音源）；Shizuku/Termux/adb 均无法授予（非 user-grantable）。
- 软件路（前台服务 + AudioRecord 流式 KWS）在**亮屏/非 idle** 完全合法可用；但 AOSP 自 Android 9 起，设备进入 idle（通常屏闭数分钟）后对非特权 app **停供麦克风数据**——这是 ChatGPT 等所有第三方 assistant 的共同边界，非 Zero 特有。
- 结论落档：**"息屏唤醒"不设计、不承诺**；文档与 UI 明示"亮屏期间可唤醒"。

### 3.3 软件 KWS 选型：sherpa-onnx（生态同栈）

- [sherpa-onnx 官方支持开放词表 keyword spotting](https://k2-fsa.github.io/sherpa/onnx/kws/index.html)（自定义关键词、zipformer 流式、CPU 实时、支持中文）——Zero 已用 sherpa 栈（TTS/ASR），**同一生态零新增框架**；模型资产放工作目录或 assets。
- **模型资产路径（开放项 AB-C，AS2 定）**：assets 打包（增 APK 体积）/ 工作目录放置（用户手动放）/ 首启下载——三选一，倾向对齐 Zero 既有 sherpa 模型资产管理方式。
- **中文唤醒词→KWS 词序列（开放项 AB-B，AS3 定）**：sherpa-onnx KWS 关键词以拼音/token 序列提供（非直接汉字输入）——默认词"你好，大帝"可硬预置序列（零依赖）；**自定义 UI** 需中文→拼音转换：模型自带方案 / 内置轻量拼音库（新依赖，R5 审）/ 用户直接输拼音——三选一，AS3 定。
- 备选：openWakeWord（ONNX）、Porcupine（商用授权）——非必要不引入。

---

## 四、Zero 基座盘点（现成 vs 缺口）

**现成（直接复用）**：
- 语音会话全链：`LiveSessionService`（前台 + `FOREGROUND_SERVICE_MICROPHONE`）+ `VoiceActivityModule`(VAD) + `speech_asr` + sherpa TTS + `LocalVoiceProvider` RealtimeState + `ChatEngineBridge`
- 会话协调：`SystemBus.onVoiceActive`（音乐让位已接线——唤醒起语音会话时音乐自动暂停/恢复，**白捡**）
- 大脑：TriggerHub 自主触发 + AutonomousThinkingJob + BudgetGuard（assistant 语义的"能不能/何时做"已治理）
- 设备动作：Termux/Shizuku 通道（assistant 场景"帮我开 App/设闹钟"的动作执行面已有）
- 本地推理：用户 llama.cpp 节点经 LlmProfile 已接——唤醒→本地低延迟应答可行

**缺口（即本轨工作项）**：
1. `VoiceInteractionService` 壳（手势/电源键 → 起 Live 语音会话或对话 overlay）——纯增量
2. 常驻唤醒服务（前台 + sherpa-onnx KWS 流式 → 命中起 Live 会话）+ 开关/省电治理
3. 默认助手角色引导（设置跳转 + 说明页）

---

## 五、方案蓝图（已裁定立轨 · 三组件 · 全部增量零基线改动）

```
手势/电源键 ──► ZeroAssistantService（VoiceInteractionService 壳，app 层）
                    │ onPrepareSession/show（系统窗口，免 BAL）
                    ▼
              LiveSessionService（语音模式）／对话 overlay（文本模式）── 既有全链
"你好，大帝" ──► HotwordService（常驻前台服务·可选开关·亮屏有效·Live 期间暂停 AB-A）
                    │ sherpa-onnx KWS 流式命中
                    ▼
              起 Live 语音会话（onVoiceActive → 音乐让位自动协同）
桌面图标/快捷方式 ──► 对话页（既有）
```

- **不触基线**：三 Tab/对话内核/模块体系/音乐轨零改动——入口层纯增量；assistant 触发经既有 Live 会话入口（音乐轨 MD17"单入口"同精神：会话启动统一经 LiveSessionService）。
- **监听治理（AB-A）**：Live 语音会话期间 Hotword **暂停监听**（订阅 `SystemBus.onVoiceActive`——与音乐让位同款协同，防 TTS 播放自唤醒/回声）；HotwordService 前台服务声明 `foregroundServiceType="microphone"`（Android 11+ 前台麦克风类型要求，LiveSessionService 同款）。
- **依赖核验（AB-D）**：KWS 复用 sherpa-onnx AAR——AS 轨设计时核验 ASR/TTS 既有依赖树（大概率已在、零新增；若 ASR 栈非 sherpa-onnx 变体，则按 R5 显式声明新增）。
- **省电治理**（唤醒服务的伦理边界）：默认关；三档策略（仅充电时常驻/全时段常驻/关闭）+ 通知栏常驻（透明性）+ 电池优化豁免引导（侧载 app 无 Play 政策约束）。

---

## 六、分期建议（AS 轨草案 · 已裁定置 KB 后执行）

| Step | 内容 | 文件量 |
|---|---|---|
| AS1 | Assistant 角色壳：manifest 声明 + `ZeroAssistantService`(VoiceInteractionService+Session) + 角色引导页 → 手势/电源键唤起 Live 语音会话 | 2–3 |
| AS2 | 唤醒词常驻（亮屏）：`HotwordService` 前台服务 + sherpa-onnx KWS 接入（模型资产放置）+ 命中→Live 会话 + 开关 ConfigField | 2–3 |
| AS3 | 打磨：省电三档策略 / 唤醒词自定义入口 / 误触率阈值调参 / 锁屏与 ROM 实测矩阵 | 1–2 |

排序（✅ 裁-A 已裁定）：**AS 轨置 KB 轨后执行**——KB0.2–KB2.1 完成后展开 AS 三件套（设计/落地计划/提示词）；AS1 与 KB 零耦合，用户点名可提前。**（⚠ 本行已被裁-5 取代：2026-09-14 并入 KB 轨 Phase KB3，非独立轨——以 KB 两文档为准）**

---

## 七、风险与 L3 实测清单

| # | 风险/未知 | 处置 |
|---|---|---|
| R1 | ROM 默认助手替换支持度 | ✅ **裁-D 已裁定：三星外版（One UI 国际版）**——前景良好：One UI「设置→应用→默认应用→数字助手应用」支持选第三方（ChatGPT 等先例）；全屏手势底部角落上滑可触发默认助手；外版带完整 Google 框架、无国行特化。L3 实测点：①默认助手能否选到 Zero ②角落手势是否触发 ③侧键（电源键）长按可配置性（One UI 侧键默认 Bixby/关机菜单，可在 设置→高级功能→侧键 调整）④Bixby 预装占位需手动替换 |
| R2 | 锁屏唤起：Android 12 起收紧，各 ROM 不一 | 不承诺；实测记录行为 |
| R3 | 常驻监听耗电（软件 KWS CPU 占用） | 三档策略 + 仅充电默认 + KWS 模型轻量（zipformer-tiny 档） |
| R4 | 误唤醒/漏唤醒 | sherpa-onnx KWS 阈值调参 + 唤醒确认音；评估用误触/天指标 |
| R5 | 麦克风隐私感知（常驻监听） | 前台通知明示 + 开关默认关 + 本地模型（音频不出机）——与 Zero 隐私叙事一致 |
| R6 | assistant 触发与 Live 会话并发（手势触发时会话已在跑） | 复用 Live 单会话语义（RealtimeState 已单会话） |
| R7 | **TTS 播放自唤醒/回声**（Hotword 与 Live 会话并发监听） | Live 会话期间 Hotword 暂停（`onVoiceActive` 协同，AB-A）——与音乐让位同模式；实现于 AS2 |

---

## 八、裁定记录（2026-09-13 用户四裁定）

| # | 项 | 裁定 | 落档 |
|---|---|---|---|
| 裁-A | 是否立 AS 轨 | ✅ **建立 AS 轨，置后**（KB 轨收尾后执行）——**后被裁-5（2026-09-14）取代为并入 KB 轨 Phase KB3** | §六排序；三件套待展开（均已被并入取代） |
| 裁-B | 唤醒词 | ✅ **自定义，默认"你好，大帝"**——唤醒词是用户偏好非约定常数，入 ConfigField(TEXT)（与 MD15 目录常量律相反方向，合规）；sherpa-onnx KWS 开放词表支持中文自定义文本，热词自定义 UI 留 AS3 |
| 裁-C | 常驻策略默认值 | ✅ **同意**：默认关，设置页三档（关 / 仅充电常驻 / 全时段常驻）+ 通知栏常驻透明 + 电池优化豁免引导 |
| 裁-D | 机型/ROM | ✅ **三星外版（One UI 国际版）** | R1 矩阵已预填（前景良好，四点 L3 实测） |

---

## 九、终审交叉审计表（AB 表 · 2026-09-13 · 逐项核实后修订同步本文）

> 审计维度：文档引用的 Zero 代码事实、Android 平台事实（上轮已检索验证）、入口层设计缺口、文档内部一致性。**结论：无 P0 设计阻塞、无落地阻塞——路径 A/B 分层结论维持；5 项补强已修订入文。**

| # | 级 | 审计发现 | 裁定与落点 |
|---|---|---|---|
| AB-A | 🟠设计 | Hotword 与 Live 语音会话并发监听未处置——TTS 播放声被 KWS 听到 = **自唤醒/回声**风险 | ✅ 已补：Live 会话期间 Hotword 暂停（`onVoiceActive` 协同——音乐让位同款现成机制）；R7 落档、§五"监听治理" |
| AB-B | 🟡开放项 | sherpa-onnx KWS 关键词以拼音/token 序列提供（非汉字直输）——**唤醒词自定义 UI** 依赖中文→拼音转换路径 | ✅ 已补 §3.3：默认词"你好，大帝"硬预置序列（零依赖）；自定义三选一（模型自带/轻量拼音库（新依赖 R5 审）/用户输拼音）AS3 定 |
| AB-C | 🟡开放项 | KWS 模型资产（zipformer 中文，数 MB–数十 MB）放置路径未定 | ✅ 已补 §3.3：assets/工作目录/首启下载三选一，对齐 Zero 既有 sherpa 模型管理方式，AS2 定 |
| AB-D | 🟡核验项 | "同一生态零新增框架"未核实 AAR 依赖树（KWS 复用 sherpa-onnx 的前提 = ASR/TTS 依赖即该 AAR） | ✅ 已补 §五"依赖核验"：AS 轨设计时核验 gradle 依赖树；不成立则 R5 显式声明 |
| AB-E | 🟢落档 | ① `BIND_VOICE_INTERACTION` 表述不精确（是 service 组件属性约束绑定方，非 app 申请权限）；② 手势触发后的启动路径未论证 BAL（Android 10+ 后台启动 Activity 限制） | ✅ 已补 §3.1：表述精确化 + "Session 自带系统窗口免 BAL、服务启动本就不受限"——入口层无 BAL 阻塞 |

---

## 附：来源

- [Android Sound Trigger（AOSP 源文档）](https://source.android.com/docs/core/audio/sound-trigger) ｜ [Voice Interaction 指南（AOSP）](https://source.android.com/docs/automotive/voice/voice_interaction_guide) ｜ [VIA 音频捕获须持 CAPTURE_AUDIO_HOTWORD（AOSP 车载文档）](https://source.android.com/docs/automotive/voice/voice_interaction_guide/app_development)
- [sherpa-onnx Keyword Spotting（官方文档：开放词表/自定义关键词/流式）](https://k2-fsa.github.io/sherpa/onnx/kws/index.html)
- 第三方唤醒词 app 实存先例（前台服务软件路）：[HAwake（r/homeassistant）](https://www.reddit.com/r/homeassistant/comments/1qu2bmb/hawake_a_privacyfirst_android_voice_assist_wake/)

---

*—— 天花板是平台划的：特权墙内的息屏唤醒不做；墙外的 assistant 体验，Zero 的基座已备好八成。*
