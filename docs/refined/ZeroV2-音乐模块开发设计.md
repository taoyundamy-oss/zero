# Zero v2 — 音乐模块开发设计

> 本轨（音乐轨，步骤前缀 **MU**，避免与模块化轨 M0–M4 冲突）为 Zero v2 增设**本地音乐库 + LLM 播放管理**能力。
> 配套落地实施计划见 `ZeroV2-音乐模块落地实施计划.md`。
> **2026-09-11 GPT 外审勘误封板修订**（M 表 16 项，MD13–MD18 新增；M15 用户裁定已执行、M16 `playlist_create` 权限勘误）：`file_name`→`relative_path`（M1）、闹钟 tick 语义钉死（M2）、认领幂等（M3）、`musicSubdir` 常量化（M4）、AUTO 双门控（M5）、统一前台入口（M6）、增量算法定义（M7）、缺失延迟删（M8）、SAF/Room 失败顺序（M9）等——详见落地计划 §M 裁定表。

---

## 一、背景与目标

Zero v2 已具备对话/FC、记忆、自动化、自主触发、云能力，但无本地媒体能力。本轨目标：

1. **本地音乐入库**：**递归**扫描工作目录下 `llmmusic/` 子树（含任意深度子目录，如 `llmmusic/Anime/C.mp3`）的音乐文件，抽取 ID3/时长元数据入 Room 库——曲目以 `relative_path`（相对 `llmmusic/` 的含子目录路径）为唯一物理定位键（MD13）。
2. **LLM 歌单**：LLM 经 FC 检索库、自主建立推荐歌单。
3. **播放全状态控制**：LLM 与用户均可控制 播放/暂停/上下首/拖动/队列/随机/循环，并查询播放状态。
4. **定时播放**：到点播放——支持 **FIXED**（直播指定歌单，不走 LLM）与 **AUTO**（到点唤醒 LLM 自主选/建歌单）双模式（MD4 用户裁定）。
5. **本地文件管理**：在 `llmmusic/` 子树内按**相对路径**重命名/删除文件并同步库 DB。

音乐库**固定**位于工作目录（SAF 树）下 `llmmusic/`——编译期常量 `MusicPaths.SUBDIR`（MD15：不配置化；改目录名会使库内 `relative_path` 全量失效，无此用户需求，单一事实源）。工作目录须用户先经「设置-工作目录」选择（`WorkFilesScreen` 的 `ACTION_OPEN_DOCUMENT_TREE`）。

---

## 二、文档定位与权威

- **权威层级**：`ARCHITECTURE_FINAL.md` > `DEVELOPMENT_DESIGN.md` > 本设计文档 > `ZeroV2-音乐模块落地实施计划.md` > [ZeroV2-LLM开发指南.md](ZeroV2-LLM开发指南.md) + 根 `CLAUDE.md`。
- 本轨遵循 LLM 开发指南的跨轨通用规范（R1–R8）与安全红线（§五）。落地前先读本设计 → 再读落地计划的 SR/C/G/F/M 交叉验证表。
- **铁律**：实现选择不确定时——查设计文档 → 查 SR/C/G 表 → 仍无答案→**向用户提问，不自行决定**。

---

## 三、架构总览

### 3.1 模块归属（MD1）

`MusicModule` 放 **`app` 层**，与 `FilesModule`（`app/.../data/FilesModule.kt`）同列——因其依赖 app 层 `WorkDirRepository`（SAF，`@Singleton`）。依赖方向 `app → services → core` 安全，不违背 X12（framework 不依赖 app；MusicModule 在 app 不构成该问题）。

`MusicModule` 是 `@Singleton` + `IZeroModule` + `AgentJob`（`AgentJob` 接口在 `services/.../agent/AgentJob.kt`，app 可实现）。

### 3.2 单例 + 前台服务分离（MD2，仿 LiveSessionService）

状态与播放引擎放 `MusicModule` `@Singleton`；`MusicService` 是**薄前台宿主**：

| 组件 | 层 | 职责 |
|---|---|---|
| `MusicModule`（`@Singleton`，IZeroModule+AgentJob） | app | 持 `ExoPlayer`(lazy) + `PlaybackState`/`libraryState` StateFlow + 库管理 + FC 注册 + 调度逻辑 |
| `MusicService`（前台 Service） | app | `@AndroidEntryPoint`、注入 `MusicModule`、`startForeground(mediaPlayback)`、把 `playbackState` 接到通知 + MediaSession |
| `MusicViewModel`（`@HiltViewModel`） | app | 注入同一 `MusicModule` 单例，collect 状态给 `MusicScreen` |

此范式直接照搬 `LiveSessionService`（状态在 `LocalVoiceProvider` 单例，服务只管前台生命周期，ViewModel 注入同一单例）。

### 3.3 数据流

```
LLM/用户 → music_* FC → MusicModule（库/播放/调度） → ExoPlayer / Room / WorkDirRepository
                                            │
                          MusicService（前台宿主）← 注入 MusicModule → 通知/MediaSession
                                            │
                          MusicViewModel ← collect playbackState/libraryState → MusicScreen
```

---

## 四、模块契约

