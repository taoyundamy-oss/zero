package com.zero.v2.core.data.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseMigrationChainTest {

    @Test
    fun `migration chain is contiguous from oldest supported version to current`() {
        val migrations = DatabaseModule.ALL_MIGRATIONS.sortedBy { it.startVersion }

        assertEquals(5, migrations.first().startVersion)
        assertEquals(38, migrations.last().endVersion)

        migrations.zipWithNext().forEach { (current, next) ->
            assertEquals(current.endVersion, next.startVersion)
        }
        assertTrue(migrations.all { it.endVersion == it.startVersion + 1 })
    }
}
