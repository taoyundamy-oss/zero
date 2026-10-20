package com.zero.v2.services.termux

import org.json.JSONObject
import java.security.MessageDigest

/** Identity comes from the loaded supervisor, not a hash of subsequently replaced files. */
internal object SupervisorBundle {
    val files = listOf("zero_tool.py", "zero_release.py", "zero_supervisor.py", "zero_host.py")

    fun hashes(contents: Map<String, ByteArray>): JSONObject = JSONObject().apply {
        files.forEach { name ->
            put(name, MessageDigest.getInstance("SHA-256").digest(contents.getValue(name))
                .joinToString("") { "%02x".format(it) })
        }
    }

    fun matches(health: JSONObject?, hashes: JSONObject): Boolean {
        val actual = health?.optJSONObject("asset_hashes")
        val installed = health?.optJSONObject("installed_asset_hashes")
        return isReady(health) && health?.optInt("release_protocol") == 1 &&
            actual != null && installed != null && files.all {
            actual.optString(it) == hashes.getString(it) && installed.optString(it) == hashes.getString(it)
        } &&
            health.optString("debian_sdk_hash") == hashes.getString("zero_tool.py")
    }

    /** Old hosts bind only after recovery; a new host must explicitly be ready. */
    fun isReady(health: JSONObject?): Boolean = health != null &&
        (!health.has("ready") || health.opt("ready") == true)
}
