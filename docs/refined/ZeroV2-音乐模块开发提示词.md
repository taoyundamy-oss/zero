# Claude Code 音乐模块开发提示词（MU 轨）

> 用途：跨会话流畅执行 `ZeroV2-音乐模块开发设计.md`（MD1–MD18 · 2026-09-11 M 表勘误封板版）与 `ZeroV2-音乐模块落地实施计划.md`（SR/C/G/F/M 五层验证 · MU0.1–MU5.2 共 15 Step）。
> 核心原则：**设计决策不变**（MD1–MD18 已封板）、**不破坏已有功能与基线**、**只做授权修改**。
> 记录日期：2026-09-11 · 基于 MU0.1 勘误封板版设计；沿用 CLAUDE_CODE_PROMPT_SHOUSHU.md 结构（完整/精简/修复 + 逐步提示词）。

---

## 版本 A：完整启动（首次会话 / 中断后恢复）

```
你正在开发 Zero V2 音乐模块（MU 轨），为 Android AI Agent 应用 Zero 增设本地音乐库 +
LLM 播放管理能力。项目位于 D:\ZeroV2。

## 第一步：加载上下文（只读，不修改任何文件）

请依次读取以下文件，了解本轨全貌：

1. `D:\ZeroV2\docs\refined\ZeroV2-音乐模块落地实施计划.md` — 实施计划与进度追踪
   （§四进度表 = 当前位置权威；§M 外审裁定表 = 勘误封板记录）
2. `D:\ZeroV2\docs\refined\ZeroV2-音乐模块开发设计.md` — 设计决策（MD1–MD18 · §五实体/§六FC/§七调度）
3. `D:\ZeroV2\docs\refined\ZeroV2-音乐模块开发提示词.md` — 本提示词（行为边界）
4. `D:\ZeroV2\docs\refined\ZeroV2-LLM开发指南.md` — 跨轨通用规范（R1–R8 + 地雷清单 + 安全红线）

根据落地计划 §四进度表中第一个 ⏳ 状态的 Step，精读该 Step 的完整定义
（文件清单/验证标准/关联 MD-M 锚点）。

## 第二步：执行当前 Step

严格按落地计划中该 Step 的定义执行：
- 只创建/修改该 Step 明确列出的文件（Android ≤3 文件/Step；Room 迁移组 4 文件同 commit 例外）
- 不改动 Step 范围外的任何文件与功能
- 每个 Step 自带验证标准——逐条对照，不达标的验证项如实标注"待实测/待用户"

## 修改权限边界（硬性，违反即回滚）

只允许三类修改：
1. 实现已批准设计（音乐设计 MD1–MD18 及落地计划对应 Step）
2. 修复落地计划 SR/C/G/F/M 验证表明确列出的问题
3. 修复实现过程中发现的真实 bug（有错误日志/复现证据）

不允许三类修改：
1. 未授权架构重构（模块边界/通信机制/数据层的结构性变更）
2. 将已裁定的方案重新设计（MD 决策——如：relative_path 定位模型、闹钟 tick+认领语义、
   musicSubdir 常量化、AUTO 双门控、ensurePlaybackService 单入口、扫描最终一致性）
3. 因"实现更简单"而修改模块边界、通信机制或 UI 基线（三 Tab 固定、chatHistory 纯净等）

遇到边界拿不准时：记录问题 + 向用户提问，不自作主张。

## 行为规则

1. **每次只做一个 Step**。完成后停下，构建验证，等用户确认再推进。
2. **Android ≤3 文件/Step**（Room 迁移组例外：Entity+Dao+DB版本+Migration 同 commit，X9）。
3. **构建验证**：代码改动后运行 `cmd //c "D:\ZeroV2\_build_apk.bat"`，验 APK 时间戳更新；
   构建失败读编译输出、自诊断、自修复后重试（不得带着失败标记完成）。