```
MusicModule : IZeroModule, AgentJob
  metadata   = ModuleMetadata(id="music", displayName="音乐", category=SYSTEM, launchOrder=+120)
  config     = MusicModuleConfig（@ConfigField，见 §十一）
  onAwake()  → 注册全部 music_*/playlist_* FC；若 autoScanOnStart 且 workDir 已配置 → 扫描
  onStart()  → 从 Room 恢复 ScheduledPlaybackEntity + 重排 scheduleWake（仿 AutomationModule.refreshNextRun）
  onDestroy()→ 释放 ExoPlayer
  AgentJob.id="music_scheduled", nextRunTime=最近 active 调度 triggerAt（无则 MAX_VALUE）
  AgentJob.execute() → **到期扫描**（§7.1）：查全部 status=active 且 triggerAt≤now 的调度 →
                       逐条原子认领（Mutex+@Transaction，MD14）→ FIXED playPlaylist / AUTO fireMusicSchedule
                       → 推进状态（一次性→fired；周期→active+triggerAt=下期，相位保持 F17）→ 重排下一最早闹钟
```

`category=SYSTEM`（与 `SystemEventModule`/`NotificationModule` 同类——系统侧能力，崩溃经 `safeCall` 自动禁用不伤 Core）。`launchOrder=+120`（在业务 Core 引擎之后启动，参照 `SystemEventModule`≈+100）。

---

## 五、Room 持久化（MD8）

当前 `ZeroDatabase` v14，`fallbackToDestructiveMigration` 开（**X9 地雷**）。新增 3 表，分 3 次迁移（各阶段一次，**各同提交**）。列名 `snake_case` 经 `@ColumnInfo(name=...)` 映射 `camelCase`；NOT NULL 列 `DEFAULT` 与 `@ColumnInfo(defaultValue=)` 完全一致（Room schema 校验）。

### 5.1 MusicTrackEntity（表 `music_tracks`，MU1.1 / v15）
```
id TEXT PK | relative_path TEXT NOT NULL | display_title TEXT NOT NULL DEFAULT '' 
| artist TEXT | album TEXT | duration_ms INTEGER NOT NULL DEFAULT 0 
| file_size INTEGER NOT NULL DEFAULT 0 | added_at INTEGER NOT NULL 
| modified_at INTEGER NOT NULL | miss_count INTEGER NOT NULL DEFAULT 0 
| play_count INTEGER NOT NULL DEFAULT 0 | last_played_at INTEGER
```
标杆：`ReminderEntity`（`core/data/.../entity/SystemStateEntity.kt`）。**`relative_path` 是唯一物理定位键**（MD13）：相对 `llmmusic/` 的含子目录路径（如 `A.mp3`、`Anime/C.mp3`），兼容同名不同目录（`Anime/C.mp3` ≠ `Game/C.mp3`）；D57 净化（拒 `..`/`.` 段，`resolvePath` 同则）。`display_title` **只负责展示**（ID3 title，缺省取文件基名），不承担文件定位职责。`miss_count` 为连续扫描缺失计数（MD16：≥2 才物理删行，防 SAF 抖动清库）。播放时由 `WorkDirRepository` 将 `relative_path` 逐级解析为 SAF 文档 Uri（`resolvePath(llmmusic 根, relative_path)`）。`UNIQUE(relative_path)` 约束 + 索引（upsert 键）。

### 5.2 PlaylistEntity（表 `music_playlists`，MU3.1 / v16）
```
id TEXT PK | name TEXT NOT NULL | created_by TEXT NOT NULL DEFAULT 'user' 
| created_at INTEGER NOT NULL | source_prompt TEXT
```

### 5.3 PlaylistItemEntity（表 `music_playlist_items`，MU3.1 / v16）
```
id TEXT PK | playlist_id TEXT NOT NULL (FK→music_playlists.id CASCADE) 
| track_id TEXT NOT NULL (FK→music_tracks.id CASCADE) | position INTEGER NOT NULL
```
`@ForeignKey(onDelete=CASCADE)` + `index` on `playlist_id`（Room 外键须索引）；`track_id` 同加索引。

### 5.4 ScheduledPlaybackEntity（表 `music_schedules`，MU4.1 / v17）
```
id TEXT PK | trigger_at INTEGER NOT NULL | playlist_id TEXT 
| mode TEXT NOT NULL DEFAULT 'FIXED' | hint TEXT | is_recurring INTEGER NOT NULL DEFAULT 0 
| recurring_ms INTEGER NOT NULL DEFAULT 0 | status TEXT NOT NULL DEFAULT 'active' 
| created_by TEXT NOT NULL DEFAULT 'user'
```
仿 `ReminderEntity` 生命周期 + G6 missed 语义（一次性过期→missed、周期→推进下一期——**原相位保持**，F17 同则：`+=ms` + while 越过 now，禁 `now+ms`）。`mode`：`FIXED`/`AUTO`。`status` 状态机（MD14 认领语义）：`active`（可认领）→ `firing`（**原子认领瞬时态**，`Mutex`+`@Transaction` 内 `UPDATE ... WHERE id=? AND status='active'`，受影响行=0 即已被另一路径认领→放弃）→ 执行成功后：一次性→`fired`（终态）、周期→回 `active` 且 `trigger_at`=下期；未认领的过期一次性→`missed`；AUTO 触顶被拒→`skipped_cap`（一次性）/ 周期跳过本期推进下期（trace 附原因）。`cancelled`=用户取消终态。

