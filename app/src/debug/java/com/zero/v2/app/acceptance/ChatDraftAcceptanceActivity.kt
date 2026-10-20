package com.zero.v2.app.acceptance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import com.zero.v2.app.ui.chat.ChatDraftState
import com.zero.v2.app.ui.chat.ChatComposer
import com.zero.v2.app.ui.theme.ZeroTheme

/** Debug-only keyboard/recreation fixture using the production saved draft and composer. */
@OptIn(ExperimentalLayoutApi::class)
class ChatDraftAcceptanceActivity : ComponentActivity() {
    class DraftModel(val saved: SavedStateHandle) : ViewModel() {
        internal val draft = ChatDraftState(saved)
    }
    lateinit var model: DraftModel
    @Volatile var imeVisible = false
    @Volatile var imeInsetPx = 0
    @Volatile var sends = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val factory = object : AbstractSavedStateViewModelFactory(this, null) {
            override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T {
                @Suppress("UNCHECKED_CAST") return DraftModel(handle) as T
            }
        }
        model = ViewModelProvider(this, factory)[DraftModel::class.java]
        setContent {
            val text by model.draft.text.collectAsState()
            val attachment by model.draft.attachment.collectAsState()
            val density = LocalDensity.current.density
            var preview by rememberSaveable { mutableStateOf(false) }
            CompositionLocalProvider(LocalDensity provides Density(density, intent.getFloatExtra("fontScale", 1f))) {
                ZeroTheme(darkTheme = intent.getBooleanExtra("dark", false), dynamicColor = false) {
                    val visible = WindowInsets.isImeVisible
                    val inset = WindowInsets.ime.getBottom(LocalDensity.current)
                    SideEffect { imeVisible = visible; imeInsetPx = inset }
                    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
                        TextButton({ preview = !preview }) { Text(if (preview) "返回对话" else "查看示例资料") }
                        if (preview) Text("资料示例，草稿已保留") else {
                            Text("隔离草稿验收", style = MaterialTheme.typography.titleLarge)
                            Text("附件路线：${attachment?.route?.name ?: "无"}")
                            Spacer(Modifier.weight(1f))
                            ChatComposer(text, model.draft::setText, false, text.isNotBlank() && !intent.getBooleanExtra("sendDisabled", false),
                                { model.draft.setAttachment(com.zero.v2.core.common.Attachment("content://fixture/image", "image/png",
                                    route = com.zero.v2.core.common.MediaRoute.TOOLS)); model.draft.conversationId = "isolated-conversation" }, {},
                                { sends++ })
                        }
                    }
                    }
                }
            }
        }
    }
}
