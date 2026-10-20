package com.zero.v2.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class AppearanceMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }
data class AppearanceState(val mode: AppearanceMode = AppearanceMode.SYSTEM, val dynamicColor: Boolean = true)

@Singleton
class AppearanceStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("zero_appearance", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(AppearanceState(
        runCatching { AppearanceMode.valueOf(preferences.getString("mode", "SYSTEM")!!) }
            .getOrDefault(AppearanceMode.SYSTEM), preferences.getBoolean("dynamic_color", true)))
    val state = mutable.asStateFlow()

    fun set(mode: AppearanceMode = state.value.mode, dynamicColor: Boolean = state.value.dynamicColor) {
        preferences.edit().putString("mode", mode.name).putBoolean("dynamic_color", dynamicColor).apply()
        mutable.value = AppearanceState(mode, dynamicColor)
    }
}

@HiltViewModel
class AppearanceViewModel @Inject constructor(private val store: AppearanceStore) : ViewModel() {
    val state = store.state
    fun setMode(mode: AppearanceMode) = store.set(mode = mode)
    fun setDynamic(enabled: Boolean) = store.set(dynamicColor = enabled)
}
