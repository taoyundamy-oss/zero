# Claude Code 自驱留痕（S2R）+ 云服演进（E1/E2）开发启动提示词

> 将此提示词粘贴到 Claude Code 新会话中，即可继续 Zero V2 **S2R 轨（自驱消息留痕重构）+ E 轨（云服极限精简 + Termux 动态模块）**开发。
> 体例沿用 `ZeroV2-开发前规范化开发提示词.md` 五版本结构（A 完整启动 / B 精简连续 / C Build 修复 / D 阶段审查 / E 单文件）。
> 权威文档：`ZeroV2-自驱消息留痕重构设计方案.md`（v5 终版·S2R 决策与步骤）+ `ZeroV2-云服架构演进设计方案.md`（v2.1·E 轨决策与步骤）> 本提示词（流程）。
> 建立日期：2026-09-19（两设计定稿同日）。

---

## 版本 A：完整启动（首次会话 / 中断后恢复）

```
你正在开发 Zero V2 的 S2R 轨（自驱消息留痕重构）与 E 轨（云服极限精简 + Termux 动态模块）。项目位于 D:\ZeroV2。

## 第一步：加载上下文（只读，不修改任何文件）

依次读取：
1. `D:\ZeroV2\docs\refined\ZeroV2-自驱消息留痕重构设计方案.md` §三 Steps 表（含状态列）—— 确认 S2R 当前 Step
2. `D:\ZeroV2\docs\refined\ZeroV2-云服架构演进设计方案.md` §五 Steps 表（含状态列）—— 确认 E1/E2 当前 Step
3. 两文档的 §一 裁定回填 + §二/§2.2 设计正文（决策语义：一律打断 R6=A / 波次节奏三计数 / 复用用户消息注入+硬提示词头 / ≪sys≫ 剥离 / Room v21）
4. 标杆文件（按当前 Step 选读，先读后写确认签名与行号）：
   - AgentRuntime.kt（services/.../agent/）—— 报点循环/onChatSent collect(:120-123)/tick 后计数(:152)/calculateNextInterval(:199)
   - AutonomousThinkingJob.kt（services/.../agent/）—— executeTurn/触发队列 Conflated/buildPrompt 五路路由
   - ChatBotModule.kt（framework/chat/）—— sendMessage(:140-230)：userMsg 构造/onChatSent emit(:164)/D68 中断守卫(:220-229)
   - ChatViewModel.kt（app/.../ui/）—— systemBubbles(:55)/projectToUiMessages(:425)/persistConversationTurn(:455)
   - ChatEvents.kt（core/common/）—— ChatMessage/MessageSource/TurnContext
   - MessageEntity.kt + ConversationRepository（core/data + app/data）—— 持久化列与 appendMessage
   - ChatEngineBridge.kt（services/.../realtime）+ ChatEngineBridgeImpl.kt（app/.../di）—— 桥缝先例（TtsWavSink 同款）
   - TriggerHub.kt —— fireTraced/emitSystemEvent 旁路(:549)/SSE/cloudTickCheck
   - E 轨另读：zero-cloud/ 目录结构（gateway/tools、docker-compose.yml、nginx/）、M 轨手册（模块化模拟器自测手册）、模块化落地实施计划 §CodeDev/动态模块

## 第二步：执行当前 Step

严格按对应设计方案该 Step 定义执行；轨间顺序约束：**E1.2 的手机侧改动必须在 S2R.3 完成之后**（同文件避双改）。
S2R 内部串行：S2R.1→2→3→4→5→6；E1 内部串行：E1.1→E1.2→E1.3→E1.4（E1.3 仅在 E1.2 全链绿后）；E2 可与 E1 并行。

## 行为规则（沿用既有纪律）

1. **三类授权修改**：实现已批准设计（两设计方案为准）/ 修复设计中列出的问题 / 修复真实 bug。三禁：未授权重构、重设计已封板决策、因简化改边界与基线。
2. Android **≤3 文件/Step**；Room 迁移组例外（Entity/Dao/DB/DatabaseModule/Repository 同 commit，X9）；云服 **≤1 逻辑单元/Step**。
3. **先读后写**；标杆对齐（安卓 Google 规范 + 周边代码风格）。
4. **构建自修复**：Android 改动后跑 `./_build_apk.bat`，读编译输出自行修复；云服从 Workbench CLI 部署验证（PowerShell 上传，E5/E6 坑）。
5. **关键路径埋 AgentTrace**：turn_preempted_by / note_turn_activity / tick_interval_changed / persist_turn / im_probe_*；**静默失败必留痕（E28 铁律）**。
6. **openclaw 受阻纪律（用户裁定）**：落地遇到问题**及时停下向用户反馈**（附实测证据与可选路径），不私自更改设计、不自定回退方案。
7. 真机验证项如实标注"待用户实测"，不伪造通过。
8. 设计层疑问 → 停下提问，不自作主张；节奏/打断语义一律以 S2R 方案 §2.2 v5 终版为准（勿沿用历史版本方向）。
9. 每步完成：更新对应设计方案 Steps 表状态（⏳→✅+验证摘要）+ git commit（尾行 Co-Authored-By: Claude Code <noreply@anthropic.com>）。

## 第三步：输出标准格式

### Step [S2R.x / E1.x / E2.x] 完成: [Step 名称]

**改动文件**:
- 新增/修改: path/to/File.kt（变化简述）

**验证**:
- [ ] Build/部署通过
- [ ] 模拟器 L3（如可行；坑 25 a11y 环境坏，对话 FC 回路不依赖 a11y 可测）
- [ ] 真机项（标注"待用户实测"）

**如果 Build 失败**: 粘贴完整错误信息立即修复（只改必要文件）。

## 第四步：更新状态

对应设计方案 Steps 表：当前 Step ✅（附验证摘要与 APK/部署时间戳）、下一步保持 ⏳；阻塞记入表后注（含 openclaw 受阻反馈件）。
```

