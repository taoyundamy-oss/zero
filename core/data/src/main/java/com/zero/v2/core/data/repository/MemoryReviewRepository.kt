package com.zero.v2.core.data.repository

import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.dao.MemoryReviewDao
import com.zero.v2.core.data.entity.MemoryEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryReviewRepository @Inject constructor(private val dao: MemoryReviewDao) {
    private val mutex = Mutex()
    fun observe() = dao.observe()
    suspend fun propose(memory: MemoryEntity, content: String, type: MemoryType, scope: String, reason: String, times: com.zero.v2.core.data.entity.MemoryReviewTimes? = null) =
        mutex.withLock { dao.propose(memory, content.trim(), type, scope.trim(), reason.trim(), System.currentTimeMillis(), times) }
    suspend fun decide(id: String, accept: Boolean) = mutex.withLock {
        dao.decide(id, accept, System.currentTimeMillis())
    }
    suspend fun source(id: String) = dao.message(id)
}