### 5.5 DAO：MusicDao（`core/data/.../dao/MusicDao.kt`）
`@Query SELECT *`、参数化 `UPDATE`、`@Insert`、`@Delete`、`@Query DELETE`，全 `suspend`。标杆：`SystemStateDao`。**曲目 upsert 走"查-留-id-更新"模式（M1 连带陷阱钉死）**：`getByRelativePath(relativePath)` 命中→`UPDATE` 元数据列（**保留 id/play_count/last_played_at/added_at**，`miss_count` 清零）；未命中→`INSERT` 新行（新生成 id）。**禁用裸 `@Insert(REPLACE)` 承接扫描**——REPLACE 撞 `UNIQUE(relative_path)`（新 id 同路径）走"删旧行插新行"路径，旧行删除会**级联清空引用它的 `music_playlist_items`**（FK CASCADE），重扫一次=歌单全丢。`DatabaseModule` 加 `@Provides fun provideMusicDao(db)=db.musicDao()`；`ZeroDatabase` 加 `abstract fun musicDao(): MusicDao` + entities 列表 + version。

迁移文件仿 `MIGRATION_11_12`（CREATE TABLE）+ `MIGRATION_13_14`（ALTER）；均为 `DatabaseModule` 内私有 `val MIGRATION_{from}_{to}`，注册入 `provideDatabase().addMigrations(...)`。

---

## 六、FC 清单（MD12，`metadata.id="music"` 注册）

FC 名一经发布**保持稳定**（LLM 熟悉，重构不改名）。权限：读类/播放控制 `AUTO`（用户主权委托）；写类 `CONFIRM`。

### 库（MU1.2）
| FC | 权限 | 参数 | 说明 |
|---|---|---|---|
| `music_scan` | AUTO | — | **递归**扫描 llmmusic 子树（增量，MD16）：以 `(relative_path, file_size, modified_at)` 三元组比对——命中且一致→跳过 `MediaMetadataRetriever`；新增/变更→抽取 ID3/时长 upsert（键=`relative_path`，`miss_count` 清零）；DB 有而枚举无→`miss_count+1`，**连续 ≥2 次才物理删行**；枚举异常→跳过删除 + trace |
| `music_list` | AUTO | artist?, album?, limit? | 列曲目，可过滤 |
| `music_search` | AUTO | query, limit? | 标题/艺术家模糊 |

### 播放（MU2.2）
| FC | 权限 | 参数 | 说明 |
|---|---|---|---|
| `music_play` | AUTO | track_id? / playlist_id? / track_ids? | 播放单曲/歌单/队列 |
| `music_pause` | AUTO | — | 暂停 |
| `music_resume` | AUTO | — | 续播 |
| `music_next` / `music_prev` | AUTO | — | 上下首 |
| `music_seek` | AUTO | position_ms | 拖动 |
| `music_status` | AUTO | — | 返回当前曲/位置/时长/是否播放/队列 |
| `music_set_queue` | CONFIRM | track_ids | 设队列顺序 |
| `music_shuffle` | AUTO | enabled | 随机开关 |
| `music_repeat` | AUTO | mode(off/all/one) | 循环模式 |

### 歌单（MU3.2）
| FC | 权限 | 参数 |
|---|---|---|
| `playlist_create` | **AUTO** | name, from_track_ids? |
| `playlist_list` | AUTO | — |
| `playlist_delete` | CONFIRM | playlist_id |
| `playlist_add_track` | CONFIRM | playlist_id, track_id, position? |
| `playlist_remove_track` | CONFIRM | playlist_id, track_id |

`playlist_create` 定 **AUTO**（2026-09-11 勘误）：MD4 用户裁定 AUTO 模式="LLM 自主选/**建**歌单"——CONFIRM 权限会使该裁定不可执行（自主回合无人确认即卡死）；且创建歌单无破坏性可逆（不覆盖不删除既有内容，与 D123 正常操作让渡精神一致）。破坏性歌单操作（delete/add/remove_track 改既有歌单）保持 CONFIRM。

### 定时（MU4.2）
| FC | 权限 | 参数 |
|---|---|---|
| `music_schedule` | CONFIRM | time, playlist_id?, mode=FIXED/AUTO, hint?, recurring?, interval_seconds? |
| `music_list_schedules` | AUTO | — |
| `music_cancel_schedule` | CONFIRM | schedule_id |

### 文件（MU1.2/MU2.x）
| FC | 权限 | 参数 | 说明 |
|---|---|---|---|
| `music_rename` | CONFIRM | old_relative_path, new_relative_path | 限 llmmusic 子树内（新旧路径均须解析于子树内，D57）；跨子目录移动允许（目标仍须在子树内）。物理操作复用 S9.1 `moveEntry` 语义 + 同步 DB `relative_path`（`display_title` 缺省跟随时更新） |
| `music_delete` | CONFIRM | relative_path | 限 llmmusic 子树，删文件 + DB 行 + 级联清歌单 items；**回执附审计**：删除前查受影响歌单清单，返回 `affected_playlists=N, removed_items=M`（CASCADE 静默移除歌单项——LLM 须知道歌单已不完整，M10 裁定） |

