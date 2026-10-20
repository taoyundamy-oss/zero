package com.zero.v2.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.zero.v2.core.data.entity.MusicTrackEntity
import com.zero.v2.core.data.entity.PlaylistEntity
import com.zero.v2.core.data.entity.PlaylistItemEntity
import com.zero.v2.core.data.entity.ScheduledPlaybackEntity

/**
 * 音乐 DAO — MU1.1（DB v15，tracks 部分；歌单/调度表随 MU3.1/MU4.1 扩展）。
 *
 * **封板纪律（设计 §5.5 · M1 连带陷阱）**：扫描 upsert 走"查-留-id-更新"——
 * `getByRelativePath` 命中 → `updateMetadataByPath`（保留 id/play_count/last_played_at/
 * added_at，miss_count 清零）；未命中 → `insertTrack`（新 id）。
 * **禁用 @Insert(REPLACE) 承接扫描**：REPLACE 撞 UNIQUE(relative_path)（新 id 同路径）
 * 走"删旧行插新行"，旧行删除会级联清空引用它的 music_playlist_items（FK CASCADE），
 * 重扫一次 = 歌单全丢。`insertTrack` 因此不带 REPLACE 策略——冲突即抛，编程错误显性化。
 * DB 写调用方（MusicModule）须以 Mutex 串行（安全守卫 #8）。
 */
@Dao
interface MusicDao {

    // ═══ 查询（music_list / music_search / 播放定位）═══

    @Query("SELECT * FROM music_tracks ORDER BY added_at DESC")
    suspend fun loadAllTracks(): List<MusicTrackEntity>

    @Query("SELECT * FROM music_tracks WHERE id = :id LIMIT 1")
    suspend fun getTrackById(id: String): MusicTrackEntity?

    @Query("SELECT * FROM music_tracks WHERE relative_path = :relativePath LIMIT 1")
    suspend fun getByRelativePath(relativePath: String): MusicTrackEntity?

