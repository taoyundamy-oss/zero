package com.zero.v2.app.acceptance

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.module.ModuleCenterScreen
import com.zero.v2.app.ui.module.ModuleDetailScreen
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.store.ModuleConfigStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Loaded production screens with long isolated metadata; no personal switches are touched. */
class ModuleLoadedPageAcceptanceTest {
    @Test fun loadedPagesResourceMatrix() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(inst.targetContext, ZeroDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = ModuleConfigStore(db.moduleConfigDao(), scope)
        val factory = EntryPointAccessors.fromApplication(inst.targetContext,
            RegenerationAcceptanceEntryPoint::class.java).moduleDetailAcceptanceFactory()
        val modules = (1..4).map {
            ModuleConfigWriteAcceptanceTest.Module("a8_loaded_${System.currentTimeMillis()}_$it",
                "模块 $it · " + "长名称用于主题与字号验收".repeat(4),
                "长描述用于验证模块布局与阅读。".repeat(20))
        }.toSet()
        val center = factory.isolatedCenter(modules, store)
        val detail = factory.isolated(modules, store)
        val owners = androidx.lifecycle.ViewModelStore().apply { put("center", center); put("detail", detail) }
        val rows = JSONArray()
        val split = InstrumentationRegistry.getArguments().getString("moduleWindow") == "split"
        val folderRoot = if (split) "a8-loaded-modules-split" else "a8-loaded-modules"
        fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
            fun visit(n: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean = predicate): AccessibilityNodeInfo? {
                if (n == null || !n.refresh()) return null
                if (n.isVisibleToUser && match(n)) return n
                for (i in 0 until n.childCount) visit(n.getChild(i), match)?.let { return it }
                return null
            }
            repeat(100) { attempt ->
                visit(inst.uiAutomation.rootInActiveWindow)?.let { return it }
                if (attempt % 3 == 2) visit(inst.uiAutomation.rootInActiveWindow) { it.isScrollable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Thread.sleep(60)
            }
            error("Loaded module control missing")
        }
        try {
            for (scale in listOf(1f, 2f)) for (dark in listOf(false, true)) for (dynamic in listOf(false, true)) {
                VisualAcceptanceActivity.fontScaleForNextLaunch = scale
                val activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
                try {
                    if (split) SplitWindowAcceptance.ensure(activity)
                    assertEquals(scale, activity.resources.configuration.fontScale, .01f)
                    val width = activity.resources.configuration.screenWidthDp
                    assertTrue(if (split) activity.isInMultiWindowMode && width in 280..599 else width >= 600)
                    fun show(page: String) = inst.runOnMainSync {
                        activity.setContent {
                            ZeroTheme(darkTheme = dark, dynamicColor = dynamic) {
                                if (page == "center") ModuleCenterScreen({}, {}, center)
                                else ModuleDetailScreen(modules.first().metadata.id, {}, {}, detail)
                            }
                        }
                    }
                    fun shot(name: String) {
                        val image = requireNotNull(inst.uiAutomation.takeScreenshot(activity.window))
                        val file = java.io.File(inst.targetContext.filesDir, "$folderRoot/$scale-$dark-$dynamic/$name.png")
                        file.parentFile!!.mkdirs()
                        file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        image.recycle()
                    }
                    show("center")
                    find { it.text?.toString() == "能力与模型" }
                    find { it.text?.toString()?.contains(modules.first().metadata.displayName) == true }
                    shot("center")
                    show("detail")
                    find { it.text?.toString() == modules.first().metadata.description }
                    shot("detail")
                    find { it.isEditable && it.text?.toString() == "3" }
                    shot("detail-parameters")
                    assertTrue(modules.all { it.config.count == 3 && it.changes == 0 })
                    rows.put(JSONObject().put("fontScale", scale).put("dark", dark).put("dynamic", dynamic)
                        .put("widthDp", activity.resources.configuration.screenWidthDp)
                        .put("multiWindow", activity.isInMultiWindowMode))
                } finally { inst.runOnMainSync { activity.finish() } }
            }
            java.io.File(inst.targetContext.filesDir, "$folderRoot/matrix.json").writeText(rows.toString(2))
        } finally {
            VisualAcceptanceActivity.fontScaleForNextLaunch = null
            inst.runOnMainSync { owners.clear() }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            db.close()
        }
    }
}
