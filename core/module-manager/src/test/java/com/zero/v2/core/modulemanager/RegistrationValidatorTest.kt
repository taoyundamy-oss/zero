package com.zero.v2.core.modulemanager

import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.moduleapi.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertThrows
import org.junit.Test

class RegistrationValidatorTest {
    @Test
    fun rejectsDuplicateModuleIds() {
        val modules = listOf(fakeModule("calendar"), fakeModule("calendar"))
        assertThrows(IllegalArgumentException::class.java) {
            RegistrationValidator.validateModules(modules)
        }
    }

    @Test
    fun rejectsInvalidFunctionNameAndUnknownSource() {
        val entries = listOf(
            FunctionEntry(
                definition = FunctionDefinition("bad name", ""),
                sourceModuleId = "missing",
                handler = { _: Map<String, Any?> -> FunctionResult.success("") }
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            RegistrationValidator.validateFunctions(entries, setOf("calendar"))
        }
    }

    @Test
    fun acceptsRegisteredTraceSourceAndReservedSource() {
        val modules = setOf("calendar")
        assert(RegistrationValidator.validateTraceSource("calendar", modules))
        assert(RegistrationValidator.validateTraceSource("system", modules))
        assert(!RegistrationValidator.validateTraceSource("unknown", modules))
    }

    private fun fakeModule(id: String): IZeroModule = object : IZeroModule {
        private val state = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
        override val metadata = ModuleMetadata(id, id, id, ModuleCategory.SYSTEM)
        override val lifecycle = state.asStateFlow()
        override var isEnabled = true
        override suspend fun onAwake() {}
        override suspend fun onStart() {}
        override suspend fun onDestroy() {}
        override val config = object : ModuleConfigBase() {
            override fun toJson() = "{}"
            override fun fromJson(json: String) {}
            override fun defaultConfig() = this
        }
        override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}

        override suspend fun checkAvailability() = true
        override val requiredPermissions = emptyList<String>()
        override fun markDisabled() {}
    }
}
