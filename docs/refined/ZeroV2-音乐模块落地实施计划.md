# Zero v2 — 音乐模块落地实施计划

> 配套设计文档：`ZeroV2-音乐模块开发设计.md`。本计划含 **SR/C/G/F/M 交叉验证表**——交叉审查全文，确保无落地阻塞、符合开发规范。
> 步骤前缀 **MU**（避免与模块化轨 M0–M4 冲突）。
> **2026-09-11 GPT 外审勘误封板**：初版"无落地阻塞项"经外审推翻——2 P0（路径模型/闹钟语义）+ 5 P1 已修订落档（§M 裁定表，MD13–MD18），修订后可进 MU1.1。

---

## 一、文档定位

权威层级：`ARCHITECTURE_FINAL.md` > `DEVELOPMENT_DESIGN.md` > `ZeroV2-音乐模块开发设计.md` > **本计划** > `ZeroV2-LLM开发指南.md`。

每个 Step 遵循 LLM 开发指南 R1–R8：**Android ≤3 文件/Step**（Room 迁移组例外同提交）；先读后写；参照标杆；埋 AgentTrace；改后 `cmd //c "D:\ZeroV2\_build_apk.bat"` 自构建+自修复；完成后按 R8 输出停下待用户验证。

---

## 二、前置依赖与基准锚点

- **工作目录须已配置**：`WorkDirRepository.hasDirectory()` 须为 true（用户经 设置-工作目录 `ACTION_OPEN_DOCUMENT_TREE` 选定）。未配置时所有文件 FC 返回引导文本（B11 同律），不崩。
- **Room v14 现状**：`fallbackToDestructiveMigration` 开 → X9 地雷，每次迁移同提交。
- **标杆模块**：FC→`FilesModule`/`WebSearchModule`；前台服务→`LiveSessionService`；调度→`AutomationModule`/`SystemEventModule`；Room→`ReminderEntity`/`MIGRATION_11_12`/`MIGRATION_13_14`；状态→UI→`SystemEventModule`+`WorkFilesViewModel`；DI→`AppBindingModule`。
- **不可破坏基线**：三 Tab 不动、chatHistory 纯净、N6.6 生成式压缩、D19 单一 tick、FunctionRegistry 唯一、BudgetGuard 存在、D57 SAF 边界。

---

## 三、分阶段实施

### Phase MU1 — 库与扫描

**MU1.1（迁移组，同提交 X9）**：`MusicTrackEntity`（`core/data/.../entity/MusicEntity.kt` 新——**`relative_path`** 列 + `miss_count` 默认 0 + `UNIQUE(relative_path)` 索引，MD13/M8）+ `MusicDao`（`core/data/.../dao/MusicDao.kt` 新，tracks 部分；**查-留-id-更新模式**：`getByRelativePath` 命中→UPDATE 保留 id/play_count，**禁裸 `@Insert(REPLACE)` 承接扫描**——REPLACE 撞 UNIQUE 走删旧插新→playlist_items FK CASCADE 清空，设计 §5.5）+ `ZeroDatabase` v15+entity+`abstract fun musicDao()` + `DatabaseModule` `MIGRATION_14_15`+`provideMusicDao`+`addMigrations`。同提交 4 文件（迁移组例外）。
- 验证：构建通过；老库升级不抹数据（X9 L3 必测）。
- 仿 `MIGRATION_11_12`（CREATE TABLE `music_tracks`），NOT NULL 列 DEFAULT 与 `@ColumnInfo(defaultValue=)` 对齐。

**MU1.2**：`MusicModule` 骨架（`app/.../music/MusicModule.kt` 新：IZeroModule+config+onAwake/onStart+`music_scan`/`music_list`/`music_search`/`music_rename`/`music_delete` FC）+ `MusicPaths` 常量（`SUBDIR="llmmusic"`，MD15）+ `AppBindingModule` `@IntoSet` 绑定。**复用 S9.1 已落地 WorkDirRepository 原语**（`resolvePath`/`listTree`/`moveEntry`/`deleteFile`——M13 勘误：不新造 subdir 方法集）。扫描=**递归子树 + 增量三元组** `(relative_path, file_size, modified_at)`（M7）；缺失 `miss_count+1`，**连续 ≥2 才删行**（M8）；`music_rename`/`music_delete` 参数为相对路径 + **物理优先失败顺序**（SAF 成功→Room 失败→trace+reconcile，M9）；`music_delete` 回执附受影响歌单审计（M10）；`MediaMetadataRetriever` 抽元数据，DB 写 `Mutex`。3 文件。
- 验证：放 mp3 进 llmmusic（**根 1 首 + 子目录 2 首同名异目录** `Anime/C.mp3`/`Game/C.mp3`）→ `music_scan` → `music_list` 三条 `relative_path` 互异且均可定位播放；**路径安全**：`../`、绝对路径、空段全拒；`music_rename`/`music_delete` 同步 DB；移走文件扫 1 次行保留、扫 2 次行删；未配工作目录返回引导。

**MU1.3**：`MusicScreen`（`app/.../ui/music/MusicScreen.kt` 新）+ `MusicViewModel`（新）+ `ZeroNavHost` 加路由（设置子路由）。3 文件。
- 验证：UI 列库+扫描按钮+搜索；杀进程重启库仍在。

### Phase MU2 — 播放

