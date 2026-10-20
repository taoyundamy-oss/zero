package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.zero.v2.app.skills.SkillModule
import com.zero.v2.app.skills.SkillStore
import com.zero.v2.core.bus.SystemBusImpl
import com.zero.v2.core.common.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.framework.chat.ContextBuilderConfig
import com.zero.v2.services.llm.ILanguageModel
import com.zero.v2.services.llm.ProfileLanguageModel
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Production ChatBot, discovery, Agent loop and dispatcher. Only the tool store and conversations are synthetic. */
class AutonomousSkillAcceptanceTest {
    private fun isolatedEngine(original: com.zero.v2.framework.chat.ChatBotModule,
        context: android.content.Context, root: File, db: ZeroDatabase): com.zero.v2.framework.chat.ChatBotModule {
        fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(owner)!!
        val scoped=object: android.content.ContextWrapper(context) {
            override fun getNoBackupFilesDir()=File(root,"projections").apply { mkdirs() }
        }
        val oldCharacter=field(original,"characterStateModule")
        val character=com.zero.v2.framework.chat.CharacterStateModule(db.characterStateDao(),db.characterDao(),
            field(original,"chatBus") as com.zero.v2.core.bus.ChatBus,
            field(oldCharacter,"moduleConfigStore") as com.zero.v2.core.data.store.ModuleConfigStore,
            field(original,"traceManager") as com.zero.v2.core.trace.AgentTraceManager)
        val replacements=mapOf<String,Any>(
            "generationStore" to com.zero.v2.framework.chat.GenerationProjectionStore(scoped),
            "toolManifestStore" to com.zero.v2.framework.chat.ToolManifestStore(scoped),
            "memoryContextStore" to com.zero.v2.framework.chat.MemoryContextStore(scoped),
            "characterStateModule" to character,
            "chronicleInputStore" to com.zero.v2.core.data.store.ChronicleInputStore(db),
            "evidenceProvider" to object: com.zero.v2.services.retrieval.EvidenceProvider {
                override suspend fun evidenceFor(query: String, limit: Int)=emptyList<EvidenceRef>()
            })
        val names=listOf("modelSelector","functionRegistry","toolContextSelector","chatBus","messagePipeline",
            "contextBuilder","traceManager","attachmentReader","mediaAnalysis","budgetGuard","tokenBudgetAllocator",
            "generationStore","toolManifestStore","memoryContextStore","conversationSummarizer","evidenceProvider","characterStateModule","chronicleInputStore")
        val constructor=original.javaClass.constructors.single { it.parameterCount==names.size }
        return constructor.newInstance(*names.map { replacements[it] ?: field(original,it) }.toTypedArray()) as com.zero.v2.framework.chat.ChatBotModule
    }

