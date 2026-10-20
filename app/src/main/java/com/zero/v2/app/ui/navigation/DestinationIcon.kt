package com.zero.v2.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

/** Decorative icons accompany text; route ownership stays in AppDestinations. */
fun destinationIcon(route: String): ImageVector = when (route) {
    "character_manage" -> Icons.Outlined.PersonOutline
    "appearance" -> Icons.Outlined.Palette
    "usage" -> Icons.Outlined.BarChart
    "agent_behavior" -> Icons.Outlined.Tune
    "trigger_settings" -> Icons.Outlined.Notifications
    "modules" -> Icons.Outlined.Widgets
    "mcp_servers" -> Icons.Outlined.Link
    "permission_center" -> Icons.Outlined.Security
    "assistant_role" -> Icons.Outlined.Mic
    "data_backup" -> Icons.Outlined.Backup
    "work_files" -> Icons.Outlined.FolderOpen
    "memory_manage" -> Icons.Outlined.BookmarkBorder
    "memory_graph" -> Icons.Outlined.AccountTree
    "music_library" -> Icons.Outlined.MusicNote
    "automation_list" -> Icons.Outlined.Checklist
    "dynamic_modules" -> Icons.Outlined.Extension
    else -> Icons.Outlined.Article
}
