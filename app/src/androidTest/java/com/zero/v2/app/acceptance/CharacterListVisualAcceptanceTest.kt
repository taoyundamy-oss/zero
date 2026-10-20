package com.zero.v2.app.acceptance

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.luminance
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.*
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.core.data.entity.CharacterEntity
import com.zero.v2.framework.chat.StateEventItem
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Production list renderer; callbacks isolated from personal characters and module settings. */
class CharacterListVisualAcceptanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val out=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n:AccessibilityNodeInfo?) { if(n==null || !n.refresh()) return;out.add(n);for(i in 0 until n.childCount)visit(n.getChild(i)) }
        // Split peers can retain active-window focus after a semantic click; inspect the fixture window.
        val root=inst.uiAutomation.windows.firstNotNullOfOrNull { window ->
            window.root?.takeIf { it.packageName?.toString()==inst.targetContext.packageName }
        } ?: inst.uiAutomation.rootInActiveWindow
        visit(root);return out
    }
    private fun find(label:String, back:Boolean=false): AccessibilityNodeInfo {
        repeat(150) { attempt ->
            val snapshot=nodes()
            (snapshot.firstOrNull { it.isVisibleToUser && it.contentDescription?.toString()==label }
                ?: snapshot.firstOrNull { it.isVisibleToUser && it.text?.toString()?.contains(label)==true })?.let { return it }
            if(attempt%3==2) nodes().firstOrNull { it.isScrollable }?.performAction(if(back) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(60)
        }
        error("Missing isolated role control: $label")
    }
    private fun owner(label:String, back:Boolean=false):AccessibilityNodeInfo {
        repeat(150) { attempt ->
            val matching=nodes().filter { it.isVisibleToUser &&
                (it.contentDescription?.toString()==label || it.text?.toString()==label) }
            for(node in matching) {
                var target:AccessibilityNodeInfo?=node
                while(target!=null && !target.isClickable) target=target.parent
                if(target!=null && target.isVisibleToUser) return target
            }
            if(attempt%3==2) nodes().firstOrNull { it.isScrollable }?.performAction(
                if(back) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(60)
        }
        error("Missing actionable role control: $label; visible="+nodes().filter { it.isVisibleToUser }
            .mapNotNull { (it.text ?: it.contentDescription)?.toString()?.take(50) }.take(30))
    }
    private fun click(label:String,activity:VisualAcceptanceActivity,back:Boolean=false) {
        var n=owner(label,back)
        // Large state cards exceed the viewport. Show-on-screen may expose only a clipped semantic rect.
        n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id);Thread.sleep(400);n=owner(label,back)
        for(attempt in 0 until 8) {
            val frame=SplitWindowAcceptance.bounds(activity)
            val rect=Rect().also(n::getBoundsInScreen)
            val density=activity.resources.displayMetrics.density
            if(frame.contains(rect) && rect.width()/density>=48 && rect.height()/density>=48) break
            val y=frame.centerY().toFloat()
            val distance=80*density*(if(rect.centerY()>frame.centerY()) -1 else 1)
            val down=android.os.SystemClock.uptimeMillis()
            for(step in 0..10) {
                val action=when(step) { 0->android.view.MotionEvent.ACTION_DOWN;10->android.view.MotionEvent.ACTION_UP;else->android.view.MotionEvent.ACTION_MOVE }
                val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,
                    frame.centerX().toFloat(),y+distance*step/10,0)
                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(inst.uiAutomation.injectInputEvent(event,true)) } finally { event.recycle() }
                Thread.sleep(15)
            }
            Thread.sleep(300);n=owner(label,back)
        }
        val r=Rect().also(n::getBoundsInScreen);val d=activity.resources.displayMetrics.density
        assertTrue("Full control visible: $label",SplitWindowAcceptance.bounds(activity).contains(r))
        assertTrue("48dp control: $label bounds=$r density=$d",r.width()/d>=48 && r.height()/d>=48)
        assertTrue(n.isEnabled && n.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(150)
    }
    private fun shot(activity:VisualAcceptanceActivity,folder:String,name:String) {
        Thread.sleep(200);val image=requireNotNull(inst.uiAutomation.takeScreenshot(activity.window))
        val f=File(inst.targetContext.filesDir,"a8-character-list/$folder/$name.png");f.parentFile!!.mkdirs()
        f.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };image.recycle()
    }
    private fun end() {
        repeat(100) {
            val viewport=nodes().firstOrNull { it.isScrollable } ?: error("Missing role list viewport")
            if(viewport.actionList.none { a -> a.id==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) return
            assertTrue(viewport.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD));Thread.sleep(80)
        }
        error("Role list actual end unreachable")
    }
    @Test fun roleListStateHistoryAndActionsInEightRealSplitConditions() {
        val report=org.json.JSONArray()
        for(scale in listOf(1f,2f)) for(dark in listOf(false,true)) for(dynamic in listOf(false,true)) {
            val folder="$scale-$dark-$dynamic"
            VisualAcceptanceActivity.fontScaleForNextLaunch=scale
            var activity=inst.startActivitySync(SplitWindowAcceptance.launchIntent(inst.targetContext)) as VisualAcceptanceActivity
            val roles=(0..5).map { CharacterEntity(id="isolated-$it",name="角色$it · 长名称用于核对窄屏完整阅读与操作布局",
                description="角色$it 的完整描述。\n".repeat(5)+"完整描述结尾 $it",systemPrompt="isolated",isPreset=it==0,isActive=it==0) }
            val data=mutableStateOf(roles)
            val actions=mutableStateOf(CharacterActionState(loading=false))
            val state=mutableStateOf(CharacterStateUiState(activeCharacterId="isolated-0",activeCharacterName=roles[0].name,loaded=true,
                recentEvents=listOf(StateEventItem("AFFINITY","独立变化依据：保留全文，不修改个人记录。",0))))
            val callbacks=mutableListOf<String>()
            val commands=CharacterPageActions({callbacks.add("create")},{callbacks.add("edit:${it.id}")},{}, {_,_,_->},
                {callbacks.add("activate:$it")},{callbacks.add("delete:${it.id}")},{callbacks.add("style:$it")},
                {callbacks.add("affinity")},{callbacks.add("mood")},{callbacks.add("reload")},{})
            fun mount() { inst.runOnMainSync { activity.setContent { ZeroTheme(darkTheme=dark,dynamicColor=dynamic) {
                val c=MaterialTheme.colorScheme
                for((fg,bg) in listOf(c.onSurface to c.surfaceContainerLow,c.onSurfaceVariant to c.surfaceContainerLow,c.error to c.background)) {
                    val a=fg.luminance();val b=bg.luminance();assertTrue((maxOf(a,b)+0.05f)/(minOf(a,b)+0.05f)>=4.5f)
                }
                CharacterManageContent(data.value,CharacterEditState.Hidden,state.value,actions.value,{callbacks.add("back")},commands)
            } } } }
            try {
                mount();SplitWindowAcceptance.ensure(activity)
                assertEquals(scale,activity.resources.configuration.fontScale,0f)
                assertTrue(activity.isInMultiWindowMode)
                find("角色与表达");shot(activity,folder,"head")
                click("返回",activity);click("新建角色",activity)
                click("动态角色表达",activity);click("查看变化依据",activity)
                find("独立变化依据");shot(activity,folder,"history")
                activity=SplitWindowAcceptance.recreate(activity,scale);mount()
                // Locate from the restored region, then verify fold state survived actual recreation.
                find("收起变化依据",back=true);find("独立变化依据");shot(activity,folder,"history-restored")
                click("重置亲密度",activity);click("重置情绪",activity)
                find("完整描述结尾 5")
                click("使用角色 isolated-5",activity);click("编辑角色 isolated-5",activity);click("删除角色 isolated-5",activity)
                end();shot(activity,folder,"tail")
                activity=SplitWindowAcceptance.recreate(activity,scale);mount()
                var tailRestored=false
                repeat(30) { if(nodes().any { it.isVisibleToUser && it.contentDescription?.toString()=="删除角色 isolated-5" }) tailRestored=true else Thread.sleep(100) }
                assertTrue("Last role must restore without scrolling",tailRestored)
                assertTrue(nodes().first { it.isScrollable }.actionList.none { a -> a.id==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD })
                shot(activity,folder,"tail-restored")
                assertEquals(listOf("back","create","style:false","affinity","mood","activate:isolated-5","edit:isolated-5","delete:isolated-5"),callbacks)
                inst.runOnMainSync { actions.value=actions.value.copy(busy=true) }
                assertFalse(owner("新建角色",back=true).isEnabled)
                assertFalse(owner("动态角色表达").isEnabled)
                assertFalse(owner("重置亲密度").isEnabled)
                shot(activity,folder,"busy")
                inst.runOnMainSync { actions.value=actions.value.copy(busy=false);state.value=state.value.copy(error="隔离状态读取失败") }
                find("隔离状态读取失败",back=true);assertFalse(owner("重置亲密度").isEnabled)
                shot(activity,folder,"state-error")
                inst.runOnMainSync { data.value=emptyList();actions.value=CharacterActionState(loading=false,readError="隔离读取失败") }
                find("隔离读取失败",back=true);assertTrue(nodes().none { it.text?.toString()?.contains("暂无角色")==true })
                click("重新读取角色",activity,back=true);assertEquals("reload",callbacks.last());shot(activity,folder,"read-error")
                inst.runOnMainSync { actions.value=CharacterActionState(loading=true);state.value=CharacterStateUiState() }
                find("正在读取角色",back=true);shot(activity,folder,"loading")
                inst.runOnMainSync { actions.value=CharacterActionState(loading=false) }
                find("暂无角色",back=true);shot(activity,folder,"empty")
                report.put(org.json.JSONObject().put("fontScale",scale).put("dark",dark).put("dynamic",dynamic)
                    .put("historyRestored",true).put("tailActions",true).put("readErrorAndEmptyDistinct",true))
            } finally { inst.runOnMainSync { activity.finish() } }
        }
        File(inst.targetContext.filesDir,"a8-character-list-report.json").writeText(report.toString(2))
    }
}
