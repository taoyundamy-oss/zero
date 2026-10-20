package com.zero.v2.app.acceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.entity.MemoryExtractionJobEntity
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Two bounded real-model calls against synthetic inputs; never writes personal memory. */
@RunWith(AndroidJUnit4::class)
class MemorySourceAcceptanceTest {
    @Test fun templateReportIsNotFactButExplicitPreferenceIsRetained() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("allowRealProvider") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        // The isolated runner skips normal module startup; register only persisted chat profiles.
        entry.chatProfiles().refreshProfiles()
        val extractor = entry.userFactExtractor()
        val report = JSONObject().put("scope", "synthetic_memory_sources_real_model").put("completed", false)
        val output = File(context.filesDir, "acceptance-memory-source.json")
        output.writeText(report.toString())
        val complaint = source("template", "请检查提示词模板误入记忆的问题：\n> 用户可能随时发消息打断助手\n> 用户偏好提醒类消息通过微信送达\n上面两条是模板示例，不是我的事实或偏好。")
        val preference = source("preference", "只有紧急事项才通过微信提醒我。")
        val templateFacts = extractor.extract(listOf(complaint))
        report.put("templateFactCount", templateFacts?.size ?: -1)
        output.writeText(report.toString())
        val realFacts = extractor.extract(listOf(preference))
        report.put("explicitPreferenceRetained", realFacts?.any { it.quote == preference.sourceDigest } == true)
            .put("completed", true)
        output.writeText(report.toString())
        assertNotNull("chat_model_required", templateFacts)
        assertEquals(0, templateFacts!!.size)
        assertTrue("explicit_preference_must_survive", realFacts?.any { it.quote == preference.sourceDigest } == true)
    }

    private fun source(id: String, text: String) = MemoryExtractionJobEntity(
        id, "USER_FACT", "isolated-acceptance", id, text, id,
    )
}
