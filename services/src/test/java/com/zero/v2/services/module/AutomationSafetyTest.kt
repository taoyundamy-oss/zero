package com.zero.v2.services.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

class AutomationSafetyTest {
    @Test fun malformedIdentifiersAndDependenciesCannotLoseOrdering() {
        for (step in listOf(
            """{"index":1.5}""", """{"index":4294967297}""", """{"index":"bad"}""",
            """{"index":null}""", """{"index":2,"dependsOn":"1"}""",
            """{"index":2,"dependsOn":null}""", """{"index":2,"dependsOn":[1.5]}""",
            """{"index":2,"dependsOn":[4294967297]}""",
        )) {
            assertNull(step, ExecutionPlan.fromJson("""{"steps":[{"index":1},$step]}"""))
        }
        assertNotNull(ExecutionPlan.fromJson("""{"steps":[{}, {"dependsOn":[1]}]}"""))
    }

    @Test fun overlapIsRejectedOtherTasksProceedAndCancelReleasesClaim() = runBlocking {
        val claims = AutomationClaims()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val first = async { claims.run("same") { started.complete(Unit); gate.await(); "first" } }
        started.await()
        assertNull(claims.run("same") { error("duplicate side effect") })
        assertEquals("other", claims.run("other") { "other" })
        first.cancelAndJoin()
        assertEquals("recovered", claims.run("same") { "recovered" })
        try { claims.run("same") { error("failed") } } catch (_: IllegalStateException) { }
        assertEquals("after failure", claims.run("same") { "after failure" })
    }

    @Test fun invalidGraphsAreRejectedBeforeAnyExecution() {
        for (steps in listOf(
            "{\"index\":1,\"dependsOn\":[2]}",
            "{\"index\":1,\"dependsOn\":[1]}",
            "{\"index\":1},{\"index\":1}",
            "{\"index\":1,\"dependsOn\":[2]},{\"index\":2,\"dependsOn\":[1]}",
            "{\"index\":0}", "{\"index\":1},null",
        )) {
            assertNull(steps, ExecutionPlan.fromJson("{\"goal\":\"test\",\"steps\":[$steps]}"))
        }
        assertNotNull(ExecutionPlan.fromJson("{\"steps\":[{\"index\":1},{\"index\":2,\"dependsOn\":[1]}]}"))
    }
}
