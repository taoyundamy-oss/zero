package com.zero.v2.services.retrieval

/** Metadata contract, not a semantic classifier. UNKNOWN must not be promoted to verified evidence. */
object EvidenceEligibility {
    enum class Verdict { ELIGIBLE, HISTORICAL_ONLY, UNVERIFIED, EXCLUDED }
    data class Source(
        val deleted: Boolean = false, val active: Boolean = true,
        val validFrom: Long? = null, val validUntil: Long? = null,
        val superseded: Boolean = false, val sourceVerified: Boolean = false,
        val userConfirmed: Boolean = false, val quotedOrHypothetical: Boolean = false,
        val unresolvedConflict: Boolean = false,
    )
    data class Decision(val verdict: Verdict, val reason: String)

    fun assess(source: Source, now: Long): Decision = when {
        source.deleted -> Decision(Verdict.EXCLUDED, "deleted")
        source.quotedOrHypothetical -> Decision(Verdict.EXCLUDED, "not_user_assertion")
        source.validFrom != null && source.validUntil != null && source.validFrom >= source.validUntil ->
            Decision(Verdict.UNVERIFIED, "invalid_interval")
        source.unresolvedConflict -> Decision(Verdict.UNVERIFIED, "unresolved_conflict")
        retired(source, now) ->
            Decision(Verdict.HISTORICAL_ONLY, "retired")
        source.validFrom?.let { now < it } == true -> Decision(Verdict.UNVERIFIED, "not_yet_valid")
        !source.sourceVerified && !source.userConfirmed -> Decision(Verdict.UNVERIFIED, "source_unknown")
        else -> Decision(Verdict.ELIGIBLE, if (source.userConfirmed) "user_confirmation" else "verified_source")
    }
    private fun retired(source: Source, now: Long) =
        !source.active || source.superseded || (source.validUntil?.let { now >= it } == true)
}
