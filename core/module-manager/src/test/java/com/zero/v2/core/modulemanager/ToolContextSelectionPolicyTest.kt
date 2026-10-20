package com.zero.v2.core.modulemanager

import com.zero.v2.core.moduleapi.FunctionCatalog
import com.zero.v2.core.moduleapi.ModuleRuntimeStateReader
import com.zero.v2.core.moduleapi.FunctionEntry
import com.zero.v2.core.moduleapi.ToolContextRequest

import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.core.moduleapi.ModuleRuntimeState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Provider

class ToolContextSelectionPolicyTest {
    private val definitions = listOf(
        FunctionDefinition("web_search", "搜索互联网"),
        FunctionDefinition("memory_recall", "查询相关记忆"),
        FunctionDefinition("push_notification", "推送系统通知"),
    )

    @Test
    fun defaultSelection_keepsOperationalDefinitionsInCatalogOrder() {
        assertEquals(
            listOf("web_search", "memory_recall", "push_notification"),
            ToolContextSelectionPolicy.select(definitions).map { it.name },
        )
    }

    @Test
    fun disabledModuleDefinitions_areNeverExposed() {
        assertEquals(
            listOf("web_search", "memory_recall"),
            ToolContextSelectionPolicy.select(definitions, disabledNames = setOf("push_notification"))
                .map { it.name },
        )
    }

    @Test
    fun explicitRequest_reloadsOnlyRequestedOperationalDefinitions() {
        assertEquals(
            listOf("memory_recall"),
            ToolContextSelectionPolicy.select(
                definitions,
                disabledNames = setOf("push_notification"),
                request = ToolContextRequest(
                    explicitFunctionNames = setOf("memory_recall", "push_notification"),
                    includeAllOperational = false,
                ),
            ).map { it.name },
        )
    }

    @Test
    fun intentSelection_matchesNameOrDescriptionWithoutExecutingTools() {
        assertEquals(
            listOf("memory_recall"),
            ToolContextSelectionPolicy.select(
                definitions,
                request = ToolContextRequest(intent = "记忆", includeAllOperational = false),
            ).map { it.name },
        )
    }

    @Test
    fun intentSelection_supportsChineseTextWithoutWhitespace() {
        assertEquals(
            listOf("web_search"),
            ToolContextSelectionPolicy.select(
                definitions,
                request = ToolContextRequest(intent = "帮我搜索天气", includeAllOperational = false),
            ).map { it.name },
        )
    }

    @Test
    fun defaultSelector_readsRuntimeSnapshotAndHidesDisabledModuleTools() {
        val catalog = object : FunctionCatalog {
            override fun getAllDefinitions() = definitions
            override fun getEntriesByModule(moduleId: String) = when (moduleId) {
                "notifications" -> listOf(entry(definitions[2], moduleId))
                else -> emptyList()
            }
        }
        val reader = object : ModuleRuntimeStateReader {
            override val runtimeStates = MutableStateFlow(
                mapOf(
                    "notifications" to ModuleRuntimeState(false, ModuleLifecycle.DISABLED),
                    "search" to ModuleRuntimeState(true, ModuleLifecycle.STARTED),
                ),
            )
        }
        val selector = DefaultToolContextSelector(catalog, Provider { reader })

        assertEquals(
            listOf("web_search", "memory_recall"),
            selector.select().map { it.name },
        )
    }

    private fun entry(definition: FunctionDefinition, moduleId: String) = FunctionEntry(
        definition = definition,
        sourceModuleId = moduleId,
        handler = { FunctionResult.success("ok") },
    )

    // ═══ C2：有界发现（top-K + 基础集，无意图不全量塞）═══

    private val manyTools = (1..12).map { i ->
        FunctionDefinition("tool_$i", if (i % 2 == 0) "搜索 search" else "其他 other $i")
    }

    @Test
    fun `bounded mode no intent returns base set not all operational`() {
        // 无意图 + 有界 → 只基础集（不全量回退，落实"无意图匹配不全量塞工具"）
        val base = setOf("tool_1", "tool_2")
        val sel = ToolContextSelectionPolicy.select(
            manyTools,
            request = ToolContextRequest(includeAllOperational = false, baseToolNames = base),
        ).map { it.name }.toSet()
        assertTrue("应只含基础集，actual=$sel", sel == base)
    }

    @Test
    fun `bounded mode intent match capped at maxTools`() {
        // 12 工具，意图"search"匹配 6 个（偶数项），maxTools=3 → 取 3
        val sel = ToolContextSelectionPolicy.select(
            manyTools,
            request = ToolContextRequest(intent = "search", includeAllOperational = false, maxTools = 3),
        )
        assertTrue("actual=${sel.size}", sel.size <= 3)
        assertTrue(sel.all { it.description.contains("搜索") || it.name.contains("tool") })
    }

    @Test
    fun `bounded mode intent match deterministic ordering`() {
        // 同输入两次结果相同（确定性：score desc + catalog 序 + name）
        val req = ToolContextRequest(intent = "search", includeAllOperational = false, maxTools = 4)
        val a = ToolContextSelectionPolicy.select(manyTools, request = req).map { it.name }
        val b = ToolContextSelectionPolicy.select(manyTools, request = req).map { it.name }
        assertEquals(a, b)
    }

    @Test
    fun `legacy all operational mode uncapped by maxTools`() {
        // 灰度兼容：includeAllOperational=true → 全量（maxTools 不裁）
        val sel = ToolContextSelectionPolicy.select(
            manyTools,
            request = ToolContextRequest(includeAllOperational = true, maxTools = 2),
        )
        assertEquals(12, sel.size)
    }

    @Test
    fun `explicit names still visible regardless of bounded mode`() {
        // 明确命名工具可见（验收）
        val sel = ToolContextSelectionPolicy.select(
            manyTools,
            request = ToolContextRequest(
                explicitFunctionNames = setOf("tool_5", "tool_7"),
                includeAllOperational = false,
            ),
        ).map { it.name }
        assertEquals(listOf("tool_5", "tool_7"), sel)
    }

    @Test
    fun `disabled tools never selected in bounded mode`() {
        // 旧工具权限/参数校验不变——禁用模块工具不入选（有界模式同）
        val sel = ToolContextSelectionPolicy.select(
            manyTools,
            disabledNames = setOf("tool_1"),
            request = ToolContextRequest(
                explicitFunctionNames = setOf("tool_1", "tool_2"),
                includeAllOperational = false,
            ),
        ).map { it.name }
        assertEquals(listOf("tool_2"), sel) // tool_1 禁用被剔
    }
}
