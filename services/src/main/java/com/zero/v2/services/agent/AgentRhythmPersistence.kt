package com.zero.v2.services.agent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

internal data class AgentRhythmSnapshot(
    val savedAt: Long,
    val remainingMs: Long,
    val count: Int,
    val activity: Boolean,
    val lastUser: Long,
    val lastEvent: Long,
    val observation: Long,
    val peak: Long,
    val deferring: Boolean,
)

/** Device-local scheduling state, separate from user settings and conversation prompts. */
@Singleton
class AgentRhythmPersistence internal constructor(
    private val read: () -> String?,
    private val write: (String) -> Boolean,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        { context.getSharedPreferences("zero_agent_rhythm", Context.MODE_PRIVATE).getString("snapshot", null) },
        { context.getSharedPreferences("zero_agent_rhythm", Context.MODE_PRIVATE)
            .edit().putString("snapshot", it).commit() },
    )

    internal var restoreStatus = "missing"
        private set

    @Synchronized internal fun load(): AgentRhythmSnapshot? {
        val raw = read() ?: return null.also { restoreStatus = "missing" }
        return try {
            val j = JSONObject(raw)
            require(j.getInt("version") == 1)
            val value = AgentRhythmSnapshot(j.getLong("savedAt"), j.getLong("remainingMs"), j.getInt("count"),
                j.getBoolean("activity"), j.getLong("lastUser"), j.getLong("lastEvent"),
                j.getLong("observation"), j.getLong("peak"), j.getBoolean("deferring"))
            require(value.savedAt > 0 && value.remainingMs in 0..MAX_REMAINING_MS && value.count in 0..MAX_TIER)
            require(listOf(value.lastUser, value.lastEvent, value.observation, value.peak).all { it >= 0 })
            restoreStatus = "restored"
            value
        } catch (_: Exception) {
            restoreStatus = "invalid"
            null
        }
    }

    @Synchronized internal fun save(s: AgentRhythmSnapshot): Boolean = write(JSONObject()
        .put("version", 1).put("savedAt", s.savedAt).put("remainingMs", s.remainingMs.coerceIn(0, MAX_REMAINING_MS))
        .put("count", s.count).put("activity", s.activity).put("lastUser", s.lastUser)
        .put("lastEvent", s.lastEvent).put("observation", s.observation).put("peak", s.peak)
        .put("deferring", s.deferring).toString())

    internal companion object {
        const val MAX_REMAINING_MS = 24 * 3600_000L
        const val MAX_TIER = 32
    }
}
