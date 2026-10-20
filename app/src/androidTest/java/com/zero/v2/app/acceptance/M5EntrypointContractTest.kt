package com.zero.v2.app.acceptance

import org.junit.Assert.assertEquals
import org.junit.Test

/** Validate JUnit discovery without constructing the cloud test or touching any model. */
class M5EntrypointContractTest {
    @Test fun cloudEntryHasJUnitVoidSignature() {
        assertEquals(Void.TYPE, M5MainModelAcceptanceTest::class.java.getMethod("boundedMainModelMemoryLoop").returnType)
    }
}
