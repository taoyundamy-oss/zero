package com.zero.v2.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.zero.v2.core.data.store.ModuleConfigStore
import com.zero.v2.app.background.AutonomousKeepAliveService
import org.json.JSONObject
import javax.inject.Inject

/**
 * Zero v2 主 Activity。
 *
 * v5.4a: enableEdgeToEdge 强制（Android 15+/SDK 35 行为基准，M3E 规范）—
 * 内容延伸至系统栏背后，Insets 交由 Compose Scaffold/TopAppBar 处理。
 * S8.3（图纸 D2 · 裁定 13）：splash 自然时长——首帧即退，无人工延迟。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var configStore: ModuleConfigStore
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZeroApp()
        }
    }
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            try {
                val app = application as ZeroApplication
                app.awaitModuleInitialization()
                val mode = configStore.get("agent_behavior")?.let { JSONObject(it).optString("mode", "SMART") } ?: "SMART"
                if (mode != "OFF" && app.agentRuntime.isEnabled && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    AutonomousKeepAliveService.start(this@MainActivity)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { android.util.Log.e("ZeroV2", "Autonomous foreground start unavailable", e) }
        }
    }
}
