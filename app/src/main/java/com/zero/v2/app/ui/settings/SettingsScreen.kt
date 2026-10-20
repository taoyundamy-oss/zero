package com.zero.v2.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zero.v2.app.ui.navigation.AppDestinations
import com.zero.v2.app.ui.theme.ZeroNavigationRow
import com.zero.v2.app.ui.theme.ZeroNavigationGroup
import com.zero.v2.app.ui.theme.ZeroNavigationDivider
import com.zero.v2.app.ui.theme.ZeroPageHeader
import com.zero.v2.app.ui.navigation.destinationIcon
import androidx.compose.ui.graphics.Color

/** Configuration only. Library and workspace operations have their own canonical homes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    SettingsContent(onNavigate) { requestAssistantRole(context) }
}

@Composable
internal fun SettingsContent(onNavigate: (String) -> Unit, onAssistantRole: () -> Unit) {
    val listState = rememberLazyListState()
    Scaffold(topBar = { ZeroPageHeader("设置") }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppDestinations.settings.groupBy { it.section }.forEach { (section, destinations) ->
                item(key = "section_$section") {
                    Text(section, style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
                }
                item(key = "group_$section") {
                    ZeroNavigationGroup {
                        destinations.forEachIndexed { index, destination ->
                            if (index > 0) ZeroNavigationDivider()
                            ZeroNavigationRow(destination.title, destination.description,
                                icon = destinationIcon(destination.route), containerColor = Color.Transparent) {
                                if (destination.route == "assistant_role") onAssistantRole()
                                else onNavigate(destination.route)
                            }
                        }
                    }
                }
            }
            item(key = "about") {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Zero · ${com.zero.v2.app.BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.titleMedium)
                        Text("个人 AI 助手", style = MaterialTheme.typography.bodyLarge)
                        Text("对话与设置保存在本机。使用云端模型或外部工具时，相关内容会发送到所选服务。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
private fun requestAssistantRole(context: android.content.Context) {
    runCatching {
        context.startActivity(android.content.Intent(
            android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
    }.onFailure {
        runCatching {
            context.startActivity(android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }.onFailure { e ->
            android.widget.Toast.makeText(context, "无法打开系统设置：${e.message?.take(40)}",
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
