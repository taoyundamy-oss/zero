package com.zero.v2.app.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import com.zero.v2.app.ui.ChatScreen
import com.zero.v2.app.ui.automation.AutomationScreen
import com.zero.v2.app.ui.automation.CreateAutomationScreen
import com.zero.v2.app.ui.live.LiveSessionScreen
import com.zero.v2.app.ui.module.ModuleCenterScreen
import com.zero.v2.app.ui.log.RunLogScreen
import com.zero.v2.app.ui.settings.AgentBehaviorScreen
import com.zero.v2.app.ui.settings.CharacterManageScreen
import com.zero.v2.app.ui.settings.DataExportScreen
import com.zero.v2.app.ui.settings.MemoryGraphScreen
import com.zero.v2.app.ui.settings.MemoryManageScreen
import com.zero.v2.app.ui.settings.PermissionCenterScreen
import com.zero.v2.app.ui.settings.SettingsScreen

/**
 * 四个主入口；二级功能由 AppDestinations 定义唯一归属。
 */
enum class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    Chat("chat", "对话", Icons.AutoMirrored.Outlined.Chat),
    Library("library", "资料", Icons.Outlined.FolderOpen),
    Automation("automation", "工作区", Icons.Outlined.Dashboard),
    Settings("settings", "设置", Icons.Outlined.Settings)
}

/**
 * Zero v2 主导航宿主。
 *
 * Scaffold + NavigationBar + NavHost 架构。
 * 对标 Alife 的 CharacterWorkspacePage Tab 切换模式。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZeroNavHost() {
    val navController = rememberNavController()
    val items = BottomNavItem.entries.toList()
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val navEntry by navController.currentBackStackEntryAsState()
    val navigateRoot: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            if (!wide) NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                items.forEach { item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        selected = AppDestinations.rootFor(currentDestination?.route) == item.route,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Row(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)) {
        if (wide) NavigationRail {
            items.forEach { item ->
                NavigationRailItem(
                    selected = AppDestinations.rootFor(navEntry?.destination?.route) == item.route,
                    onClick = { navigateRoot(item.route) },
                    icon = { Icon(item.icon, contentDescription = item.label) },
                    label = { Text(item.label) },
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        NavHost(
            navController = navController,
            startDestination = BottomNavItem.Chat.route,
            modifier = Modifier.widthIn(max = 720.dp).fillMaxSize()
        ) {
            // ── 主 Tab ──
            composable(BottomNavItem.Chat.route) { backStackEntry ->
                // S1（L-5）：liveActive 取自 SystemStateRepository 语音会话投影
                // （与 zero_status FC 同源），不再硬编码 false
                val statusVm: SystemStatusViewModel = hiltViewModel()
                val liveActive by statusVm.liveActive.collectAsState()
                ChatScreen(
                    onOpenLive = { navController.navigate("live_session") },
                    liveActive = liveActive)
            }
            composable("live_session") {
                LiveSessionScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable(BottomNavItem.Library.route) {
                RootHubScreen("资料", "保存、整理与回看你的内容", AppDestinations.library,
                    onNavigate = { navController.navigate(it) })
            }
            composable(BottomNavItem.Automation.route) {
                RootHubScreen("工作区", "查看任务、模块与运行状态", AppDestinations.workspace,
                    onNavigate = { navController.navigate(it) }, showStatus = true)
            }
            composable(BottomNavItem.Settings.route) {
                SettingsScreen(onNavigate = { navController.navigate(it) })
            }
            composable("appearance") {
                com.zero.v2.app.ui.settings.AppearanceScreen(onBack = { navController.popBackStack() })
            }
            composable("automation_list") {
                AutomationScreen(onCreateClick = { navController.navigate("automation_create") },
                    onBack = { navController.popBackStack() })
            }
            // ── 设置子页面 ──
            composable("modules") {
                ModuleCenterScreen(onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate(it) })
            }
            composable("logs") {
                RunLogScreen(onBack = { navController.popBackStack() })
            }
            composable("character_manage") {
                CharacterManageScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable("memory_manage") {
                MemoryManageScreen(
                    onBack = { navController.popBackStack() },
                    onReview = { navController.navigate("memory_review") }
                )
            }
            composable("vector_maintenance") {
                com.zero.v2.app.ui.settings.VectorMaintenanceScreen(onBack = { navController.popBackStack() })
            }
            composable("memory_review") {
                com.zero.v2.app.ui.settings.MemoryReviewScreen(onBack = { navController.popBackStack() })
            }
            composable("memory_graph") {
                MemoryGraphScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable("usage") {
                com.zero.v2.app.ui.settings.UsageScreen(onBack = { navController.popBackStack() })
            }
            composable("agent_behavior") {
                AgentBehaviorScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable("permission_center") {
                PermissionCenterScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable("work_files") {
                com.zero.v2.app.ui.settings.WorkFilesScreen(onBack = { navController.popBackStack() })
            }
            composable("music_library") {
                com.zero.v2.app.ui.music.MusicScreen(onBack = { navController.popBackStack() })
            }
            composable("mcp_servers") {
                com.zero.v2.app.ui.settings.McpServersScreen(onBack = { navController.popBackStack() })
            }
            // M2.4：动态模块列表 + D68 详情配置页
            composable("dynamic_modules") {
                com.zero.v2.app.ui.settings.DynamicModulesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { navController.navigate("dyn_module/${android.net.Uri.encode(it)}") }
                )
            }
            composable("dyn_module/{id}") { entry ->
                com.zero.v2.app.ui.settings.DynModuleDetailScreen(
                    moduleId = entry.arguments?.getString("id") ?: "",
                    onBack = { navController.popBackStack() }
                )
            }
            composable("trigger_settings") {
                com.zero.v2.app.ui.settings.TriggerSettingsScreen()
            }
            composable("data_backup") {
                DataExportScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            // ── 自动化子页面 ──
            composable("automation_create") { entry ->
                // 与列表屏共享 ViewModel（父 destination 作用域）— 创建后列表即时刷新
                val parentEntry = remember(entry) {
                    navController.getBackStackEntry("automation_list")
                }
                CreateAutomationScreen(
                    onBack = { navController.popBackStack() },
                    onCreateDone = { navController.popBackStack() },
                    viewModel = hiltViewModel(parentEntry)
                )
            }
        }
        }
        }
    }
}