4. **先读后写**。修改现有文件前必须 Read 确认当前内容；WorkDirRepository/TriggerHub/
   SystemBus/ExactAlarmReceiver/AppBindingModule 改动前精读相关段。
5. **参考标杆**：FC → FilesModule；前台服务 → LiveSessionService；调度+到期扫描+认领 →
   SystemEventModule（execute :418 同构）；Room+迁移 → ReminderEntity/MIGRATION_13_14；
   双绑定 → AppBindingModule:91-92（bindSystemEvent + bindSystemEventJob）。
6. **埋 AgentTrace**：每个关键集成点 record 一句；静默失败路径必须留 trace（E28 铁律）。
7. **onAwake 必须自调 registerFunctions()**（ModuleLifecycleManager 不集中调——S4.2-fix 实测教训：
   漏调=FC 静默不在工具列表）。
8. **真实偏差入档**：发现设计与现实不符（如 SAF/Media3 真机行为），先实测取证，记入 §E
   教训表并同步两文档，再继续——不静默偏航。

## 第三步：输出标准格式

### Step MUx.y 完成：[名称]

**改动文件**：
- 新增: path/to/File（N 行）
- 修改: path/to/File（变化简述）

**验证结果**：
- [x] Build 通过 / APK 时间戳
- [x] 该 Step 验证项（逐条，实测数据）
- [ ] 待用户 L3 实测项（如有，写明操作）

**执行偏差**（如有）：与计划的差异 + 原因 + 已入档位置

## 第四步：更新状态

修改 `ZeroV2-音乐模块落地实施计划.md` §四进度表：
- 该 Step ⏳→✅ + 验证摘要
- 发现的新问题 → 追加到对应验证表（新编号）
- 用 git commit 留痕（信息含 Step 号与关键结论，尾行 Co-Authored-By: Claude Code）

---

不确定的实现选择：先查设计文档对应 MD 决策 → 再查落地计划 SR/C/G/F/M 表 → 都没有 → 向我提问。
```

---

## 版本 B：精简步进（连续开发 · 与版本 D 二选一）

```
继续 Zero V2 音乐模块（MU 轨）开发。项目在 D:\ZeroV2。

1. 读 `docs/refined/ZeroV2-音乐模块落地实施计划.md` §四进度表确认当前 Step（首个 ⏳）
2. 读该 Step 定义 + 关联设计决策（ZeroV2-音乐模块开发设计.md 对应节 + MD 锚点）
3. 执行（Android ≤3 文件/Step；迁移组 4 文件同 commit；构建验证 _build_apk.bat + 自修复）
4. 输出标准格式（改动文件/验证结果/执行偏差）
5. 更新进度表 + git commit

行为约束（详版见 ZeroV2-音乐模块开发提示词.md）：
- 只做三类授权修改（实现已批准设计/修复表中列出问题/修复真实 bug）
- 不做三类禁止修改（未授权重构/重设计 MD 封板决策/因简化改边界与基线）
- 先读后写；标杆对齐；关键路径埋 trace；静默失败必留痕（E28）
- onAwake 自调 registerFunctions；DB 写 Mutex+@Transaction；迁移同 commit（X9）
- 音乐轨红线：relative_path 唯一定位 / 闹钟 tick+认领 / musicSubdir 常量 / AUTO 双门控
  + 禁 CONFIRM FC / ensurePlaybackService 单入口 / miss_count≥2 才删
- 不确定时提问，不自作主张
```

---

## 版本 C：修复模式（Build 失败 / 真机反馈后）

```
Zero V2 音乐模块测试反馈。项目在 D:\ZeroV2。

信息：
[粘贴完整编译错误 / 运行日志 / Zero agent 实测反馈]

