# Zero V2 自驱消息留痕重构设计方案（S2R 轨 · 裁定版 v5 终版 · 可执行）

> 目标：自驱/系统消息统一为一等对话公民——复用用户消息注入、硬提示词区分、实时渲染、永久留痕、界面简洁。
> **状态：v5 · 2026-09-19 终版（仅参考 Alife 公式；方向=消息活跃退避安静/闲置活跃；夜间自驱波次=逐级拉长、冲顶重置）· 待开工 · 无待裁项**。
> 推翻旧裁定：C3/C10/SR1（零 chatHistory 改动 + 会话级投影）。**节奏方向沿革**：09-18 反转（活跃安静/闲置回密）→ v4 曾翻回 Alife 方向 → **v5 澄清废止 v4 方向：仅参考 Alife 公式，方向沿用 09-18 反转 + 新增冲顶重置波次**。

---

## 一、裁定回填（v1 决策点 → 用户裁定）

| v1 决策点 | 裁定 |
|---|---|
| D-P1 注入身份 | **复用用户消息注入方式**（role=USER + source 标记，F4 既有路径零新管道）+ 消息正文带**硬提示词头**（`[事件·来源]`——LLM 一眼区分"系统注入 ≠ 用户亲发"，UI 同源取图标/配色） |
| D-P2 noop 留痕 | 留（弱化灰样式）——完整日记价值优先 |
| D-P3 自由活动载荷 | "🧠 自由活动时间"一句（活动清单归指令层，不占正文） |
| D-P4 实时渲染粒度 | **观察 chatHistory StateFlow**（回合内流式随之实时——bridge 的 applyStreamChunk 已回写 history，观察即得流式，无需镜像管道） |
| D-P5/6 迁移/清理 | 无需迁移；triggerId 列预留清理策略，一期不做 |
| D-P7 纯显示事件 | 不再上屏（RunLog 可查）——`onSystemEventInjected` 显示旁路整链退役 |
| D-P8 外来消息布局 | ~~微信/QQ/企业微信=左侧外来位~~ → **v3.1 推翻：统一右位**（实现从简） |
| D-P9 退避缺陷先行 | ~~是~~ → v3 取消（跳顶格=现行行为）→ **v3.1 恢复一行改动**（步进语义 +1 封顶，另加区间求值前 300ms settle——闭环咬合，见 §2.2） |
| 新裁定（v2） | 自驱消息可打断随点自驱（外来即时消息取消进行中回合并接管） |
| **新裁定（v3 · 最终打断模型）** | **所有消息来源一律作为打断**——云服微信等消息、自驱动通知等消息、用户消息，任何新触发取消进行中回合（最新者赢）。白天事件流已足够自驱；随点自驱为**夜间补充**（事件稀疏后退避衰减，tick 回合重新出声）——**节省 token 又有活动**。UI 保留系统消息框颜色区分（可读性） |
| **新裁定（v3.1 · 退避步进语义澄清）** | ~~每条消息 tier+1 步进、连四到顶~~ → **v4 取代**（节奏算法整体对齐 Alife，见下行） |
| **新裁定（v4 · R6 落案）** | **一律打断（R6=A）**：任何新触发取消进行中回合，最新者赢（无来源分类表）——v5 维持不变 |
| **新裁定（v5 · 节奏终版）** | **仅参考 Alife 算法（公式），方向自定**：消息活跃→退避安静；消息闲置→活跃。**夜间仅报点活跃时也能逐级拉长间隔，冲顶后重置节奏**——自驱成"波次"（sawtooth）：基距起跑→每回合拉长一档→顶格（2h）→重置回基距→新一轮。UI：系统卡统一右位、三色系 |

---

## 二、最终设计（简版 · 三要点）

### 2.1 消息模型（复用 + 两字段 + 内容协议）

