package com.zero.v2.app.ui.settings

import com.zero.v2.core.common.AffinityBand
import com.zero.v2.framework.chat.CharacterStateSnapshot
import com.zero.v2.framework.chat.StateEventItem
import org.junit.Assert.*
import org.junit.Test

class CharacterStateUiStateTest {
    private val previous = CharacterStateUiState(
        activeCharacterId = "alice",
        affinityLabel = "亲近",
        interactionCount = 42,
        recentEvents = listOf(StateEventItem("AFFINITY", "previous", 1L)),
        loaded = true,
    )

    @Test
    fun failedReadClearsPreviousRoleEvidenceAndShowsError() {
        val result = previous.withSnapshot("alice", null)
        assertNotNull(result.error)
        assertTrue(result.loaded)
        assertEquals(0, result.interactionCount)
        assertTrue(result.recentEvents.isEmpty())
        assertEquals(0L, result.moodExpiresAt)
        assertEquals(0L, result.lastAffinityChangeAt)
    }

    @Test
    fun resetCompletingAfterRoleSwitchCannotReplaceCurrentRole() {
        val switched = previous.copy(activeCharacterId = "bob")
        val reset = CharacterStateSnapshot(AffinityBand.DEFAULT, 0, 0, emptyList())
        assertSame(switched, switched.withSnapshot("alice", reset))
        assertSame(switched, switched.withSnapshot("alice", null))
    }

    @Test
    fun successfulRefreshAfterErrorRestoresSnapshot() {
        val failed = previous.withSnapshot("alice", null)
        val snapshot = CharacterStateSnapshot(AffinityBand.WARM, 7, 2, previous.recentEvents)
        val result = failed.withSnapshot("alice", snapshot)
        assertNull(result.error)
        assertEquals("熟络", result.affinityLabel)
        assertEquals(7, result.interactionCount)
        assertEquals(snapshot.recentEvents, result.recentEvents)
    }
}
