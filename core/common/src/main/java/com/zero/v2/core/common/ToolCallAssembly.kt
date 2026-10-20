package com.zero.v2.core.common

/** Opaque provider token. Never interpret it as arguments or include it in diagnostic text. */
class ToolProviderState(val scope: String, val googleThoughtSignature: String) {
    override fun equals(other: Any?): Boolean = other is ToolProviderState &&
        scope == other.scope && googleThoughtSignature == other.googleThoughtSignature
    override fun hashCode(): Int = 31 * scope.hashCode() + googleThoughtSignature.hashCode()
    override fun toString(): String = "ToolProviderState(redacted)"
}

/** SSE indices, not arrival order, identify parallel calls. Incomplete calls cannot execute. */
object ToolCallAssembly {
    fun append(calls: List<ToolCall>, delta: ToolCallDelta): List<ToolCall> {
        require(delta.index in 0 until MAX_CALLS) { "provider_tool_index_invalid" }
        val result = calls.toMutableList()
        val at = result.indexOfFirst { it.streamIndex == delta.index }
        val old = result.getOrNull(at) ?: ToolCall("", "", "", streamIndex = delta.index)
        require(delta.id == null || old.id.isEmpty() || delta.id == old.id) { "provider_tool_id_changed" }
        require(old.providerState == null || delta.providerState == null || old.providerState == delta.providerState) {
            "provider_tool_state_changed"
        }
        val next = old.copy(id = delta.id ?: old.id, name = delta.name ?: old.name,
            arguments = old.arguments + delta.argumentsDelta.orEmpty(),
            providerState = delta.providerState ?: old.providerState)
        if (at < 0) result.add(next) else result[at] = next
        return result.sortedBy { it.streamIndex }
    }

    private const val MAX_CALLS = 128
}