```
ChatMessage 既有：role=USER, source=AUTONOMOUS（sendMessage 既有路径，零新管道）
新增字段：systemSource: String?（TriggerEvent.source 细名）, triggerId: String?
内容协议：
  content = "[事件·{来源名}] {净载荷}" + "\n≪sys≫" + "{回合指令}"
             └─ 用户可见（UI 气泡正文，简洁）      └─ UI 投影剥离（先例：视觉预分析剥离），
                                                    持久化保留（后续轮 LLM 仍知当时指令）
系统提示词不再改动；LLM 靠硬头 `[事件·X]` 区分用户消息（裁定指定的"硬提示词提醒"）。
```

`buildSelfDrivePrompt/buildWechatPrompt/...` 重排为两段产出：净载荷（微信原文仍走 D107 定界）+ 指令段（纪律/工具引导/D124 尾段）。

### 2.2 打断与节奏（**v6 修订版** · 2026-09-20 用户六裁定 · R6=A 废止/顺延归零）

**规则一（打断 · P0/P1/P2 优先级矩阵，废止 R6=A）**：
```
P0 用户消息（CHAT_WINDOW/VOICE 直输）
P1 实质事件：IM 入站（WECHAT/QQ/WECOM_PRIVATE·QQ_GROUP）+ 白名单通知（既有白名单
   用户自管零改动）+ BACKLOG 补检 + CLOUD_TASK 完成 + 任务续跑（next_tick 带载/automation）
P2 报点自驱（SELF_DRIVE 空载 tick）

跨级规则：高级可打断低级；同级最新者赢；P2 永不打断任何人（turnActive 时丢弃）。
回合类型标记：TASK（next_tick 带载/automation/SCHEDULED_PLAYBACK）/ INTERACTIVE（IM·
通知·补检·云任务）/ TICK（报点自驱）——source→type 映射表实现。
```
| 正在跑↓＼新到→ | P0 用户 | P1 实质 | P2 报点 |
|---|---|---|---|
| 用户回合 | 接续 | 排队（D67 等闲既有） | **丢弃** |
| 任务回合 | 打断（D68 既有） | **排队**（改——原抢占） | **丢弃** |
| 交互回合 | 打断（D68 既有） | 最新者赢（抢占+Conflated 既有） | **丢弃** |
| 报点回合 | 打断 | 打断 | 可丢 |

**规则二（节奏 · 顺延归零波次，取代 v5 冲顶即归零）**：
```
count 爬到 MAX → 顶峰静默（报点不开自驱回合）
  每次后续 tick：realEventSince(观察点)？ 有实质事件→顺延（保持静默，推进观察点）
                                    无→兑现归零 count=0，新波次起跑
顺延累计 >6h → 强制归零一次（防背景噪声永久锁死）
用户在场 = 顺延的自然结果（对话→事件不断→一直顺延→一直静；离开→归零→波次重启）

计数维持 v5 两条：① 用户直输步进 +1 封顶；② 一切自主回合（含报点自驱、含被 D67
跳过/丢弃的回合）入口 count++（用户裁定：事件已按点发生应计入）——但因无 overflow-
reset，不再穿透 ①（v5 缺陷由本设计消解）。
```
**顺延源分类**（✅ 实质事件）：用户消息/IM 入站/白名单通知/BACKLOG/CLOUD_TASK；
**❌ 不顺延**：BATTERY_*/SCHEDULED_PLAYBACK（用户裁定"定时播放到点不算"）/报点自驱本身/
白名单外通知（既有白名单天然滤除）。

**参数**（沿用 v5 + 新增）：base 90±30s · mult 3.0 · maxDoublings 4 · 顺延上限 6h ·
`userPresenceWindowMin` **可调默认 0**（用户裁定：事件探测已实质实现在场判定，此值仅
保险开关，0=禁用）。

**配套（任务续跑 · 最小实现）**：TASK 型回合被打断（仅 P0 可打断）时——S2R.4 留痕已保
上下文（prompt+部分回复在 Room）→ **自动重排一个 next_tick 续跑事件**（带载 SELF_DRIVE，
延迟一个报点周期）→ 新回合带完整对话历史从断点语义续做。automation 域执行路径开发前
复核（其自有调度是否需要同样重排另验）。