**MU2.1**：`app/build.gradle.kts` 加 `media3-exoplayer:1.5.1`+`media3-session:1.5.1` + `AndroidManifest.xml` 加 `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限+`MusicService`（`mediaPlayback` 类型）声明 + `MusicService` 骨架（`app/.../background/MusicService.kt` 新，仿 `LiveSessionService`：`@AndroidEntryPoint`+`startForeground` 按 `SDK_INT` 分支+`START_NOT_STICKY`+companion start/stop，注入 `MusicModule` collect `playbackState`→通知）。3 文件。
- 验证：构建通过；服务能前台启动。

**MU2.2**：`MusicModule` 播放核心（lazy `ExoPlayer` + `PlaybackState` StateFlow + `music_play`/`pause`/`resume`/`next`/`prev`/`seek`/`status`/`set_queue`/`shuffle`/`repeat` FC + `AudioFocusRequest` + **`ensurePlaybackService()` 统一前台入口**——`play()` 第一件事起服务，三入口共用，M6/MD17）+ `MusicService` 接 `playbackState`→通知更新。播放定位=`resolvePath(musicRoot, relative_path)` 逐级解析（MD13，非单级 findChild）。2 文件。
- 验证：`music_play`→前台通知+出声+`music_status` 位置/时长；`next`/`seek` 生效；`music_status` 返回队列。
- **音频协调按 C2 裁决**：MU2.2 仅用 `AudioFocus`；**不**注入 `LocalVoiceProvider`。语音暂停 gap 留 MU2.3 真机验。

**MU2.3**：`MusicScreen` now-playing UI（曲名/位置/进度/控制/队列）+ 真机验音频协调（G2）。1–2 文件。
- 若真机显示 TTS 期间音乐不暂停 → 按 G2 fallback 加 `SystemBus.onVoiceActive` 或给 TTS 加焦点请求（另立 Step）。

### Phase MU3 — 歌单

**MU3.1（迁移组，同提交）**：`PlaylistEntity`+`PlaylistItemEntity`（入 `MusicEntity.kt`）+ `MusicDao` 歌单查询 + `MIGRATION_15_16`（CREATE 2 表，外键 `CASCADE`+索引）+ `ZeroDatabase` v16+entities。同提交。
- 验证：构建通过；老库升级保留 tracks。

**MU3.2**：`MusicModule` 加 `playlist_create`/`list`/`delete`/`add_track`/`remove_track` FC + `MusicScreen`/`ViewModel` 歌单 UI。2–3 文件。
- 验证：`playlist_create`→`music_play playlist_id` 按序播放。

### Phase MU4 — 定时 FIXED

**MU4.1（迁移组，同提交）**：`ScheduledPlaybackEntity` + `MusicDao` 调度查询 + `MIGRATION_16_17` + `ZeroDatabase` v17。同提交。
- 验证：构建通过。

**MU4.2**：`SystemBus` 加 `onMusicDue: MutableSharedFlow<Unit>`（core:bus，仿 `onScheduleTriggered`，**C1 裁决**；Unit 载荷=tick 语义，M2）+ `ExactAlarmScheduler.scheduleMusic`+`ACTION_MUSIC_DUE`/`REQUEST_MUSIC` + `ExactAlarmReceiver` `MUSIC_DUE` 分支 `emit onMusicDue`（**不**注入 MusicModule，X12）+ `MusicModule`（`AgentJob.execute()` **到期扫描+原子认领**→FIXED 直播/AUTO fire + `music_schedule`/`list_schedules`/`cancel_schedule` FC + `scheduleWake`/重排 + onStart 恢复 G6 + 订阅 `onMusicDue`→`execute`）。3 文件（`ExactAlarmReceiver.kt`+`SystemBus` 合一 services/core 改 + `MusicModule`）。实际触及 `ExactAlarmReceiver.kt`/`SystemBus.kt`+`SystemBusImpl.kt`/`MusicModule.kt` → 按 ≤3 拆为两 Step：
  - **MU4.2a**：`SystemBus`(`core/bus/.../SystemBus.kt`)+`SystemBusImpl`(`core/bus/.../SystemBusImpl.kt`) 加 `onMusicDue`（仿 `onScheduleTriggered`，core:bus 2 文件；**Unit 载荷保持**——tick 语义，M2）+ `ExactAlarmReceiver`(`services/.../agent/ExactAlarmReceiver.kt`，含 `ExactAlarmScheduler`) 加 `scheduleMusic`/`ACTION_MUSIC_DUE`+`MUSIC_DUE` 分支 `emit`（services 1 文件；**单一 `REQUEST_MUSIC=4003` 合法**——闹钟=唤醒 tick 非 per-schedule 身份，恒指最近到期，执行后必重排，设计 §7.1 已论证）。共 3 文件，≤3 合规。
  - **MU4.2b**：`MusicModule`（AgentJob.execute **到期扫描+原子认领**：`UPDATE...SET status='firing' WHERE id=? AND status='active' AND trigger_at<=now`，`Mutex`+`@Transaction`，受影响行=0 即弃——ExactAlarm/kick 双路径幂等，M3/MD14 + 调度 FC（**拒绝过去时刻**，F17）+ scheduleWake/scheduleNextMusicAlarm 重排 + onStart 恢复 G6 原相位 + 订阅 onMusicDue）+ `AppBindingModule` 补绑 AgentJob（`SystemEventModule` 双绑定先例：`bindSystemEvent`+`bindSystemEventJob`，AppBindingModule:91-92）。2 文件。
- 验证：`music_schedule(time,playlist_id,FIXED)`→息屏 Doze 准点播；重启恢复；一次性过期→missed、周期→推进；**双调度并发**（相隔 1 分钟两条）各自准点互不覆盖（M2）；**前台双路径同刻**仅执行一次（M3）。

**MU4.3**：`MusicScreen`/`ViewModel` 定时 UI（列表/建/取消）。2 文件。

### Phase MU5 — 自主 AUTO

**MU5.1**：`TriggerHub` 加 `friendlySource("SCHEDULED_PLAYBACK")`+`TriggerHubConfig.musicScheduledTriggerEnabled`+公开 `fireMusicSchedule(payload,hint)` helper（C3 裁决：`fireTraced` 私有→加公开入口）+ `AutonomousThinkingJob` `buildPrompt()` 加 `"SCHEDULED_PLAYBACK"→buildMusicPrompt`（**明示禁用 CONFIRM FC**、只走 `playlist_create→music_play`，M11/MD18）+ `MusicModule.execute()` AUTO 分支**先过音乐日额度**（`scheduledAutoCap` 独立计数，超顶→`skipped_cap`+trace 不 fire，M5）再调 `triggerHub.fireMusicSchedule(...)`（TriggerHub `dailyTriggerCap` 第二道门自动生效）。3 文件。
- 验证：`music_schedule(time,mode=AUTO,hint="学习轻音乐")`→到点触发自主思考→LLM 调 `music_search`+`playlist_create`+`music_play`→出声；bubble 显示"🎵 定时播放"；AUTO 回话零 CONFIRM 弹窗（M11）；`scheduledAutoCap` 触顶→`skipped_cap`+trace 拒绝（M5）；`dailyTriggerCap` 触顶有 trace。

**MU5.2**：Prompt 路由打磨 + 文档说明可由 AutomationModule 编排音乐 FC（不改 AutomationModule）。1 文件（文档）。

---

## 四、§进度表

| Step | 内容 | 状态 |
|---|---|---|
| MU0.1 | **勘误封板**（GPT 外审 M 表 16 项裁定落档：2 P0 修订 + 8 P1 落 Step + 2 P2 采纳 + 4 项自查/裁定记录；设计 MD13–MD18 新增；纯文档零代码） | ✅ 2026-09-11 |
| MU1.1 | MusicTrackEntity（relative_path+miss_count） + MusicDao + v15 + MIGRATION_14_15 + DatabaseModule | ✅ | Build ✅ APK 09-11 23:12（4 文件同 commit X9：Entity——relative_path 唯一定位键+UNIQUE 索引+miss_count，DEFAULT 与 @ColumnInfo 逐项对齐、added_at 无 DEFAULT；DAO——查-留-id-更新两半（updateMetadataByPath 保留 id/play_count/last_played_at/added_at+miss_count 清零 / insertTrack 无 REPLACE 策略冲突即抛）+searchTracks LIKE+rename/delete 路径同步，MU1.2 FC 所需查询一次到位免后续动 DAO；MIGRATION_14_15 CREATE TABLE+UNIQUE INDEX 仿 11_12）；**老库升级不抹数据 = L3 必测待用户（v14→15 覆盖安装）** |
| MU1.2 | MusicPaths 常量 + MusicModule 骨架 + scan/list/search/rename/delete FC（复用 S9.1 原语） + 绑定 | ✅ | Build ✅ APK 09-11 23:23（3 文件：MusicModule.kt 新——MusicPaths.SUBDIR 常量（MD15）+MusicModuleConfig 5 字段（§十一）+launchOrder=120+onAwake 自调 registerFunctions+autoScanOnStart 后台扫；FC×5：music_scan/list/search(AUTO)+music_rename/delete(CONFIRM)；**扫描引擎 MD16**：listTree 递归过滤 llmmusic/ 子树+增量三元组跳过 MMR+miss_count≥2 才删+**枚举可疑守卫**（零音频但库存非空→跳过缺失处理防 SAF 抖动误删）+MMR 失败回落基名；**物理优先 M9**：rename=moveEntry(inSubdir)+DB 同步+失败 reconcile 重扫；delete=deleteFile+审计位（M10 歌单表 MU3.1 后接线恒 0）；validateRelative 拒空/绝对/..-段；WorkDirRepository +resolveInSubtree 薄出口（M13 复用私有原语）；AppBindingModule 绑定）；**三层路径区分/路径安全/miss 两轮收敛/DB 同步 = L3 必测待用户（需真机放 mp3）** |
| MU1.3 | MusicScreen + MusicViewModel + 路由 | ✅ | Build ✅ APK 09-11 23:36（3 文件：MusicScreen.kt 新——**VM 同文件**（WorkFilesScreen 惯例）注入 MusicDao+WorkDir+FunctionRegistry；**扫描经 FC 程序化通道** `execute("music_scan")`（S4.2 先例——UI 与 LLM 同一实现路径零另造防漂移）；搜索=内存即时过滤（标题/艺术家/路径）；曲目卡含 miss_count>0 "缺失中" 徽标；ZeroNavHost +music_library 路由+onNavigateToMusic 接线；SettingsScreen +音乐库入口行（LibraryMusic，工作目录行后，设置子路由 D122 三 Tab 不动）；自修复 1 处：FunctionResult.content→.data/.errorMessage 字段名；**UI 列库/扫描/搜索/杀进程重启库仍在 = L3 待用户**；**MU1 轨收官**） |
| MU2.1 | media3 依赖 + manifest + MusicService 骨架 | ✅ | Build ✅ APK 09-12 20:15（3 文件：build.gradle.kts +media3-exoplayer/session 1.5.1（R5 本步显式声明，SR12 minSdk26 满足）；Manifest +FOREGROUND_SERVICE_MEDIA_PLAYBACK（SR10）+ MusicService mediaPlayback 声明；MusicService.kt 新——仿 LiveSessionService：3-arg startForeground SDK_INT 分支（29+/30+）+START_NOT_STICKY+onTaskRemoved→stopSelf（§8.2b v1 语义：进程死=播放终止）+companion start/stop（start 经 MD17 ensurePlaybackService 单入口）+channel zero_music/ID 2002+ic_stat_zero 小图标；MU2.2 接线位已留（serviceScope+注释）；构建 3m31s 真实拉取 media3）；**服务前台启动（通知可见）= L3 待用户（MU2.2 起播后一并验）** |
| MU2.2 | ExoPlayer + PlaybackState + 播放 FC + AudioFocus + ensurePlaybackService 统一入口 | ✅ | Build ✅ APK 09-12 20:21（2 文件：MusicModule——lazy ExoPlayer（playerDelegate.isInitialized 探测防无谓构造）+ withPlayer Main 收敛访问口（异常上抛调用方留 trace，E28）+ Player.Listener 五事件刷新 PlaybackState StateFlow（文件顶层类型：tag=relative_path 溯源）+ **ensurePlaybackService() MD17 单入口**（startQueue 起播先过）+ AudioFocusRequest C2（LOSS/TRANSIENT→暂停+GAIN 续播，仅焦点不注入 LocalVoiceProvider）+ 队列播完 ENDED→停服务（§8.2a v1 语义）+ onDestroy release+abandonFocus；FC×9：play/pause/resume/next/prev/seek/status/shuffle/repeat(AUTO)+set_queue(CONFIRM)——play 参数 track_id/track_ids 现役、playlist_id 占位返"随 MU3 开放"（MD12 schema 稳定渐进实现）；定位=resolveInSubtree 逐级（MD13）；defaultVolume 应用；MusicService——注入 MusicModule collect playbackState→通知动态更新（曲名·艺术家·播放态）；**play_count 递增延 MU3.1（DAO 扩展步）已记偏差**；**真机起播/通知/AudioFocus/双入口= L3 待用户**；**fix（L3 实测 09-12 20:42 · 播放全报错双根因）**：① music_list/search 渲染 `id.take(8)` 截断——LLM 只能拿短 id，getTrackById 完整 UUID 直查恒"曲目不存在"→ 改显**完整 id** + FC 描述注明；② `track_ids as? List<Any>` 恒 null——parseToolArguments（ChatBotModule:590 org.json）数组产物为 **JSONArray** → `stringListArg` 双类型兼容收口（play/set_queue 两处）；③ 存量短 id 容错：resolveTrackId 完整直查失败且 <36 位 → 唯一前缀命中回退（resolveFileRef 唯一匹配同域先例），多命中=歧义报错防误播（trace short_id_ambiguous）） |
| MU2.3 | now-playing UI + 真机音频协调验 | ✅ | Build ✅ APK 09-12 20:56（2 文件：MusicModule——+`playbackPosition()` 实时位置出口（playerDelegate.isInitialized 探测免无谓构造；与 music_status 同源）；MusicScreen——now-playing 卡（曲名/艺术家/队列 i/N/播放暂停 FilledIconButton/Slider 拖动 seek（拖动态分离防跳变）/随机/循环高亮切换/上下首）+ **1s ProgressTicker**（播放中经模块出口实时刷位置——PlaybackState 事件点快照 + ticker 补连续进度）+ **列表项点按即播**（UI 第二播放入口汇入 FC 通道同一实现路径 MD17 精神）+ 控制 6 项全走 execute FC 通道（togglePlayPause/next/prev/shuffle/repeat 循环切换/seek）+ repeat 常量镜像（UI 层不直接依赖 media3）；自修复 1 处：非法链式全限定 clickable→import；**G2 真机音频协调（起 Live 语音→音乐是否让位）= L3 关键验证待用户——不暂停则按 C2 fallback 另立步加 onVoiceActive**；**MU2 轨收官**（播放全链：UI/LLM 双入口齐）；**fix（L3 实测 09-12 21:16 · 三项）**：① **shuffle 下 next 假成功**——随机序末尾 hasNext=false 时原 block 无操作但不抛异常恒返 ✅（现象"连续 next 不切歌"根因，非 ExoPlayer bug）→ `skipTo()` 如实返回：切动=成功附新曲信息，端点=failure 提示三条出路；② **UI 点按改"从该曲起播可见列表"**（原单曲播放队列仅 1 首致 next 无处切——反馈#1"新歌未入列"即此）→ `playFrom(index, ids.drop(index))` 经 FC 通道 music_play(track_ids)；③ **G2 触发 C2 fallback 落地（Live 语音不让位实测）**——`SystemBus.onVoiceActive`（DROP_OLDEST replay1）+ LiveSessionService onCreate/onDestroy emit（服务生命周期=会话生命周期）+ MusicModule onStart 订阅：true→暂停（voicePausedPlayback）/false→恢复（**focusPermanentlyLost 守卫**——焦点永久丢失不抢播；与 resumeOnFocusGain 瞬时语义分离）；isPlaying 判定收敛 Main 线程；**fix2（L3 实测 09-12 21:27 · 文件夹 4 首队列仅 3）**：UI 点按原 `drop(index)`=从点按曲到列表尾——点按非首曲天然缺前面曲目 → 改**轮转**（点按曲置顶+其余按原序跟随，全可见列表入队） |
| MU3.1 | PlaylistEntity + PlaylistItemEntity + MIGRATION_15_16 + v16 | ✅ | Build ✅ APK 09-12 21:31（X9 迁移组 4 文件同 commit：MusicEntity——PlaylistEntity（created_by DEFAULT 'user'/source_prompt 可溯）+PlaylistItemEntity（**双 FK CASCADE**+playlist_id/track_id 双索引；M10 注释：删 Track 级联清项是静默的——music_delete 须先查审计）；MusicDao——歌单域 11 方法一次到位（MU3.2 不动 DAO）：insertPlaylist/loadPlaylists/getPlaylistById/deletePlaylist/insertPlaylistItem/deletePlaylistItem/loadPlaylistItems/tracksOfPlaylist(JOIN 按 position)/nextPlaylistPosition(IFNULL MAX+1)/countPlaylistsContainingTrack/countPlaylistItemsOfTrack（M10 审计双查）+ **incrementPlayCount（MU2.2 补账——接线随 MU3.2 MusicModule 步）**；MIGRATION_15_16 CREATE 2 表+双索引（FK 子句 ON UPDATE NO ACTION ON DELETE CASCADE 与 Room 生成一致）；ZeroDatabase v16+2 entities）；**老库升级保留 tracks = L3 必测待用户（v15→16 覆盖安装）** |
| MU3.2 | playlist FC + 歌单 UI | ✅ | Build ✅ APK 09-12 21:37（2 文件：MusicModule——FC×5：**playlist_create=AUTO（M16 勘误：source_prompt 参数=LLM 建单意图→created_by=llm 标记，用户直建=user）**/playlist_list(AUTO)/playlist_delete/add_track/remove_track(CONFIRM)；from_track_ids 复用 stringListArg JSONArray 兼容+resolveTrackId 短 id 容错；**三项接线补账**：①music_play(playlist_id) 实装（tracksOfPlaylist 按 position 起播）②play_count 递增（playerListener onMediaItemTransition——含初始/单曲循环，暂停恢复不重复计；失败留 trace）③M10 审计接线（music_delete 删前 countPlaylistsContainingTrack/countPlaylistItemsOfTrack→回执"影响歌单 N 个/移除 M 条"）；MusicScreen——歌单区（列表含曲数/源提示/播放 FilledTonalButton/删除确认对话框/**从可见列表建单**对话框名输入；删除与建单对话框承担 CONFIRM 用户确认角色=UI 主权确认语义等价）；自修复 2 处：非法 text2 参数残留、Delete 图标 import；**加减曲 UI 未做（FC 完备，UI 增强后续——已记偏差）；playlist_create→play 按序播放 = L3 待用户**；**MU3 轨收官** |
| MU4.1 | ScheduledPlaybackEntity + MIGRATION_16_17 + v17 | ✅ | Build ✅ APK 09-12 21:47（X9 迁移组 4 文件同 commit：MusicEntity——ScheduledPlaybackEntity（MD14 状态机六态常量入 companion：active→firing→fired/missed/skipped_cap/cancelled；playlist_id 弱引用无 FK 注释；mode FIXED/AUTO）；MusicDao——调度域 13 方法一次到位（MU4.2b 不动 DAO）：**claimSchedule 条件更新返回受影响行数=MD14 原子认领幂等落点**（WHERE id AND status='active' AND trigger_at<=now）/loadDueSchedules 到期扫描/markFired/advanceRecurring（回 active+下期，F17 相位保持由调用方算）/markMissed/markSkippedCap/cancelSchedule（条件防重复取消）/nextActiveTriggerAt（MIN 重排用）/loadStaleOneShots（G6 恢复期陈旧一次性）+ 基础三查；MIGRATION_16_17 单表 CREATE（DEFAULT 逐项对齐）；ZeroDatabase v17）；**老库升级 = L3 必测（v16→17）** |
| MU4.2a | SystemBus.onMusicDue + ExactAlarm scheduleMusic/MUSIC_DUE | ✅ | Build ✅ APK 09-12 21:56（3 文件：SystemBus+Impl——`onMusicDue: MutableSharedFlow<Unit>`（**Unit 载荷=tick 语义 M2**——身份由到期扫描+认领决定，防漂移注释入档；仿 onScheduleTriggered SUSPEND 不可丢帧，订阅方幂等重复消费无害）；ExactAlarmReceiver——`scheduleMusic(t)=schedule(ACTION_MUSIC_DUE, REQUEST_MUSIC=4003, t)`（**单一身份合法性论证入代码注释**：闹钟=唤醒 tick 非 per-schedule 身份，恒指最近到期+执行后必重排——M2 裁定钉死防实现漂移）+ MUSIC_DUE 分支 `systemBus.onMusicDue.emit(Unit)`（**不注入 MusicModule——X12/C1**，注入 SystemBus core:bus）+ cancelAll 补 MUSIC 项；订阅方（MusicModule collect→execute）随 MU4.2b） |
| MU4.2b | MusicModule AgentJob.execute（到期扫描+认领）+ 调度 FC + onStart 恢复 | ✅ | Build ✅ APK 09-12 22:06（2 文件：MusicModule——**AgentJob 实装**（id=music_scheduled/nextRunTime 可变/execute=到期扫描→runDueSchedule 逐条→重排）；**MD14 认领落位**：`claimSchedule` 行数=0→trace claim_lost 放弃（ExactAlarm/kick 双路径幂等）；**F17 原相位**：周期推进 `+=ms while 越过 now`；**scheduleNextMusicAlarm**=闹钟（exactAlarmScheduler.scheduleMusic 单身份）+kick 快路径（wakeJob 取消旧）+nextRunTime 三合一重排；**G6 恢复**：onStart 陈旧一次性（<now-5min 宽容窗）→missed/过期周期→推进/刚过期→execute 照播；**onMusicDue 订阅**（onStart，tick→execute）；FC×3：music_schedule(CONFIRM——parseMusicTime 三段仿 parseTime：+Ns/m/h/d、21:30 今日已过顺延明天、完整时间；**拒过去时刻 F17**；FIXED 需 playlist_id；周期 interval≥60s)/music_list_schedules(AUTO 状态化)/music_cancel_schedule(CONFIRM 条件更新防重复+取消后重排)；**AUTO 到期执行体占位**（trace auto_pending_mu51+markFired 不卡 firing 态——FC 描述注明"即将开放"，MU5.1 接线）；AppBindingModule——bindMusicJob 双绑定（SystemEventModule:91-92 先例）；自修复 1 处：无用 staleCountForTrace 残段删除）；**双调度并发/双路径幂等/Doze 准点/重启恢复 = L3 待用户（FIXED 可全验，AUTO 待 MU5.1）** |
| MU4.3 | 定时 UI | ✅ | Build ✅ APK 09-12 22:21（1 文件 MusicScreen 同文件 VM 惯例：**定时区**——列表卡（时间 MM-dd HH:mm+周期标注/模式/六态状态徽标色彩化（active 主色待触发/missed·skipped_cap error/cancelled·fired 变暗）/歌单名 join 映射（歌单已删显"歌单已删"）或 hint；active 行取消按钮+确认对话框）；**ScheduleDialog 新建**——时间文本（+1m/21:30/完整时间三段与 FC 解析一致）/模式 FilterChip（FIXED 歌单多选 chip/AUTO hint 输入+“即将开放”标注）/周期 Checkbox+间隔秒；提交全走 FC 通道 music_schedule/cancel（对话框确认=用户主权确认）；setSchedule/cancelSchedule 后 refresh 联动；自修复 1 处：onConfirm 实参闭括号错位致 enabled 悬挂；**UI 建/列/取消与 FC 联动 = L3 待用户**；**MU4 轨收官**（FIXED 全链：FC+UI+调度+闹钟）；**fix（L3 实测 09-12 22:38 · 两项 UI 缺陷）**：① 终态定时项（fired/missed/cancelled）无删除入口累积挤占 → DAO +`deleteSchedule` 物理删除 + 终态行清除按钮（无业务价值免确认直接删，trace 留痕）；② **曲目列表仅一行可见**——整页原为固定高 Column，歌单/定时区非 Lazy `forEach` 直接展开挤死内嵌曲目 LazyColumn → **整页重构单一 LazyColumn**（扫描/now-playing/搜索 item 化、歌单/定时卡 `item(key)` 化、曲目 items 并入尾部，嵌套 LazyColumn 消除，页面整体滚动，spacedBy 12 统一） |
| MU5.1 | TriggerHub 源 + AutonomousThinkingJob 路由 + AUTO 分支 | ✅ | Build ✅ APK 09-12 22:56（3 文件：TriggerHub——friendlySource+"🎵 定时播放"+TriggerHubConfig.musicScheduledTriggerEnabled(TOGGLE,json 三处)+**公开 `fireMusicSchedule(payload,hint):Boolean`**（C3：fireTraced 私有→公开入口；内检开关→dailyTriggerCap 配额（BATTERY 同模式）→fireTraced 气泡"🎵 定时播放"——**第二道门自动生效不可绕 M5**）；AutonomousThinkingJob——回应徽标 when+buildPrompt 路由 SCHEDULED_PLAYBACK→**buildMusicPrompt**（hint 从 payload 提取；检索→playlist_create(source_prompt)→music_play 链；**明示禁用全部 CONFIRM FC 逐个点名**（music_set_queue/playlist_delete/add/remove/music_rename/music_delete/music_schedule——M11/MD18）；库空才 autonomous_notify）；MusicModule——AUTO 占位→实装：**第一道门 acquireAutoQuota**（音乐域日额度，prefs day+count 仿 TriggerHub 模式）→fireMusicSchedule 第二道→**被拒分流**（一次性 skipped_cap/周期公共段推进不终态阻塞）+公共推进段条件化（executed 才 markFired，防 skipped_cap 被覆盖）+注入 TriggerHub（SR4 一向依赖）+FC 描述/回执去"即将开放"；**AUTO 全链 = L3 待用户**（到点自主建单播放/气泡🎵/零 CONFIRM 弹窗/触顶 skipped_cap）；**偏差：ScheduleDialog "AUTO（即将开放）"文案残留——随 MU5.2 顺带清** |
| MU5.2 | Prompt 打磨 + AutomationModule 编排说明 | ✅ | Build ✅ APK 09-13 00:17（3 文件：AutonomousThinkingJob——buildMusicPrompt +曲目不足降级（<3 首跳建单直接 track_ids 播）；MusicScreen——"AUTO（即将开放）"文案清理（MU5.1 偏差收口）；设计文档 +§十b AutomationModule 编排说明（音乐 FC 经 FunctionRegistry 同一寻址空间，PLAN/REACT 可直调；权限约束照常；自动化=显式任务流 vs 定时=音乐域原生调度，互不替代；**说明性文档 AutomationModule 零改动**））；**MU 轨 15/15 全收官**（MU0.1 勘误封板 + MU1 库/MU2 播放/MU3 歌单/MU4 定时/MU5 自主链）——全轨 L3 验收清单见收束记录 |

---

## 五、§SR 实现级事实/风险表

| # | 级 | 事实/风险 | 处置 |
|---|---|---|---|
| SR1 | 🟠 | 音乐依赖工作目录已配置（SAF tree Uri） | 未配置时 FC 返回引导文本（B11），UI 提示"请先选工作目录" |
| SR2 | 🔴 | `ExactAlarmReceiver`（services）不可注入 app 层 `MusicModule`（X12） | **C1**：经 `SystemBus.onMusicDue` 解耦 |
| SR3 | 🟡 | `TriggerHub.fireTraced` 为私有，`MusicModule` 无法直调 | 加公开 `fireMusicSchedule(...)` helper（C3） |
| SR4 | 🟠 | `MusicModule`(app) 注入 `TriggerHub`(services) 一向依赖，无 cycle（TriggerHub 不反向依赖 MusicModule） | 确认无 cycle，可注入 |
| SR5 | 🟡 | `MusicModule` 同为 IZeroModule+AgentJob，双 `@IntoSet` 绑定 | `SystemEventModule` 同模式先例（**实证**：`AppBindingModule.kt:91-92` `bindSystemEvent`+`bindSystemEventJob` 并存），仿之 |
| SR6 | 🟡 | Hilt multibinding 收集 app 层 AgentJob 给 services 的 `Scheduler` | 图在 app 编译期合并，跨模块 multibinding 成立；构建期验证 |
| SR7 | 🟠 | SAF 列大库慢（无 File API，`DocumentsContract` 逐项） | `moduleScope` 后台扫描+**增量三元组 `(relative_path, file_size, modified_at)`**（M7：全一致跳过 `MediaMetadataRetriever`；不一致/新增才重抽）+trace+超时 |
| SR8 | 🟡 | `MediaMetadataRetriever.setDataSource(ctx, contentUri)` 读 SAF Uri | L2 真机验（MU1.2） |
| SR9 | 🟡 | Media3 `DefaultDataSource` 播 SAF `content://` Uri | L2 真机验（MU2.2） |
| SR10 | 🔴 | `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限（targetSdk 34+ 必需）+ `mediaPlayback` FGS 类型 | manifest 声明（MU2.1）；`startForeground` 3-arg 按 `SDK_INT` 分支仿 `LiveSessionService` |
| SR11 | 🟡 | sherpa TTS 用 `AudioTrack` 未必申请 AudioFocus → 音乐可能不暂停 | **C2**：MU2.2 仅 AudioFocus；gap 留 MU2.3 真机验，必要时加 `SystemBus.onVoiceActive` |
| SR12 | 🟢 | media3 1.5.1 需 minSdk 21 | 项目 minSdk 26，满足 |
| SR13 | 🟡 | FC 名 `music_*`/`playlist_*` 须不与既有冲突 | MU1.2 前后 grep 全量 FC 名确认（既有无此前缀） |
| SR14 | 🟢 | `MusicDao` 外键 `playlist_id` 须索引（Room 要求） | `MIGRATION_15_16` 加 `CREATE INDEX` |

---

## 六、§C 交叉审查冲突裁决表（核心——落地阻塞项）

| # | 级 | 冲突 | 裁决 |
|---|---|---|---|
| **C1** | 🔴P0 | 设计 §7.1 写"`ExactAlarmReceiver` MUSIC_DUE 分支注入 `MusicModule` 调 `execute()`"——**X12 违规**：receiver 在 services，MusicModule 在 app，services 不可依赖 app，**构建失败**。 | **改经 `SystemBus.onMusicDue: MutableSharedFlow<Unit>`（core:bus）解耦**：receiver（services）`emit`；`MusicModule`（app）`onStart` collect→`execute()`。先例：`SystemBus.onScheduleTriggered` 正是 v2.1 为解耦 SystemEventModule→AutomationModule 而设。**设计 §7.1/MD5 据此修订**（见下"设计修订"）。 |
| **C2** | 🟠P1 | 设计 MD9 写"`MusicModule` 注入 `LocalVoiceProvider` collect `RealtimeState` 暂停音乐"——耦合 app↔realtime 且可能过早触发 sherpa 模型构造。 | **MU2.2 仅用 `AudioFocus`（解耦）**；`LocalVoiceProvider` 订阅降为 fallback：仅当 MU2.3 真机显示 TTS 期间音乐不暂停时，才加 `SystemBus.onVoiceActive` 事件（core:bus，broadcast）让音乐订阅。避免无谓注入。**设计 MD9 据此修订**。 |
| **C3** | 🟡P2 | 设计 §7.2/MD6 写"`MusicModule` 调 `triggerHub.fireTraced(...)`"——`fireTraced` 为私有方法。 | `TriggerHub` 加**公开** `fireMusicSchedule(payload, hint, title, detail)` helper（内部调 `fireTraced`）。MU5.1 落地。 |
| C4 | 🟢 | `MusicModule` 既是 IZeroModule 又是 AgentJob，双 `@IntoSet` 是否合法 | 合法，`SystemEventModule` 同模式先例（C 表确认）。 |
| C5 | 🟢 | 三次迁移 vs 一次迁移全表 | 分三次（各阶段一次）降变更面、配合 X9 同提交；优于一次性大迁移。 |

---

## 七、§G 外部审查/开放项表

| # | 级 | 开放项 | 处置/验证点 |
|---|---|---|---|
| G1 | 🟠P1 | SAF 大库扫描耗时/电量 | 增量扫描+后台+trace；首次扫描给进度反馈 |
| G2 | 🟠P1 | sherpa TTS 与音乐音频协调真机未验 | MU2.3 真机测；gap 则 fallback `SystemBus.onVoiceActive`（C2） |
| G3 | 🟡P2 | ExoPlayer 播 SAF content Uri 真机未验 | MU2.2 L2 验；失败则 fallback 临时拷 app 私有目录再播 |
| G4 | 🟡P2 | Hilt app 层 AgentJob 收集给 services Scheduler | MU4.2b 构建期验证（先例少，但机制成立） |
| G5 | 🟢 | FC 名 grep 确认无冲突 | MU1.2 前 grep |
| G6 | 🟡P2 | `ExoPlayer` 长驻 `@Singleton` 电量 | 空闲超时释放（可选优化，不阻塞） |
| G7 | 🟢 | mediaPlayback FGS 在 Android 14 partial 行为 | 仿 LiveSessionService `SDK_INT` 分支，真机验 |

---

## 八、§F 终审表（阻塞清零确认 · 2026-09-11 M 表勘误后）

| 项 | 结论 |
|---|---|
| 依赖方向 X12 | ✅ 清零——C1：调度经 `SystemBus.onMusicDue` 解耦，services 不注入 app |
| Room 迁移 X9 | ✅ 三次各同提交，老库升级 L3 必测 |
| FC 名冲突 | ✅ 既有无 `music_`/`playlist_` 前缀（MU1.2 grep 复核） |
| chatHistory 纯净 | ✅ 音乐事件不进 chatHistory；状态走 StateFlow；AUTO 气泡是 S2 投影 |
| 不可破坏禁区 | ✅ 不触 N6.6/D19/FunctionRegistry/BudgetGuard |
| 子树路径模型 | ✅ **M1 修订完毕**——`relative_path` 唯一定位键（原 `file_name` 不含子路径与"子树"目标结构性冲突，编码前已修） |
| 多调度闹钟 | ✅ **M2 修订完毕**——tick+到期扫描+认领语义钉死（对齐 Reminder 既有模式，单一 `REQUEST_MUSIC` 在该语义下正确） |
| 音频双放/自听 | ⚠ MU2.3 真机验（G2），有 fallback |
| FGS 权限/类型 | ✅ MU2.1 声明，仿 LiveSessionService |
| 模块双绑定 | ✅ SystemEventModule 先例（AppBindingModule:91-92 实证） |
| ≤3 文件/Step | ✅ 迁移组例外已注明，MU4.2 已拆 a/b |
| 构建验证 | ✅ 每 Step `_build_apk.bat` |
| **结论** | **初版"无落地阻塞项"判断过于乐观（GPT 外审证实）——2 个 P0 已于 2026-09-11 勘误封板修订完毕（M1 路径模型/M2 闹钟语义），5 个 P1 已落对应 Step（M3-M7），P2 采纳 2 项（M10/M12）；修订后可进 MU1.1 编码**。G2/G3/G4 为真机验证项，均有 fallback，不阻塞编码 |

---

## 八b、§M GPT 外审勘误封板表（2026-09-11 · 逐项代码实证裁定）

> 外审结论"2 P0 阻塞 + 5 P1 高风险"——逐项对照仓库代码核实后裁定。**外审 12 项全处置（2 项修正论据/方案后采纳）、自查新增 4 项（M13/M14/M15/M16）**。

| # | 级 | 外审主张 | 核实证据 | 裁定与落点 |
|---|---|---|---|---|
| M1 | 🔴P0 | `file_name` 不含子路径无法支撑 llmmusic 子树（同名异目录不可区分/不可定位） | 设计 §一"子树" vs §5.1"不含子路径" vs §8.3 单级 findChild——三者结构性矛盾，**成立** | ✅ 全盘采纳：`relative_path` 唯一物理定位键 + `UNIQUE` 索引（§5.1）；播放 `resolvePath` 逐级解析（§8.3）；rename/delete 参数改相对路径（§六）；增量键随之升级（M7）。MD13 |
| M2 | 🔴P0 | `REQUEST_MUSIC=4003` 单身份=多调度互相覆盖，须 per-schedule 身份 + `onMusicDue(scheduleId)` | **半对半错**：`SystemEventModule.execute()`（:418-428）=`due=count{active&&<=now}`→`processDueTasks`→`scheduleNextAlarm()`；`ExactAlarmReceiver`（:71 注释）"收到即执行到期扫描——与 Scheduler 路径幂等"——**单一身份+到期扫描是既有 proven 模式**，覆盖是"恒指最近到期"所需特性；但原设计未写明此语义（§7.1 只写单数 `execute()`），外审因此误判 | ⚠ **结论采纳、方案改向**：不引入 per-schedule PendingIntent/scheduleId 载荷（多一套 cancel 记账且偏离先例）；改为**显式钉死 tick 语义**（§7.1 重写：到期扫描+执行后必重排+替换特性论证），`onMusicDue` 保持 Unit。MD14 前半 |
| M3 | 🟠P1 | ExactAlarm+`delay()/kick()` 双路径可能双执行（AUTO 双触发=双 LLM 回合） | **成立**——`AgentJob.kt:12` 契约明言"实现方负责推进与幂等"，原设计未定义音乐侧认领 | ✅ 采纳：§5.4 状态机 `active→firing` 原子认领（`UPDATE...WHERE status='active'`，`Mutex`+`@Transaction`，受影响行=0 即弃）→ MU4.2b。MD14 后半 |
| M4 | 🟠P1 | `musicSubdir:String` 违反开发指南"类型仅 Int/Float/Boolean"，不能落地 | **论据不成立**：`ConfigFieldType.TEXT` 存在（ConfigField.kt:38）、写回路径 `else->value` 直通 String（ModuleDetailViewModel.kt:209）、S4.1 ASR 引擎 DROPDOWN 即 String ConfigField 先例——指南表述与代码现实不符（M15 已裁定修订）；**但结论方向对**：目录配置化→改目录名致整库 `relative_path` 失效 | ⚠ **结论采纳、理由改写**：`musicSubdir` 移出配置→`MusicPaths.SUBDIR` 编译期常量（§十一/MD15）；指南矛盾经 M15 裁定修订 |
| M5 | 🟠P1 | `scheduledAutoCap` 有配置无执行闭环 | **成立**——原 §7.2 仅 `dailyTriggerCap` | ✅ 采纳：双门控链 `scheduledAutoCap`（模块内当日计数，超顶→`skipped_cap`+trace 不 fire）→ `dailyTriggerCap`（TriggerHub :599 实证存在）→ MU5.1。MD18 |
| M6 | 🟠P1 | MusicService 启动责任未定义（FC/UI/调度三入口） | **成立** | ✅ 采纳：`ensurePlaybackService()` 统一入口（§8.2a/MD17）→ MU2.2 |
| M7 | 🟠P1 | 增量扫描"定义过于粗糙"（file_name+modified_at 不足） | **成立**（且 M1 后增量键必须重定义） | ✅ 采纳：三元组 `(relative_path, file_size, modified_at)` 全一致跳过 MMR（§六/SR7/MU1.2）。MD16 |
| M8 | 🟠P1 | 缺失立即删行→单次 SAF 异常可清空整库 | **成立**（`fallbackToDestructiveMigration` 同为 DB 域地雷，谨慎一致） | ✅ 采纳：`miss_count` 列，连续 ≥2 次才物理删；枚举异常跳过删除+trace（§5.1/§六）。MD16 |
| M9 | 🟠P1 | SAF/Room 双写无失败顺序定义 | **成立** | ✅ 采纳：物理优先+Room 失败→trace+立即 reconcile 扫描；扫描=最终一致性修复机制，不做伪事务（§九）。MD16 |
| M10 | 🟡P2 | `music_delete` 级联清歌单应告知 LLM 受影响面 | 合理且廉价 | ✅ 采纳：删前查受影响歌单，回执附 `affected_playlists/removed_items`（§六）。MU1.2 |
| M11 | 🟠P1 | AUTO 调 `music_set_queue`(CONFIRM) 会卡死自主回合 | **成立**（外审 §十三；原 §7.2 已走 playlist_create 路径但未禁用 CONFIRM） | ✅ 采纳：`buildMusicPrompt` 明示禁用全部 CONFIRM FC（§六/§7.2/MD18）→ MU5.1 |
| M12 | 🟡P2 | Service 被杀/进程死亡后播放状态恢复未定义 | 范围界定问题（非缺陷） | ⚠ 部分采纳：v1 **明确不做**进程死亡自动恢复（§8.2b 钉死语义：进程死=播放终止，库/歌单/调度 Room 恢复）；`currentTrackId+positionMs` 持久化→backlog 提案 |
| M13 | 🟢自查 | （外审未发现）MD7/§九 引用"属未开工 S9.1"已过时；拟新增 subdir 方法集与 S9.1 落地原语重复 | S9.1 已于 2026-09-10 落地：`resolvePath`/`listTree`/`moveEntry`/`deleteFile`/`resolveFileRef`/路径化写族全在 | ✅ 勘误：§九 重写为复用 S9.1 原语+`MusicPaths` 薄约定；MD7 修订 |
| M14 | 🟢自查 | （外审未发现）§十一 表格含 `String(text)` 与脚注"类型仅 Int/Boolean"自相矛盾 | 原稿即有 | ✅ 修正：musicSubdir 移除后脚注与表格一致 |
| M15 | 🟢记录 | 开发指南"ConfigField 类型仅 Int/Float/Boolean（D30 约束）"与代码现实不符 | D30 实为 kotlin-reflect 反射规范非类型限制；TEXT/SECRET/DROPDOWN/MULTILINE 枚举+渲染+写回全支持；S4.1 引擎 DROPDOWN=String 字段已上线 | ✅ **用户已裁定（2026-09-11）**：指南已修订——数值 Int/Float 显式 min/max + 开关 Boolean + 文本/选择类 TEXT/DROPDOWN/MULTILINE 按需 + **常数/约定不进 Config**（§3.1/§六命名表/§八索引加音乐轨行 同步） |
| M16 | 🟢自查 | （外审未发现）`playlist_create` 标 CONFIRM 与 MD4 用户裁定"AUTO=LLM 自主**建**歌单"矛盾——自主回合调 CONFIRM FC 即卡死，MD18 的 `playlist_create→music_play` 链路不可执行 | MD4/MD18/§7.2 三处均要求 AUTO 回合建歌单；FC 表权限与之冲突（原稿即有，首轮修订亦未抓出） | ✅ 勘误：`playlist_create` 定 **AUTO**（创建无破坏性可逆，D123 让渡精神一致）；破坏性歌单操作 delete/add/remove_track 保持 CONFIRM（§六 歌单表+勘误注） |

**M 表后：2 P0 修订封板、P1 全落 Step、P2 采纳/入 backlog、指南矛盾已裁定修订（M15）、自查勘误 3 项（M13/M14/M16）——文档可进 MU1.1 编码。**

---

## 九、§E 经验教训表（预登）

| # | 教训 | 触发点 |
|---|---|---|
| EM1 | services 层组件不可注入 app 层类（X12）——跨层触发一律经 `SystemBus` 事件解耦 | C1 |
| EM2 | 私有方法勿在设计里当公开接口写——落地前核对可见性 | C3 |
| EM3 | 跨层音频协调优先 AudioFocus（解耦），状态订阅作 fallback，避免过早注入重组件 | C2 |
| EM4 | SAF 无 File API，大库操作须增量+后台+trace | SR7 |

---

## 十、防漂移锚点（每会话自检）

- **决策封板**：MD1–MD18（含 2026-09-11 M 表勘误新增 MD13–MD18）为本轨基线；"更好方案"不是修改理由，仅 backlog 提案。
- **路径模型**：`relative_path` 唯一物理定位键（MD13）——`display_title` 永不承担定位职责；"改回 file_name"或"用标题定位"即漂移。
- **闹钟语义**：单一 `REQUEST_MUSIC` tick + 到期扫描 + 原子认领 + 执行后重排（MD14）——"给每调度独立 PendingIntent/scheduleId 载荷"即偏离 Reminder 先例的漂移。
- **依赖方向红线**：services→app 禁止（C1）；跨层触发经 SystemBus。
- **迁移同提交**：每次 Entity+Dao+版本+Migration 同 commit（X9）。
- **chatHistory 纯净**：音乐事件不进 chatHistory（MD11）。
- **≤3 文件/Step**：迁移组例外同提交；MU4.2 拆 a/b。
- **音频协调**：MU2.2 仅 AudioFocus；状态订阅为 fallback（C2）。
- **扫描最终一致性**：三元组增量 + miss_count≥2 + 物理优先失败顺序（MD16）——"扫描即删缺失行"回归即 M8 漂移。
- **AUTO 纪律**：双门控不可绕 + 禁 CONFIRM FC（MD18）。
- **真机验证项**：G2（TTS 协调）/G3（SAF 播放）/G4（Hilt 收集）——不阻塞编码，阻塞发版。

---

## 十一、相关文档

- 设计：`ZeroV2-音乐模块开发设计.md`
- 开发指南：`ZeroV2-LLM开发指南.md`
- 真相源：`ARCHITECTURE_FINAL.md`（根）
- 标杆：`FilesModule`/`LiveSessionService`/`SystemEventModule`/`AutomationModule`/`ReminderEntity`/`MIGRATION_11_12`/`MIGRATION_13_14`/`SystemBus.onScheduleTriggered`
