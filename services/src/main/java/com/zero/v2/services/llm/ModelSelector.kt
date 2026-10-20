package com.zero.v2.services.llm

import com.zero.v2.core.common.InputModality
import com.zero.v2.core.common.ModelRequirements
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模型选择器 — Zero v2 Phase 1 / M2 能力路由。
 *
 * M2：按 [ModelRequirements] 路由——输入模态映射到必需 [ModelCapability]（TEXT→CHAT，
 * IMAGE→IMAGE_INPUT，needsTools→FUNCTION_CALLING），筛 enabled+keyConfigured+能力超集+上下文够的候选，
 * 按用户优先级/上下文/确定性 tie-break。**不把 CHAT 等同 IMAGE_INPUT**；缺能力返回 [SelectionResult]
 * 含回退原因（不静默选错）。不因 modelId 字符串含"vl"自动声明能力——能力来自 profile 显式声明。
 *
 * 旧 [select]（Set<ModelCapability>）保留迁移兼容。
 */
@Singleton
class ModelSelector @Inject constructor() {

    @Volatile private var models: List<ILanguageModel> = emptyList()

    /** 注册一个语言模型 */
    @Synchronized
    fun register(model: ILanguageModel) {
        if (models.none { it.profileId == model.profileId }) {
            models = models + model
        }
    }

    /** Remove exactly one configuration, even if another connection serves the same model. */
    @Synchronized
    fun unregister(profileId: String) {
        models = models.filterNot { it.profileId == profileId }
    }

    /** Publish one complete owner snapshot so selection cannot observe half a refresh. */
    @Synchronized
    fun replaceProfiles(ownedIds: Set<String>, replacements: List<ILanguageModel>) {
        val incomingIds = replacements.map { it.profileId }
        require(incomingIds.distinct().size == incomingIds.size) { "duplicate_profile_identity" }
        val retained = models.filterNot { it.profileId in ownedIds }
        require(retained.none { it.profileId in incomingIds }) { "profile_identity_owned_elsewhere" }
        models = retained + replacements
    }

    /** 获取所有已注册且启用的模型 */
    fun getAll(): List<ILanguageModel> = models.filter { it.enabled }

    /**
     * M2：按 [ModelRequirements] 路由。返回 [SelectionResult]（含模型或回退原因）。
     *
     * 候选过滤：enabled && keyConfigured && 能力超集 && contextWindow ≥ minContextTokens。
     * tie-break：priority desc → contextWindow desc → modelId（确定性）。
     */
    fun select(requirements: ModelRequirements): SelectionResult {
        val requiredCaps = requiredCapabilities(requirements)
        val chosen = chooseCandidate(requiredCaps, requirements.minContextTokens)
        if (chosen == null) {
            return SelectionResult(null, models.noMatchReason(requirements, requiredCaps))
        }
        return SelectionResult(chosen, "selected: ${chosen.modelId} " +
            "caps=${chosen.capabilities.joinToString(",") { it.name }} priority=${chosen.priority}")
    }

    /** Fresh routing for internal callers that do not consume diagnostic prose. */
    fun selectModel(requirements: ModelRequirements): ILanguageModel? =
        if (models.isEmpty()) null else
            chooseCandidate(requiredCapabilities(requirements), requirements.minContextTokens)

    private fun chooseCandidate(requiredCaps: Set<ModelCapability>, minContextTokens: Int): ILanguageModel? {
        val candidates = models
            .filter { it.enabled && it.keyConfigured }
            .filter { m -> requiredCaps.all { it in m.capabilities } }
            .filter { it.contextWindow >= minContextTokens }
        return candidates.maxWithOrNull(
            // 升序比较器 + maxWithOrNull → 选最高 priority / 最大 contextWindow（descending 会让 max 选最低，故用升序）
            compareBy<ILanguageModel> { it.priority }
                .thenBy { it.contextWindow }
                .thenBy { it.modelId }.thenBy { it.profileId }
        )
    }

    /**
     * 旧路径：按能力集合选（迁移兼容；ChatBot 既有 select(setOf(CHAT)) 调用）。
     * M2 起也过滤 keyConfigured（缺 Key 不入候选）。
     */
    fun select(required: Set<ModelCapability>): ILanguageModel? {
        return models
            .filter { it.enabled && it.keyConfigured }
            .filter { model -> required.all { it in model.capabilities } }
            .maxWithOrNull(compareBy<ILanguageModel> { it.priority }
                .thenBy { it.contextWindow }.thenBy { it.modelId }.thenBy { it.profileId })
    }

    /** Legacy wire-model lookup: ambiguous names must not silently select another connection. */
    fun getById(modelId: String): ILanguageModel? =
        models.singleOrNull { it.modelId == modelId }

    fun getByProfileId(profileId: String): ILanguageModel? =
        models.firstOrNull { it.profileId == profileId }


}

/**
 * M2 路由结果——模型或回退原因（不静默选错；缺能力/缺 Key/无候选均给可解释原因）。
 */
data class SelectionResult(
    val model: ILanguageModel?,
    val reason: String,
) {
    val isSelected: Boolean get() = model != null
}

/** 输入模态 → 必需 ModelCapability（不把 CHAT 当 IMAGE_INPUT）。 */
private fun requiredCapabilities(req: ModelRequirements): Set<ModelCapability> = buildSet {
    if (InputModality.TEXT in req.input) add(ModelCapability.CHAT)
    if (InputModality.IMAGE in req.input) add(ModelCapability.IMAGE_INPUT)
    if (InputModality.AUDIO in req.input) add(ModelCapability.AUDIO_INPUT)
    if (InputModality.VIDEO in req.input) add(ModelCapability.VIDEO_INPUT)
    if (req.needsTools) add(ModelCapability.FUNCTION_CALLING)
}


/** 无匹配回退原因（解释为何回退，落实"缺能力解释回退"）。 */
private fun List<ILanguageModel>.noMatchReason(req: ModelRequirements, requiredCaps: Set<ModelCapability>): String {
    val disabled = count { !it.enabled }
    val noKey = count { it.enabled && !it.keyConfigured }
    val missing = filter { it.enabled && it.keyConfigured }
        .filter { m -> !requiredCaps.all { it in m.capabilities } }
        .map { "${it.modelId}(lacks:${(requiredCaps - it.capabilities).joinToString(",") { c -> c.name }})" }
    return "no_match: input=${req.input.joinToString(",") { it.name }} " +
        "required=${requiredCaps.joinToString(",") { it.name }} " +
        "disabled=$disabled noKey=$noKey missingFrom=${missing.joinToString(";")}"
}

