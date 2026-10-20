package com.zero.v2.services.realtime

import com.zero.v2.core.data.store.ModuleConfigStore
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.llm.LlmProfile
import com.zero.v2.services.llm.LlmProfileStore
import com.zero.v2.services.llm.MimoTtsLlmAdapter
import com.zero.v2.services.llm.OpenAiCompatibleClient
import com.zero.v2.services.security.CredentialStore
import javax.inject.Inject

/** Online speech boundary: routing, legacy credential/voice migration and PCM transport. */
class MimoSpeechProvider @Inject constructor(
    private val credentials: CredentialStore,
    private val client: OpenAiCompatibleClient,
    private val adapter: MimoTtsLlmAdapter,
    private val configs: ModuleConfigStore,
    private val profiles: LlmProfileStore,
    private val trace: AgentTraceManager,
) {
    fun resolveKey(config: SpeechTtsConfig): String? {
        val ref = config.mimoApiKeyRef
        if (ref.startsWith("sk-") && ref.length > MINIMUM_LEGACY_KEY_LENGTH) {
            credentials.put(KEY_REF, ref)
            config.mimoApiKeyRef = KEY_REF
            configs.updateConfigDebounced(MODULE_ID, config.toJson())
            trace.record("tts", NodeType.SYSTEM_EVENT, MODULE_ID, "mimo_key_migrated")
            return ref
        }
        return credentials.getOrFallbackString(ref, null) ?: credentials.getString(KEY_REF)
    }

    fun profile(config: SpeechTtsConfig): LlmProfile {
        val stored = profiles.byKind(LlmProfile.Kind.TTS).firstOrNull()
            ?: LlmProfile.defaults().first { it.id == "mimo-tts" }
        if (stored.voice == DEFAULT_VOICE && config.mimoVoice != DEFAULT_VOICE) {
            val migrated = stored.copy(voice = config.mimoVoice)
            profiles.upsert(migrated)
            trace.record("tts", NodeType.SYSTEM_EVENT, MODULE_ID, "tts_voice_migrated")
            return migrated
        }
        return stored
    }

    suspend fun streamPcm(
        profile: LlmProfile,
        text: String,
        shouldStop: () -> Boolean,
        apiKey: String?,
        onPcm: suspend (ByteArray) -> Unit,
    ) = client.ttsStreamPcm(profile, text, adapter, shouldStop, apiKey, onPcm)

    private companion object {
        const val MINIMUM_LEGACY_KEY_LENGTH = 20
        const val KEY_REF = "tts:mimo"
        const val MODULE_ID = "speech_tts"
        const val DEFAULT_VOICE = "mimo_default"
    }
}
