package com.zero.v2.services.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class EvidenceSupportTest {
    private val source = EvidenceSupport.Source("m", "c", "仅周末可以。\n其他时间不行。", role = "user")
    private val anchor = EvidenceSupport.Anchor("m", "c", EvidenceSupport.digest(source.body))
    private val state = EvidenceSupport.State()

    @Test fun matchingSourceDoesNotDecideSemanticUse() {
        EvidenceSupport.Use.entries.forEach { use ->
            assertEquals(use, EvidenceSupport.assess(use, anchor, source, state,
                EvidenceSupport.QueryScope(100)).use)
        }
    }

    @Test fun editsWrongOwnerDeletedAndGeneratedSourcesCannotBeCertified() {
        listOf(null, source.copy(body = "周末可以。"), source.copy(messageId = "other"),
            source.copy(conversationId = "other"), source.copy(deleted = true),
            source.copy(role = "assistant"), source.copy(systemGenerated = true)).forEach { invalid ->
            listOf(EvidenceSupport.Use.CERTAIN, EvidenceSupport.Use.UNCERTAIN).forEach { use ->
                assertEquals(EvidenceSupport.Use.NONE,
            EvidenceSupport.assess(use, anchor, invalid, state,
                EvidenceSupport.QueryScope(100)).use)
            }
        }
        assertFalse(EvidenceSupport.verified(null, source))
    }

    @Test fun currentAndHistoricalScopesRemainDistinctWithoutDeletingHistory() {
        listOf(state.copy(active = false), state.copy(superseded = true), state.copy(validUntil = 100),
            state.copy(validFrom = 101)).forEach { old ->
            assertEquals(EvidenceSupport.Use.NONE,
            EvidenceSupport.assess(EvidenceSupport.Use.CERTAIN, anchor, source, old,
                EvidenceSupport.QueryScope(100)).use)
            assertEquals(EvidenceSupport.Use.CERTAIN,
            EvidenceSupport.assess(EvidenceSupport.Use.CERTAIN, anchor, source, old,
                EvidenceSupport.QueryScope(100, true)).use)
        }
        assertEquals(EvidenceSupport.Use.CERTAIN, EvidenceSupport.assess(EvidenceSupport.Use.CERTAIN,
            anchor, source, state.copy(validFrom = 100, validUntil = 101),
                EvidenceSupport.QueryScope(100)).use)
    }

    @Test fun malformedIntervalsDoNotBecomeHistoricalProof() {
        assertEquals(EvidenceSupport.Use.NONE, EvidenceSupport.assess(EvidenceSupport.Use.CERTAIN,
            anchor, source, state.copy(validFrom = 100, validUntil = 100),
                EvidenceSupport.QueryScope(100, true)).use)
    }

    @Test fun emptyButCorrectlyHashedSourceIsNotEvidence() {
        listOf("", " \n\t").forEach { body ->
            assertFalse(EvidenceSupport.verified(anchor.copy(bodyDigest = EvidenceSupport.digest(body)),
                source.copy(body = body)))
        }
    }

    @Test fun absentRoleMustNotDefaultToVerifiedUser() {
        assertFalse(EvidenceSupport.verified(anchor, EvidenceSupport.Source("m", "c", source.body)))
    }

    @Test fun conflictCanOnlyDowngradeAndCannotResurrectUnrelatedEvidence() {
        val conflict = state.copy(unresolvedConflict = true)
        assertEquals(EvidenceSupport.Use.UNCERTAIN,
            EvidenceSupport.assess(EvidenceSupport.Use.CERTAIN, anchor, source, conflict,
                EvidenceSupport.QueryScope(100)).use)
        assertEquals(EvidenceSupport.Use.NONE,
            EvidenceSupport.assess(EvidenceSupport.Use.NONE, anchor, source, conflict,
                EvidenceSupport.QueryScope(100)).use)
        assertEquals(EvidenceSupport.Use.NONE,
            EvidenceSupport.assess(EvidenceSupport.Use.CERTAIN, anchor, source.copy(deleted = true),
                conflict,
                EvidenceSupport.QueryScope(100)).use)
    }

    @Test fun decoderPreservesThreeUsesAndAbstention() {
        assertEquals(EvidenceSupport.Selection(listOf(0, 7), listOf(2)),
            EvidenceSupport.decode("""{"certain":[0,7],"uncertain":[2]}""", 8))
        assertEquals(EvidenceSupport.Selection(emptyList(), emptyList()),
            EvidenceSupport.decode("""{"certain":[],"uncertain":[]}""", 8))
        assertEquals(EvidenceSupport.Selection(listOf(1), emptyList()),
            EvidenceSupport.decode("```json\n{\"certain\":[1],\"uncertain\":[]}\n```", 8))
    }

    @Test fun invalidProtocolIsFailureInsteadOfEmptyEvidence() {
        listOf("{}", "[]", "null", """{"certain":[],"uncertain":[],"verified":true}""",
            """{"certain":[0],"uncertain":[0]}""", """{"certain":[0,0],"uncertain":[]}""",
            """{"certain":[8],"uncertain":[]}""", """{"certain":[0.5],"uncertain":[]}""",
            """{"certain":[true],"uncertain":[]}""", """{"certain":["0"],"uncertain":[]}""",
            """{"certain":[],"uncertain":[]} trailing""", "x".repeat(2049)).forEach {
            assertNull(it, EvidenceSupport.decode(it, 8))
        }
    }
}
