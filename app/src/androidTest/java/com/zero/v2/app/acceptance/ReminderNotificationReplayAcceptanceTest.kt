package com.zero.v2.app.acceptance

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.services.module.SystemEventModule
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/** Replays the actual notification outlet, not a SIGKILL or a durable delivery claim. */
class ReminderNotificationReplayAcceptanceTest {
    private suspend fun replay(ids: List<Pair<String, Long>>, expected: Int) {
        WorkerReminderDeliveryAcceptanceTest().fixture { module, _, _, _, _ ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val content = "A8 notification replay ${UUID.randomUUID()}"
            fun own() = manager.activeNotifications.filter {
                it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == content
            }
            // Exercise both the original outlet and the revised occurrence-aware outlet.
            val method = SystemEventModule::class.java.declaredMethods.single { it.name == "postReminderNotification" }
                .apply { isAccessible = true }
            try {
                for ((id, trigger) in ids) {
                    if (method.parameterCount == 1) method.invoke(module, content)
                    else method.invoke(module, id, trigger, content)
                    delay(100)
                }
                withTimeout(3000) { while (own().isEmpty()) delay(20) }
                assertEquals("Only the same reminder occurrence may coalesce", expected, own().size)
                if (expected == 1) assertEquals(Notification.FLAG_ONLY_ALERT_ONCE,
                    own().single().notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
            } finally {
                own().forEach { manager.cancel(it.tag, it.id) }
            }
        }
    }

    @Test fun replayAfterUncommittedDeliveryDoesNotStackVisibleNotifications() = runBlocking {
        replay(listOf("same" to 1000L, "same" to 1000L), 1)
    }

    @Test fun identicalTextFromDifferentRemindersStaysDistinct() = runBlocking {
        replay(listOf("first" to 1000L, "second" to 1000L), 2)
    }

    @Test fun differentRecurringOccurrencesStayDistinct() = runBlocking {
        replay(listOf("same" to 1000L, "same" to 2000L), 2)
    }
}
