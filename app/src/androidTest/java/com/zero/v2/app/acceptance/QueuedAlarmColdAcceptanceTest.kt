package com.zero.v2.app.acceptance

import android.app.Notification
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.zero.v2.core.data.entity.ModuleConfigEntity
import com.zero.v2.services.agent.ExactAlarmReceiver
import android.app.NotificationManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.zero.v2.app.background.AutomationWorker
import com.zero.v2.app.background.DurableAlarmWorkQueue
import com.zero.v2.core.data.entity.ReminderEntity
import javax.inject.Provider
import java.util.UUID
import com.zero.v2.services.agent.ExactAlarmScheduler
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Same committed production WorkSpec across SIGKILL; no reminder notification is due. */
class QueuedAlarmColdAcceptanceTest {
    private val configMutex = Mutex()
    private val probeId = "a8-queued-alarm-unused"
    private val content = "A8验收测试：Worker独立冷提醒"
    private fun fingerprint(rows: List<ReminderEntity>) = MessageDigest.getInstance("SHA-256")
        .digest(rows.sortedBy { it.id }.joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun originalCommittedWorkSpecSurvivesTermination() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("queuedAlarmPhase")
        assumeTrue(phase == "seed" || phase == "verify" || phase == "cleanup")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val db = entry.database()
        val dao = db.systemStateDao()
        val ready = File(context.filesDir, "a8-queued-alarm-ready.json")
        val receipt = File(context.filesDir, "a8-queued-alarm-receipt.json")
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val work = WorkManager.getInstance(context)
        val workName = DurableAlarmWorkQueue.workName(ExactAlarmScheduler.ACTION_REMINDER_DUE)
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val wakeIntent = Intent(context, ExactAlarmReceiver::class.java).setAction("com.zero.v2.action.A8_QUEUED_PREWAKE")
        fun restoreMode(marker: JSONObject) = runBlocking {
            if (marker.has("workId")) {
                val id = UUID.fromString(marker.getString("workId"))
                val info = work.getWorkInfoById(id).get()
                if (info != null && !info.state.isFinished) work.cancelWorkById(id).result.get()
            }
            configMutex.withLock { db.withTransaction {
                val current = requireNotNull(db.moduleConfigDao().getByModuleId("agent_behavior"))
                val original = marker.getString("originalConfig")
                check(current.configJson == original || current.configJson == marker.getString("offConfig")) {
                    "Concurrent user edit: do not overwrite mode"
                }
                db.moduleConfigDao().upsert(ModuleConfigEntity("agent_behavior", original, marker.getLong("originalUpdatedAt")))
            } }
            PendingIntent.getBroadcast(context, 900_825, wakeIntent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { alarm.cancel(it); it.cancel() }
            dao.deleteReminder(probeId)
            entry.systemEvents().restoreAfterBoot()
            ready.delete()
        }
        if (phase == "cleanup") {
            if (ready.exists()) restoreMode(JSONObject(ready.readText()))
            notifications.activeNotifications.filter {
                it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == content
            }.forEach { notifications.cancel(it.tag, it.id) }
            return@runBlocking
        }
        if (phase == "seed") {
            assertFalse(ready.exists())
            assertFalse(receipt.exists())
            val now = System.currentTimeMillis()
            val rows = dao.loadReminders()
            assertFalse(rows.any { it.id == probeId })
            assertFalse("Do not compete with a personal reminder", rows.any {
                it.status == ReminderEntity.STATUS_ACTIVE && it.triggerAt <= now + 180_000 })
            assertTrue(notifications.areNotificationsEnabled())
            assertTrue(notifications.getNotificationChannel("zero_reminder")?.importance != NotificationManager.IMPORTANCE_NONE)
            val originalWork = work.getWorkInfosForUniqueWork(workName).get()
            assertTrue("Do not interrupt pending reminder delivery", originalWork.all { it.state.isFinished })
            assertTrue(alarm.canScheduleExactAlarms())
            val originalConfig = requireNotNull(db.moduleConfigDao().getByModuleId("agent_behavior"))
            val offConfig = JSONObject(originalConfig.configJson).put("mode", "OFF").toString()
            val trigger = now + 20_000
            val marker = JSONObject().put("pid", Process.myPid()).put("uid", Process.myUid())
                .put("triggerAt", trigger).put("createdAt", now).put("remindersHash", fingerprint(rows))
                .put("oldWorkIds", JSONArray(originalWork.map { it.id.toString() }))
                .put("originalConfig", originalConfig.configJson).put("originalUpdatedAt", originalConfig.updatedAt)
                .put("offConfig", offConfig).put("stage", "preparing")
            ready.writeText(marker.toString())
            try {
                configMutex.withLock { db.withTransaction {
                    db.moduleConfigDao().upsert(originalConfig.copy(configJson = offConfig))

                } }
                DurableAlarmWorkQueue(Provider { work }).enqueue(ExactAlarmScheduler.ACTION_REMINDER_DUE)
                val oldIds = originalWork.map { it.id }.toSet()
                val committed = withTimeout(10_000) {
                    var info = work.getWorkInfosForUniqueWork(workName).get().single { it.id !in oldIds }
                    while (info.state != WorkInfo.State.RUNNING) {
                        check(!info.state.isFinished) { "Original Worker unexpectedly finished before initialization" }
                        delay(50)
                        info = requireNotNull(work.getWorkInfoById(info.id).get())
                    }
                    info
                }
                marker.put("workId", committed.id.toString()).put("seedWorkState", committed.state.name)
                    .put("seedAttempt", committed.runAttemptCount)
                SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, "androidx.work.workdb").path,
                    null, SQLiteDatabase.OPEN_READONLY).use { physical ->
                    physical.rawQuery("SELECT worker_class_name,state FROM workspec WHERE id=?",
                        arrayOf(committed.id.toString())).use { c ->
                        assertTrue(c.moveToFirst())
                        assertEquals(AutomationWorker::class.java.name, c.getString(0))
                        assertEquals(1, c.getInt(1))
                        marker.put("seedPhysicalState", c.getInt(1)).put("seedClass", c.getString(0))
                    }
                }
                val prewake = PendingIntent.getBroadcast(context, 900_825, wakeIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 20_000, prewake)
                ready.writeText(marker.put("stage", "ready").toString())
            } catch (e: Exception) {
                restoreMode(marker)
                throw e
            }
            delay(150_000)
            fail("Driver did not terminate seed process")
        } else {
            val marker = JSONObject(ready.readText())
            val originalIds = marker.getJSONArray("oldWorkIds").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            val infos = work.getWorkInfosForUniqueWork(workName).get().filter { it.id.toString() !in originalIds }
            val own = notifications.activeNotifications.filter { it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == content }
            val trace = JSONArray()
            val sql = db.openHelper.readableDatabase
            sql.query("SELECT timestamp,summary FROM agent_trace_events WHERE timestamp >= ? AND (summary = ? OR summary = ? OR summary = ? OR summary LIKE 'job_execute:%' OR summary LIKE 'loop_started:%' OR summary LIKE 'loop_blocked_by_mode:%') ORDER BY id",
                arrayOf(marker.getLong("createdAt"), "reminder_notified: $content", "alarm_fired: REMINDER_DUE",
                    "alarm_scan_finished: success=true action=${ExactAlarmScheduler.ACTION_REMINDER_DUE}")).use { c ->
                while (c.moveToNext()) trace.put(JSONObject().put("timestamp", c.getLong(0)).put("summary", c.getString(1)))
            }
            val classes = JSONArray()
            SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, "androidx.work.workdb").path, null, SQLiteDatabase.OPEN_READONLY).use { physical ->
                infos.forEach { info -> physical.rawQuery("SELECT worker_class_name,state FROM workspec WHERE id=?", arrayOf(info.id.toString())).use { c ->
                    assertTrue(c.moveToFirst())
                    classes.put(JSONObject().put("uuid", info.id.toString()).put("class", c.getString(0)).put("state", c.getInt(1)))
                } }
            }
            receipt.writeText(JSONObject().put("seedPid", marker.getInt("pid")).put("verifyPid", Process.myPid())
                .put("notifications", own.size).put("probeRowPresent", dao.loadReminders().any { it.id == probeId })
                .put("work", classes).put("trace", trace).put("triggerAt", marker.getLong("triggerAt"))
                .put("originalWorkId", marker.getString("workId")).put("seedWorkState", marker.getString("seedWorkState"))
                .put("seedPhysicalState", marker.getInt("seedPhysicalState")).put("seedClass", marker.getString("seedClass"))
                .put("seedAttempt", marker.getInt("seedAttempt")).put("finalAttempt", infos.single().runAttemptCount).toString())
            try {
                assertNotEquals(marker.getInt("pid"), Process.myPid())
                assertEquals(1, infos.size)
                assertEquals(WorkInfo.State.SUCCEEDED, infos.single().state)
                assertEquals(AutomationWorker::class.java.name, classes.getJSONObject(0).getString("class"))
                assertEquals("OFF", JSONObject(requireNotNull(db.moduleConfigDao().getByModuleId("agent_behavior")).configJson).getString("mode"))
                val events = (0 until trace.length()).map { trace.getJSONObject(it) }
                assertFalse("Runtime must never start during independent delivery", events.any { it.getString("summary").startsWith("loop_started:") })
                assertTrue(events.any { it.getString("summary").startsWith("loop_blocked_by_mode:") })
                assertEquals(marker.getString("workId"), infos.single().id.toString())
                assertTrue(events.any { it.getString("summary") == "alarm_scan_finished: success=true action=${ExactAlarmScheduler.ACTION_REMINDER_DUE}" })
                assertFalse(events.any { it.getString("summary").startsWith("job_execute:") })
                assertEquals(0, own.size)
                assertFalse(dao.loadReminders().any { it.id == probeId })
                assertEquals(marker.getString("remindersHash"), fingerprint(dao.loadReminders()))
            } finally {
                own.forEach { notifications.cancel(it.tag, it.id) }
                restoreMode(marker)
            }
        }
    }
}
