package com.zero.v2.app.acceptance

import java.io.File
import org.json.JSONObject

/** Test evidence only. Budget reservation remains separately durable before every request. */
internal class M5EvidenceWriter(
    private val file: File,
    private val redact: (String) -> String = { it },
    private val now: () -> Long = System::nanoTime,
) {
    private var last: Long? = null
    var writes = 0; private set
    var bytes = 0L; private set
    fun write(report: JSONObject, force: Boolean = true) {
        val instant = now()
        if (!force && last?.let { instant - it < 1_000_000_000L } == true) return
        val text = redact(report.toString(2))
        file.writeText(text)
        writes++; bytes += text.toByteArray(Charsets.UTF_8).size
        last = instant
    }
}