请读取相关文件，分析根因，只做最小必要修复（授权三类之三：真实 bug）。
若根因指向设计层问题（非实现 bug），停下来向我报告，不自行改设计。
修复后构建验证（_build_apk.bat）+ 更新进度表 + commit。
```

---

## 版本 D：逐步提示词（每 Step 一段，直接粘贴执行）

> 每段自带该 Step 的文件边界/设计锚点/验证标准——用于精确驱动单步；
> 连续开发用版本 B 亦可（进度表自动定位）。**顺序执行，不跳步**：
> MU4.2a FAIL 不进 MU4.2b；MU2.3 真机音频协调 FAIL 时按 G2 fallback 另立步，不硬推 MU3。

### Step MU1.1 — 存储层 v15（⚠️ X9 迁移组 · 4 文件同 commit）

```
执行 Zero V2 音乐轨 Step MU1.1（Room v15 迁移组）。项目在 D:\ZeroV2。
先读 docs/refined/ZeroV2-音乐模块落地实施计划.md MU1.1 定义 + 设计 §5.1/§5.5，再动手。

文件（迁移组例外，4 文件同 commit）：
1. 新 core/data/.../entity/MusicEntity.kt — MusicTrackEntity（表 music_tracks）：
   id TEXT PK | relative_path TEXT NOT NULL | display_title NOT NULL DEFAULT '' |
   artist/album TEXT | duration_ms/file_size/modified_at INTEGER NOT NULL DEFAULT 0 |
   added_at INTEGER NOT NULL | miss_count INTEGER NOT NULL DEFAULT 0 |
   play_count INTEGER NOT NULL DEFAULT 0 | last_played_at INTEGER
   + UNIQUE(relative_path) 索引。MD13：relative_path=唯一物理定位键（含子目录，
   如 Anime/C.mp3）；display_title 只展示。NOT NULL 列 DEFAULT 与
   @ColumnInfo(defaultValue=) 严格对齐（Room schema 校验）。
2. 新 core/data/.../dao/MusicDao.kt — tracks 部分，全 suspend。查-留-id-更新模式：
   getByRelativePath() 命中→参数化 UPDATE（保留 id/play_count/last_played_at/added_at，
   miss_count 清零）；未命中→INSERT 新 id。禁裸 @Insert(REPLACE) 承接扫描——REPLACE 撞
   UNIQUE 走删旧插新，旧行删除级联清空 playlist_items（设计 §5.5 钉死陷阱）。
3. ZeroDatabase — version 14→15 + entities + abstract fun musicDao()
4. DatabaseModule — 私有 MIGRATION_14_15（仿 MIGRATION_11_12 CREATE TABLE + CREATE
   UNIQUE INDEX）+ provideMusicDao + addMigrations 注册

验证：Build 通过 APK 时间戳更新；老库升级不抹数据（X9 L3 必测——交装机实测）。
输出 R8 格式 + 更新进度表 + commit（迁移组同 commit 铁律）。
```

### Step MU1.2 — MusicModule 骨架 + 库/文件 FC（3 文件）

```
执行 Zero V2 音乐轨 Step MU1.2（MusicModule 骨架）。项目在 D:\ZeroV2。
先读落地计划 MU1.2 定义 + 设计 §四/§六/§九/§十一 + WorkDirRepository.kt 现状，再动手。

文件（3 文件）：
1. 新 app/.../music/MusicModule.kt — @Singleton，IZeroModule（id="music",
   displayName="音乐", category=SYSTEM, launchOrder=+120）+ MusicModuleConfig
   （§十一 5 字段：autoScanOnStart/scanIntervalMin/defaultVolume/audioDuckDuringVoice/
   scheduledAutoCap——无 musicSubdir，MD15）+ onAwake 自调 registerFunctions() +
   FC ×5：music_scan/music_list/music_search(AUTO) + music_rename/music_delete(CONFIRM)
2. 同文件或同包 MusicPaths — const SUBDIR = "llmmusic"（编译期常量，MD15；不进配置）
3. AppBindingModule — @Binds @IntoSet 绑定 IZeroModule

