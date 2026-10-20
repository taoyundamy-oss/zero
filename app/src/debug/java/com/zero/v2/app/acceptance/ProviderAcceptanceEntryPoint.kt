package com.zero.v2.app.acceptance

import com.zero.v2.services.embedding.EmbeddingProvider
import com.zero.v2.core.trace.AgentTraceManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug instrumentation access only; no exported Android component or credential accessor. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProviderAcceptanceEntryPoint {
    fun lifecycleManager(): com.zero.v2.core.modulemanager.ModuleLifecycleManager
    fun chatGptAuth(): com.zero.v2.services.chatgpt.ChatGptAuth
    fun chatGptCatalog(): com.zero.v2.services.chatgpt.ChatGptCatalog
    fun workDirectory(): com.zero.v2.app.data.WorkDirRepository
    fun systemEvents(): com.zero.v2.services.module.SystemEventModule
    fun workTraces(): com.zero.v2.core.trace.AgentTraceManager
    fun releaseManager(): com.zero.v2.services.termux.ReleaseManager
    fun termuxBridge(): com.zero.v2.services.termux.TermuxBridge
    fun systemState(): com.zero.v2.core.moduleapi.SystemStateRepository
    fun llmClient(): com.zero.v2.services.llm.OpenAiCompatibleClient
    fun modelSelector(): com.zero.v2.services.llm.ModelSelector
    fun embeddingProvider(): EmbeddingProvider
    fun traces(): AgentTraceManager
    fun userFactExtractor(): com.zero.v2.framework.memory.UserFactExtractor
    fun chatProfiles(): com.zero.v2.services.llm.OpenAiChatModule
    fun embeddingCandidates(): EmbeddingCandidateFactory
    fun semanticReranker(): com.zero.v2.services.retrieval.BoundedSemanticReranker
    fun memoryModule(): com.zero.v2.framework.memory.MemoryModule
    fun knowledgeModule(): com.zero.v2.app.knowledge.KnowledgeModule
    fun database(): com.zero.v2.core.data.db.ZeroDatabase
    fun profiles(): com.zero.v2.services.llm.LlmProfileStore
    fun mediaAnalysis(): com.zero.v2.services.llm.MediaAnalysisService
    fun budget(): com.zero.v2.services.agent.BudgetGuard
}
