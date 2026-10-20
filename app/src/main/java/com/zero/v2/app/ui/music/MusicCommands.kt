package com.zero.v2.app.ui.music

import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.moduleapi.ToolDispatcher

/** 音乐 UI typed commands；FC 名称与参数映射集中在单一实现。 */
sealed interface MusicCommand {
    data object Scan : MusicCommand
    data object Pause : MusicCommand
    data object Resume : MusicCommand
    data object Next : MusicCommand
    data object Previous : MusicCommand
    data class Shuffle(val enabled: Boolean) : MusicCommand
    data class Repeat(val mode: String) : MusicCommand
    data class Seek(val positionMs: Long) : MusicCommand
    data class Play(val playlistId: String) : MusicCommand
    data class DeletePlaylist(val playlistId: String) : MusicCommand
    data class Schedule(val args: Map<String, Any?>) : MusicCommand
    data class CancelSchedule(val id: String) : MusicCommand
    data class CreatePlaylist(val name: String, val trackIds: List<String>) : MusicCommand
    data class PlayTracks(val trackIds: List<String>) : MusicCommand
}

class MusicCommandDispatcher(private val dispatcher: ToolDispatcher) {
    suspend fun execute(command: MusicCommand): FunctionResult = when (command) {
        MusicCommand.Scan -> dispatcher.execute("music_scan", emptyMap())
        MusicCommand.Pause -> dispatcher.execute("music_pause", emptyMap())
        MusicCommand.Resume -> dispatcher.execute("music_resume", emptyMap())
        MusicCommand.Next -> dispatcher.execute("music_next", emptyMap())
        MusicCommand.Previous -> dispatcher.execute("music_prev", emptyMap())
        is MusicCommand.Shuffle -> dispatcher.execute("music_shuffle", mapOf("enabled" to command.enabled))
        is MusicCommand.Repeat -> dispatcher.execute("music_repeat", mapOf("mode" to command.mode))
        is MusicCommand.Seek -> dispatcher.execute("music_seek", mapOf("position_ms" to command.positionMs))
        is MusicCommand.Play -> dispatcher.execute("music_play", mapOf("playlist_id" to command.playlistId))
        is MusicCommand.DeletePlaylist -> dispatcher.execute("playlist_delete", mapOf("playlist_id" to command.playlistId))
        is MusicCommand.Schedule -> dispatcher.execute("music_schedule", command.args)
        is MusicCommand.CancelSchedule -> dispatcher.execute("music_cancel_schedule", mapOf("schedule_id" to command.id))
        is MusicCommand.CreatePlaylist -> dispatcher.execute("playlist_create", mapOf("name" to command.name, "from_track_ids" to command.trackIds))
        is MusicCommand.PlayTracks -> dispatcher.execute("music_play", mapOf("track_ids" to command.trackIds))
    }
}