    /** 标题/艺术家模糊（music_search；设计 §六：不含路径匹配） */
    @Query("SELECT * FROM music_tracks WHERE display_title LIKE '%' || :query || '%' " +
            "OR IFNULL(artist, '') LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun searchTracks(query: String, limit: Int): List<MusicTrackEntity>

    @Query("SELECT COUNT(*) FROM music_tracks")
    suspend fun countTracks(): Int

    // ═══ 写入（扫描 upsert：查-留-id-更新两半）═══

    /** 命中已有曲目：仅元数据列——保留 id/play_count/last_played_at/added_at，miss_count 清零 */
    @Query("UPDATE music_tracks SET display_title = :displayTitle, artist = :artist, " +
            "album = :album, duration_ms = :durationMs, file_size = :fileSize, " +
            "modified_at = :modifiedAt, miss_count = 0 WHERE relative_path = :relativePath")
    suspend fun updateMetadataByPath(relativePath: String, displayTitle: String,
                                     artist: String?, album: String?,
                                     durationMs: Long, fileSize: Long, modifiedAt: Long)

    /** 新文件插入（仅 getByRelativePath 未命中时调用；冲突抛异常而非静默替换） */
    @Insert
    suspend fun insertTrack(entity: MusicTrackEntity)

    // ═══ 缺失收敛（M8：连续 ≥2 次才删）═══

    @Query("UPDATE music_tracks SET miss_count = miss_count + 1 WHERE relative_path = :relativePath")
    suspend fun incrementMissCount(relativePath: String)

    @Query("SELECT * FROM music_tracks WHERE miss_count >= :threshold")
    suspend fun loadTracksWithMissCountAtLeast(threshold: Int): List<MusicTrackEntity>

    // ═══ 文件操作同步（music_rename / music_delete · MU1.2）═══

    /** rename 后路径同步（id 不变 → 歌单引用零迁移） */
    @Query("UPDATE music_tracks SET relative_path = :newPath WHERE relative_path = :oldPath")
    suspend fun updateTrackPath(oldPath: String, newPath: String)

    @Query("DELETE FROM music_tracks WHERE relative_path = :relativePath")
    suspend fun deleteTrackByPath(relativePath: String)

    /** MU2.2 补账（本步落地）：播放开始递增（play_count+1 + last_played_at=now） */
    @Query("UPDATE music_tracks SET play_count = play_count + 1, last_played_at = :nowMs WHERE id = :trackId")
    suspend fun incrementPlayCount(trackId: String, nowMs: Long)

    // ═══ 歌单（MU3.1 · 设计 §5.2/§5.3——MU3.2 FC 所需一次到位）═══

    @Insert
    suspend fun insertPlaylist(entity: PlaylistEntity)

    @Query("SELECT * FROM music_playlists ORDER BY created_at DESC")
    suspend fun loadPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM music_playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylistById(id: String): PlaylistEntity?

    @Query("DELETE FROM music_playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Insert
    suspend fun insertPlaylistItem(entity: PlaylistItemEntity)

    @Query("DELETE FROM music_playlist_items WHERE playlist_id = :playlistId AND track_id = :trackId")
    suspend fun deletePlaylistItem(playlistId: String, trackId: String)

    @Query("SELECT * FROM music_playlist_items WHERE playlist_id = :playlistId ORDER BY position")
    suspend fun loadPlaylistItems(playlistId: String): List<PlaylistItemEntity>

    /** 歌单含曲目明细（JOIN，按 position 序）——music_play(playlist_id) 建队列用 */
    @Query("SELECT t.* FROM music_playlist_items i JOIN music_tracks t ON t.id = i.track_id " +
            "WHERE i.playlist_id = :playlistId ORDER BY i.position")
    suspend fun tracksOfPlaylist(playlistId: String): List<MusicTrackEntity>

    /** add_track 末尾追加位 */
    @Query("SELECT IFNULL(MAX(position), -1) + 1 FROM music_playlist_items WHERE playlist_id = :playlistId")
    suspend fun nextPlaylistPosition(playlistId: String): Int

    // ═══ M10 审计（music_delete 删前查受影响面——CASCADE 是静默的）═══

    /** 受影响歌单数（DISTINCT playlist_id） */
    @Query("SELECT COUNT(DISTINCT playlist_id) FROM music_playlist_items WHERE track_id = :trackId")
    suspend fun countPlaylistsContainingTrack(trackId: String): Int

    /** 将被级联移除的歌单项数 */
    @Query("SELECT COUNT(*) FROM music_playlist_items WHERE track_id = :trackId")
    suspend fun countPlaylistItemsOfTrack(trackId: String): Int

    // ═══ 定时播放调度（MU4.1 · 设计 §5.4/§7.1——MU4.2b 所需一次到位）═══
    // 闹钟=唤醒 tick（单一 REQUEST_MUSIC 恒指最近到期，M2/MD14）：多调度并存不靠闹钟
    // 身份区分，靠到期扫描 + 状态机原子认领区分。

    @Insert
    suspend fun insertSchedule(entity: ScheduledPlaybackEntity)

    @Query("SELECT * FROM music_schedules ORDER BY trigger_at")
    suspend fun loadSchedules(): List<ScheduledPlaybackEntity>

    @Query("SELECT * FROM music_schedules WHERE id = :id LIMIT 1")
    suspend fun getScheduleById(id: String): ScheduledPlaybackEntity?

    /** 到期扫描（execute() 入口）：全部可认领行，按到期序 */
    @Query("SELECT * FROM music_schedules WHERE status = 'active' AND trigger_at <= :now ORDER BY trigger_at")
    suspend fun loadDueSchedules(now: Long): List<ScheduledPlaybackEntity>

    /**
     * MD14 原子认领：active→firing 条件更新。**返回受影响行数**——0 即已被并行路径
     * （ExactAlarm / 进程内 kick 双路径）认领，调用方必须放弃该行（幂等落点）。
     */
    @Query("UPDATE music_schedules SET status = 'firing' WHERE id = :id AND status = 'active' AND trigger_at <= :now")
    suspend fun claimSchedule(id: String, now: Long): Int

    /** 一次性执行完成（终态） */
    @Query("UPDATE music_schedules SET status = 'fired' WHERE id = :id AND status = 'firing'")
    suspend fun markFired(id: String)

    /** Rejected playback is a failed terminal result; a concurrent cancellation wins. */
    @Query("UPDATE music_schedules SET status = 'failed' WHERE id = :id AND status = 'firing'")
    suspend fun markFailed(id: String)

    /** 周期推进：回 active 且 trigger_at=下期（调用方算好原相位保持 F17） */
    @Query("UPDATE music_schedules SET status = 'active', trigger_at = :nextTriggerAt WHERE id = :id AND status = 'firing'")
    suspend fun advanceRecurring(id: String, nextTriggerAt: Long)

    /** Startup recovery skips old periods only if the active row still matches the read phase. */
    @Query("UPDATE music_schedules SET trigger_at = :nextTriggerAt WHERE id = :id " +
        "AND status = 'active' AND is_recurring = 1 AND trigger_at = :expectedTriggerAt")
    suspend fun restoreRecurring(id: String, expectedTriggerAt: Long, nextTriggerAt: Long): Int

    /** 进程死错过的过期一次性（G6 恢复期判定） */
    @Query("UPDATE music_schedules SET status = 'missed' WHERE id = :id")
    suspend fun markMissed(id: String)

    /** AUTO 触顶被拒（M5 双门控第一道） */
    @Query("UPDATE music_schedules SET status = 'skipped_cap' WHERE id = :id AND status = 'firing'")
    suspend fun markSkippedCap(id: String)

    /** 用户取消（终态） */
    @Query("UPDATE music_schedules SET status = 'cancelled' WHERE id = :id AND status IN ('active', 'firing')")
    suspend fun cancelSchedule(id: String): Int

    /** MU4.3-fix（L3 实测）：物理删除调度行——终态记录（fired/missed/cancelled/skipped_cap）页面清理 */
    @Query("DELETE FROM music_schedules WHERE id = :id AND status IN ('fired', 'failed', 'missed', 'skipped_cap', 'cancelled')")
    @Transaction
    suspend fun deleteSchedule(id: String): Int

    /** 最近 active 到期时刻（scheduleWake/scheduleNextMusicAlarm 重排用；无 = null） */
    @Query("SELECT MIN(trigger_at) FROM music_schedules WHERE status = 'active'")
    suspend fun nextActiveTriggerAt(): Long?

    /** 恢复期陈旧一次性扫描（onStart：trigger_at < cutoff 且未周期——cutoff=now-宽容窗） */
    @Query("SELECT * FROM music_schedules WHERE status = 'active' AND is_recurring = 0 AND trigger_at < :cutoff")
    suspend fun loadStaleOneShots(cutoff: Long): List<ScheduledPlaybackEntity>
}