**代码落点（终审修订 · 防两处计数陷阱 · v6 修订注记）**：
1. `AgentRuntime.onChatSent.collect`：`count = maxDoublings` → **`if (source == CHAT_WINDOW || source == VOICE) count = minOf(count + 1, maxDoublings)`**（仅用户直接输入——IM 入站不在此计，走 ② 统一，**防双重计数**）；
2. `AutonomousThinkingJob.executeTurn` 入口 → `agentRuntime.noteTurnActivity()`：`count++；if (count > max) count = 0`（**覆盖一切自主回合**——IM 入站/tick 自驱/通知/云任务统一，与 ① 无交集；注入经 `Lazy<AgentRuntime>` 破环——AgentRuntime 已持 `Lazy<TriggerHub>` 同法先例）〔**v6：overflow-reset 由顺延归零取代——count 封顶 MAX，仅在干净 tick 兑现归零（S2R.8）**〕；
3. tick 后 `count--` 改**条件化**：`activityThisCycle` 脏标记（noteTurnActivity 与 ① 步进均置位）——**本周期内有任何计数事件则跳过 --**（否则无条件 -- 会把回合 ++ 抵消，波次退化为平档——终审抓出的逻辑缺陷）；
4. **区间求值前 300ms settle**（tick 自驱回合的 ++ 确定性入账——波次序列精确不漂档）；
5. 公式与偏移范围（`Random.nextInt(-offset, offset+1)`）零改动——与 Alife 同源。

实现要点（打断侧 · v6 修订注记）：
- `AutonomousThinkingJob.onTrigger`：`turnActive` 标志真 → emit `onUserPriorityInterrupt`（D68 同一信号，watcher 既有 cancel 路径）+ 200ms settle → 事件入 Conflated 队列随即执行。〔**v6：改为优先级矩阵——P2 丢弃/P1×TASK 排队/同级最新者赢（S2R.7）**〕
- 每日自驱不过分多：打断消灭堆叠 + Conflated 合并 + 每日触发上限三层兜底。
- 被打断回合留痕：prompt 消息 + 已流式部分照常落库（finally persistTurn 不区分取消；**取消路径落库须 `withContext(NonCancellable)` 包裹**——协程已取消时普通 suspend 调用即抛 CancellationException，留痕会丢）；空 assistant 占位被既有投影规则过滤。〔**v6：TASK 型被打断追加 next_tick 重排续跑（S2R.9）**〕
- ~~R6=A 字面含义接受~~ 〔**v6 废止（用户裁定 2026-09-20）**〕

### 2.3 渲染与留痕

- **实时**：`ChatViewModel` 观察 `chatBot.chatHistory`（StateFlow）——`_isGenerating==false`（非用户轮）时重投影；用户轮由既有路径刷新（守卫防双写）。系统消息/回复随回合流式出现。
- **留痕**：`ChatEngineBridge` +`persistTurn()` 桥缝（app 侧 ConversationRepository 实装，TtsWavSink 先例）；`AutonomousThinkingJob` 回合 finally 调用——系统消息+assistant+TOOL 全量落 Room。
- **Room**：`MessageEntity` +`source`/`system_source`/`trigger_id` 三列（v20→**v21**，X9 同 commit）。
- **UI**：单一样式系统卡=图标+来源名+时间+折叠正文（S8.3 图纸 A"右对称卡/折叠 40 字"沿用）；**统一右位**（v3.1：外来左位取消，从简）；三色系：外来 IM=绿 / 系统自驱=紫 / 环境事件=灰（颜色=source→Color 一 map，无复杂度差；嫌繁可降 2 色）。删 `emitResponseBubble`（因果气泡）与 `systemBubbles` 合流层。

---

## 三、Steps（全部无阻塞 · Android ≤3 文件/Step · 构建验证）