关键锚点（照做，勿偏）：
- 复用 S9.1 已落地原语（M13）：resolvePath（拒 ../ 与 . 段）/listTree/moveEntry/deleteFile
  ——不新造 subdir 方法集；musicRoot()=resolvePath(根,"llmmusic")，首次扫描不存在则建
- music_scan：递归子树 + 增量三元组 (relative_path,file_size,modified_at) 全一致跳过
  MediaMetadataRetriever（M7）；缺失 miss_count+1，连续≥2 才删行；枚举异常跳过删除+trace（M8）
- music_rename(old_relative_path,new_relative_path)：物理 moveEntry("llmmusic/$old",
  "llmmusic/$new")+DB 同步；失败顺序=物理优先，Room 失败→trace+触发一次扫描 reconcile（M9）
- music_delete(relative_path)：删前查受影响歌单，回执附 affected_playlists/removed_items（M10）
- 未配工作目录返回引导文本不崩（B11）；DB 写 Mutex；静默路径全留 trace（E28）

验证：llmmusic 放 根1首+Anime/C.mp3+Game/C.mp3 → music_scan → music_list 三条
relative_path 互异；../ 与绝对路径全拒；移走文件扫1次行保留、扫2次行删；
未配目录返回引导。Build + R8 输出 + 进度表 + commit。
```

### Step MU1.3 — 库 UI（3 文件）

```
执行 Zero V2 音乐轨 Step MU1.3（MusicScreen）。项目在 D:\ZeroV2。
先读设计 §十 + 标杆 WorkFilesScreen/ModuleDetailScreen，再动手。

文件（3 文件）：
1. 新 app/.../ui/music/MusicScreen.kt — 库列表（过滤/搜索）+ 扫描按钮
2. 新 app/.../ui/music/MusicViewModel.kt — @HiltViewModel 注入 MusicModule 单例，
   StateFlow collect（仿 WorkFilesViewModel）
3. ZeroNavHost — 加设置子路由（三 Tab 不动，D122 基线）；配置页走 AutoForm

验证：UI 列库+扫描+搜索可用；杀进程重启库仍在（Room 持久）。
Build + R8 输出 + 进度表 + commit。
```

### Step MU2.1 — media3 + MusicService 骨架（3 文件 · build.gradle 本步显式授权）

```
执行 Zero V2 音乐轨 Step MU2.1（MusicService 骨架）。项目在 D:\ZeroV2。
先读设计 §8.1/§8.2 + 标杆 LiveSessionService（SDK_INT 分支写法），再动手。

文件（3 文件；R5 例外——本步显式声明新增依赖 media3）：
1. app/build.gradle.kts — +androidx.media3:media3-exoplayer:1.5.1 +media3-session:1.5.1
2. AndroidManifest.xml — +FOREGROUND_SERVICE_MEDIA_PLAYBACK 权限 +
   service .background.MusicService exported=false foregroundServiceType=mediaPlayback
3. 新 app/.../background/MusicService.kt — @AndroidEntryPoint；onCreate 建 channel +
   startForeground(3-arg 按 SDK_INT 分支)；START_NOT_STICKY；onTaskRemoved→stopSelf；
   companion start/stop；注入 MusicModule collect playbackState→通知（本步可空实现接线）

验证：Build 通过；服务能前台启动（日志/通知可见）。
R8 输出 + 进度表 + commit。
```

### Step MU2.2 — 播放核心 + 统一前台入口（2 文件）

```
执行 Zero V2 音乐轨 Step MU2.2（播放核心）。项目在 D:\ZeroV2。
先读设计 §8.2a/§8.3/§8.4 + C2 裁决，再动手。

文件（2 文件）：
1. MusicModule.kt — lazy ExoPlayer + PlaybackState StateFlow + FC ×9：
   music_play/pause/resume/next/prev/seek/status/shuffle/repeat（播放控制全 AUTO；
   music_set_queue=CONFIRM）+ AudioFocusRequest（焦点丢失暂停/回归续播）
   + ensurePlaybackService()：play() 第一件事起服务（幂等），FC/UI/调度三入口共用（MD17，
   禁任何入口自行 startService）
