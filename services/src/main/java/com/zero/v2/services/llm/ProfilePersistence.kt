package com.zero.v2.services.llm

/** Failed reads/writes freeze this owner; never replace damaged user data with defaults. */
internal class ProfilePersistence(
    snapshot: String?,
    legacy: String?,
    private val commit: (String) -> Boolean,
) {
    var profiles: List<LlmProfile> = emptyList()
        private set
    var error: String? = null
        private set

    init {
        runCatching {
            val loaded = when {
                snapshot != null -> ProfileSnapshot.decode(snapshot)
                legacy != null -> ProfileSnapshot.legacy(legacy)
                else -> LlmProfile.defaults()
            }
            // Never upgrade model IDs or parameters as a side effect of schema migration.
            profiles = ProfileSnapshot.decode(ProfileSnapshot.encode(loaded))
            if (snapshot == null || !ProfileSnapshot.isCurrent(snapshot)) save(profiles)
        }.onFailure { if (error == null) error = "模型配置读取失败，原数据已保留" }
    }

    @Synchronized
    fun save(next: List<LlmProfile>) {
        check(error == null) { error.orEmpty() }
        val encoded = ProfileSnapshot.encode(next)
        val projected = ProfileSnapshot.decode(encoded)
        if (!runCatching { commit(encoded) }.getOrDefault(false)) {
            error = "模型配置写入失败；未发布修改，请重启后重试"
            throw java.io.IOException(error)
        }
        profiles = projected
    }
}
