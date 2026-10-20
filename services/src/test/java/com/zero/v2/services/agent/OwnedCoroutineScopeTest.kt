package com.zero.v2.services.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedCoroutineScopeTest {
    @Test
    fun closeCancelsChildren() = runBlocking {
        val owner = OwnedCoroutineScope(Dispatchers.Default)
        val child = owner.scope.launch { delay(10_000) }
        owner.close()
        child.join()
        assertTrue(child.isCancelled)
    }
}
