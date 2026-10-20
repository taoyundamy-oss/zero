package com.zero.v2.app.acceptance

import android.os.Bundle
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject

/** Exports only the isolated fixture report when run-as is unavailable for benchmark APKs. */
internal fun streamAcceptanceReport(report: JSONObject) {
    if (InstrumentationRegistry.getArguments().getString("streamReport") != "true") return
    val encoded = Base64.encodeToString(report.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
        putString("acceptance_report_base64", encoded)
    })
}
