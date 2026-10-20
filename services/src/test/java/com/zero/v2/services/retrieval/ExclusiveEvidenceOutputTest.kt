package com.zero.v2.services.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ExclusiveEvidenceOutputTest {
    @Test fun supportsUnorderedCompleteSlotsAndExplicitAbstention() {
        assertEquals(EvidenceSupport.Selection(listOf(2), listOf(0)),
            ExclusiveEvidenceOutput.decode("""{"2":"certain","0":"uncertain","1":"none"}""", 3))
        assertEquals(EvidenceSupport.Selection(emptyList(), emptyList()),
            ExclusiveEvidenceOutput.decode("""{"0":"none"}""", 1))
    }

    @Test fun malformedMissingDuplicateAndInventedSlotsNeverBecomeAbstention() {
        listOf("{}", "[]", "null", """{"0":"none","0":"certain"}""",
            """{"0":"none"}""", """{"0":"none","2":"none"}""",
            """{"0":"none","01":"none"}""", """{"0":"maybe","1":"none"}""",
            """{"0":["certain","uncertain"],"1":"none"}""",
            """{0:"none","1":"none"}""", """{"0":"none","1":"none",}""",
            """{"0":"none","1":"none"} extra""", "x".repeat(2049)).forEach {
            assertNull(it, ExclusiveEvidenceOutput.decode(it, 2))
        }
        assertNull(ExclusiveEvidenceOutput.decode("{}", 0))
        assertNull(ExclusiveEvidenceOutput.decode("{}", 9))
    }

    @Test fun rejectsLenientJsonCommentsAndEmbeddedNul() {
        listOf("""{/*comment*/"0":"none"}""",
            """{"0":"none"}/*comment*/""",
            """{"0":"none"}""" + '\u0000',
            """{"0":"none"}""" + '\u0000' + "extra").forEach {
            assertNull(ExclusiveEvidenceOutput.decode(it, 1))
        }
    }

    @Test fun schemaRequiresEverySlotAndForbidsUnknownProperties() {
        val schema = ExclusiveEvidenceOutput.schema(8)
        assertEquals(8, schema.getJSONArray("required").length())
        assertEquals(8, schema.getJSONObject("properties").length())
        assertFalse(schema.getBoolean("additionalProperties"))
        assertEquals(3, schema.getJSONObject("properties").getJSONObject("7").getJSONArray("enum").length())
    }
}
