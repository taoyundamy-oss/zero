package com.zero.v2.core.data.di

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.UsageRequestEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UsageLedgerDatabaseTest {
    @Test fun fullRangeAggregateSurvivesReopenAndDoesNotInheritDetailLimit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "usage-isolated-${java.util.UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, ZeroDatabase::class.java, name).build()
        try {
            val dao = db.usageRequestDao()
            repeat(601) { i ->
                dao.insert(UsageRequestEntity("r$i", 1000L + i, connectionId = "c", profileId = "p",
                    model = "m", kind = "stream"))
                dao.complete("r$i", 2000, "completed", 100, 90, 10)
                dao.complete("r$i", 3000, "failed", null, null, null)
            }
            dao.insert(UsageRequestEntity("unknown", 1800, connectionId = "c", profileId = "p", model = "m", kind = "sync"))
            dao.insert(UsageRequestEntity("end-exclusive", 2000, connectionId = "c", profileId = "p", model = "m", kind = "sync"))
            dao.insert(UsageRequestEntity("other-model", 1800, connectionId = "c", profileId = "p", model = "other", kind = "sync"))
            db.close()
            db = Room.databaseBuilder(context, ZeroDatabase::class.java, name).build()
            val restored = db.usageRequestDao()
            val total = restored.buckets(1000, 2000, "c", "m").first().single()
            assertEquals(602L, total.requests)
            assertEquals(60100L, total.input)
            assertEquals(54090L, total.cached)
            assertEquals(1L, total.unfinished)
            assertEquals(1L, total.unknownInput)
            assertEquals(500, restored.details(1000, 2000, "c", "m", 0).first().size)
            assertEquals(102, restored.details(1000, 2000, "c", "m", 500).first().size)
            assertEquals(0, restored.buckets(1000, 2000, "other", "m").first().size)
            assertEquals("completed", restored.find("r0")!!.status)
            assertNull(restored.find("unknown")!!.inputTokens)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
