package com.zero.v2.app.acceptance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.zero.v2.app.ui.settings.WorkFilesScreen
import com.zero.v2.app.ui.settings.WorkFilesViewModel
import com.zero.v2.app.ui.theme.ZeroTheme
import dagger.hilt.android.AndroidEntryPoint

/** Debug-only actual screen/Hilt owner; never starts models or normal background jobs. */
@AndroidEntryPoint
class WorkFilesAcceptanceActivity : ComponentActivity() {
    val model: WorkFilesViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { ZeroTheme { WorkFilesScreen(onBack = { finish() }, viewModel = model) } }
    }
}