2. MusicService.kt — playbackState→通知更新接线

关键锚点：播放定位=resolvePath(musicRoot, relative_path) 逐级解析（MD13，任意深度子目录；
解析失败返回失败文案不崩）；C2：仅 AudioFocus，不注入 LocalVoiceProvider（gap 留 MU2.3 真机验）。

验证：music_play→前台通知+出声+music_status 位置/时长/队列；next/seek 生效；
三入口（FC/UI 两条先行）均出前台通知。
Build + R8 输出 + 进度表 + commit。
```

### Step MU2.3 — now-playing UI + 真机音频协调验（1–2 文件）

```
执行 Zero V2 音乐轨 Step MU2.3（now-playing + 音频协调真机验）。项目在 D:\ZeroV2。
先读设计 §8.4 + G2/C2，再动手。

文件（1–2 文件）：
1. MusicScreen.kt — now-playing 区（曲名/位置/进度条/控制/队列）
2. （如需）MusicViewModel.kt — 状态扩展

真机验证（G2，结果入档）：起 Live 语音会话→音乐是否自动暂停（AudioFocus 生效性）。
若 TTS 期间音乐不暂停（sherpa AudioTrack 未申请焦点）→ 停下报告，按 C2 fallback
另立 Step 加 SystemBus.onVoiceActive——本步不改架构硬推。

Build + R8 输出 + 进度表 + commit；真机项交用户 L3。
```

### Step MU3.1 — 歌单存储 v16（⚠️ X9 迁移组 · 同 commit）

```
执行 Zero V2 音乐轨 Step MU3.1（Room v16 迁移组）。项目在 D:\ZeroV2。
先读设计 §5.2/§5.3，再动手。

文件（迁移组同 commit）：MusicEntity.kt（+PlaylistEntity/PlaylistItemEntity）+
MusicDao（歌单查询）+ ZeroDatabase v16 + DatabaseModule MIGRATION_15_16
（CREATE 2 表；FK CASCADE + playlist_id/track_id 双索引，Room 外键须索引）。

验证：Build 通过；老库升级保留 tracks。
R8 输出 + 进度表 + commit。
```

### Step MU3.2 — 歌单 FC + UI（2–3 文件）

```
执行 Zero V2 音乐轨 Step MU3.2（歌单 FC + UI）。项目在 D:\ZeroV2。
先读设计 §六 歌单表（含 playlist_create=AUTO 勘误注/M16），再动手。

文件（2–3 文件）：
1. MusicModule.kt — FC ×5：playlist_create(**AUTO**——MD4 自主建歌单裁定所必需，
   创建无破坏性可逆；勿改回 CONFIRM)/playlist_list(AUTO)/playlist_delete(CONFIRM)/
   playlist_add_track(CONFIRM)/playlist_remove_track(CONFIRM)
2. MusicScreen/ViewModel — 歌单管理 UI（列表/建/删/加减曲）

验证：playlist_create(from_track_ids)→music_play(playlist_id) 按序播放。
Build + R8 输出 + 进度表 + commit。
```

### Step MU4.1 — 调度存储 v17（⚠️ X9 迁移组 · 同 commit）

```
执行 Zero V2 音乐轨 Step MU4.1（Room v17 迁移组）。项目在 D:\ZeroV2。
先读设计 §5.4（status 状态机：active→firing→fired/missed/skipped_cap/cancelled），再动手。

文件（迁移组同 commit）：MusicEntity.kt（+ScheduledPlaybackEntity：trigger_at/playlist_id/
mode FIXED|AUTO/hint/is_recurring/recurring_ms/status DEFAULT 'active'/created_by）+
MusicDao（调度查询：到期扫描/按状态/最近 active）+ ZeroDatabase v17 + MIGRATION_16_17。