**AUTO 模式禁用 CONFIRM FC（MD18）**：`buildMusicPrompt` 明确指示 AUTO 回合**只可用 AUTO 权限 FC**（`music_search`/`music_list`/`playlist_create`/`music_play` 等）；**禁调** `music_set_queue`/`playlist_delete`/`music_rename`/`music_delete` 等 CONFIRM 项——CONFIRM 弹窗会卡死自主回合（无人确认）。歌单构建一律走 `playlist_create(from_track_ids) → music_play(playlist_id)`，不经 `music_set_queue`。

**前置依赖**：所有文件 FC 与 `music_scan` 在 `WorkDirRepository.hasDirectory()==false` 时返回引导文本（"请先在设置-工作目录选择目录"），不崩（B11 同律）。

---

## 七、调度与触发（MD4/MD5/MD6）

### 7.1 FIXED 路径（MD5/MD14，复用 ExactAlarmScheduler + AgentJob · 对齐 Reminder 到期扫描模式）

`music_schedule` FC → 解析时间（仿 `SystemEventModule.parseTime`，绝对/相对；**拒绝过去时刻**，F17 同则）→ 入 `ScheduledPlaybackEntity` + `MusicModule.scheduleWake()`。

**闹钟身份语义（M2 裁定——为什么单一 `REQUEST_MUSIC=4003` 是正确的）**：音乐闹钟不是"每调度一个 PendingIntent"，而是**唤醒 tick**——与既有 `REMINDER_DUE`（`REQUEST_REMINDER=4001` 单身份管全部提醒，`ExactAlarmReceiver` 收到即调 `systemEventModule.execute()` 做**到期扫描**，`SystemEventModule.execute()`（:418）= `count{isActive && triggerTimeMs<=now}` → `processDueTasks` → `scheduleNextAlarm()` 重排下一最早）完全同模式。AlarmManager 对同一 PendingIntent 后设**替换**先设——在"恒指向最近到期"语义下这正是所需行为：新增更早调度→重排为更早时刻；新增更晚调度→不动（现闹钟仍是最早）；删除最早项→重排或由下次扫描自愈。**多调度并存不靠闹钟身份区分，靠到期扫描 + 状态机认领区分**。GPT 外审曾判定"单一 requestCode 多任务互相覆盖"为 P0——该判断仅在"闹钟=每调度专用身份"的错误前提下成立；本设计显式钉死 tick 语义以防后续实现漂移。

```
scheduleWake():  exactAlarmScheduler.scheduleMusic(最近 active triggerAt)  // 主：系统闹钟（Doze-immune）
                + wakeScope.launch{ delay(wait); agentRuntime.kick() }     // 辅：前台时更快（快路径）
执行后必调 scheduleNextMusicAlarm()（重排下一最近；无 active 则 MAX_VALUE 不排）
```

**双路径幂等（MD14 认领）**：ExactAlarm（进程冻结下可靠）与 `kick()`（前台快路径）可能**先后都触发** `execute()`——幂等由 §5.4 状态机保证：`execute()` 到期扫描逐条 `UPDATE ... SET status='firing' WHERE id=? AND status='active' AND trigger_at<=?`（`Mutex`+`@Transaction`），**受影响行=0 即已被另一路径认领→跳过该条**；两路径并发跑至多一方认领成功。第二次扫描无可认领行=no-op（trace `music_wake_noop`）。AUTO 双触发若无此守卫=双 LLM 回话双建歌单双播放——已封死。

到点链：`ExactAlarmReceiver` 新增 `ACTION_MUSIC_DUE` 分支 → `systemBus.onMusicDue.tryEmit(Unit)` → `MusicModule`（onStart 订阅）`execute()` → 到期扫描认领 → mode==FIXED 直接 `playPlaylist(playlist_id)`，**不唤醒 LLM**。

`ExactAlarmScheduler` 加 `scheduleMusic(triggerAtMs)=schedule(ACTION_MUSIC_DUE, REQUEST_MUSIC, t)`（常量 `ACTION_MUSIC_DUE="com.zero.v2.action.MUSIC_DUE"`、`REQUEST_MUSIC=4003`，仿 `ACTION_REMINDER_DUE=4001`）。`ExactAlarmReceiver.onReceive` `when` 加 `MUSIC_DUE` 分支 `emit`——**不注入 app 层 `MusicModule`（X12：services 不可依赖 app，注入即构建失败）**。先例：`SystemBus.onScheduleTriggered`（v2.1 为解耦 SystemEventModule→AutomationModule 而设）。**C1 裁决**（见落地计划 §C）。`SystemBus`+`SystemBusImpl` 加 `onMusicDue: MutableSharedFlow<Unit>`（core:bus，仿 `onScheduleTriggered`；**保持 Unit 载荷**——tick 语义下闹钟不携带"是哪个调度"的信息，身份由到期扫描决定，M2）。

