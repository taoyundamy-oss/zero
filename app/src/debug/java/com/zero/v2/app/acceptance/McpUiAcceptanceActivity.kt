package com.zero.v2.app.acceptance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable

/** Non-exported production-page host; test-owned state only, and no normal Application startup. */
class McpUiAcceptanceActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(android.content.res.Configuration().apply { fontScale = scale })
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { requireNotNull(content).invoke() }
    }
    companion object {
        @Volatile var scale = 1f
        @Volatile var content: (@Composable () -> Unit)? = null
    }
}
