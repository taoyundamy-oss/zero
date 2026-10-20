package com.zero.v2.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.core.data.dao.CharacterDao
import com.zero.v2.core.data.entity.CharacterEntity
import com.zero.v2.framework.chat.CharacterStateModule
import com.zero.v2.framework.chat.CharacterStateSnapshot
import com.zero.v2.framework.chat.StateEventItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import com.zero.v2.core.data.store.CharacterRepository
import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import javax.inject.Inject

/**
 * 编辑对话框状态 — 显式三态，消除 null 二义性。
 * （v3.6 修复: 旧实现用 null 同时表示"新建模式"与"无对话框"，
 *  导致列表非空时点新建无响应、进页面闪现创建对话框）
 */
sealed interface CharacterEditState {
    data object Hidden : CharacterEditState
    data object Creating : CharacterEditState
    data class Editing(val character: CharacterEntity) : CharacterEditState
}

/**
 * 动态角色状态 UI 状态（P3 / 设计§6）——角色页"动态角色表达"区数据源。
 *
 * 展示活跃角色当前亲密度 + 最近变化依据 + 动态表达开关；支持分别重置亲密度/情绪。
 */
data class CharacterStateUiState(
    val dynamicStyleEnabled: Boolean = true,
    val activeCharacterId: String? = null,
    val activeCharacterName: String? = null,
    val affinityLabel: String = "初识",
    val interactionCount: Int = 0,
    val recentEvents: List<StateEventItem> = emptyList(),
    val loaded: Boolean = false,
    val error: String? = null,
    val lastAffinityChangeAt: Long = 0L,
    val moodLabel: String = "平静",
    val moodExpiresAt: Long = 0L,
) {
    internal fun withSnapshot(characterId: String, snapshot: CharacterStateSnapshot?): CharacterStateUiState {
        if (activeCharacterId != characterId) return this
        return copy(
            affinityLabel = snapshot?.affinity?.label ?: "初识",
            interactionCount = snapshot?.interactionCount ?: 0,
            recentEvents = snapshot?.recentEvents ?: emptyList(),
            loaded = true,
            error = if (snapshot == null) "角色状态读取失败，请重新进入此页重试" else null,
            lastAffinityChangeAt = snapshot?.lastAffinityChangeAt ?: 0L,
            moodLabel = snapshot?.latestMood?.label ?: "平静",
            moodExpiresAt = snapshot?.moodExpiresAt ?: 0L,
        )
    }
}

data class CharacterActionState(
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val loading: Boolean = true,
    val readError: String? = null,
)