### 7.2 AUTO 路径（MD6/MD18，TriggerHub 源 + 音乐 Prompt 路由 · 双门控）
`execute()` 认领成功且 `mode==AUTO` → **第一道门：音乐日额度**（`scheduledAutoCap`，模块内当日计数器，独立于 TriggerHub）——超顶：一次性→`status=skipped_cap`、周期→跳过本期推进下期，trace `music_auto_cap_reached`，**不 fire**（拒绝可见，不静默）→ 通过 → `triggerHub.fireMusicSchedule(payload="schedule=$id hint=$hint", hint=hint)`（**C3 裁决**：`fireTraced` 私有，`TriggerHub` 加公开 `fireMusicSchedule(...)` helper，内部调 `fireTraced`）：
- `TriggerHub` 加 `friendlySource("SCHEDULED_PLAYBACK"→"🎵 定时播放")` + `TriggerHubConfig.musicScheduledTriggerEnabled`（@ConfigField TOGGLE，gate AUTO 是否允许）。
- `fireTraced()` 既有逻辑：发 `SystemEventDisplay` 气泡到 `chatBus.onSystemEventInjected`（**仅 UI 投影，F8，不进 chatHistory**）+ `autonomousJob.onTrigger(event)`。
- **第二道门：`TriggerHub` 既有 `dailyTriggerCap`**（:599 `if (count >= cfg.dailyTriggerCap) return false`）——音乐 AUTO 触发同样计数受顶，不可绕过。双门控链：`scheduledAutoCap`（音乐域额度）→ `dailyTriggerCap`（全局自主额度）→ `waitForIdle`（让位用户）→ `onUserPriorityInterrupt`（用户中断取消）——任何一道拒绝即不触发。
- `AutonomousThinkingJob.buildPrompt()` `when` 加 `"SCHEDULED_PLAYBACK"→buildMusicPrompt(event)`，指示 LLM 用 `music_search`/`music_list`/`playlist_create`/`music_play` 按 hint 建/选歌单并播放；**明示禁用 CONFIRM 权限 FC**（`music_set_queue` 等，见 §六——CONFIRM 卡死自主回合，MD18）。

### 7.3 开机恢复（MD5，仿 AutomationModule）
`MusicModule.onStart()` 从 Room 恢复 `ScheduledPlaybackEntity` + G6 重排（一次性过期→`missed`、周期→推进下一期原相位）+ `scheduleWake` 下一最近调度（**恢复后必重排闹钟**——进程死亡期间闹钟随进程亡，`onStart` 重排即自愈，与 `SystemEventModule.scheduleNextAlarm()` 同则）。**无需改 `BootReceiver`**——开机后进程重启→`ZeroApplication.onCreate`→`moduleManager.initializeAll()`→`onStart` 自恢复（与 `AutomationModule` 同机制）。

---

## 八、播放服务与音频焦点（MD3/MD9/MD10）

### 8.1 依赖与声明
`app/build.gradle.kts` 加 `androidx.media3:media3-exoplayer:1.5.1` + `androidx.media3:media3-session:1.5.1`。Manifest 加 `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"/>` + `<service android:name=".background.MusicService" android:exported="false" android:foregroundServiceType="mediaPlayback"/>`。

### 8.2 MusicService（仿 LiveSessionService 骨架）
`@AndroidEntryPoint`；`onCreate` 建 channel + `startForeground(NOTIFICATION_ID, notif, FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)`（按 `SDK_INT` 分支仿 `LiveSessionService` 麦克风类型写法）；`onStartCommand` 返 `START_NOT_STICKY`；`onTaskRemoved→stopSelf`；`onDestroy` 释 ExoPlayer + `scope.cancel`；companion `start/stop`。注入 `MusicModule`，`serviceScope.launch{ musicModule.playbackState.collect{ updateNotification(it) } }`。

### 8.2a 统一前台入口（MD17，M6 裁定）
**`MusicModule.play()` 第一件事 `ensurePlaybackService()`**（内部 `MusicService.start(context)`，幂等——已在跑则 no-op）。三条播放入口（LLM FC / 用户 UI / 定时调度）**全部经 `MusicModule.play()` 单一启动路径**，禁止任何入口自行 `startService`——否则"服务未起而 ExoPlayer 已播"（后台无前台通知=Freeze/杀风险）与"多入口重复启动"两类缺陷。停止同理：`playbackState` 流转到"无队列/暂停超时"时由 `MusicModule` 调 `MusicService.stop()`。

### 8.2b 生命周期与恢复语义（M12 裁定 · v1 范围钉死）
- **进程死亡=播放终止**（v1 明确不做跨进程死亡自动恢复）。`MusicService` `START_NOT_STICKY`、`MusicModule` 状态随进程亡——重启后 `playbackState` 归零，**库/歌单/调度经 Room 恢复**（`onStart` 重排）。`StateFlow` 不是持久层，不做恢复源。
- `onTaskRemoved→stopSelf`：用户划掉任务=停止播放（v1 语义，与 `LiveSessionService` 一致）。
- backlog 提案（非本期）：持久化 `currentTrackId+positionMs` 供重启后"继续播放"按钮；空闲超时自动释放 ExoPlayer（G6）。

