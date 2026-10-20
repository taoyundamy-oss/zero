package com.zero.v2.services.retrieval

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays frozen synthetic model outputs through the actual Kotlin contract.
 * This checks evaluator parity, NOT whether the model meets the separate semantic quality gate.
 */
class EvidenceSupportReplayTest {
    @Test fun frozenEightCandidateOutputsMatchClientContract() {
        val input = checkNotNull(javaClass.getResourceAsStream("/retrieval/support8-replay.json"))
        val data = input.use { JSONObject(it.reader(Charsets.UTF_8).readText()) }
        val cases = data.getJSONArray("cases")
        val byId = (0 until cases.length()).map { cases.getJSONObject(it) }
            .associateBy { it.getString("id") + ":" + it.getString("language") }
        val samples = data.getJSONArray("samples")
        assertEquals(45, byId.size)
        assertEquals(90, samples.length())
        val identities = mutableSetOf<String>()
        for (i in 0 until samples.length()) {
            val sample = samples.getJSONObject(i)
            val key = sample.getString("id") + ":" + sample.getString("language")
            assertTrue(identities.add(key + ":" + sample.getString("strategy")))
            val case = checkNotNull(byId[key])
            assertEquals(8, case.getJSONArray("candidates").length())
            replay(case, sample)
        }
    }

    private fun replay(case: JSONObject, sample: JSONObject) {
        val decoded = EvidenceSupport.decode(sample.getString("raw"), 8)
        if (!sample.getBoolean("valid")) {
            assertNull(decoded)
            return
        }
        val selection = checkNotNull(decoded)
        val candidates = case.getJSONArray("candidates")
        for (slot in 0 until 8) {
            val use = when (slot) {
                in selection.certain -> EvidenceSupport.Use.CERTAIN
                in selection.uncertain -> EvidenceSupport.Use.UNCERTAIN
                else -> EvidenceSupport.Use.NONE
            }
            assertEquals(sample.getJSONObject("selected").getString(slot.toString()), use.name)
            val candidate = candidates.getJSONObject(slot)
            val a = candidate.getJSONObject("anchor")
            val s = candidate.getJSONObject("source")
            val anchor = EvidenceSupport.Anchor(a.getString("messageId"), a.getString("conversationId"),
                a.getString("bodyDigest"))
            val source = EvidenceSupport.Source(s.getString("messageId"), s.getString("conversationId"),
                s.getString("body"), s.getString("role"), s.getBoolean("systemGenerated"), s.getBoolean("deleted"))
            val actual = EvidenceSupport.assess(use, anchor, source, state(candidate.getJSONObject("state")),
                EvidenceSupport.QueryScope(case.getLong("now"), case.getBoolean("history")))
            assertEquals(sample.getJSONObject("effective").getString(slot.toString()), actual.use.name)
        }
    }

    private fun state(s: JSONObject) = EvidenceSupport.State(
        active = s.optBoolean("active", true), superseded = s.optBoolean("superseded", false),
        validFrom = if (s.has("validFrom")) s.getLong("validFrom") else null,
        validUntil = if (s.has("validUntil")) s.getLong("validUntil") else null,
        unresolvedConflict = s.optBoolean("unresolvedConflict", false),
    )
}