| Step | 内容 | 文件 | 验证 | 状态 |
|---|---|---|---|---|
| S2R.1 | 模型扩展 + 节奏落位：ChatMessage +systemSource/triggerId；AgentRuntime——onChatSent 过滤（CHAT_WINDOW/VOICE → 步进封顶）+ `noteTurnActivity()`（++/冲顶重置）+ `activityThisCycle` 条件化 -- + 区间求值前 300ms settle；AutonomousThinkingJob executeTurn 入口接线（`Lazy<AgentRuntime>` 破环注入） | ChatEvents.kt + AgentRuntime.kt + AutonomousThinkingJob.kt（3） | Build ✅（2026-09-19 14:27 APK）；L3 波次轨迹/直输封顶待用户实测（埋点：user_msg_step/note_turn_activity/tick_interval_changed） | ✅ |
| S2R.2 | 迁移组：MessageEntity +3 列 + MIGRATION_20_21 + appendMessage/回读映射 | MessageEntity/ZeroDatabase/DatabaseModule/ConversationRepository（迁移组同 commit） | Build ✅（2026-09-19 15:07 APK）；老库升级零丢失/全新安装零回归待用户实测（三列全可空，ALTER 不触存量数据） | ✅ |
| S2R.3 | 注入改造：五路 buildPrompt 拆双层（净载荷+≪sys≫指令）；**ChatEngineBridge.chatAndCollect 签名 +systemSource 参数**→ChatBotModule 落 systemSource/triggerId 到 userMsg；**一律打断**（onTrigger 任意 source 且回合进行中 → emit D68 信号取消，事件随即执行——R6=A 无分类表）；删 emitResponseBubble | AutonomousThinkingJob.kt + ChatEngineBridge.kt + ChatBotModule.kt（3） | Build；微信触发→净载荷气泡；任意新事件打断自由活动回合（trace `turn_preempted_by: source`）；微信回合计一次 count+1（无双计 trace） | ✅ Build ✅（2026-09-19 15:20 APK）；L3 气泡/打断矩阵/无双计待用户实测（埋点：turn_preempted_by/turn_cancel_by_event/trigger_enqueued preempted=true） |
| S2R.4 | 留痕+实时：persistTurn 桥缝 app 实装（**取消路径 NonCancellable**，照常落 prompt+部分回复）；删 TriggerHub 显示旁路；VM 观察 chatHistory+≪sys≫ 剥离 | ChatEngineBridgeImpl.kt + TriggerHub.kt + ChatViewModel.kt（3） | 自驱回合重启可见；流式实时显示；用户轮零双写；被打断回合留痕完整 | ✅ Build ✅（2026-09-19 15:32 APK，物理 5 文件见 R12）；L3 重启留痕/打断留痕/流式待用户实测（埋点：persist_turn msgs=N/persist_turn_failed） |
| S2R.5 | UI 收口：系统卡样式（图标/三色/**统一右位**；noop 回复弱化灰样式——D-P2） | ChatScreen/MessageBubble（≤2） | 三类来源气泡样式正确；系统消息与用户消息颜色一眼可辨；noop 弱化 | ✅ Build ✅（2026-09-19 15:39 APK，ChatScreen 单文件）；三色/右位/noop 弱化待用户实测 |
| S2R.6 | L3 收官：全链矩阵（重启留痕/一律打断矩阵/波次节奏轨迹/世代压缩含系统消息无异常——摘要器默认按 role 归入用户轮，接受的简化） | — | 手册追加坑位（如有） | ✅ 清理+坑位（2026-09-19 17:44 APK，模拟器全链冒烟绿）；L3 矩阵待用户实测。收官清理：死流 onSystemEventInjected/SystemEventDisplay 物理删除（零引用核实后）+TriggerHub 失用 chatBus 参数退役（D-P7 整链收官）；模拟器实证不破坏功能（对话回合 UI→LLM→回复→落库、启动补检自主回合以 system_source=BACKLOG+trigger_id 落库、会话数稳定、系统卡三色右位与 noop 弱化截图确认、无 FATAL）；手册追加坑 26-28（构造期 StateFlow.collect NPE/判定口径不对称/exec-out 拉库+screencap 定位） |
| S2R.7 | **v6 打断优先级矩阵**：回合类型标记（source→type：TASK/INTERACTIVE/TICK）+ onTrigger 矩阵判定——P2 永不打断（turnActive 时丢弃留痕）/P1×TASK 排队（不抢占）/同级最新者赢（抢占+Conflated 既有） | AutonomousThinkingJob.kt + AgentRuntime.kt（2） | Build；用例 3（任务回合×报点=不打断）/用例 9（用户说话×IM=排队已由 D67 保证）埋点 turn_dropped_p2/turn_queued_p1 | ✅ 代码完成（2026-09-20，实际单文件 AutonomousThinkingJob——TurnType 枚举+turnTypeOf 映射（SELF_DRIVE 空载=TICK/带载+AUTOMATION+SCHEDULED_PLAYBACK=TASK/其余=INTERACTIVE）+onTrigger 三分支（TICK 丢弃/P1×TASK 排队/其余抢占）+executeTurn 入口 currentTurnType 标记）；Build ✅ APK 54s+冒烟 FATAL=0；矩阵九格行为待 L3（用例 3/9 重点） |
| S2R.8 | **v6 顺延归零**：pendingReset + lastRealEventAt + 顺延源分类（✅IM/白名单通知/BACKLOG/CLOUD_TASK；❌BATTERY/SCHEDULED_PLAYBACK/SELF_DRIVE）+ 顶峰静默（count=MAX 时 tick 不开自驱回合）+ 干净 tick 兑现归零 + 6h 强制归零 + userPresenceWindowMin 可调默认 0；noteTurnActivity 去除 overflow-reset（封顶 MAX） | AgentRuntime.kt + AutonomousThinkingJob.kt（2） | Build；用例 1（连发 4 条 count=4 间隔≥81min）/用例 2（在场零自主回合）/用例 5（离开 30min 恢复）/用例 6（顶锋有事件顺延）/用例 7（安静归零）/用例 8（充电不顺延）埋点 wave_deferred/wave_reset | ✅ 代码完成（2026-09-20，实际 3 文件含 AgentRuntimeConfig）。**AgentRuntime**：noteTurnActivity 封顶 MAX 去溢出重置（根治在场穿透）+初达顶峰记 peakEnteredAt+6h 强制归零+tick 顶峰分支（有实质事件→顺延静默 wave_deferred/干净→wave_reset/超 6h→wave_force_reset）+noteRealEvent（零计数防双计）+onChatSent 补 lastUserMsgAt/lastRealEventAt+userPresenceWindowMin 保险窗（@ConfigField 可调默认 0）；**AutonomousThinkingJob**：isRealEventForDeferral 源分类（❌BATTERY 系/SCHEDULED_PLAYBACK/报点空载）+onTrigger 到达即登记（含丢弃/排队——按点发生语义）。实测坑：**注释内 `X*/Y` 写法的 `*/` 提前闭合块注释**（两处同雷，编译 Expecting member declaration 乍看莫名——留档手册坑 29 候选）；Build ✅ 18s+冒烟 FATAL=0；波次行为 L3 待验（用例 1/2/5/6/7/8） |
| S2R.9 | **v6 任务续跑（最小实现）**：TASK 型回合被打断（仅 P0 可打断）→ 留痕既有 + 自动重排 next_tick 续跑事件（带载 SELF_DRIVE 延迟一报点周期，payload 标记续跑）；automation 自有调度路径复核 | AutonomousThinkingJob.kt（1，TriggerHub.fireSelfDrive 复用） | Build；用例 4（任务被打断且进度已存——重排事件入队埋点 task_rescheduled） | ✅ 代码完成（2026-09-20，单文件）。CancellationException 分支：currentTurnType==TASK → 90s settle 后重排 SELF_DRIVE 带载续跑事件（payload「续跑#N」+原任务描述+断点续做指令——S2R.4 留痕上下文在场）→ 经 onTrigger 正规入口（矩阵/顺延/计数全适用）；**链上限 3 次**（task_reschedule_capped 防无限续跑）。automation 自有调度（不经本队列）复核项如实标注未做——其回合若不经 AutonomousThinkingJob 则本续跑不覆盖，待实测确认路径；Build ✅ 16s+冒烟 FATAL=0；用例 4 待 L3 |

依赖：S2R.1→2→3→4→5 串行；S2R.6 收官。E1.2 手机侧改动在 S2R.3 之后（见云服方案 §五）。

---

## 四、旧裁定处置（v1 §五 摘要）

| 旧裁定 | 处置 |
|---|---|
| C3/SR1 零 chatHistory 改动 | 推翻 → 受控入 history（发送视图原样发送构成连续记忆；孤儿 TOOL 守卫已覆盖） |
| C10 会话级气泡/历史=RunLog | 推翻 → Room 持久化；RunLog 仍为审计全量（triggerId 关联） |
| S2.2 F7 triggerId 贯通 | 保留——载体从 UI 徽标改为消息字段+审计链 |
| S8.3 图纸 A 系统卡 | 保留并增来源图标/三色矩阵 |
| F8/F16 聚合与逐条纪律 | 显示旁路退役后聚合层自然消失；业务链逐条 Trigger 不变 |

---

## 五、审核记录（v3.2 · 2026-09-19 全文通读）

| # | 发现 | 处置 |
|---|---|---|
| R1 | §一 D-P8（外来左位）与 v3.1（统一右位）矛盾残留 | D-P8 标记推翻，以 v3.1 为准 |
| R2 | D-P9 "零改动"与 v3.1 步进一行改动矛盾 | D-P9 更新（v3 取消→v3.1 恢复一行） |
| R3 | 夜间稳态两处表述不一（"低档稳态" vs "钉在入夜水平"） | 统一为"入夜 tier+1 档"（settle 后区间按 t+1、报点 -1 回 t） |
| R4 | S2R.3 依赖 chatAndCollect 签名扩展但 ChatEngineBridge.kt 排在 S2R.4——步骤依赖错位 | S2R.3/S2R.4 文件重排（签名扩展归 S2R.3；Impl/TriggerHub/VM 归 S2R.4） |
| R5 | 取消回合的 persistTurn 在已取消协程内调用 suspend 会即抛 CancellationException——留痕静默丢 | 实现注记：`withContext(NonCancellable)` 包裹（S2R.4 验证项） |
| R6 | "所有来源一律打断"字面含**内部定时类掐断主人 IM 回复**场景 | **已裁定（v4）：A 一律打断**——无分类表；节奏保护改由 Alife 算法承担（打断型=主人来源消息才重置 count，环境/定时事件不重置——09-18 三方通知刷屏教训保持隔离） |
| R7 | noop 弱化样式（D-P2 裁定）未落到任何 Step | 并入 S2R.5 |
| R8（v4） | Alife 参考源核查：本地 `Alife.Client` 为编译产物（DLL，无 .cs 源；Data/ 纯资产）——算法以用户引用描述为权威；该式与 Zero 现行公式**逐项同源** | v5 落 §2.2 |
| R9（v5） | v4 方向翻转（Alife 重置=活跃贴身）与用户"消息活跃则退避安静，消息闲置则活跃"相悖——**v5 澄清：仅参考公式，方向自定**；夜间"仅报点活跃时逐级拉长、冲顶重置"= 波次（sawtooth）节奏；300ms settle 恢复（波次序列防漂档）；v3.1 步进语义（连四封顶）保留于主人消息路径 | 已落 §2.2 终版 |
| R10（终审） | ① tick 后无条件 `--` 会抵消同周期回合 `++` → 波次退化为平档——**条件化**（`activityThisCycle` 脏标记）；② IM 回合会被 onChatSent 与 executeTurn 双重计数——**收敛**：用户直输（CHAT_WINDOW/VOICE）走 ① onChatSent，一切自主回合（含 IM 入站）走 ② noteTurnActivity，零交集零重复 | 已落 §2.2 代码落点与 Steps；Steps 表补状态列（⏳）供中断恢复 |
| R11（S2R.3 落地） | chatAndCollect 接口加默认参数 → Kotlin override 必须同步参数表——R4 的"S2R.3 只改接口、Impl 归 S2R.4"拆分编译不可行 | ChatEngineBridgeImpl 机械签名同步（1 参数 + 1 传参，零逻辑）归入 S2R.3 同 commit（4 文件）；persistTurn/删显示旁路/VM 接线仍归 S2R.4 |
| R12（S2R.4 落地） | ①persistTurn 桥缝=接口声明+Impl 实装+设计正文指定的"回合 finally 调用点"三处一体——文件列表 3 的物理落点实为 5（+RealtimeVoiceProvider 接口文件互锁 +AutonomousThinkingJob）；②persistTurn 按 history.id 落库，而 startNewConversation 未回写 id 到 chatBot（FK 失配静默失败）——VM 补 loadHistory 对齐；③persistTurn 与 VM persistConversationTurn 的 TOOL 整表过滤会双写（后者兼有逐轮重复累积既有 bug）——TOOL 过滤收窄到本轮 | ①按设计正文落 5 文件（互锁+指定调用点，非逻辑越界）；②③为 S2R.4 引入链路的必要配套，均留痕注释 |
| R13（S2R.4 闪退修复） | chatHistory 观察块置于 init#1（属性声明前）——viewModelScope=Main.immediate，launch 体构造期同步执行，StateFlow.collect 即时回放首值→读到未初始化 `_isGenerating`（null）→ NPE 启动闪退（旧 SharedFlow 收集无回放故未触发此雷） | 观察块迁至 init#2（全部属性声明之后，行为不变）；Android 模拟器复现→修复→回装验证绿（APK 2026-09-19 15:49，无 FATAL、进程存活） |
| R14（既有缺陷修复·非 S2R 引入） | refreshActiveCharacter 的 unchanged 判定拿**原始角色字段**对比**兜底后全局值**（null vs "Zero"/DEFAULT_PROMPT_TEXT）——characters 表为空的安装上恒判"已切换"，每次 ChatScreen 进入（LaunchedEffect→refresh）都 startNewConversation→每次开 app 新建空会话、上下文丢失（DB 实证：3 条空"新对话"与启动时刻一一对应）。S2R 提交对该函数零改动（git diff 218ceb1^ 0 命中） | 判定改为与 applyGlobalCharacter 同口径的兜底有效值（`?: "Zero"`/`?: DEFAULT_PROMPT_TEXT`）——角色真正变化仍新开（D26 语义不变）；模拟器两次重启验证会话数稳定（APK 2026-09-19 16:02） |
| R15（v6 修订 · 2026-09-20 用户六裁定） | **探索核证**（Zero 观测+提案 vs 实码）：问题 1 根因精确化——count++ 本身在生效，真凶是 ② 冲顶归零在用户在场期被"D67 跳过回合的入口计数"触发（noteTurnActivity 在 waitForIdle 之前，turn_skipped 也 ++）→ 归零穿透 ① 步进 → count 恒 0-1 振荡、间隔 88/287/198s 吻合；问题 2/3 确认（R6=A 字面/冲顶即归零）。**六裁定**：①废止 R6=A 立 P0/P1/P2 矩阵；②顺延归零取代冲顶（夜间波次变慢接受）；③通知白名单用户自管零改动，通知+BACKLOG 归交互类；④SCHEDULED_PLAYBACK 不顺延、报点自驱照常计数、**turn_skipped 维持计入**（事件已按点发生）；⑤任务续跑本期做；⑥userRecent 为可调项**默认 0**（事件探测已实现在场判定）。附录 B 68min 异常落 count3/4 区间夹缝（27-54/81-162min），归因 skipped 计数致档位漂移，RunLog tick_interval_changed 对照归入 L3 验收 | §2.2 v6 + S2R.7/8/9 落档；开发按序执行 |
