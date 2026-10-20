package com.zero.v2.app.acceptance

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Debug acceptance only. Reserve before dispatch; failed/cancelled requests still consume quota. */
class M5CallBudget(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        const val REQUESTS = 24
        const val BYTES = 768 * 1024L
        const val OUTPUT_PER_REQUEST = 4096
        const val DURATION_MS = 15 * 60 * 1000L
        val GROUP_CAPS = mapOf("other-kinds" to 4, "update-complete" to 4, "withdraw" to 2, "conflict" to 2, "fresh-context" to 4)
        val REVIEW_GROUP_CAPS = mapOf("review-kinds" to 7, "review-conflict" to 4, "review-task" to 7)
        const val REVIEW_APPROVAL = "m5-review-20261018-1"
        const val REVIEW_REQUEST_CAP = 41
        const val REVIEW_BYTE_CAP = 277812L + 768 * 1024L
    }
    private var attempts = 0
    private var bytes = 0L
    private var output = 0L
    private val started: Long
    private var windowStarted = 0L
    private var windowDuration = DURATION_MS
    private var approval = ""
    private val groups = linkedSetOf<String>()
    private var group = ""
    private var groupAttempts = 0
    private var requestCap = REQUESTS
    private var byteCap = BYTES
    private fun groupCaps() = if (approval == REVIEW_APPROVAL) GROUP_CAPS + REVIEW_GROUP_CAPS else GROUP_CAPS
    init {
        if (file.exists()) {
            val p = Properties().apply { file.inputStream().use { load(it) } }
            require(p.getProperty("schema") == "m5-budget-v1") { "budget_corrupt" }
            started = p.getProperty("started").toLong()
            attempts = p.getProperty("attempts").toInt()
            bytes = p.getProperty("bytes").toLong()
            output = p.getProperty("output").toLong()
            approval = p.getProperty("approval", "")
            requestCap = p.getProperty("requestCap", REQUESTS.toString()).toInt()
            byteCap = p.getProperty("byteCap", BYTES.toString()).toLong()
            require(if (approval == REVIEW_APPROVAL) requestCap == REVIEW_REQUEST_CAP && byteCap == REVIEW_BYTE_CAP
                else requestCap == REQUESTS && byteCap == BYTES) { "budget_caps_without_approval" }
            windowStarted = p.getProperty("windowStarted", started.toString()).toLong()
            windowDuration = p.getProperty("windowDuration", DURATION_MS.toString()).toLong()
            groups.addAll(p.getProperty("groups", "").split(',').filter { it.isNotEmpty() })
            group = p.getProperty("group", "")
            groupAttempts = p.getProperty("groupAttempts", "0").toInt()
            require(groups.all { it in groupCaps() } && (group.isEmpty() || group in groups))
            require(groupAttempts in 0..(groupCaps()[group] ?: 0))
            require(windowDuration in 1..25 * 60 * 1000L && windowStarted >= started)
            require(attempts in 0..requestCap && bytes in 0..byteCap && output in 0..requestCap * OUTPUT_PER_REQUEST.toLong())
        } else { started = now(); windowStarted = started; persist() }
    }
    @Synchronized fun beginGroup(id: String) {
        check(approval.isNotEmpty()) { "continuation_approval_required" }
        require(id in groupCaps())
        check(id !in groups) { "group_already_attempted_no_auto_retry" }
        check(now() >= windowStarted && now() - windowStarted < windowDuration) { "budget_deadline" }
        group = id; groupAttempts = 0; groups.add(id); persist()
    }
    /** A separate explicit grant, not a reset or an automatic use of the previous unused request. */
    @Synchronized fun approveReview(approvalId: String) {
        require(approvalId == REVIEW_APPROVAL)
        if (approval == REVIEW_APPROVAL) return // The persisted window never renews on reopen.
        check(approval == "m5-followup-20261018-1" && attempts == 23 && bytes == 277812L && output == 94208L &&
            groups == GROUP_CAPS.keys) { "review_baseline_mismatch" }
        check(now() >= windowStarted) { "budget_clock_rollback" }
        approval = approvalId; requestCap = REVIEW_REQUEST_CAP; byteCap = REVIEW_BYTE_CAP
        windowStarted = now(); windowDuration = 25 * 60 * 1000L
        group = ""; groupAttempts = 0 // Only the new active group resets; lifetime totals and old groups remain.
        persist()
    }
    /** Explicit continuation only; existing counts and original start never reset. Same approval cannot renew time. */
    @Synchronized fun continueApproved(approvalId: String, durationMs: Long) {
        require(approvalId.matches(Regex("[a-zA-Z0-9-]{1,64}")))
        require(durationMs in 1..25 * 60 * 1000L)
        if (approval.isNotEmpty()) {
            check(approval == approvalId && windowDuration == durationMs) { "continuation_already_bound" }
            return
        }
        check(attempts == 8 && bytes == 119191L && output == 32768L) { "continuation_baseline_mismatch" }
        check(now() >= windowStarted + windowDuration) { "original_window_not_closed" }
        approval = approvalId; windowStarted = now(); windowDuration = durationMs
        persist()
    }
    @Synchronized fun reserve(bodyBytes: Long, outputLimit: Int) {
        check(now() >= windowStarted && now() - windowStarted < windowDuration) { "budget_deadline" }
        require(bodyBytes > 0 && outputLimit in 1..OUTPUT_PER_REQUEST)
        check(approval.isEmpty() || group.isNotEmpty()) { "group_required" }
        check(group.isEmpty() || groupAttempts < groupCaps().getValue(group)) { "group_exhausted" }
        check(attempts < requestCap && bodyBytes <= byteCap - bytes) { "budget_exhausted" }
        attempts++; bytes += bodyBytes; output += outputLimit
        if (group.isNotEmpty()) groupAttempts++
        persist() // If persistence fails, the interceptor must not call proceed().
    }
    @Synchronized fun snapshot(): Map<String, Long> = mapOf("attempts" to attempts.toLong(), "bodyBytes" to bytes,
        "outputReserved" to output, "started" to started, "windowStarted" to windowStarted, "windowDuration" to windowDuration,
        "groupAttempts" to groupAttempts.toLong(), "requestCap" to requestCap.toLong(), "byteCap" to byteCap)
    @Synchronized fun remainingWindowMs(): Long {
        val elapsed = now() - windowStarted
        check(elapsed in 0 until windowDuration) { "budget_deadline" }
        return windowDuration - elapsed
    }
    private fun persist() {
        val pending = File(file.parentFile, file.name + ".pending")
        val p = Properties().apply {
            setProperty("schema", "m5-budget-v1"); setProperty("started", started.toString())
            setProperty("approval", approval); setProperty("windowStarted", windowStarted.toString())
            setProperty("windowDuration", windowDuration.toString())
            setProperty("groups", groups.joinToString(",")); setProperty("group", group)
            setProperty("groupAttempts", groupAttempts.toString())
            setProperty("requestCap", requestCap.toString()); setProperty("byteCap", byteCap.toString())
            setProperty("attempts", attempts.toString()); setProperty("bytes", bytes.toString()); setProperty("output", output.toString())
        }
        java.io.FileOutputStream(pending).use { stream -> p.store(stream, "M5 bounded acceptance"); stream.fd.sync() }
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
