package com.zero.v2.services.retrieval

import org.junit.Assert.assertEquals
import org.junit.Test

class EvidenceEligibilityTest {
    @Test fun frozenMetadataCasesDoNotConflateRelevanceWithEvidence() {
        val verified = EvidenceEligibility.Source(sourceVerified = true)
        val cases = listOf(
            verified to "verified_source",
            verified.copy(deleted = true, userConfirmed = true) to "deleted",
            verified.copy(quotedOrHypothetical = true) to "not_user_assertion",
            verified.copy(active = false) to "retired",
            verified.copy(superseded = true) to "retired",
            verified.copy(validUntil = 100) to "retired",
            verified.copy(validUntil = 101) to "verified_source",
            verified.copy(validFrom = 101) to "not_yet_valid",
            verified.copy(validFrom = 100) to "verified_source",
            verified.copy(validFrom = 200, validUntil = 100) to "invalid_interval",
            verified.copy(unresolvedConflict = true) to "unresolved_conflict",
            EvidenceEligibility.Source() to "source_unknown",
            EvidenceEligibility.Source(userConfirmed = true) to "user_confirmation",
        )
        cases.forEach { (source, reason) -> assertEquals(reason, EvidenceEligibility.assess(source, 100).reason) }
    }

    @Test fun unknownAndHistoricalAreNotDeletedOrCertified() {
        assertEquals(EvidenceEligibility.Verdict.UNVERIFIED,
            EvidenceEligibility.assess(EvidenceEligibility.Source(), 100).verdict)
        assertEquals(EvidenceEligibility.Verdict.HISTORICAL_ONLY,
            EvidenceEligibility.assess(EvidenceEligibility.Source(active = false), 100).verdict)
    }
}
