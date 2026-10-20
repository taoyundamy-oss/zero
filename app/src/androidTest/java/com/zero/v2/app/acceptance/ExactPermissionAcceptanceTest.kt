package com.zero.v2.app.acceptance

import android.app.AlarmManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.entity.ModuleConfigEntity
import com.zero.v2.core.data.entity.ReminderEntity
import com.zero.v2.services.agent.ExactAlarmScheduler
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Actual user-0 permission denial/grant; driver restores shared power exemption in finally. */
class ExactPermissionAcceptanceTest {
    private val mutex = Mutex()
    private val id = "a8-real-exact-permission"
    private fun fingerprint(rows: List<ReminderEntity>) = MessageDigest.getInstance("SHA-256")
        .digest(rows.sortedBy { it.id }.joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun realDenialAndSystemGrantRestoreOriginalAlarm() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("exactPermissionPhase")
        assumeTrue(phase in setOf("prepare", "observe", "cleanup"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val db = entry.database()
        val dao = db.systemStateDao()
        val backup = File(context.filesDir, "a8-exact-permission-backup.json")
        val ready = File(context.filesDir, "a8-exact-permission-ready.json")
        val receipt = File(context.filesDir, "a8-exact-permission-receipt.json")
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (phase == "prepare") {
            assertFalse(backup.exists())
            assertFalse(ready.exists())
            assertFalse(receipt.exists())
            val now = System.currentTimeMillis()
            val rows = dao.loadReminders()
            assertFalse(rows.any { it.id == id })
            assertFalse("Personal reminder too near", rows.any { it.status == ReminderEntity.STATUS_ACTIVE && it.triggerAt <= now + 180_000 })
            assertTrue(alarms.canScheduleExactAlarms())
            val original = requireNotNull(db.moduleConfigDao().getByModuleId("agent_behavior"))
            val off = JSONObject(original.configJson).put("mode", "OFF").toString()
            val marker = JSONObject().put("originalConfig", original.configJson).put("updatedAt", original.updatedAt)
                .put("offConfig", off).put("remindersHash", fingerprint(rows)).put("triggerAt", now + 120_000)
            backup.writeText(marker.toString())
            mutex.withLock { db.withTransaction {
                db.moduleConfigDao().upsert(original.copy(configJson = off))
                dao.saveReminder(ReminderEntity(id, "A8权限验收测试", marker.getLong("triggerAt")))
            } }
            return@runBlocking
        }
        if (phase == "cleanup") {
            if (backup.exists()) {
                val marker = JSONObject(backup.readText())
                mutex.withLock { db.withTransaction {
                    val current = requireNotNull(db.moduleConfigDao().getByModuleId("agent_behavior"))
                    check(current.configJson == marker.getString("originalConfig") || current.configJson == marker.getString("offConfig")) {
                        "Concurrent config edit: do not overwrite"
                    }
                    db.moduleConfigDao().upsert(ModuleConfigEntity("agent_behavior", marker.getString("originalConfig"), marker.getLong("updatedAt")))
                    dao.deleteReminder(id)
                } }
                assertEquals(marker.getString("remindersHash"), fingerprint(dao.loadReminders()))
                entry.systemEvents().restoreAfterBoot()
                backup.delete()
            }
            ready.delete()
            return@runBlocking
        }
        val marker = JSONObject(backup.readText())
        val trigger = marker.getLong("triggerAt")
        fun window(): String? {
            val lines = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys alarm"))
                .bufferedReader().use { it.readLines() }
            val index = lines.indexOfFirst { "origWhen $trigger" in it && "com.zero.v2" in it }
            if (index < 0) return null
            return Regex("window=([^ ]+)").find(lines.subList(index, (index + 5).coerceAtMost(lines.size)).joinToString("\n"))?.groupValues?.get(1)
        }
        assertFalse("Effective device permission must really be denied", alarms.canScheduleExactAlarms())
        val module = entry.systemEvents()
        val start = System.currentTimeMillis()
        try {
            module.onAwake()
            module.onStart()
            ExactAlarmScheduler(context).scheduleReminder(trigger)
            val deniedWindow = requireNotNull(window())
            assertNotEquals("0", deniedWindow)
            ready.writeText(JSONObject().put("deniedWindow", deniedWindow).put("canExact", false).toString())
            withTimeout(40_000) {
                while (true) {
                    val changed = db.openHelper.readableDatabase.query(
                        "SELECT count(*) FROM agent_trace_events WHERE timestamp >= ? AND summary = 'alarm_permission_changed: exact=true'",
                        arrayOf(start)).use { it.moveToFirst(); it.getInt(0) }
                    if (alarms.canScheduleExactAlarms() && changed > 0 && window() == "0") break
                    delay(100)
                }
            }
            receipt.writeText(JSONObject().put("deniedCanExact", false).put("grantedCanExact", alarms.canScheduleExactAlarms())
                .put("deniedWindow", deniedWindow).put("restoredWindow", window()).put("systemBroadcastObserved", true).toString())
            assertEquals(marker.getString("remindersHash"), fingerprint(dao.loadReminders().filter { it.id != id }))
        } finally { module.onDestroy(); ready.delete() }
    }
}
