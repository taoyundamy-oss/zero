package com.zero.v2.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDestinationsTest {
    @Test fun `nested destinations keep their root navigation selected`() {
        assertEquals("automation", AppDestinations.rootFor("automation_create"))
        assertEquals("automation", AppDestinations.rootFor("dyn_module/{id}"))
        assertEquals("library", AppDestinations.rootFor("work_files"))
        assertEquals("settings", AppDestinations.rootFor("modules"))
        assertEquals("chat", AppDestinations.rootFor("live_session"))
    }
    @Test fun `existing user functions retain one home after settings consolidation`() {
        val all = AppDestinations.library + AppDestinations.workspace + AppDestinations.settings
        assertEquals(all.size, all.map { it.route }.distinct().size)
        assertTrue(all.map { it.route }.containsAll(listOf("work_files", "memory_manage", "memory_graph",
            "music_library", "automation_list", "dynamic_modules", "logs", "modules", "mcp_servers",
            "character_manage", "agent_behavior", "usage", "trigger_settings", "permission_center", "data_backup")))
        assertTrue(AppDestinations.settings.none { it.route in AppDestinations.library.map { entry -> entry.route } })
    }
}
