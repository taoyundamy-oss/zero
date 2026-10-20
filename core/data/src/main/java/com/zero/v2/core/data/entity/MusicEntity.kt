package com.zero.v2.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 音乐轨实体 — MU1.1（DB v15 · 音乐轨 MD13/M8）。
 *
 * MusicTrackEntity：`relative_path` 是**唯一物理定位键**——相对 llmmusic/ 的含子目录路径
 * （如 `A.mp3`、`Anime/C.mp3`），兼容同名不同目录；`display_title` 只负责展示（ID3 title，
 * 缺省取文件基名），永不承担定位职责。UNIQUE 约束 = 扫描 upsert 锚点。
 * `miss_count`：连续扫描缺失计数（≥2 才物理删行，防 SAF 抖动清库——MD16）。
 *
 * ⚠️ DAO 侧"查-留-id-更新"模式为封板纪律（设计 §5.5）：扫描更新禁走裸
 * @Insert(REPLACE)——REPLACE 撞 UNIQUE 走"删旧插新"，旧行删除将级联清空
 * music_playlist_items（MU3.1 FK CASCADE 上线后），重扫一次 = 歌单全丢。
 */
@Entity(
    tableName = "music_tracks",
    indices = [Index(value = ["relative_path"], unique = true)]
)
data class MusicTrackEntity(
    @PrimaryKey val id: String,
    /** 唯一物理定位键：相对 llmmusic/ 的含子目录路径（拒 ".."/"." 段，D57） */
    @ColumnInfo(name = "relative_path") val relativePath: String,
    /** 展示标题（ID3 title，缺省取文件基名）——不承担定位职责 */
    @ColumnInfo(name = "display_title", defaultValue = "") val displayTitle: String = "",
    @ColumnInfo(name = "artist") val artist: String? = null,
    @ColumnInfo(name = "album") val album: String? = null,
    @ColumnInfo(name = "duration_ms", defaultValue = "0") val durationMs: Long = 0,
    @ColumnInfo(name = "file_size", defaultValue = "0") val fileSize: Long = 0,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "modified_at", defaultValue = "0") val modifiedAt: Long = 0,
    /** 连续扫描缺失计数：≥2 才物理删行（M8）；命中即清零 */
    @ColumnInfo(name = "miss_count", defaultValue = "0") val missCount: Int = 0,
    @ColumnInfo(name = "play_count", defaultValue = "0") val playCount: Int = 0,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long? = null
)

/**
 * 歌单 — MU3.1（DB v16 · 设计 §5.2）。
 * `created_by`：user / llm（AUTO 建单标记）；`source_prompt`：LLM 建单时的意图提示（可追溯）。
 */
@Entity(tableName = "music_playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "created_by", defaultValue = CREATED_BY_USER) val createdBy: String = CREATED_BY_USER,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** LLM 建单的意图提示（如"学习轻音乐"）；用户手建为 null */
    @ColumnInfo(name = "source_prompt") val sourcePrompt: String? = null
) {
    companion object {
        const val CREATED_BY_USER = "user"
        const val CREATED_BY_LLM = "llm"
    }
}

/**
 * 歌单曲目 — MU3.1（DB v16 · 设计 §5.3）。
 * 双外键 CASCADE：删 Track → 歌单项自动清（**music_delete 回执须先查受影响歌单**——M10，
 * CASCADE 是静默的）；删 Playlist → 项随清。`position` 为歌单内序（0 起）。
 */
@Entity(
    tableName = "music_playlist_items",
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"],
            childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MusicTrackEntity::class, parentColumns = ["id"],
            childColumns = ["track_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("playlist_id"), Index("track_id")]  // Room 外键须索引
)
data class PlaylistItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "position") val position: Int
)

/**
 * 定时播放调度 — MU4.1（DB v17 · 设计 §5.4 · MD14 认领语义）。
 *
 * `status` 状态机：`active`（可认领）→ `firing`（**原子认领瞬时态**——条件更新
 * `WHERE id=? AND status='active' AND trigger_at<=now`，受影响行=0 即被并行路径认领，
 * ExactAlarm/kick 双路径幂等的落点）→ 执行后：一次性 `fired`（终态）／周期回 `active`
 * 且 `trigger_at`=下期（原相位保持 F17）。未认领的过期一次性（进程死过）→ `missed`（G6）；
 * 一次性固定播放被拒 → `failed`；AUTO 触顶被拒 → `skipped_cap`；用户取消 → `cancelled`。`playlist_id` 为弱引用（无 FK）——
 * 歌单可先删，执行时查不到走失败文案。
 * `mode`：FIXED（直播指定歌单零 token）/ AUTO（到点唤醒 LLM 按 hint 自主建选歌单）。
 */
@Entity(tableName = "music_schedules")
data class ScheduledPlaybackEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "trigger_at") val triggerAt: Long,
    /** FIXED 模式的直播歌单（AUTO 可空） */
    @ColumnInfo(name = "playlist_id") val playlistId: String? = null,
    @ColumnInfo(name = "mode", defaultValue = MODE_FIXED) val mode: String = MODE_FIXED,
    /** AUTO 模式的建单意图（如"学习轻音乐"） */
    @ColumnInfo(name = "hint") val hint: String? = null,
    @ColumnInfo(name = "is_recurring", defaultValue = "0") val isRecurring: Boolean = false,
    @ColumnInfo(name = "recurring_ms", defaultValue = "0") val recurringMs: Long = 0,
    @ColumnInfo(name = "status", defaultValue = STATUS_ACTIVE) val status: String = STATUS_ACTIVE,
    @ColumnInfo(name = "created_by", defaultValue = CREATED_BY_USER) val createdBy: String = CREATED_BY_USER
) {
    companion object {
        const val MODE_FIXED = "FIXED"
        const val MODE_AUTO = "AUTO"
        const val STATUS_ACTIVE = "active"
        const val STATUS_FIRING = "firing"
        const val STATUS_FIRED = "fired"
        const val STATUS_FAILED = "failed"
        const val STATUS_MISSED = "missed"
        const val STATUS_SKIPPED_CAP = "skipped_cap"
        const val STATUS_CANCELLED = "cancelled"
        const val CREATED_BY_USER = "user"
        const val CREATED_BY_LLM = "llm"
    }
}
