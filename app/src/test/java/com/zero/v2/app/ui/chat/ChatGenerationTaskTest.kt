package com.zero.v2.app.ui.chat

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ChatGenerationTaskTest {
    @Test fun `unchanged refresh and unrelated deletion do not cancel a reply`() = runBlocking {
        val task = ChatGenerationTask(this)
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        task.start { try { started.complete(Unit); awaitCancellation() } finally { cancelled = true } }
        started.await()
        task.transition(shouldTransition = { false }) { error("unchanged refresh") }.join()
        var deleted = false
        task.transition(cancelGeneration = { false }) { deleted = true }.join()
        assertTrue(deleted)
        assertFalse(cancelled)
        task.stop()
        task.transition {}.join()
        assertTrue(cancelled)
    }

    @Test fun `history changes wait for cancelled persistence and remain ordered`() = runBlocking {
        val task = ChatGenerationTask(this)
        val started = CompletableDeferred<Unit>()
        val saving = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        task.start {
            try { started.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { saving.complete(Unit); saved.await(); events += "saved old" } }
        }
        started.await()
        val first = task.transition { events += "load next" }
        val second = task.transition { events += "delete next" }
        saving.await()
        assertTrue(task.isTransitioning.value)
        assertTrue(events.isEmpty())
        assertFalse(task.start { error("must not start while switching") })
        assertFalse(task.userStopped)
        saved.complete(Unit)
        first.join(); second.join()
        assertEquals(listOf("saved old", "load next", "delete next"), events)
        assertFalse(task.isTransitioning.value)
        val next = CompletableDeferred<Unit>()
        assertTrue(task.start { next.complete(Unit) }); next.await()
    }

    @Test fun `cancelled queued transition releases reservation without changing history`() = runBlocking {
        val task = ChatGenerationTask(this)
        val released = CompletableDeferred<Unit>()
        val first = task.transition { released.await() }
        val queued = task.transition { error("cancelled transition must not execute") }
        queued.cancelAndJoin()
        assertTrue(task.isTransitioning.value)
        assertFalse(task.start {})
        released.complete(Unit); first.join()
        assertFalse(task.isTransitioning.value)
        assertTrue(task.start {})
    }

    @Test fun `stop cancels the collector and waits for partial persistence before allowing retry`() = runBlocking {
        val task = ChatGenerationTask(this)
        val emitted = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val persist = CompletableDeferred<Unit>()
        var partial = ""
        var saved = ""
        assertTrue(task.start {
            try { flow { emit("partial"); emitted.complete(Unit); awaitCancellation() }.collect { partial += it } }
            finally { withContext(NonCancellable) { cleanup.complete(Unit); persist.await(); saved = partial } }
        })
        emitted.await()
        assertTrue(task.stop())
        cleanup.await()
        assertTrue(task.userStopped)
        assertFalse(task.start { error("must not overlap cleanup") })
        assertFalse(task.stop())
        persist.complete(Unit)
        yield()
        assertEquals("partial", saved)
        val next = CompletableDeferred<Unit>()
        assertTrue(task.start { next.complete(Unit) })
        next.await()
        assertFalse(task.userStopped)
    }

    @Test fun `lifecycle cancellation is not a user stop`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val task = ChatGenerationTask(scope)
        val finished = CompletableDeferred<Unit>()
        assertTrue(task.start { try { awaitCancellation() } finally { finished.complete(Unit) } })
        scope.cancel(); finished.await()
        assertFalse(task.userStopped)
        assertFalse(task.stop())
    }
}