### 8.3 ExoPlayer + SAF（MD10/MD13）
`MusicModule` lazy 持 `ExoPlayer`（`@Singleton` 单例）。播放时由 `WorkDirRepository` 解析 track 的 SAF 文档 Uri（**`resolvePath(llmmusic 根, relative_path)` 逐级解析——支持任意深度子目录**，非单级 `findChild`），Media3 `DefaultDataSource` 支持 `content://` Uri（经 `ContentResolver.openInputStream`），无需拷贝到应用私有目录。解析失败（文件已被外部移动/删除）→返回失败文案 + `miss_count` 逻辑同扫描（下次扫描收敛），不崩。

### 8.4 音频协调（MD9，与 Live 语音会话防双放/防自听）
- **AudioFocus**：`MusicModule` 播放起 `AudioManager.requestAudioFocus(AudioFocusRequest)`；焦点丢失 `AUDIOFOCUS_LOSS_TRANSIENT`→暂停；回归→可续播。
- **语音会话感知（C2 裁决）**：以 `AudioFocus` 为主（解耦，不注入 realtime 组件）；sherpa TTS 用 `AudioTrack` 未必申请焦点 → 若 MU2.3 真机显示 TTS 期间音乐不暂停，再加 `SystemBus.onVoiceActive` 事件（core:bus）让音乐订阅作 fallback。**MU2.2 不注入 `LocalVoiceProvider`**，避免过早触发 sherpa 模型构造与 app↔realtime 耦合。
- 防自听：语音会话期间音乐暂停即杜绝麦克风收音乐；与 `LocalVoiceProvider` 既有 D52 防自听互补。
- 依赖方向：`MusicModule`(app)→`TriggerHub`(services)（AUTO 触发）一向依赖无 cycle；`MusicModule` 不注入 `LocalVoiceProvider`（C2）；`ExactAlarmReceiver`(services) 不注入 `MusicModule`(app)，经 `SystemBus.onMusicDue` 解耦（C1）。

### 8.5 MediaSession（可选，MU2.x 后置）
`MusicService` 建 `MediaSession`，绑 `MusicModule` 的 ExoPlayer，提供锁屏/蓝牙控制。

---

## 九、文件操作（MD7/MD16，llmmusic 子树 · 基于 S9.1 已落地原语）

**S9.1 已落地（2026-09-10，M13 勘误）**：`WorkDirRepository` 现已具备全路径原语——`resolvePath`（逐级解析，拒 `..`/`.`）、`listTree`（递归树 ≤5 层）、`moveEntry`（同目录 rename 快路径/跨目录 copy+delete）、`deleteFile`（统一引用解析）、`resolveFileRef`、路径化写族（`ensureParentDir`）。本轨**不再新造 §九 方法集**，音乐域只加一层薄约定：

```
MusicPaths.SUBDIR = "llmmusic"                       // 编译期常量（MD15）
musicRoot(): Uri = resolvePath(workDir 根, "llmmusic") ?: 引导建目录（首次扫描自动 createFolder）
resolveInMusic(relativePath): Uri? = resolvePath(musicRoot(), relativePath)  // 唯一定位出口
```

`music_rename` = `moveEntry("llmmusic/$old", "llmmusic/$new")` + DB 同步；`music_delete` = 物理 `deleteDocument(resolveInMusic(...))` + DB 删行（级联清 items 由 FK CASCADE 承担）+ 受影响歌单审计（M10）。D57 边界白捡：`resolvePath` 拒绝穿越段 + `build*UriUsingTree` 只能寻址树内后代——**新旧路径都被钳制在 llmmusic 子树内**（`music_rename` 目标含 `../` 即拒），不依赖 LLM 自觉。

**SAF/Room 失败顺序（MD16，M9 裁定——不做伪事务，扫描=最终一致性修复）**：
```
物理操作（SAF）成功 → Room 同步
  ├─ Room 也成功 → 完毕
  └─ Room 失败 → trace(music_db_sync_failed) + 立即触发一次 music_scan（reconcile：
       扫描以物理世界为准收敛 DB——多出的行 miss_count 收敛、缺的行补建）
物理操作失败 → 直接返回失败文案，DB 不动
```
顺序定为**物理优先**：物理世界是事实源，DB 是投影；反序会造成"DB 说没了文件还在"的永久漂移，而正序最坏情况=一次扫描自愈。

音乐文件**获取**：现有 `download_file`/`writeBinaryStream(subdir="llmmusic")` 已能把云下载落 llmmusic——复用下载管线，本轨不另造。

---

## 十、UI（MU1.3/MU2.3/MU3.2/MU4.3）

`MusicScreen` + `MusicViewModel`（仿 `WorkFilesScreen`/`ModuleDetailScreen`）：库列表（过滤/搜索）、扫描按钮、now-playing（曲名/位置/进度/控制）、歌单管理、定时列表。路由入 `ZeroNavHost` **设置子路由**（三 Tab 不动，D122 基线）。配置页走 AutoForm（`ModuleConfigParser` 零运行时反射）。

