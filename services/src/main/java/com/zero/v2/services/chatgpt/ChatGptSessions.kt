package com.zero.v2.services.chatgpt

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** One serialized snapshot owner. Network results carry a generation and never publish after logout/switch. */
internal class ChatGptSessions(private val storage: ChatGptStorage) {
    private val mutex = Mutex()
    private var snapshot: JSONObject? = null
    private var generation = 0L
    private val signingOut = mutableSetOf<String>()
    class Ticket(val generation: Long, val host: String, val account: JSONObject?, val activate: Boolean = true)
    private fun load(): JSONObject {
        snapshot?.let { return it }
        val value = storage.read()
        if (!value.has("host")) {
            value.put("host", "urn:uuid:${UUID.randomUUID()}").put("accounts", JSONArray())
            storage.write(value)
        }
        snapshot = value
        return value
    }
    private fun accounts(value: JSONObject): List<JSONObject> = value.getJSONArray("accounts").let { a ->
        (0 until a.length()).map(a::getJSONObject)
    }
    suspend fun view(): JSONObject = mutex.withLock { JSONObject(load().toString()) }
    suspend fun begin(client: String?): Ticket = mutex.withLock {
        val data = load()
        val account = client?.let { id -> accounts(data).first { it.getString("client_id") == id } }
        generation++
        Ticket(generation, data.getString("host"), account?.let { JSONObject(it.toString()) })
    }
    suspend fun invalidate() = mutex.withLock { generation++ }
    suspend fun startSignOut(client: String): Ticket = mutex.withLock {
        val data = load()
        val account = accounts(data).first { it.getString("client_id") == client }
        generation++
        signingOut.add(client)
        Ticket(generation, data.getString("host"), JSONObject(account.toString()), activate = false)
    }
    suspend fun select(client: String) = mutex.withLock {
        val data = JSONObject(load().toString())
        require(client !in signingOut && accounts(data).any {
            it.getString("client_id") == client && it.optString("access_token").isNotBlank()
        })
        generation++
        data.put("active", client)
        storage.write(data)
        snapshot = data
    }
    suspend fun credentials(client: String): Ticket = mutex.withLock {
        val data = load()
        check(client !in signingOut && data.optString("active") == client) { "ChatGPT account is not active" }
        val account = accounts(data).first { it.getString("client_id") == client }
        Ticket(generation, data.getString("host"), JSONObject(account.toString()), activate = false)
    }
    suspend fun save(ticket: Ticket, client: String, tokens: JSONObject, claims: JSONObject,
        now: Long) = mutex.withLock {
        check(generation == ticket.generation) { "Obsolete authorization result" }
        val data = JSONObject(load().toString())
        if (!ticket.activate) check(client !in signingOut && data.optString("active") == client)
        val old = accounts(data).find { it.getString("client_id") == client }
        val subject = claims.getString("sub")
        ticket.account?.let { require(it.getString("client_id") == client && it.getString("subject") == subject) }
        old?.let { require(it.getString("subject") == subject) }
        require(tokens.getString("token_type").equals("Bearer", true))
        require(tokens.getString("access_token").isNotBlank() && tokens.getLong("expires_in") > 0)
        val record = JSONObject().put("client_id", client).put("subject", subject)
            .put("email", claims.optString("email", old?.optString("email").orEmpty()))
            .put("access_token", tokens.getString("access_token"))
            .put("refresh_token", tokens.optString("refresh_token", old?.optString("refresh_token").orEmpty()))
            .put("id_token", tokens.optString("id_token", old?.optString("id_token").orEmpty()))
            .put("scope", tokens.optString("scope", old?.optString("scope").orEmpty()))
            .put("expires_at", Math.addExact(now, tokens.getLong("expires_in")))
            .put("earliest_refresh_at", tokens.optLong("earliest_refresh_at", 0))
        val next = accounts(data).filter { it.getString("client_id") != client } + record
        data.put("accounts", JSONArray(next))
        if (ticket.activate) data.put("active", client)
        storage.write(data)
        snapshot = data
        if (ticket.activate) {
            generation++
            signingOut.remove(client)
        }
    }
    suspend fun clear(ticket: Ticket) = mutex.withLock {
        check(generation == ticket.generation) { "Obsolete sign-out" }
        val data = JSONObject(load().toString())
        val id = requireNotNull(ticket.account).getString("client_id")
        accounts(data).find { it.getString("client_id") == id }?.apply {
            listOf("access_token", "refresh_token", "id_token", "scope", "expires_at", "earliest_refresh_at")
                .forEach(::remove)
        }
        if (data.optString("active") == id) data.remove("active")
        storage.write(data)
        snapshot = data
    }
}
