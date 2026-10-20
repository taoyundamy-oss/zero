package com.zero.v2.services.security

import android.content.SharedPreferences

/** Disk acknowledgement precedes publication; failed commit may already have changed the process cache. */
internal fun writePreferenceConfirmed(prefs: SharedPreferences, key: String, value: String?, failureMessage: String) {
    val previous = prefs.getString(key, null)
    val editor = prefs.edit()
    if (value == null) editor.remove(key) else editor.putString(key, value)
    if (!editor.commit()) {
        val restore = prefs.edit()
        if (previous == null) restore.remove(key) else restore.putString(key, previous)
        restore.commit()
        error(failureMessage)
    }
}
