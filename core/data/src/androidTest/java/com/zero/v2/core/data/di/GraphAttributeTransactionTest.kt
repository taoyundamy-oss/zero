package com.zero.v2.core.data.di

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.AttributeSource
import com.zero.v2.core.data.entity.UserGraphAttribute
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GraphAttributeTransactionTest {
    @Test fun failedReplacementKeepsPreviousCurrentAttribute() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, ZeroDatabase::class.java).build()
        try {
            val dao = db.userGraphDao()
            val old = UserGraphAttribute("user", "location", "old", AttributeSource.USER_STATED, 0.9f, 1)
            dao.insertAttribute(old)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_graph_insert BEFORE INSERT ON " +
                "user_graph_attributes WHEN NEW.value = 'rejected' BEGIN SELECT RAISE(ABORT, 'fixture_failure'); END")
            val failed = runCatching {
                dao.replaceCurrentAttribute(old.copy(value = "rejected", effectiveFrom = 2))
            }
            assertTrue(failed.isFailure)
            assertEquals(listOf(old), dao.getCurrentAttributes("user"))
            assertEquals(listOf(old), dao.getAttributes("user"))
            val replacement = old.copy(value = "new", effectiveFrom = 3)
            dao.replaceCurrentAttribute(replacement)
            assertEquals(listOf(replacement), dao.getCurrentAttributes("user"))
            assertEquals(listOf(replacement, old.copy(effectiveTo = 3)), dao.getAttributes("user"))
        } finally { db.close() }
    }
}
