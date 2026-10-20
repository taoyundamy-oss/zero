package com.zero.v2.app.ui.settings

import com.zero.v2.framework.chat.CharacterStateModule
import com.zero.v2.framework.chat.CharacterStateSnapshot
import kotlinx.coroutines.flow.StateFlow
import com.zero.v2.core.modulemanager.ModuleLifecycleManager

/** UI seam; the character module remains the owner of expression state. */
internal interface CharacterStateActions {
    val dynamicStyleEnabled: StateFlow<Boolean>
    suspend fun snapshot(characterId: String): CharacterStateSnapshot?
    suspend fun setDynamicStyleEnabled(enabled: Boolean)
    suspend fun resetRelationForCharacter(characterId: String)
    suspend fun resetMoodForCharacter(characterId: String)
}

internal class ModuleCharacterStateActions(private val module: CharacterStateModule, private val lifecycle: ModuleLifecycleManager) : CharacterStateActions {
    override val dynamicStyleEnabled get() = module.dynamicStyleEnabled
    override suspend fun snapshot(characterId: String) = lifecycle.safeCall(module, "ui_character_snapshot") { module.snapshot(characterId) }
    override suspend fun setDynamicStyleEnabled(enabled: Boolean) {
        checkNotNull(lifecycle.safeCall(module, "ui_character_style") { module.setDynamicStyleEnabled(enabled) }) { "动态表达设置失败" }
    }
    override suspend fun resetRelationForCharacter(characterId: String) {
        checkNotNull(lifecycle.safeCall(module, "ui_character_affinity") { module.resetRelationForCharacter(characterId) }) { "亲密度重置失败" }
    }
    override suspend fun resetMoodForCharacter(characterId: String) {
        checkNotNull(lifecycle.safeCall(module, "ui_character_mood") { module.resetMoodForCharacter(characterId) }) { "情绪重置失败" }
    }
}
