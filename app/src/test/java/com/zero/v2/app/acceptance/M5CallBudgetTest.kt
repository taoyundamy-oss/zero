package com.zero.v2.app.acceptance

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class M5CallBudgetTest {
    private fun reviewBaseline(file: File) = file.writeText("schema=m5-budget-v1\nstarted=100\nattempts=23\nbytes=277812\noutput=94208\n" +
        "approval=m5-followup-20261018-1\nwindowStarted=1000000\nwindowDuration=1500000\n" +
        "groups=other-kinds,update-complete,withdraw,conflict,fresh-context\ngroup=fresh-context\ngroupAttempts=3")
    @Test fun reviewGrantAddsEighteenWithoutResettingAnyLifetimeCount() = isolated { file ->
        reviewBaseline(file)
        val b = M5CallBudget(file) { 3_000_000 }
        b.approveReview(M5CallBudget.REVIEW_APPROVAL)
        assertEquals(23L, b.snapshot()["attempts"]); assertEquals(277812L, b.snapshot()["bodyBytes"])
        assertEquals(94208L, b.snapshot()["outputReserved"]); assertEquals(100L, b.snapshot()["started"])
        rejects { b.beginGroup("other-kinds") }
        M5CallBudget.REVIEW_GROUP_CAPS.forEach { (id, cap) -> b.beginGroup(id); repeat(cap) { b.reserve(1, 4096) } }
        rejects { b.reserve(1, 4096) }
        assertEquals(41L, M5CallBudget(file) { 3_000_001 }.snapshot()["attempts"])
    }
    @Test fun reviewReopenCannotRenewWindowOrRetryStartedGroups() = isolated { file ->
        reviewBaseline(file)
        val b = M5CallBudget(file) { 3_000_000 }; b.approveReview(M5CallBudget.REVIEW_APPROVAL)
        b.beginGroup("review-conflict"); b.reserve(1, 4096)
        val later = M5CallBudget(file) { 4_500_000 }; later.approveReview(M5CallBudget.REVIEW_APPROVAL)
        rejects { later.remainingWindowMs() }; rejects { later.reserve(1, 4096) }
        rejects { later.beginGroup("review-conflict") }
    }
    @Test fun reviewByteCapAndWrongBaselineFailClosed() = isolated { file ->
        reviewBaseline(file)
        val b = M5CallBudget(file) { 3_000_000 }; b.approveReview(M5CallBudget.REVIEW_APPROVAL)
        b.beginGroup("review-task"); b.reserve(768 * 1024L, 4096)
        rejects { b.reserve(1, 4096) }
        reviewBaseline(file); file.appendText("\nattempts=24")
        rejects { M5CallBudget(file) { 3_000_000 }.approveReview(M5CallBudget.REVIEW_APPROVAL) }
    }
    @Test fun reviewCannotRunUnderOldGrantOrElevateCapsByPropertiesAlone() = isolated { file ->
        reviewBaseline(file)
        rejects { M5CallBudget(file) { 1_000_001 }.beginGroup("review-kinds") }
        rejects { M5CallBudget(file) { 3_000_000 }.approveReview("different-id") }
        file.appendText("\nrequestCap=41\nbyteCap=1064244")
        rejects { M5CallBudget(file) { 3_000_000 } }
    }
    private fun baseline(file: File) = file.writeText("schema=m5-budget-v1\nstarted=100\nattempts=8\nbytes=119191\noutput=32768")
    @Test fun continuationCarriesExactOldCountsAndRemainingCaps() = isolated { file ->
        baseline(file)
        val b = M5CallBudget(file) { 1_000_000 }
        b.continueApproved("m5-followup-1", 1_500_000)
        assertEquals(100L, b.snapshot()["started"])
        assertEquals(8L, b.snapshot()["attempts"])
        M5CallBudget.GROUP_CAPS.forEach { (id, cap) -> b.beginGroup(id); repeat(cap) { b.reserve(1, 4096) } }
        rejects { b.reserve(1, 4096) }
        assertEquals(24L, M5CallBudget(file) { 1_000_001 }.snapshot()["attempts"])
    }
    @Test fun reopeningApprovalCannotExtendDeadlineOrReplaceApproval() = isolated { file ->
        baseline(file)
        M5CallBudget(file) { 1_000_000 }.continueApproved("m5-followup-1", 1_500_000)
        val later = M5CallBudget(file) { 2_500_000 }
        later.continueApproved("m5-followup-1", 1_500_000)
        rejects { later.remainingWindowMs() }
        rejects { later.reserve(1, 4096) }
        rejects { later.continueApproved("m5-followup-2", 1_500_000) }
        rejects { later.continueApproved("m5-followup-1", 1_400_000) }
    }
    @Test fun independentGroupsShareOneAbsoluteWindow() = isolated { file ->
        baseline(file)
        M5CallBudget(file) { 1_000_000 }.continueApproved("m5-followup-1", 1_500_000)
        val later = M5CallBudget(file) { 2_000_000 }
        later.continueApproved("m5-followup-1", 1_500_000)
        later.beginGroup("other-kinds")
        assertEquals(500_000L, later.remainingWindowMs())
    }
    @Test fun continuationPreservesRemainingByteLimit() = isolated { file ->
        baseline(file)
        val b = M5CallBudget(file) { 1_000_000 }
        b.continueApproved("m5-followup-1", 1_500_000)
        b.beginGroup("other-kinds")
        b.reserve(M5CallBudget.BYTES - 119191L, 4096)
        rejects { b.reserve(1, 4096) }
    }
    @Test fun groupCannotRetryAndItsCapCannotBorrowFromOtherGroups() = isolated { file ->
        baseline(file)
        val b = M5CallBudget(file) { 1_000_000 }
        b.continueApproved("m5-followup-1", 1_500_000)
        rejects { b.reserve(1, 4096) }
        b.beginGroup("withdraw"); repeat(2) { b.reserve(1, 4096) }
        rejects { b.reserve(1, 4096) }
        val reopened = M5CallBudget(file) { 1_000_001 }
        rejects { reopened.beginGroup("withdraw") }
        reopened.beginGroup("conflict"); reopened.reserve(1, 4096)
        assertEquals(11L, reopened.snapshot()["attempts"])
    }
    @Test fun continuationRejectsNewOrUnexpectedLedger() = isolated { file ->
        rejects { M5CallBudget(file) { 1_000_000 }.continueApproved("m5-followup-1", 1_500_000) }
        baseline(file)
        rejects { M5CallBudget(file) { 101 }.continueApproved("m5-followup-1", 1_500_000) }
        file.appendText("\nbytes=119192")
        rejects { M5CallBudget(file) { 1_000_000 }.continueApproved("m5-followup-1", 1_500_000) }
    }
    private fun isolated(block: (File) -> Unit) {
        val root = Files.createTempDirectory("m5-budget-test").toFile()
        try { block(File(root, "budget.properties")) } finally { root.deleteRecursively() }
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("must reject") } catch (_: IllegalStateException) { } catch (_: IllegalArgumentException) { }
    }
    @Test fun failedAttemptsRemainCountedAfterReopen() = isolated { file ->
        M5CallBudget(file) { 100 }.reserve(20, 4096)
        val reopened = M5CallBudget(file) { 101 }
        assertEquals(1L, reopened.snapshot()["attempts"])
        assertEquals(4096L, reopened.snapshot()["outputReserved"])
    }
    @Test fun capRejectsWithoutResettingJournal() = isolated { file ->
        val b = M5CallBudget(file) { 100 }
        repeat(M5CallBudget.REQUESTS) { b.reserve(1, 4096) }
        rejects { M5CallBudget(file) { 101 }.reserve(1, 1) }
        assertEquals(24L, M5CallBudget(file) { 101 }.snapshot()["attempts"])
    }
    @Test fun cumulativeBytesAreInclusiveAndPersisted() = isolated { file ->
        val b = M5CallBudget(file) { 100 }; b.reserve(M5CallBudget.BYTES, 1)
        rejects { M5CallBudget(file) { 101 }.reserve(1, 1) }
    }
    @Test fun invalidSizesAndOutputDoNotConsumeBudget() = isolated { file ->
        val b = M5CallBudget(file) { 100 }
        rejects { b.reserve(0, 1) }; rejects { b.reserve(1, 4097) }; rejects { b.reserve(-1, 1) }
        assertEquals(0L, b.snapshot()["attempts"])
    }
    @Test fun deadlineAndClockRollbackFailClosed() = isolated { file ->
        M5CallBudget(file) { 100 }
        rejects { M5CallBudget(file) { 99 }.reserve(1, 1) }
        rejects { M5CallBudget(file) { 100 + M5CallBudget.DURATION_MS }.reserve(1, 1) }
    }
    @Test fun corruptedOrOutOfRangeJournalCannotResetQuota() = isolated { file ->
        file.writeText("schema=unknown")
        rejects { M5CallBudget(file) }
        file.writeText("schema=m5-budget-v1\nstarted=100\nattempts=25\nbytes=1\noutput=1")
        rejects { M5CallBudget(file) }
    }
}