@HiltViewModel
class CharacterManageViewModel internal constructor(
    private val characterDao: CharacterDao,
    /** P3：动态角色状态模块——读快照/开关/重置 */
    private val characterStateModule: CharacterStateActions,
    private val writes: CharacterRepository = CharacterRepository(characterDao),
) : ViewModel() {
    @Inject constructor(dao: CharacterDao, module: CharacterStateModule, lifecycle: ModuleLifecycleManager, writes: CharacterRepository) :
        this(dao, ModuleCharacterStateActions(module, lifecycle), writes)

    private val _actionState = MutableStateFlow(CharacterActionState())
    val actionState: StateFlow<CharacterActionState> = _actionState.asStateFlow()
    private var readJob: Job? = null
    private var readGeneration = 0L

    fun dismissFeedback() { _actionState.value = _actionState.value.copy(error = null, message = null) }

    private fun perform(message: String, block: suspend () -> Unit) {
        if (_actionState.value.busy) return
        _actionState.value = _actionState.value.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            try {
                block()
                coroutineContext.ensureActive()
                _actionState.value = _actionState.value.copy(message = message)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _actionState.value = _actionState.value.copy(error = "操作未完成，请重新读取后重试") }
            finally { _actionState.value = _actionState.value.copy(busy = false) }
        }
    }

    private val _characters = MutableStateFlow<List<CharacterEntity>>(emptyList())
    val characters: StateFlow<List<CharacterEntity>> = _characters.asStateFlow()

    private val _editState = MutableStateFlow<CharacterEditState>(CharacterEditState.Hidden)
    val editState: StateFlow<CharacterEditState> = _editState.asStateFlow()

    private val _stateUi = MutableStateFlow(CharacterStateUiState())
    val stateUi: StateFlow<CharacterStateUiState> = _stateUi.asStateFlow()

    fun reloadCharacters() {
        readJob?.cancel()
        val generation = ++readGeneration
        _actionState.value = _actionState.value.copy(loading = true, readError = null)
        readJob = viewModelScope.launch {
            try {
                characterDao.observeAll().collectLatest { list ->
                    _characters.value = list
                    _actionState.value = _actionState.value.copy(loading = false, readError = null)
                    // 活跃角色变化 → 重载动态状态快照
                    val active = list.firstOrNull { it.isActive }
                    _stateUi.value = _stateUi.value.copy(
                        activeCharacterId = active?.id,
                        activeCharacterName = active?.name,
                        affinityLabel = "初识", interactionCount = 0,
                        recentEvents = emptyList(), loaded = false, error = null,
                    )
                    if (active != null) loadStateSnapshot(active.id) else
                        _stateUi.value = _stateUi.value.copy(
                            affinityLabel = "初识", interactionCount = 0,
                            recentEvents = emptyList(), loaded = true
                        )
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _actionState.value = _actionState.value.copy(readError = "角色列表读取失败，已保留上次列表，请重试") }
            finally { if (generation == readGeneration) _actionState.value = _actionState.value.copy(loading = false) }
        }
    }

    init {
        reloadCharacters()
        // 同步动态表达开关到 UI（模块 own config，onAwake 后就绪）
        viewModelScope.launch {
            characterStateModule.dynamicStyleEnabled.collect { enabled ->
                _stateUi.value = _stateUi.value.copy(dynamicStyleEnabled = enabled)
            }
        }
    }

    /** 拉取活跃角色当前亲密度 + 最近变化依据事件。 */
    private suspend fun loadStateSnapshot(characterId: String) {
        val snap: CharacterStateSnapshot? = try { characterStateModule.snapshot(characterId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        coroutineContext.ensureActive()
        _stateUi.value = _stateUi.value.withSnapshot(characterId, snap)
    }

    fun startCreate() {
        if (!_actionState.value.busy) _editState.value = CharacterEditState.Creating
    }

    fun startEdit(character: CharacterEntity) {
        if (!_actionState.value.busy) _editState.value = CharacterEditState.Editing(character)
    }

    fun cancelEdit() {
        if (!_actionState.value.busy) _editState.value = CharacterEditState.Hidden
    }

    fun saveCharacter(name: String, description: String?, systemPrompt: String) {
        if (_actionState.value.busy) return
        val editor = _editState.value
        if (editor == CharacterEditState.Hidden || name.isBlank() || systemPrompt.isBlank()) {
            _actionState.value = _actionState.value.copy(error = "请填写角色名称与角色说明")
            return
        }
        perform("已保存角色") {
            when (editor) {
                is CharacterEditState.Editing -> writes.update(editor.character.id, name.trim(), description?.trim(), systemPrompt.trim())
                CharacterEditState.Creating -> writes.create(CharacterEntity(id = UUID.randomUUID().toString(), name = name.trim(), description = description?.trim(), systemPrompt = systemPrompt.trim()))
                CharacterEditState.Hidden -> error("角色编辑器已关闭")
            }
            coroutineContext.ensureActive()
            _editState.value = CharacterEditState.Hidden
        }
    }

    fun deleteCharacter(character: CharacterEntity) = perform("已删除角色") { writes.delete(character.id) }
    fun setActive(id: String) = perform("已切换角色") { writes.activate(id) }
    fun setDynamicStyleEnabled(enabled: Boolean) = perform("已更新动态表达") { characterStateModule.setDynamicStyleEnabled(enabled) }

    fun resetAffinity() {
        val id = _stateUi.value.activeCharacterId ?: return
        perform("已重置亲密度") { characterStateModule.resetRelationForCharacter(id); loadStateSnapshot(id) }
    }

    fun resetMood() {
        val id = _stateUi.value.activeCharacterId ?: return
        perform("已重置情绪") { characterStateModule.resetMoodForCharacter(id); loadStateSnapshot(id) }
    }

    /** 插入预设角色（如果数据库中没有角色时调用） */
    fun insertPresetsIfEmpty() {
        perform("角色列表已就绪") {
            val presets = listOf(
                CharacterEntity(
                    id = "preset_default",
                    name = "默认助手",
                    description = "通用 AI 助手，简洁实用",
                    systemPrompt = "你是 Zero，一个智能 AI 助手。请用简洁的中文回复用户。",
                    isPreset = true
                ),
                CharacterEntity(
                    id = "preset_coder",
                    name = "编程专家",
                    description = "擅长代码编写和技术问题解答",
                    systemPrompt = "你是一位资深软件工程师。用中文回答技术问题，提供清晰的代码示例和解释。",
                    isPreset = true
                ),
                CharacterEntity(
                    id = "preset_creative",
                    name = "创意伙伴",
                    description = "富有想象力的对话伙伴",
                    systemPrompt = "你是一个富有创意和想象力的 AI 伙伴。用生动有趣的方式与用户交流，激发灵感。",
                    isPreset = true
                )
            )
            writes.insertPresetsIfEmpty(presets)
        }
    }
}
