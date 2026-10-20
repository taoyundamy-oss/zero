package com.zero.v2.app.knowledge

import com.zero.v2.app.data.WorkDirRepository

/** Read boundary for indexing; the user-selected SAF directory remains the source of truth. */
internal interface KnowledgeSource {
    fun identity(): String
    fun hasDirectory(): Boolean
    suspend fun createFolder(path: String): String?
    suspend fun listTree(): WorkDirRepository.TreeListing
    suspend fun readText(path: String): String?
}

internal class WorkDirectoryKnowledgeSource(private val repository: WorkDirRepository) : KnowledgeSource {
    override fun identity() = repository.currentDirUri()?.toString().orEmpty()
    override fun hasDirectory() = repository.hasDirectory()
    override suspend fun createFolder(path: String) = repository.createFolder(path)
    override suspend fun listTree() = repository.listTreeSnapshot()
    override suspend fun readText(path: String) = repository.readText(path)
}
