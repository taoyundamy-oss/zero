package com.zero.v2.app.acceptance

import com.zero.v2.framework.chat.ChatBotModule
import com.zero.v2.services.llm.ModelSelector
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RegenerationAcceptanceEntryPoint {
    fun moduleDetailAcceptanceFactory(): ModuleDetailAcceptanceFactory
    fun automationAcceptanceFactory(): AutomationAcceptanceFactory
    fun automation(): com.zero.v2.services.module.AutomationModule
    fun musicAcceptanceFactory(): MusicAcceptanceFactory
    fun systemBus(): com.zero.v2.core.bus.SystemBus
    fun music(): com.zero.v2.app.music.MusicModule
    fun chatBot(): ChatBotModule
    fun modelSelector(): ModelSelector
    fun toolContextSelector(): com.zero.v2.core.moduleapi.ToolContextSelector
    fun traces(): com.zero.v2.core.trace.AgentTraceManager
    fun chatBus(): com.zero.v2.core.bus.ChatBus
    fun attachments(): com.zero.v2.app.data.AttachmentStore
    fun termux(): com.zero.v2.services.termux.TermuxBridge
    fun toolRegistrar(): com.zero.v2.core.moduleapi.ToolRegistrar
    fun contextBuilder(): com.zero.v2.framework.chat.ContextBuilderModule
    fun mcp(): com.zero.v2.services.mcp.McpClientModule
    fun files(): com.zero.v2.app.data.FilesModule
    fun lifecycleManager(): com.zero.v2.core.modulemanager.ModuleLifecycleManager
    fun dispatcher(): com.zero.v2.core.moduleapi.ToolDispatcher
    fun vision(): com.zero.v2.services.module.VisionModule
}
