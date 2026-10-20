package com.zero.v2.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.hilt.navigation.compose.hiltViewModel
import com.zero.v2.app.ui.settings.AppearanceMode
import com.zero.v2.app.ui.settings.AppearanceViewModel
import com.zero.v2.app.ui.navigation.ZeroNavHost
import com.zero.v2.app.ui.theme.ZeroTheme

@Composable
fun ZeroApp() {
    val appearance: AppearanceViewModel = hiltViewModel()
    val state by appearance.state.collectAsState()
    val dark = when (state.mode) {
        AppearanceMode.SYSTEM -> isSystemInDarkTheme()
        AppearanceMode.LIGHT -> false
        AppearanceMode.DARK -> true
    }
    val view = LocalView.current
    SideEffect {
        val activity = view.context as? android.app.Activity
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    ZeroTheme(darkTheme = dark, dynamicColor = state.dynamicColor) {
        ZeroNavHost()
    }
}
