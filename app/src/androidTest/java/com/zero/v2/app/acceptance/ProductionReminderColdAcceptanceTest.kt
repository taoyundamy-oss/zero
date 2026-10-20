package com.zero.v2.app.acceptance

import android.app.Notification
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

/** Explicit opt-in actual production reminder, no replacement Hilt factory/Gate/database. */
class ProductionReminderColdAcceptanceTest {
    private val probeId = "a8-production-cold-reminder"
    private val content = "A8验收测试：生产冷恢复提醒"
    private fun fingerprint(rows: List<ReminderEntity>) = MessageDigest.getInstance("SHA-256")
        .digest(rows.sortedBy { it.id }.joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun realRtcReceiverAndOriginalWorkerDeliverNotification() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("productionReminderPhase")
        assumeTrue(phase == "seed" || phase == "verify")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val db = entry.database()
        val dao = db.systemStateDao()
        val ready = File(context.filesDir, "a8-production-reminder-ready.json")
        val receipt = File(context.filesDir, "a8-production-reminder-receipt.json")
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val work = WorkManager.getInstance(context)
        val workName = DurableAlarmWorkQueue.workName(ExactAlarmScheduler.ACTION_REMINDER_DUE)
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
            val trigger = now + 60_000
            dao.saveReminder(ReminderEntity(probeId, content, trigger, title = "A8验收测试"))
            ExactAlarmScheduler(context).scheduleReminder(trigger)
            ready.writeText(JSONObject().put("pid", Process.myPid()).put("uid", Process.myUid())
                .put("triggerAt", trigger).put("createdAt", now).put("remindersHash", fingerprint(rows))
                .put("oldWorkIds", JSONArray(originalWork.map { it.id.toString() })).toString())
            delay(150_000)
            fail("Driver did not terminate seed process")
        } else {
            val marker = JSONObject(ready.readText())
            val originalIds = marker.getJSONArray("oldWorkIds").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            val infos = work.getWorkInfosForUniqueWork(workName).get().filter { it.id.toString() !in originalIds }
            val own = notifications.activeNotifications.filter { it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == content }
            val trace = JSONArray()
            val sql = db.openHelper.readableDatabase
            sql.query("SELECT timestamp,summary FROM agent_trace_events WHERE timestamp >= ? AND (summary = ? OR summary = ? OR summary = ? OR summary LIKE 'job_execute:%') ORDER BY id",
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
                .put("work", classes).put("trace", trace).put("triggerAt", marker.getLong("triggerAt")).toString())
            try {
                assertNotEquals(marker.getInt("pid"), Process.myPid())
                assertEquals(1, infos.size)
                assertEquals(WorkInfo.State.SUCCEEDED, infos.single().state)
                assertEquals(AutomationWorker::class.java.name, classes.getJSONObject(0).getString("class"))
                assertEquals(1, own.size)
                assertFalse(dao.loadReminders().any { it.id == probeId })
                assertEquals(marker.getString("remindersHash"), fingerprint(dao.loadReminders()))
            } finally {
                own.forEach { notifications.cancel(it.tag, it.id) }
                dao.deleteReminder(probeId)
                ready.delete()
            }
        }
    }
}
