package com.zero.v2.core.data.store

import androidx.room.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.ModuleConfigEntity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Dao
interface ModuleConfigDao {
    @Query("SELECT * FROM module_configs WHERE module_id = :moduleId LIMIT 1")
    suspend fun getByModuleId(moduleId: String): ModuleConfigEntity?

    @Query("SELECT * FROM module_configs")
    fun observeAll(): Flow<List<ModuleConfigEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ModuleConfigEntity)

    @Query("DELETE FROM module_configs WHERE module_id = :moduleId")
    suspend fun delete(moduleId: String)
}

/**
 * 模块配置持久化存储 — Zero v2.3。
 *
 * 非敏感配置（模型选择、temperature、maxTokens 等）存储在 Room module_configs 表。
 * API Key 通过 SecureKeyStore 独立加密存储。
 *
 * 防抖写入：updateConfigDebounced() 使用 Coroutine 管道合并 300ms 内的频繁变更。
 */
@Singleton
class ModuleConfigStore constructor(
    private val dao: ModuleConfigDao,
    private val scope: CoroutineScope,
) {
    @Inject constructor(dao: ModuleConfigDao) : this(dao, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private data class Pending(val json: String, val revision: Long)
    private val monitor = Any()
    private val pending = mutableMapOf<String, Pending>()
    private val revisions = mutableMapOf<String, Long>()
    private val writeMutex = Mutex()
    // Only wakeups are conflated. Values are retained independently for every module.
    private val flushChannel = Channel<Unit>(Channel.CONFLATED)

    private fun enqueue(id: String, json: String): Pending = synchronized(monitor) {
        val entry = Pending(json, (revisions[id] ?: 0) + 1)
        revisions[id] = entry.revision
        pending[id] = entry
        entry
    }

    private suspend fun persist(id: String, entry: Pending) = writeMutex.withLock {
        if (synchronized(monitor) { revisions[id] != entry.revision }) return@withLock
        dao.upsert(ModuleConfigEntity(moduleId = id, configJson = entry.json))
        synchronized(monitor) {
            if (pending[id] == entry) pending.remove(id)
        }
    }

    init {
        scope.launch {
            for (signal in flushChannel) {
                delay(300) // 防抖窗口 300ms
                val batch = synchronized(monitor) { pending.toMap() }
                for ((id, entry) in batch) {
                    try { persist(id, entry) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Retain failed values for the next flush. */ }
                }
                if (synchronized(monitor) { pending.isNotEmpty() }) flushChannel.trySend(Unit)
            }
        }
    }

    suspend fun get(moduleId: String): String? = withContext(Dispatchers.IO) {
        dao.getByModuleId(moduleId)?.configJson
    }

    /** 防抖写入：300ms 内多次调用只触发一次 Room 写入 */
    fun updateConfigDebounced(moduleId: String, configJson: String) {
        enqueue(moduleId, configJson)
        flushChannel.trySend(Unit)
    }

    /** 同步立即写入（手动保存时调用） */
    suspend fun saveNow(moduleId: String, configJson: String) {
        val entry = enqueue(moduleId, configJson)
        try { persist(moduleId, entry) }
        finally { flushChannel.trySend(Unit) }
    }

    /**
     * Confirmed manual change: publish runtime state only after this write returns successfully.
     * A rejected candidate is never queued. Existing accepted pending values and concurrent newer
     * requests keep their normal retry contract; this does not change saveNow/debounced semantics.
     */
    suspend fun saveConfirmed(moduleId: String, configJson: String) = writeMutex.withLock {
        val (previous, reserved) = synchronized(monitor) {
            val old = revisions[moduleId] ?: 0L
            revisions[moduleId] = old + 1
            old to old + 1
        }
        var committed = false
        try {
            dao.upsert(ModuleConfigEntity(moduleId = moduleId, configJson = configJson))
            committed = true
        } finally {
            synchronized(monitor) {
                if (!committed) {
                    if (revisions[moduleId] == reserved) revisions[moduleId] = previous
                } else if (pending[moduleId]?.revision?.let { it <= reserved } == true) {
                    pending.remove(moduleId)
                }
            }
        }
    }
}

/** 供 Hilt 提供 ModuleConfigDao */
@Module
@InstallIn(SingletonComponent::class)
object ModuleConfigDaoModule {
    @Provides fun provideModuleConfigDao(db: ZeroDatabase): ModuleConfigDao = db.moduleConfigDao()
}