状态链：`MutableStateFlow`/`asStateFlow()` + `@HiltViewModel` init collect + `collectAsState()`（仿 `SystemEventModule`/`WorkFilesViewModel`）。

### 十b、AutomationModule 编排说明（MU5.2 · 不改 AutomationModule）

音乐 FC（`music_*`/`playlist_*`）经 `FunctionRegistryModule` 统一注册，与其它 FC 同一寻址空间——**AutomationModule 的 PLAN/REACT 引擎在执行计划步骤时可直接调用音乐 FC**（如自动化任务步骤"每晚 22:30 播放《睡前》歌单"→ `music_play(playlist_id=...)`），无需任何音乐侧适配。边界约定：自动化编排走的是 FC 层（受各 FC 权限约束——AUTO 项直执、CONFIRM 项经用户确认流程），与定时播放（§七 调度域）互不替代：自动化=用户显式编排的任务流，定时=音乐域原生轻量调度。本节为**说明性文档**，AutomationModule 代码零改动（MU5.2 落地时亦然）。

---

## 十一、配置（MusicModuleConfig，@ConfigField，§7.1 显式 min/max）

| 字段 | 类型 | 默认 | min/max | 说明 |
|---|---|---|---|---|
| autoScanOnStart | Boolean(toggle) | true | — | 启动自动扫描 |
| scanIntervalMin | Int(number) | 60 | 10/1440 | 扫描间隔（分钟） |
| defaultVolume | Int(slider) | 70 | 0/100 | 默认音量 |
| audioDuckDuringVoice | Boolean(toggle) | true | — | 语音会话暂停音乐 |
| scheduledAutoCap | Int(number) | 10 | 1/50 | AUTO 定时播放日上限（**第一道门**，§7.2 双门控——达到即拒：trace + 状态 `skipped_cap`，不静默） |

**`musicSubdir` 不入配置（MD15，M4 裁定）**：音乐库目录 = 编译期常量 `MusicPaths.SUBDIR = "llmmusic"`。理由：① 目录名配置化会使库内 `relative_path` 全量失效（改配置=整库报废）；② 无用户需求（"音乐放 llmmusic"是约定不是偏好）；③ 单一事实源。未来若真要可换目录，属配置系统能力扩展，须另立设计经用户裁定，不得默默加字段。

字段类型仅 Int/Boolean（数值均显式 min/max）；SECRET 无。`toJson/fromJson` 手写，`fromJson` 写入 `coerceIn`（N4.1 通用钳位）。

---

## 十二、决策记录（MD1–MD18 · 2026-09-11 M 表勘误后含 MD13–MD18）

| # | 决策 | 理由 |
|---|---|---|
| MD1 | 模块归属 app 层 | 依赖 app 层 `WorkDirRepository`（SAF），同 `FilesModule`；依赖方向安全 |
| MD2 | 单例+前台服务分离 | 仿 `LiveSessionService`：状态在 `@Singleton`，服务薄宿主，ViewModel 共享单例 |
| MD3 | Media3 ExoPlayer+MediaSession | 项目无 media3，从零引入；Media3 是 Android 标准媒体栈 |
| MD4 | 定时双模式 FIXED+AUTO | 用户裁定：FIXED 可靠零 token，AUTO 智能；成本可控 |
| MD5 | 调度复用 ExactAlarmScheduler+AgentJob | 仿 `AutomationModule`；Doze-immune 精准；不新造调度器；经 `SystemBus.onMusicDue` 解耦 services→app（C1） |
| MD6 | AUTO 走 TriggerHub 源+音乐 Prompt 路由 | 复用既有自主思考链；受 dailyTriggerCap/waitForIdle 约束 |
| MD7 | 文件操作限 llmmusic+同步 DB | **复用 S9.1 已落地原语**（moveEntry/deleteFile/resolvePath——M13 勘误：原稿写"属未开工 S9.1"已过时，S9.1 于 2026-09-10 落地）；DB 同步防孤儿行 |
| MD8 | Room 三次迁移各同提交 | X9 地雷：Entity+Dao+版本+Migration 同 commit；分阶段降风险 |
| MD9 | 音频协调 AudioFocus 为主，状态订阅 fallback | 防 Live 语音双放/自听；MU2.2 仅 AudioFocus，gap 真机验后加 `SystemBus.onVoiceActive`（C2） |
| MD10 | ExoPlayer 播 SAF content Uri | `DefaultDataSource` 支持 content://，无需拷贝 |
| MD11 | chatHistory 纯净 | 音乐事件不进 chatHistory；状态走 StateFlow/UI；AUTO 气泡是 S2 投影 |
| MD12 | FC 名 music_*/playlist_* 稳定 | LLM 熟悉，重构不改名 |
| MD13 | `relative_path` 唯一物理定位键（M1） | 子树含任意深度子目录，纯文件名无法区分同名异目录（`Anime/C.mp3`≠`Game/C.mp3`）也无法定位；`display_title` 只管展示 |
| MD14 | 闹钟=唤醒 tick + 到期扫描 + 状态机原子认领 + 重排（M2/M3） | 对齐 Reminder 既有模式（`SystemEventModule.execute()` :418）：单一 `REQUEST_MUSIC` 身份在"恒指最近到期"语义下正确；ExactAlarm/kick 双路径幂等由 `active→firing` 认领保证（`Mutex`+`@Transaction`，受影响行=0 即弃） |
| MD15 | `musicSubdir` 常量化不入配置（M4） | 配置化=改目录名致整库 `relative_path` 失效；无用户需求；单一事实源 |
| MD16 | 扫描=最终一致性修复机制（M7/M8/M9） | 增量键 `(relative_path, file_size, modified_at)` 三元组；缺失 `miss_count≥2` 才删（防 SAF 抖动清库）；SAF 成功 Room 失败→trace+立即 reconcile 扫描；物理优先不做伪事务 |
| MD17 | `ensurePlaybackService()` 统一前台入口（M6） | FC/UI/调度三入口全经 `MusicModule.play()`；禁各入口自行 startService（防无前台播放/重复启动） |
| MD18 | AUTO 双门控 + 禁 CONFIRM FC（M5/M11） | `scheduledAutoCap`（音乐域）→ `dailyTriggerCap`（全局）两道都不可绕，触顶=trace+`skipped_cap` 拒绝不静默；AUTO 回合禁用 CONFIRM 权限 FC（弹窗卡死自主回合），歌单走 `playlist_create→music_play` |

