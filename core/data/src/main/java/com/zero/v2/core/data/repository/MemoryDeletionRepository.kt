package com.zero.v2.core.data.repository

import com.zero.v2.core.data.dao.MemoryExtractionJobDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** UI single-record deletion owns its write serialization; the DAO owns the atomic boundary. */
@Singleton
class MemoryDeletionRepository @Inject constructor(private val jobs: MemoryExtractionJobDao) {
    private val mutex = Mutex()

    suspend fun deleteById(id: String): Int = mutex.withLock {
        withContext(Dispatchers.IO) { jobs.forgetById(id, System.currentTimeMillis()) }
    }
}
