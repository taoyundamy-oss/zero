package com.zero.v2.core.data.store

import androidx.room.withTransaction
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.ChronicleSourceEntity
import com.zero.v2.core.data.entity.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Record input before provider selection; independent of transcript success and memory extraction. */
@Singleton
class ChronicleInputStore @Inject constructor(private val db: ZeroDatabase) {
    private val mutex = Mutex()
    suspend fun capture(message: MessageEntity): Boolean = withContext(Dispatchers.IO) {
        val source = ChronicleSourceEntity.message(message, System.currentTimeMillis()) ?: return@withContext false
        mutex.withLock { db.withTransaction {
            val dao = db.chronicleSourceDao()
            if (dao.hasAnchor("message", message.id)) false else dao.retainSource(source)
        } }
    }
}
