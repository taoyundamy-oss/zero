package com.zero.v2.services.llm

import org.json.JSONArray
import org.json.JSONObject

/** One versioned document: CHAT connection fields have exactly one persisted owner. */
internal object ProfileSnapshot {
    private const val VERSION = 3
    private val connectionFields = listOf(
        "baseUrl", "apiKeyRef", "apiKeyRefFallback", "authMode", "authHeader",
        "cloudflareByok", "cloudflareGatewayId", "gatewayMode",
    )

    fun isCurrent(raw: String): Boolean = JSONObject(raw).getInt("schemaVersion") == VERSION

    fun encode(profiles: List<LlmProfile>): String {
        require(profiles.map { it.id }.distinct().size == profiles.size) { "重复模型配置 ID" }
        val connections = linkedMapOf<String, JSONObject>()
        val models = JSONArray()
        profiles.forEach { profile ->
            val model = LlmProfileJson.toJson(profile)
            if (profile.kind == LlmProfile.Kind.CHAT) {
                val id = ConnectionProfile.from(profile).id
                val connection = JSONObject().put("id", id).put("protocol", "openai-chat")
                moveConnectionFields(model, connection)
                val previous = connections.put(id, connection)
                require(previous == null || connectionFields.all { previous.opt(it) == connection.opt(it) }) {
                    "共享连接配置冲突"
                }
                model.put("connectionId", id)
            }
            models.put(model)
        }
        return JSONObject().put("schemaVersion", VERSION)
            .put("connections", JSONArray(connections.values.toList())).put("profiles", models).toString()
    }

    private fun moveConnectionFields(model: JSONObject, connection: JSONObject) {
        connectionFields.filter { model.has(it) }.forEach { connection.put(it, model.remove(it)) }
    }

    fun decode(raw: String): List<LlmProfile> {
        val root = JSONObject(raw)
        require(root.getInt("schemaVersion") in 1..VERSION) { "不支持的模型配置版本；保留原数据" }
        val connections = root.getJSONArray("connections")
        val byId = linkedMapOf<String, JSONObject>()
        for (i in 0 until connections.length()) {
            val connection = connections.getJSONObject(i)
            require(connection.getString("protocol") == "openai-chat")
            require(byId.put(connection.getString("id"), connection) == null) { "重复连接 ID" }
        }
        val models = root.getJSONArray("profiles")
        val result = (0 until models.length()).map { i ->
            val model = models.getJSONObject(i)
            if (model.getString("kind") == "CHAT") {
                val connection = requireNotNull(byId[model.getString("connectionId")]) { "模型连接引用不存在" }
                connectionFields.forEach { field ->
                    require(!model.has(field)) { "模型内存在重复连接字段" }
                    if (connection.has(field)) model.put(field, connection.get(field))
                }
            }
            LlmProfileJson.fromJson(model)
        }
        require(result.all { it.id.isNotBlank() } && result.map { it.id }.distinct().size == result.size)
        return result
    }

    fun legacy(raw: String): List<LlmProfile> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { LlmProfileJson.fromJson(array.getJSONObject(it)) }
    }
}
