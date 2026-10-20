package com.zero.v2.services.llm

sealed interface ProfileEdit {
    data class Save(val profile: LlmProfile, val updateShared: Boolean = false) : ProfileEdit
    data class Remove(val id: String) : ProfileEdit
}

/** Pure edits; the store commits the complete result before publishing it. */
object ProfileManagement {
    fun draft(id: String, source: LlmProfile? = null): LlmProfile = source?.copy(
        id = id, displayName = "${source.displayName} 副本", enabled = false, priority = 0,
    ) ?: LlmProfile(
        id = id, displayName = "新模型", baseUrl = "", modelId = "",
        connectionId = "connection:$id", apiKeyRef = "llm_connection:$id", enabled = false,
        contextWindow = DEFAULT_CONTEXT, maxTokens = DEFAULT_OUTPUT,
        capabilities = setOf(ModelCapability.CHAT),
    )

    fun apply(current: List<LlmProfile>, edit: ProfileEdit): List<LlmProfile> = when (edit) {
        is ProfileEdit.Save -> save(current, edit)
        is ProfileEdit.Remove -> {
            val target = requireNotNull(current.find { it.id == edit.id }) { "模型已不存在" }
            require(target.kind == LlmProfile.Kind.CHAT && !target.enabled) { "请先停用模型再删除" }
            current.filterNot { it.id == edit.id }
        }
    }

    private fun save(current: List<LlmProfile>, edit: ProfileEdit.Save): List<LlmProfile> {
        val profile = edit.profile
        require(profile.kind == LlmProfile.Kind.CHAT) { "本操作仅用于对话模型" }
        require(profile.id.isNotBlank() && profile.modelId.isNotBlank()) { "模型 ID 不能为空" }
        val uri = runCatching { java.net.URI(profile.baseUrl) }.getOrNull()
        require(uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank()) { "连接地址无效" }
        ModelContracts.get(profile.contractId).validate(profile)
        val connection = ConnectionProfile.from(profile)
        val peers = current.filter { it.id != profile.id && it.connectionId == connection.id }
        val changed = peers.any { ConnectionProfile.from(it) != connection }
        require(!changed || edit.updateShared) { "连接被其他模型引用；请选择同时更新共享连接或独立连接" }
        val next = current.map {
            when {
                it.id == profile.id -> connection.project(profile)
                changed && it.connectionId == connection.id -> connection.project(it)
                else -> it
            }
        }
        val result = if (current.any { it.id == profile.id }) next else next + connection.project(profile)
        result.filter { it.connectionId == connection.id }.forEach {
            require(CloudflareChatTransport.validationError(it) == null) { "共享连接与模型路由不兼容" }
            ModelContracts.get(it.contractId).validate(it)
        }
        return result
    }

    private const val DEFAULT_CONTEXT = 32_768
    private const val DEFAULT_OUTPUT = 2048
}