---

## 版本 B：精简启动（连续开发）

```
继续 Zero V2 S2R/E 轨开发。项目在 D:\ZeroV2。

1. 读两设计方案 Steps 表（自驱消息留痕重构 §三 / 云服架构演进 §五）确认当前 Step
2. 读该 Step 定义 + 对应设计正文（裁定语义以文档为准）
3. 执行（Android ≤3 文件、迁移组同 commit、云服 ≤1 逻辑单元、E1.2 手机侧在 S2R.3 后）
4. 输出标准格式 + 更新 Steps 状态 + commit（Co-Authored-By 尾行）

行为约束：三类授权/三禁；先读后写（AgentRuntime:120/152/199、ChatBotModule:164/220、ChatViewModel:425/455 行锚）；
_build_apk.bat 构建自修复；E28 静默失败留痕；openclaw 受阻即停反馈不自定回退；真机项如实标注；不确定即问。
```

---

## 版本 C：修复模式（Build/部署失败后）

```
Zero V2 S2R/E 轨 Build 反馈。项目在 D:\ZeroV2。

信息：
[粘贴完整编译错误 / 云服部署日志]

请读取相关文件（先读后写确认签名），分析根因，只改必要文件修复。
保持：≤3 文件约束、迁移组同 commit、E28 留痕、设计语义不改（修复≠改设计——若根因指向设计层，停下报告）。
修复后说明改了什么 + 涉及哪条约束。
```

---

## 版本 D：审查模式（阶段完成）

```
Zero V2 [S2R / E1 / E2] 阶段完成。审查是否符合设计。

1. 读对应设计方案全文（含裁定回填与审核记录 R/C-R 表）
2. 读该阶段全部 Step 定义与验证标准
3. 检查涉及文件（S2R：core/common、core/data、framework/chat、services/agent、services/realtime、app/ui、app/di；
   E 轨：zero-cloud/、Termux 工作站侧）
4. 逐项对照：Step 产物到位？关键语义一致（一律打断无分类表/三计数波次节奏/≪sys≫ 剥离/NonCancellable 留痕/
   im 三源命名/游标持久/文件链路）？Hilt 绑定注册？Room v21 迁移完整？
5. 输出：✅ 完成 / ⚠️ 偏差 / ❌ 遗漏 + 真机待验项清单
```

---

## 版本 E：单一文件修改

```
修改 D:\ZeroV2 中的 [文件路径]（S2R/E 轨）。

修改内容：[具体描述]

约束：先 Read 确认当前内容（核心文件注意行号锚）；不改无关代码；保持设计语义与周边风格；
改后 _build_apk.bat 构建验证；更新 Steps 状态并 commit。
```

---

## 使用方式

```
版本 A: 首次启动 / 中断超 1 天恢复 / 新会话冷启
版本 B: 刚完成上一 Step、Build 通过、立即继续
版本 C: Build/部署失败
版本 D: 阶段（S2R 全部 / E1 / E2）完成质量对照
版本 E: 临时小修改

推荐节奏:
  A(1 次) → S2R.1→2→3 → (E1.1/E1.2 云服侧可并行) → S2R.4→5→6 → E1.2 手机侧 → E1.3 退役 → E1.4 UI
  → E2.1→E2.2→E2.3（Termux，任意时点并行） → D 审查收官
```

---

## 附：轨际衔接与关键语义速查

- **进度归属**：S2R 记于自驱消息留痕重构设计方案 §三；E 轨记于云服架构演进设计方案 §五——各自独立，互不覆盖。
- **节奏语义速查（v5 终版，勿用历史版本）**：公式 `(base±offset)×mult^min(count,max)` 零改动；用户直输（CHAT_WINDOW/VOICE）onChatSent → `count=min(count+1,max)`；一切自主回合（含 IM 入站/tick/事件）executeTurn 入口 → `count++`，`>max` 重置 0（波次 sawtooth：90s→4.5m→13m→40m→2h⟲）；tick 无回合且周期内无计数事件 → `count--`；区间求值前 300ms settle。
- **打断语义**：R6=A 一律打断——任何新触发取消进行中回合（onUserPriorityInterrupt 同一信号），无来源分类表。
- **消息协议**：`content = "[事件·{来源}] {净载荷}\n≪sys≫{指令}"`——UI 剥离 ≪sys≫ 后内容，持久化保留全文；role=USER + source=AUTONOMOUS + systemSource/triggerId 两字段。
- **E 轨终态**：4 容器（nginx/gateway/redis/openclaw）；最低 1C1G+2G swap、推荐 1C2G；先立后破（E1.3 仅在 E1.2 全链绿后）；openclaw 受阻纪律全程有效。
- **决策沿革警示**：节奏方向历经 反转(09-18)→v4 Alife 方向(误)→**v5 终版**——实现时以 v5 为准，勿被 git 历史中的旧方向误导。