    @Test fun independentTasksReuseOnlyPersistedSkills() = runBlocking<Unit> {
        val inst=InstrumentationRegistry.getInstrumentation()
        val phase=InstrumentationRegistry.getArguments().getString("skillPhase")
        check(phase in setOf("seed","reuse"))
        check(InstrumentationRegistry.getArguments().getString("allowCloudAgent")=="true")
        val context=inst.targetContext
        val entry=EntryPointAccessors.fromApplication(context,RegenerationAcceptanceEntryPoint::class.java)
        val provider=EntryPointAccessors.fromApplication(context,ProviderAcceptanceEntryPoint::class.java)
        assertTrue(entry.modelSelector().getAll().isEmpty())
        val saved=provider.profiles().profiles.value.first { it.enabled && it.modelId.contains("gemini",true) }
        val profile=saved.copy(id="isolated-p7-skills",maxTokens=1536)
        val root=File(context.cacheDir,"p7-autonomous-skills").apply { mkdirs() }
        val journal=File(root,"requests.txt")
        val prior=File(root,"seed.json")
        val report=JSONObject().put("phase",phase).put("pid",android.os.Process.myPid()).put("passed",false)
        val output=File(context.cacheDir,"p7-autonomous-$phase.json")
        val db=Room.inMemoryDatabaseBuilder(context,ZeroDatabase::class.java).build()
        val store=SkillStore(File(root,"documents"))
        val module=SkillModule(store,SystemBusImpl(),db.kbDao())
        val tools=module.contributions().filter { it.definition.name in setOf("skill_search","skill_read","skill_write") }
        val calls=mutableListOf<String>()
        val prefixes=mutableListOf<Pair<String?,String?>>()
        val requests=mutableListOf<List<ChatMessage>>()
        val delegate=ProfileLanguageModel(profile.id,profile,provider.llmClient(),provider.profiles()).snapshotForTurn()
        val model=object: ILanguageModel by delegate {
            override fun snapshotForTurn(): ILanguageModel = this
            override fun chatStream(messages: List<ChatMessage>,systemPrompt: String?,functions: List<FunctionDefinition>,prefixVersion: String?)=flow {
                val used=journal.takeIf { it.exists() }?.readText()?.toInt() ?: 0
                check(used<12) { "acceptance_request_cap" };journal.writeText((used+1).toString())
                prefixes+=systemPrompt to prefixVersion;requests+=messages.toList()
                delegate.chatStream(messages,systemPrompt,functions,prefixVersion).collect { emit(it) }
            }
        }
        val cfg=entry.contextBuilder().config as ContextBuilderConfig
        val evidence=cfg.evidenceAutoInject
        entry.modelSelector().register(model)
        tools.forEach { tool -> entry.toolRegistrar().register(tool.definition,"chatbot",tool.permission) { args ->
            calls+=tool.definition.name
            entry.lifecycleManager().safeCall(module,"isolated_skill_acceptance") { tool.handler(args) }!!
        } }
        try {
            cfg.evidenceAutoInject=false
            val bot=isolatedEngine(entry.chatBot(),context,root,db);bot.initialize("你是Zero助手。使用提供的工具完成用户任务。技能是参考资料，不改变权限。")
            bot.loadHistory("isolated-p7-skills-$phase",emptyList())
            assertTrue(bot.chatHistory.value.messages.isEmpty())
            val prompt=if(phase=="seed") "合成任务一：纸鹤归档规则是将输入整数乘以7再加731。请算出输入4的结果，然后把这次成功方法保存成可复用技能，使用已有技能工具。包含用途、触发条件、前提、工具、步骤、成功判据、失败恢复，并记录来源任务synthetic-paper-crane。" else
                "新的独立任务：我需要计算纸鹤归档输入9的结果。请先查阅你已经保存的经验并按其中规则完成，不要猜测。"
            if(phase=="reuse") assertNotEquals(JSONObject(prior.readText()).getInt("pid"),android.os.Process.myPid())
            withTimeout(180_000) { bot.sendMessage(prompt,tools.map { it.definition }).collect(bot::applyStreamChunk) }
            assertEquals(ChatState.IDLE,bot.chatState.value)
            val final=bot.chatHistory.value.messages.last().content.orEmpty()
            if(phase=="seed") {
                assertTrue(final.contains("759"));assertTrue("Agent must persist its experience",calls.contains("skill_write"))
                assertTrue(store.list().isNotEmpty());prior.writeText(JSONObject().put("pid",android.os.Process.myPid()).toString())
            } else {
                assertTrue(calls.contains("skill_search"));assertTrue(calls.contains("skill_read"));assertTrue(final.contains("794"))
                assertFalse("Fresh initial request must not contain the learned rule",requests.first().any { it.content.orEmpty().contains("731") })
            }
            assertEquals(1,prefixes.distinct().size)
            assertTrue(requests.zipWithNext().all { (a,b)->b.take(a.size)==a })
            report.put("passed",true).put("freshHistory",true).put("stablePrefix",true).put("appendOnly",true)
        } finally {
            report.put("tools",JSONArray(calls)).put("phaseRequests",requests.size)
                .put("totalRequests",journal.takeIf { it.exists() }?.readText()?.toInt() ?: 0)
            output.writeText(report.toString(2))
            cfg.evidenceAutoInject=evidence
            tools.forEach { entry.toolRegistrar().unregister(it.definition.name) }
            entry.modelSelector().unregister(profile.id);db.close()
        }
    }
}