验证：Build 通过。R8 输出 + 进度表 + commit。
```

### Step MU4.2a — SystemBus.onMusicDue + ExactAlarm（3 文件）

```
执行 Zero V2 音乐轨 Step MU4.2a（调度总线+闹钟）。项目在 D:\ZeroV2。
先读设计 §7.1（tick 语义论证——MD14）+ ExactAlarmReceiver.kt 现状，再动手。

文件（3 文件）：
1. core/bus/.../SystemBus.kt — +onMusicDue: MutableSharedFlow<Unit>（仿 onScheduleTriggered；
   Unit 载荷=tick 语义，M2——勿加 scheduleId）
2. core/bus/.../SystemBusImpl.kt — 同步实现
3. services/.../agent/ExactAlarmReceiver.kt — ExactAlarmScheduler +scheduleMusic(t)=
   schedule(ACTION_MUSIC_DUE, REQUEST_MUSIC=4003, t)（单一身份合法——闹钟=唤醒 tick，
   恒指最近到期，执行后必重排，设计 §7.1 已论证，勿改 per-schedule 身份）+
   Receiver when 加 MUSIC_DUE 分支 emit onMusicDue（不注入 MusicModule，X12/C1）

验证：Build 通过；代码审读对照 SystemEventModule 同构。
R8 输出 + 进度表 + commit。
```

### Step MU4.2b — MusicModule 调度执行（2 文件）

```
执行 Zero V2 音乐轨 Step MU4.2b（调度执行+FC）。项目在 D:\ZeroV2。
先读设计 §7.1/§7.3 + §5.4 状态机 + SystemEventModule.execute()（:418 同构标杆），再动手。

文件（2 文件）：
1. MusicModule.kt — 实现 AgentJob（id="music_scheduled"，nextRunTime=最近 active triggerAt）：
   execute()=到期扫描（status=active 且 trigger_at<=now）→ 逐条原子认领
   UPDATE...SET status='firing' WHERE id=? AND status='active'（Mutex+@Transaction，
   受影响行=0 即被另一路径认领→跳过，M3/MD14）→ FIXED playPlaylist / AUTO 占位（MU5.1 接）
   → 推进（一次性→fired；周期→active+trigger_at=下期原相位 +=ms while 越过 now，F17）
   → scheduleNextMusicAlarm() 重排下一最近 + FC ×3：music_schedule(CONFIRM，
   拒绝过去时刻)/music_list_schedules(AUTO)/music_cancel_schedule(CONFIRM→cancelled)
   + onStart 恢复 G6 重排 + 订阅 systemBus.onMusicDue→execute + scheduleWake（闹钟+kick 双路径）
2. AppBindingModule — @Binds @IntoSet 补绑 AgentJob（SystemEventModule 双绑定先例 :91-92）

验证：music_schedule(+1min,playlist_id,FIXED)→准点播；双调度相隔1分钟各自触发不互覆（M2）；
前台双路径同刻仅执行一次（trace 认领竞争，M3）；重启恢复重排。
Build + R8 输出 + 进度表 + commit。
```

### Step MU4.3 — 定时 UI（2 文件）

```
执行 Zero V2 音乐轨 Step MU4.3（定时 UI）。项目在 D:\ZeroV2。

文件（2 文件）：MusicScreen/ViewModel — 定时列表（时间/模式/状态徽标 active|firing|
fired|missed|skipped_cap|cancelled）+ 新建（时间/歌单或 hint/模式/周期）+ 取消。

验证：UI 建/列/取消与 FC 联动。Build + R8 输出 + 进度表 + commit。
```

### Step MU5.1 — AUTO 自主链（3 文件）

```
执行 Zero V2 音乐轨 Step MU5.1（AUTO 触发链）。项目在 D:\ZeroV2。
先读设计 §7.2（双门控）+ C3 裁决 + TriggerHub.fireTraced/dailyTriggerCap 现状，再动手。

