package com.zero.v2.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.zero.v2.core.data.entity.UsageRequestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageRequestDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: UsageRequestEntity)

    @Query("SELECT * FROM usage_requests WHERE id = :id")
    suspend fun find(id: String): UsageRequestEntity?

    @Query("UPDATE usage_requests SET finishedAt=:at, status=:status, inputTokens=:input, " +
        "cachedTokens=:cached, outputTokens=:output WHERE id=:id AND status='prepared'")
    suspend fun finish(id: String, at: Long, status: String, input: Long?, cached: Long?, output: Long?)

    @Transaction
    suspend fun complete(id: String, at: Long, status: String, input: Long?, cached: Long?, output: Long?) {
        finish(id, at, status, input, cached, output)
    }

    @Query("SELECT * FROM usage_requests WHERE startedAt >= :start AND startedAt < :end ORDER BY startedAt, id LIMIT 500")
    fun observe(start: Long, end: Long): Flow<List<UsageRequestEntity>>

    @Query("SELECT CASE WHEN :daily THEN CAST(strftime('%s', date(startedAt / 1000, " +
        "'unixepoch', 'localtime'), 'utc') AS INTEGER) * 1000 " +
        "ELSE ((startedAt - :start) / 3600000) * 3600000 + :start END AS bucket, " +
        "COUNT(*) AS requests, COALESCE(SUM(inputTokens),0) AS input, " +
        "COALESCE(SUM(cachedTokens),0) AS cached, " +
        "COALESCE(SUM(CASE WHEN cachedTokens IS NOT NULL THEN inputTokens ELSE 0 END),0) AS observedInput, " +
        "COALESCE(SUM(outputTokens),0) AS output, COUNT(cachedTokens) AS knownCache, " +
        "SUM(CASE WHEN inputTokens IS NULL THEN 1 ELSE 0 END) AS unknownInput, " +
        "SUM(CASE WHEN status='prepared' THEN 1 ELSE 0 END) AS unfinished, " +
        "SUM(CASE WHEN status IN ('failed','cancelled') THEN 1 ELSE 0 END) AS failed, " +
        "SUM(CASE WHEN local=1 THEN 1 ELSE 0 END) AS localRequests " +
        "FROM usage_requests WHERE startedAt >= :start AND startedAt < :end " +
        "AND (:connection='' OR connectionId=:connection) AND (:model='' OR model=:model) " +
        "GROUP BY bucket ORDER BY bucket")
    fun buckets(start: Long, end: Long, connection: String, model: String, daily: Boolean = false): Flow<List<UsageBucket>>

    @Query("SELECT * FROM usage_requests WHERE startedAt >= :start AND startedAt < :end " +
        "AND (:connection='' OR connectionId=:connection) AND (:model='' OR model=:model) " +
        "ORDER BY startedAt DESC, id DESC LIMIT 500 OFFSET :offset")
    fun details(start: Long, end: Long, connection: String, model: String, offset: Int): Flow<List<UsageRequestEntity>>

    @Query("SELECT DISTINCT connectionId, model FROM usage_requests ORDER BY connectionId, model")
    fun routes(): Flow<List<UsageRoute>>
}
