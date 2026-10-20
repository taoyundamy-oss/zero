package com.zero.v2.app.acceptance

import android.content.Intent
import androidx.lifecycle.ViewModelStore
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.background.MusicService
import com.zero.v2.app.music.MusicModuleConfig
import com.zero.v2.app.ui.music.MusicViewModel
import com.zero.v2.core.data.entity.MusicTrackEntity
import com.zero.v2.core.moduleapi.IZeroModule
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Production Hilt engine, service and dispatcher; only UUID-owned files/rows are mutable. */
class MusicRealPlaybackAcceptanceTest {
    @Test fun ownAudioQueuePlaylistAndFixedSchedule() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val data = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val db = data.database(); val dao = db.musicDao(); val directory = data.workDirectory()
        assertTrue("A persisted work directory is required", directory.hasDirectory())
        val beforeTracks = dao.loadAllTracks(); val beforePlaylists = dao.loadPlaylists(); val beforeSchedules = dao.loadSchedules()
        assertTrue("Do not disturb existing active or firing music schedules", beforeSchedules.none { it.status in setOf("active", "firing") })
        val beforeItems = beforePlaylists.associate { it.id to dao.loadPlaylistItems(it.id) }
        val token = UUID.randomUUID().toString(); val folder = "a8_acceptance_music_$token"
        val ids = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val playlistName = "a8_acceptance_$token"
        val paths = listOf("llmmusic/$folder/first.wav", "llmmusic/$folder/second.wav")
        val bytes = wav()
        assertTrue(directory.listFiles("llmmusic").none { it.name == folder })
        val module = entry.music(); val cfg = module.config as MusicModuleConfig; val originalConfig = cfg.toJson()
        val store = ViewModelStore(); var activity: VisualAcceptanceActivity? = null
        val written = mutableListOf<String>(); var model: MusicViewModel? = null
        val ownedSchedules = mutableSetOf<String>(); val ownedPlaylists = mutableSetOf<String>()
        var completed = false
        val reportFile = File(context.filesDir,"a8-music-real-report.json")
        if (reportFile.exists()) assertTrue(reportFile.delete())
        val musicTicks = java.util.concurrent.atomic.AtomicInteger()
        val observer = launch(Dispatchers.Default) { entry.systemBus().onMusicDue.collect { musicTicks.incrementAndGet() } }
        val marker = File(context.filesDir, "a8-music-real-fixture.json")
        assertFalse("Pending fixture must be cleaned before another is created", marker.exists())
        marker.writeText(JSONObject().put("folder", folder).put("trackIds", org.json.JSONArray(ids))
            .put("playlistName", playlistName).put("paths", org.json.JSONArray(paths)).toString())
        try {
            cfg.autoScanOnStart = false; cfg.defaultVolume = 0 // Decode and AudioTrack run without audible test tones.
            activity = inst.startActivitySync(Intent(context, VisualAcceptanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
            assertNotNull(entry.lifecycleManager().safeCall(module, "acceptance-awake") { module.onAwake() })
            assertNotNull(entry.lifecycleManager().safeCall(module, "acceptance-start") { module.onStart() })
            withTimeout(5000) { while (entry.systemBus().onMusicDue.subscriptionCount.value < 2) delay(50) }
            val registry = entry.dispatcher() as IZeroModule
            assertNotNull(entry.lifecycleManager().safeCall(registry, "acceptance-registry-awake") { registry.onAwake() })
            entry.toolRegistrar().registerContributions(module)
            for (path in paths) {
                val out = directory.writeBinary(path, "audio/wav", bytes)
                assertNull("Owned WAV write failed", out.error); written.add(out.finalRelPath)
                assertEquals(path, out.finalRelPath)
            }
            db.withTransaction {
                paths.forEachIndexed { i, path -> dao.insertTrack(MusicTrackEntity(ids[i], path.removePrefix("llmmusic/"),
                    displayTitle = "a8_audio_$i", durationMs = 8000, fileSize = bytes.size.toLong(), addedAt = 0)) }
            }
            val vm = withContext(Dispatchers.Main) { MusicViewModel(dao, directory, entry.dispatcher(), module, entry.traces()).also { store.put("owned-music", it) } }
            model = vm
            withTimeout(5000) { vm.state.first { !it.loading } }
            suspend fun command(action: () -> Unit) {
                withContext(Dispatchers.Main) { action() }
                withTimeout(10000) { vm.state.first { !it.busy && !it.loading } }
                assertNull("Production command must report success", vm.state.value.operationError)
            }
            command { vm.playFrom(0, ids) }
            withTimeout(10000) { module.playbackState.first { it.isPlaying && it.trackId == ids[0] && it.durationMs > 0 } }
            assertEquals(2, module.playbackState.value.queueSize)
            withTimeout(5000) { while ((module.playbackPosition()?.first ?: 0) < 250) delay(50) }
            command { vm.togglePlayPause() }
            withTimeout(5000) { module.playbackState.first { !it.isPlaying } }
            val paused = module.playbackPosition()!!.first; delay(250)
            assertTrue("Pause must stop position progression", kotlin.math.abs(module.playbackPosition()!!.first - paused) <= 60)
            command { vm.togglePlayPause() }
            withTimeout(5000) { module.playbackState.first { it.isPlaying } }
            command { vm.next() }; withTimeout(5000) { module.playbackState.first { it.trackId == ids[1] } }
            command { vm.prev() }; withTimeout(5000) { module.playbackState.first { it.trackId == ids[0] } }
            command { vm.seekTo(1500) }
            assertTrue("Seek reaches requested position", module.playbackPosition()!!.first in 1400..2200)
            command { vm.createPlaylistFrom(playlistName, ids) }
            val playlist = dao.loadPlaylists().single { it.name == playlistName }; ownedPlaylists.add(playlist.id)
            assertTrue("Real playlist preserves track order", dao.loadPlaylistItems(playlist.id).map { it.trackId } == ids)
            command { vm.playPlaylist(playlist.id) }
            withTimeout(5000) { module.playbackState.first { it.isPlaying && it.queueSize == 2 && it.trackId == ids[0] } }
            val alarms = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            val exact = android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
            assertTrue("Real OS delivery requires current exact alarm permission", exact)
            command { vm.setSchedule("+10s", "FIXED", playlist.id, token, false, null) }
            val shortCancel = dao.loadSchedules().single { it.hint == token }; ownedSchedules.add(shortCancel.id)
            withTimeout(3000) { while (module.nextRunTime != shortCancel.triggerAt) delay(50) }
            delay(200) // The actual AlarmManager call follows nextRunTime publication.
            val ticksBefore = musicTicks.get()
            command { vm.cancelSchedule(shortCancel.id) }
            withTimeout(3000) { while (module.nextRunTime != Long.MAX_VALUE) delay(50) }
            delay((shortCancel.triggerAt - System.currentTimeMillis()).coerceAtLeast(0) + 2000)
            assertEquals("Cancelling the last schedule must remove its OS wakeup", ticksBefore, musicTicks.get())
            command { vm.removeSchedule(shortCancel.id) }
            command { vm.setSchedule("+3600s", "FIXED", playlist.id, token, false, null) }
            val cancel = dao.loadSchedules().single { it.hint == token }; ownedSchedules.add(cancel.id)
            command { vm.cancelSchedule(cancel.id) }
            assertEquals("cancelled", dao.getScheduleById(cancel.id)!!.status)
            command { vm.removeSchedule(cancel.id) }; assertNull(dao.getScheduleById(cancel.id))
            command { vm.setSchedule("+2s", "FIXED", playlist.id, token, false, null) }
            val fire = dao.loadSchedules().single { it.hint == token }; ownedSchedules.add(fire.id)
            val beforeFireTicks = musicTicks.get()
            withTimeout(20000) { while (dao.getScheduleById(fire.id)?.status != "fired") delay(100) }
            assertTrue("OS receiver must deliver the observed music-due event", musicTicks.get() > beforeFireTicks)
            withTimeout(5000) { module.playbackState.first { it.isPlaying && it.trackId == ids[0] } }
            delay(250)
            val count = dao.getTrackById(ids[0])!!.playCount
            assertNotNull(entry.lifecycleManager().safeCall(module, "acceptance-duplicate-tick") { module.execute() })
            delay(250); assertEquals("Already fired schedule must not play twice", count, dao.getTrackById(ids[0])!!.playCount)
            command { vm.removeSchedule(fire.id) }; assertNull(dao.getScheduleById(fire.id))
            command { vm.setSchedule("+2s", "FIXED", playlist.id, token, true, 60) }
            val recurring = dao.loadSchedules().single { it.hint == token }; ownedSchedules.add(recurring.id)
            val beforeRecurringTicks = musicTicks.get()
            withTimeout(20000) { while (dao.getScheduleById(recurring.id)!!.triggerAt == recurring.triggerAt) delay(100) }
            val advanced = dao.getScheduleById(recurring.id)!!
            assertEquals("active",advanced.status)
            assertEquals(recurring.triggerAt + 60000,advanced.triggerAt)
            assertTrue("Recurring first period comes from the OS receiver",musicTicks.get() > beforeRecurringTicks)
            command { vm.cancelSchedule(recurring.id) };assertEquals("cancelled",dao.getScheduleById(recurring.id)!!.status)
            command { vm.removeSchedule(recurring.id) };assertNull(dao.getScheduleById(recurring.id))
            command { vm.deletePlaylist(playlist.id) }; assertNull(dao.getPlaylistById(playlist.id))
            reportFile.writeText(JSONObject().put("passed",true)
                .put("actualDecoder",true).put("muted",true).put("queuePauseResumeSeek",true)
                .put("productionDispatcherAndViewModel",true).put("realPlaylistWrites",true)
                .put("osExactAlarmDelivered",true).put("fixedScheduleWithoutLlm",true)
                .put("lastCancelledScheduleDidNotWake",true)
                .put("recurringFirstPeriodPreservedPhaseAndCancelled",true)
                .put("duplicateTickDidNotReplay",true).put("providerRequests",0).put("cleaned",false).toString(2))
            completed = true
        } finally {
            observer.cancelAndJoin()
            withContext(Dispatchers.Main) { store.clear() }
            MusicService.stop(context)
            entry.lifecycleManager().safeCall(module,"acceptance-destroy") { module.onDestroy() }
            cfg.fromJson(originalConfig)
            // IDs and file paths came only from this fixture. Never remove existing library entries.
            ownedPlaylists.addAll(dao.loadPlaylists().filter { it.name == playlistName }.map { it.id })
            ownedSchedules.addAll(dao.loadSchedules().filter { it.hint == token }.map { it.id })
            db.withTransaction {
                for (id in ownedSchedules) { dao.cancelSchedule(id); dao.deleteSchedule(id) }
                for (id in ownedPlaylists) dao.deletePlaylist(id)
                for (path in paths) dao.deleteTrackByPath(path.removePrefix("llmmusic/"))
            }
            // Only the music PendingIntent created here; reminder/automation alarms stay intact.
            com.zero.v2.services.agent.ExactAlarmScheduler(context).cancelMusic()
            for (path in written) {
                assertTrue("Never delete an unverified owned file", directory.readBinary(path)?.contentEquals(bytes) == true)
                assertNull(directory.deleteFile(path))
            }
            if (written.isNotEmpty()) { assertTrue(directory.listFiles("llmmusic/$folder").isEmpty()); assertNull(directory.deleteTree("llmmusic/$folder",true).error) }
            assertTrue("Existing tracks unchanged", beforeTracks == dao.loadAllTracks())
            assertTrue("Existing playlists unchanged", beforePlaylists == dao.loadPlaylists())
            assertTrue("Existing schedules unchanged", beforeSchedules == dao.loadSchedules())
            assertTrue("Existing playlist items unchanged", beforeItems.all { (id, items) -> dao.loadPlaylistItems(id) == items })
            assertTrue(marker.delete())
            activity?.let { inst.runOnMainSync { it.finish() } }
            if (completed) {
                val out = File(context.filesDir,"a8-music-real-report.json")
                out.writeText(JSONObject(out.readText()).put("cleaned",true).put("existingMusicDataUnchanged",true).toString(2))
            }
        }
    }

    private fun wav(): ByteArray {
        val frames = 8000 * 8; val dataSize = frames * 2
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(36 + dataSize);put("WAVEfmt ".toByteArray());putInt(16)
            putShort(1);putShort(1);putInt(8000);putInt(16000);putShort(2);putShort(16)
            put("data".toByteArray());putInt(dataSize)
            repeat(frames) { putShort((kotlin.math.sin(it * 2 * Math.PI * 440 / 8000) * 1000).toInt().toShort()) }
        }.array()
    }
}