文件（3 文件）：
1. TriggerHub.kt — +friendlySource("SCHEDULED_PLAYBACK"→"🎵 定时播放") +
   TriggerHubConfig.musicScheduledTriggerEnabled（TOGGLE）+ 公开 fireMusicSchedule(payload,hint)
   helper（内部调私有 fireTraced，C3）
2. AutonomousThinkingJob.kt — buildPrompt() when 加 "SCHEDULED_PLAYBACK"→buildMusicPrompt：
   按 hint 用 music_search/music_list/playlist_create(AUTO)/music_play 建选歌单播放；
   明示禁用一切 CONFIRM FC（music_set_queue/playlist_delete/music_rename/music_delete 等，
   MD18——CONFIRM 卡死自主回合）
3. MusicModule.kt — execute() AUTO 分支：先过音乐日额度 scheduledAutoCap（模块内当日计数，
   超顶→一次性 skipped_cap/周期跳过本期，trace music_auto_cap_reached 不 fire，M5）→
   通过才 fireMusicSchedule（TriggerHub dailyTriggerCap 第二道自动生效）

验证：music_schedule(+1min,mode=AUTO,hint="学习轻音乐")→到点自主思考→LLM 检索建歌单播放；
气泡"🎵 定时播放"；AUTO 回话零 CONFIRM 弹窗（M11）；scheduledAutoCap 触顶 skipped_cap+trace（M5）。
Build + R8 输出 + 进度表 + commit；真机全链交用户 L3。
```

### Step MU5.2 — Prompt 打磨 + 编排说明（1 文件 · 文档）

```
执行 Zero V2 音乐轨 Step MU5.2（Prompt 打磨+文档）。

文件（1 文件，文档）：基于 MU5.1 实测反馈打磨 buildMusicPrompt（措辞/失败降级/歌单命名规范）；
文档说明 AutomationModule 可编排音乐 FC（不改 AutomationModule 代码）。

R8 输出 + 进度表 + commit。本轨收官，输出全轨验收清单交用户 L3。
```

---

## 音乐轨防漂移锚点（每会话自检 · 摘自落地计划 §十）

- **决策封板**：MD1–MD18 为基线；"更好方案"仅 backlog 提案。
- **路径模型（MD13）**：`relative_path` 唯一物理定位键；`display_title` 永不定位；DAO 查-留-id-更新（禁裸 REPLACE 扫描）。
- **闹钟语义（MD14）**：单一 `REQUEST_MUSIC` tick + 到期扫描 + `active→firing` 原子认领 + 执行后重排——"per-schedule PendingIntent/scheduleId"即漂移。
- **目录常量（MD15）**：`MusicPaths.SUBDIR="llmmusic"`，不进配置。
- **扫描最终一致性（MD16）**：三元组增量 + `miss_count≥2` 才删 + 物理优先失败顺序——"扫描即删缺失行"即 M8 漂移。
- **统一前台入口（MD17）**：三入口全经 `ensurePlaybackService()`。
- **AUTO 纪律（MD18）**：`scheduledAutoCap`→`dailyTriggerCap` 双门控不可绕；AUTO 禁 CONFIRM FC；`playlist_create`=AUTO（M16 勘误）。
- **依赖红线**：services→app 禁止（C1，经 SystemBus）；MU2.2 不注入 LocalVoiceProvider（C2）。
- **通用**：迁移同 commit（X9）；chatHistory 纯净（MD11）；≤3 文件/Step；onAwake 自调 registerFunctions；E28 静默失败留痕。

---

## 相关文档

- 设计：`ZeroV2-音乐模块开发设计.md`（MD1–MD18 · 权威）
- 实施计划：`ZeroV2-音乐模块落地实施计划.md`（SR/C/G/F/M · 进度表权威）
- 开发指南：`ZeroV2-LLM开发指南.md`（跨轨 R1–R8 + 地雷 + 红线）
- 结构范式参照：`CLAUDE_CODE_PROMPT_SHOUSHU.md`（收束轨提示词，本档结构母本）
