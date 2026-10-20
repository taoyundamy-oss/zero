package com.zero.v2.framework.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnGateTest {
    @Test fun `another source cannot append a user until the active tool stream completes`() = runBlocking {
        val gate = TurnGate()
        val release = CompletableDeferred<Unit>()
        var prepared = 0
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.stream { prepared++; flow { release.await(); emit("tool result") } }.collect()
        }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.stream { prepared++; flow { emit("next turn") } }.collect()
        }
        try { assertEquals(1, prepared) } finally { release.complete(Unit); first.join(); second.join() }
        assertEquals(2, prepared)
    }

    @Test fun `cancelled stream releases ownership and uncollected flow does not acquire it`() = runBlocking {
        val gate = TurnGate()
        var prepared = 0
        gate.stream { prepared++; flow { emit("never collected") } }
        assertEquals(0, prepared)
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.stream { flow<String> { CompletableDeferred<Unit>().await() } }.collect()
        }
        first.cancelAndJoin()
        gate.stream { prepared++; flow { emit("next") } }.collect()
        assertEquals(1, prepared)
    }
}
