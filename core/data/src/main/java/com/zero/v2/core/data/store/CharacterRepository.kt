package com.zero.v2.core.data.store

import com.zero.v2.core.data.dao.CharacterDao
import com.zero.v2.core.data.entity.CharacterEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Shared write owner for settings and chat fallback; Room owns transaction rollback. */
@Singleton
class CharacterRepository @Inject constructor(private val dao: CharacterDao) {
    private val writes = Mutex()
    suspend fun activate(id: String) = writes.withLock { dao.activateExisting(id) }
    suspend fun ensureActive() = writes.withLock { dao.ensureActive() }
    suspend fun create(character: CharacterEntity) = writes.withLock { dao.insert(character) }
    suspend fun update(id: String, name: String, description: String?, prompt: String) = writes.withLock {
        dao.updateExisting(id, name, description, prompt, System.currentTimeMillis())
    }
    suspend fun delete(id: String) = writes.withLock { dao.deleteUserCharacter(id) }
    suspend fun insertPresetsIfEmpty(presets: List<CharacterEntity>) = writes.withLock { dao.insertPresetsIfEmpty(presets) }
}
