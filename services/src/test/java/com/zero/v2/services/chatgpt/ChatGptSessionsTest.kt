package com.zero.v2.services.chatgpt

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptSessionsTest {
    @Test fun terminalRefreshClearsOnlyTokensWhileTransientFailureRetainsThem() = runBlocking<Unit> {
        val sessions = ChatGptSessions(Store())
        sessions.save(sessions.begin(null), "oaiapp_one", tokens(), identity(), 0)
        for ((code, terminal) in listOf("server_error" to false, "invalid_grant" to true)) {
            val renewal = ChatGptRenewal(sessions, {
                throw ChatGptTokenFailure(400, JSONObject().put("error", code))
            }, { 4000 })
            assertTrue(runCatching { renewal.token("oaiapp_one") }.isFailure)
            val account = sessions.view().getJSONArray("accounts").getJSONObject(0)
            assertEquals(!terminal, account.has("access_token"))
            assertEquals("oaiapp_one", account.getString("client_id"))
            assertEquals("one", account.getString("subject"))
        }
    }
    @Test fun signOutStopsNewCredentialsAndLoginCommitInvalidatesOldRefresh() = runBlocking {
        val sessions = ChatGptSessions(Store())
        sessions.save(sessions.begin(null), "oaiapp_one", tokens(), identity(), 100)
        val login = sessions.begin(null)
        val oldRefresh = sessions.credentials("oaiapp_one")
        sessions.save(login, "oaiapp_two", tokens(), identity("two"), 100)
        assertTrue(runCatching {
            sessions.save(oldRefresh, "oaiapp_one", tokens("late"), identity(), 100)
        }.isFailure)
        val logout = sessions.startSignOut("oaiapp_two")
        assertTrue(runCatching { sessions.credentials("oaiapp_two") }.isFailure)
        sessions.clear(logout)
        assertEquals("", sessions.view().optString("active"))
    }

    @Test fun concurrentRenewalsShareOneExchangeAndLateLogoutCannotRestoreTokens() = runBlocking {
        val sessions = ChatGptSessions(Store())
        sessions.save(sessions.begin(null), "oaiapp_one", tokens(), identity(), 0)
        var exchanges = 0
        val renewal = ChatGptRenewal(sessions, {
            exchanges++
            kotlinx.coroutines.delay(20)
            tokens("renewed") to identity()
        }, { 4000 })
        val requests = List(8) { async { renewal.token("oaiapp_one") } }
        requests.forEach { assertEquals("renewed", it.await()) }
        assertEquals(1, exchanges)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        val late = ChatGptRenewal(sessions, {
            entered.complete(Unit); finish.await(); tokens("late") to identity()
        }, { 8000 })
        val pending = async { runCatching { late.token("oaiapp_one") } }
        entered.await()
        sessions.clear(sessions.begin("oaiapp_one"))
        finish.complete(Unit)
        assertTrue(pending.await().isFailure)
        assertFalse(sessions.view().getJSONArray("accounts").getJSONObject(0).has("access_token"))
    }

    private class Store : ChatGptStorage {
        var saved = "{}"
        var reject = false
        override fun read() = JSONObject(saved)
        override fun write(value: JSONObject) { check(!reject); saved = value.toString() }
    }
    private fun tokens(access: String = "access") = JSONObject().put("token_type", "Bearer")
        .put("access_token", access).put("refresh_token", "refresh").put("id_token", "id")
        .put("expires_in", 3600).put("scope", ChatGptOAuth.PLAN_SCOPE)
    private fun identity(subject: String = "one") = JSONObject().put("sub", subject).put("email", "same@example.test")

    @Test fun snapshotRestoresHostAndSeparateRegistrationsEvenWithSameEmail() = runBlocking {
        val store = Store()
        val sessions = ChatGptSessions(store)
        val first = sessions.begin(null)
        sessions.save(first, "oaiapp_one", tokens(), identity(), 100)
        sessions.save(sessions.begin(null), "oaiapp_two", tokens(), identity("two"), 100)
        val restored = ChatGptSessions(store).view()
        assertEquals(first.host, restored.getString("host"))
        assertEquals(2, restored.getJSONArray("accounts").length())
        assertEquals("oaiapp_two", restored.getString("active"))
    }

    @Test fun logoutAndSwitchRejectLateRefreshWithoutRevivingOldAccount() = runBlocking {
        val sessions = ChatGptSessions(Store())
        sessions.save(sessions.begin(null), "oaiapp_one", tokens(), identity(), 100)
        val refresh = sessions.credentials("oaiapp_one")
        sessions.clear(sessions.begin("oaiapp_one"))
        assertTrue(runCatching { sessions.save(refresh, "oaiapp_one", tokens("late"), identity(), 100) }.isFailure)
        assertFalse(sessions.view().getJSONArray("accounts").getJSONObject(0).has("refresh_token"))
        assertEquals("", sessions.view().optString("active"))
        sessions.save(sessions.begin("oaiapp_one"), "oaiapp_one", tokens(), identity(), 100)
        val beforeSwitch = sessions.credentials("oaiapp_one")
        sessions.save(sessions.begin(null), "oaiapp_two", tokens(), identity("two"), 100)
        assertTrue(runCatching { sessions.save(beforeSwitch, "oaiapp_one", tokens("late"), identity(), 100) }.isFailure)
        assertEquals("oaiapp_two", sessions.view().getString("active"))
    }

    @Test fun failedSaveAndIdentityMismatchCannotPublishCandidate() = runBlocking {
        val store = Store()
        val sessions = ChatGptSessions(store)
        sessions.save(sessions.begin(null), "oaiapp_one", tokens(), identity(), 100)
        val before = sessions.view().toString()
        val ticket = sessions.begin("oaiapp_one")
        assertTrue(runCatching { sessions.save(ticket, "oaiapp_one", tokens(), identity("wrong"), 100) }.isFailure)
        store.reject = true
        assertTrue(runCatching { sessions.save(ticket, "oaiapp_one", tokens("changed"), identity(), 100) }.isFailure)
        assertEquals(before, sessions.view().toString())
        assertEquals(before, ChatGptSessions(store).view().toString())
    }

    @Test fun cancelInvalidatesPendingRegistration() = runBlocking {
        val sessions = ChatGptSessions(Store())
        val ticket = sessions.begin(null)
        sessions.invalidate()
        assertTrue(runCatching { sessions.save(ticket, "oaiapp_one", tokens(), identity(), 100) }.isFailure)
        assertEquals(0, sessions.view().getJSONArray("accounts").length())
    }
}

