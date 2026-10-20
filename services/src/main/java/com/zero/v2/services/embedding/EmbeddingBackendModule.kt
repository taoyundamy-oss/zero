package com.zero.v2.services.embedding

import com.zero.v2.core.moduleapi.ConfigField
import com.zero.v2.core.moduleapi.ConfigFieldType
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.core.moduleapi.ModuleMetadata
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class EmbeddingBackendConfig : ModuleConfigBase() {
    @ConfigField(
        label = "向量后端",
        fieldType = ConfigFieldType.DROPDOWN,
        options = ["remote", "local"],
        description = "保留原远程配置。local使用已部署的BGE候选包，不可用时仅退词法，不自动上传；需重建并验证独立索引，手机验收后才建议切换。")
    var backend = "remote"

    override fun toJson() = JSONObject().put("backend", backend).toString()

    override fun fromJson(json: String) {
        backend =
            JSONObject(json).optString("backend", "remote").takeIf {
                it in setOf("local", "remote")
            } ?: "remote"
    }

    override fun defaultConfig() = EmbeddingBackendConfig()
}

@Singleton
class EmbeddingBackendModule @Inject constructor() : IZeroModule {
    override val metadata =
        ModuleMetadata(
            "embedding_backend",
            "向量后端",
            "本地候选模型与远程向量切换；不会自动切换或自动下载模型",
            ModuleCategory.SYSTEM,
            launchOrder = 20)
    private val state = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = state.asStateFlow()
    override var isEnabled = true
    override val requiredPermissions = emptyList<String>()
    override val config = EmbeddingBackendConfig()

    override suspend fun onAwake() {
        state.value = ModuleLifecycle.AWAKE
    }

    override suspend fun onStart() {
        state.value = ModuleLifecycle.STARTED
    }

    override suspend fun onDestroy() {
        state.value = ModuleLifecycle.DESTROYED
    }

    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) = Unit

    override suspend fun checkAvailability() = true

    override fun markDisabled() {
        state.value = ModuleLifecycle.DISABLED
    }
}

@Singleton
class SelectedEmbeddingProvider
@Inject
constructor(
    private val local: LocalBgeProvider,
    private val remote: RemoteEmbeddingProvider,
    private val settings: EmbeddingBackendModule
) : EmbeddingProvider {
    private fun selected(): EmbeddingProvider =
        if (settings.config.backend == "local") local else remote

    override val modelId
        get() = selected().modelId

    override val identity
        get() = selected().identity

    override suspend fun isAvailable() = settings.isEnabled && selected().isAvailable()

    override suspend fun embed(text: String) = embedBatch(listOf(text)).firstOrNull()

    override suspend fun embedQuery(text: String) =
        embedBatch(listOf(text), EmbeddingPurpose.QUERY).firstOrNull()

    override suspend fun embedBatch(texts: List<String>) =
        embedBatch(texts, EmbeddingPurpose.DOCUMENT)

    override suspend fun embedBatch(
        texts: List<String>,
        purpose: EmbeddingPurpose
    ): List<FloatArray?> {
        if (!settings.isEnabled) return texts.map { null }
        val provider = selected()
        val before = identity
        val result = provider.embedBatch(texts, purpose)
        return if (identity == before && settings.isEnabled) result else texts.map { null }
    }
}