---

## 十三、安全与禁区遵守

- **X9**：每次迁移 Entity+Dao+版本+Migration 同提交；老库升级 L3 必测。
- **safeCall**：`MusicModule` FC handler 经 `FunctionRegistry` → `ModuleLifecycleManager.safeCall` 自动包裹；DB 写 `Mutex`+`@Transaction`（仿 `MemoryModule`）；调度认领 `UPDATE...WHERE status='active'` 条件更新即幂等锁（MD14——AgentJob 契约明言"实现方负责推进与幂等"）。
- **chatHistory 纯净（MD11）**：音乐事件**不进 chatHistory**；`music_status` 返回的是 TOOL 结果（正常）；播放状态变化走 StateFlow/UI，**不进 `onSystemEventInjected`→chatHistory**。AUTO 触发的 `SystemEventDisplay` 气泡是会话级投影，符合 S2 范式（F8：仅 UI 显示，不进持久化/N6.6）。
- **不可破坏禁区**：N6.6 生成式压缩（不触）、D19 单一 tick（不另造 tick 源）、FunctionRegistry 唯一（FC 只经 `FunctionRegistryModule`）、BudgetGuard（AUTO 走自主思考受其约束，不绕过）、D57 SAF 边界（限 llmmusic 子树）。
- **E28**：扫描/调度/播放静默失败路径留 `traceManager.record(...)`。
- **§7.1**：所有数值 ConfigField 显式 min/max + `coerceIn`。
- **不触 `build.gradle` 除非 Step 明确要求**（MU2.1 显式声明 media3）。
- **B11**：`WorkDirRepository` 未配置/不可用时 FC 返回引导文本，不崩内核。

---

## 十四、验证（要点，详见落地计划）

- **构建**：每 Step `cmd //c "D:\ZeroV2\_build_apk.bat"`，验 APK 时间戳。
- **MU1**：放 mp3 进 llmmusic（**根 1 首 + 子目录 2 首同名异目录**，如 `A.mp3`、`Anime/C.mp3`、`Game/C.mp3`）→ `music_scan` → `music_list` 三条 `relative_path` 互异、均可播放（MD13 实证）；`../`、绝对路径、空段路径全拒（M 路径安全）；杀进程重启库仍在；**临时移走一文件→扫描 1 次→行保留（miss_count=1）→再扫→行删**（M8 实证）。
- **MU2**：`music_play`→前台通知+出声+锁屏；**三入口（LLM FC / 用户 UI / 定时调度）均经 `ensurePlaybackService()` 出前台**（MD17）；`music_status` 位置/时长；起 Live 语音→音乐暂停。
- **MU4**：`music_schedule(time,playlist_id,FIXED)`→息屏 Doze 下准点播；重启恢复；一次性过期→missed、周期→推进；**双调度并发**（08:00 与 08:01 两条）→ 各自准点触发互不覆盖（M2 实证）；**前台进程下双路径同刻触发**→仅执行一次（trace 见 claim 竞争一条生效，M3 实证）。
- **MU5**：`music_schedule(time,mode=AUTO,hint)`→到点触发自主思考→LLM 建歌单播放；bubble 显示"🎵 定时播放"；`scheduledAutoCap` 触顶→`skipped_cap`+trace 拒绝（M5 实证）；AUTO 回话全程零 CONFIRM 弹窗（M11）。

---

## 十五、相关文档

- 落地实施计划：`ZeroV2-音乐模块落地实施计划.md`
- 开发指南：`ZeroV2-LLM开发指南.md`
- 架构真相源：`ARCHITECTURE_FINAL.md`（根）
- 标杆模块：`FilesModule`/`LiveSessionService`/`SystemEventModule`/`AutomationModule`/`ReminderEntity`/`MIGRATION_11_12`/`MIGRATION_13_14`
