package com.zero.v2.core.data.di

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GraphOutboxTransactionTest {
    @Test fun forgetRemovesOwnedOrphansButPreservesLegacyAndSharedEntities() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        try {
            val one = source("user-fact:one")
            val two = source("user-fact:two")
            db.memoryDao().insertAll(listOf(one, two))
            val dao = db.userGraphDao()
            dao.upsertEntity(UserGraphEntity("legacy", "历史实体", EntityType.CONCEPT))
            dao.enqueueGraphSources()
            val jobs = dao.pendingGraphJobs(100)
            val entities = listOf("owned", "shared", "legacy").map { UserGraphEntity(it, "模型名称", EntityType.CONCEPT) }
            val attrs = listOf("owned", "shared", "legacy").map {
                UserGraphAttribute(it, "preference", one.content, AttributeSource.EXTRACTED, 0.4f, 100,
                    evidence = GraphMemoryEvidence.reference(one))
            } + UserGraphAttribute("shared", "other", two.content, AttributeSource.EXTRACTED, 0.4f, 100,
                evidence = GraphMemoryEvidence.reference(two))
            assertTrue(dao.commitGraphBatch(jobs, jobs.map { if (it.sourceMessageId == one.id) one else two }, entities, attrs, emptyList(), 100))
            assertEquals("历史实体", dao.getEntity("legacy")!!.name)
            db.memoryExtractionJobDao().forgetById(one.id, 200)
            assertNull(dao.getEntity("owned"))
            assertNotNull(dao.getEntity("shared"))
            assertNotNull(dao.getEntity("legacy"))
            db.memoryExtractionJobDao().forgetById(two.id, 300)
            assertNull(dao.getEntity("shared"))
            assertNotNull(dao.getEntity("legacy"))
        } finally { db.close() }
    }
    @Test fun retractedRelationshipCannotBeRevivedByOlderSource() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        try {
            val old = source("user-fact:old")
            val newer = source("user-fact:new").copy(content = "他不再是我的同事", createdAt = 2)
            db.memoryDao().insertAll(listOf(old, newer))
            val dao = db.userGraphDao()
            val positive = UserGraphRelationship("user", "person", "colleague", 100, evidence = GraphMemoryEvidence.reference(old))
            assertTrue(dao.replaceGroundedRelationship(positive, old))
            val negative = positive.copy(effectiveFrom = 200, effectiveTo = 200, evidence = GraphMemoryEvidence.reference(newer))
            assertTrue(dao.replaceGroundedRelationship(negative, newer))
            assertTrue(dao.replaceGroundedRelationship(positive.copy(effectiveFrom = 300), old))
            assertTrue(dao.getCurrentRelationships("user").isEmpty())
        } finally { db.close() }
    }
    @Test fun lateOldBatchCannotOverwriteNewerCorrection() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        try {
            val old = source("user-fact:old")
            val newer = source("user-fact:new").copy(content = "更正：我只喝热水", createdAt = 2)
            db.memoryDao().insertAll(listOf(old, newer))
            val dao = db.userGraphDao()
            dao.enqueueGraphSources()
            val jobs = dao.pendingGraphJobs(100).associateBy { it.sourceMessageId }
            suspend fun commit(source: MemoryEntity, now: Long) = dao.commitGraphBatch(
                listOf(jobs.getValue(source.id)), listOf(source), emptyList(),
                listOf(UserGraphAttribute("user", "drink", source.content, AttributeSource.EXTRACTED, 0.4f, now,
                    evidence = GraphMemoryEvidence.reference(source))), emptyList(), now)
            assertTrue(commit(newer, 100))
            assertTrue(commit(old, 200))
            assertEquals(newer.content, dao.getCurrentAttributes("user").single().value)
        } finally { db.close() }
    }

    @Test fun crossProcessStage() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("graphPhase")
        org.junit.Assume.assumeTrue("Run separately with graphPhase", phase != null)
        val name = "acceptance-graph-process.db"
        if (phase == "seed") context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, ZeroDatabase::class.java, name).build()
        try {
            val dao = db.userGraphDao()
            when (phase) {
                "seed" -> {
                    repeat(51) { db.memoryDao().insert(source("user-fact:$it")) }
                    dao.enqueueGraphSources()
                    assertEquals(50, dao.pendingGraphJobs(1000).size)
                }
                "complete" -> {
                    val jobs = dao.pendingGraphJobs(1000)
                    assertEquals(50, jobs.size)
                    assertTrue(dao.commitGraphBatch(jobs, jobs.map { db.memoryDao().getById(it.sourceMessageId!!)!! },
                        emptyList(), emptyList(), emptyList(), 1000))
                }
                "verify" -> {
                    dao.enqueueGraphSources()
                    val remaining = dao.pendingGraphJobs(1001)
                    assertEquals(1, remaining.size)
                    assertTrue(dao.commitGraphBatch(remaining, remaining.map { db.memoryDao().getById(it.sourceMessageId!!)!! },
                        emptyList(), emptyList(), emptyList(), 1001))
                    dao.enqueueGraphSources()
                    assertTrue(dao.pendingGraphJobs(Long.MAX_VALUE).isEmpty())
                }
                else -> error("Unknown phase")
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("graph_phase", phase); putInt("graph_pid", android.os.Process.myPid())
            })
        } finally { db.close(); if (phase == "verify") context.deleteDatabase(name) }
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun source(id: String = "user-fact:one") = MemoryEntity(id, "我喜欢示例食物A", type = MemoryType.FACT,
        sourceConversationId = "chat", createdAt = 1)

    @Test fun reopenResumesAll51SourcesAndDoesNotReplayCompletedWork() = runBlocking {
        val name = "acceptance-graph-outbox.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, ZeroDatabase::class.java, name).build()
        try {
            repeat(51) { db.memoryDao().insert(source("user-fact:$it")) }
            db.userGraphDao().enqueueGraphSources()
            db.close()
            db = Room.databaseBuilder(context, ZeroDatabase::class.java, name).build()
            val dao = db.userGraphDao()
            val jobs = dao.pendingGraphJobs(1000)
            assertEquals(50, jobs.size)
            val sources = jobs.map { db.memoryDao().getById(it.sourceMessageId!!)!! }
            assertTrue(dao.commitGraphBatch(jobs, sources, emptyList(), emptyList(), emptyList(), 1000))
            assertFalse(dao.commitGraphBatch(jobs, sources, emptyList(), emptyList(), emptyList(), 1000))
            assertEquals(0, db.memoryExtractionJobDao().purgeCompleted(Long.MAX_VALUE))
            dao.enqueueGraphSources()
            assertEquals(1, dao.pendingGraphJobs(1001).size)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun batchFailureRollsBackEntitiesAttributesRelationshipsAndCompletion() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        try {
            val source = source()
            db.memoryDao().insert(source)
            val dao = db.userGraphDao()
            dao.enqueueGraphSources()
            val jobs = dao.pendingGraphJobs(100)
            val reference = GraphMemoryEvidence.reference(source)
            val entities = listOf(UserGraphEntity("fixture", "fixture", EntityType.CONCEPT, 1))
            val attrs = listOf(UserGraphAttribute("fixture", "preference", source.content, AttributeSource.EXTRACTED,
                0.4f, 2, evidence = reference))
            val relations = listOf(UserGraphRelationship("fixture", "food", "likes", 2, evidence = reference))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_graph_batch BEFORE INSERT ON " +
                "user_graph_relationships BEGIN SELECT RAISE(ABORT, 'fixture_failure'); END")
            assertTrue(runCatching { dao.commitGraphBatch(jobs, listOf(source), entities, attrs, relations, 100) }.isFailure)
            assertNull(dao.getEntity("fixture"))
            assertTrue(dao.getAttributes("fixture").isEmpty())
            assertEquals("PENDING", dao.graphJob(jobs.single().id)!!.state)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_graph_batch")
            assertTrue(dao.commitGraphBatch(jobs, listOf(source), entities, attrs, relations, 100))
            assertEquals("SUCCEEDED", dao.graphJob(jobs.single().id)!!.state)
        } finally { db.close() }
    }

    @Test fun deletedSourceCannotCommitDelayedBatchAndRetryIsBounded() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        try {
            val source = source()
            db.memoryDao().insert(source)
            val dao = db.userGraphDao()
            dao.enqueueGraphSources()
            val jobs = dao.pendingGraphJobs(100)
            val job = jobs.single()
            repeat(5) { db.memoryExtractionJobDao().markFailed(job.id, "fixture", 5, 1000, 100) }
            assertEquals("FAILED", dao.graphJob(job.id)!!.state)
            dao.enqueueGraphSources()
            assertTrue(dao.pendingGraphJobs(Long.MAX_VALUE).isEmpty())
            db.memoryExtractionJobDao().forgetById(source.id, 101)
            assertFalse(dao.commitGraphBatch(jobs, listOf(source), emptyList(), emptyList(), emptyList(), 102))
            assertNull(dao.graphJob(job.id))
        } finally { db.close() }
    }
}
