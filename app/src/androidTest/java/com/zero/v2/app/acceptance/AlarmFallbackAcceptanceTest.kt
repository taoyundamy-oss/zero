package com.zero.v2.app.acceptance

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.services.agent.AlarmSchedulingAcceptanceAccess
import com.zero.v2.services.agent.ExactAlarmReceiver
import org.junit.Assert.*
import org.junit.Test

/** Real system registration, independent identity. Permission denial/revocation are injected. */
class AlarmFallbackAcceptanceTest {
    private fun probe(allowed: Boolean, revokeAtSubmission: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertTrue("Keep the device's existing exact permission", alarms.canScheduleExactAlarms())
        val action = "com.zero.v2.action.A8_ISOLATED_ALARM_FALLBACK"
        val pending = PendingIntent.getBroadcast(context, 900_823,
            Intent(context, ExactAlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val trigger = System.currentTimeMillis() + 3_600_000
        var exactCalls = 0
        var windowCalls = 0
        try {
            val result = AlarmSchedulingAcceptanceAccess.submit({ allowed }, {
                exactCalls++
                if (revokeAtSubmission) throw SecurityException("Injected revocation")
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            }, {
                windowCalls++
                alarms.setWindow(AlarmManager.RTC_WAKEUP, trigger, 60_000, pending)
            })
            val exactExpected = allowed && !revokeAtSubmission
            assertEquals(if (exactExpected) "EXACT" else "WINDOW", result)
            assertEquals(if (allowed) 1 else 0, exactCalls)
            assertEquals(if (exactExpected) 0 else 1, windowCalls)
            val dump = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("dumpsys alarm"))
                .bufferedReader().use { it.readLines() }
            val index = dump.indexOfFirst { action in it }
            assertTrue("Own alarm must be physically registered", index >= 0)
            val ownBlock = dump.subList(index, (index + 4).coerceAtMost(dump.size)).joinToString("\n")
            val window = Regex("window=([^ ]+)").find(ownBlock)?.groupValues?.get(1)
            assertNotNull("Own alarm must expose its scheduling window", window)
            if (exactExpected) assertEquals("0", window) else assertNotEquals("0", window)
        } finally {
            alarms.cancel(pending)
            pending.cancel()
        }
    }

    @Test fun exactBranchRegistersRealExactAlarm() = probe(true, false)
    @Test fun deniedBranchRegistersRealWindowAlarm() = probe(false, false)
    @Test fun revokedSubmissionRegistersOneRealWindowAlarm() = probe(true, true)
}
