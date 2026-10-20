package com.zero.v2.services.chatgpt

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptOAuthTest {
    @Test fun pkceAndRegistrationDoNotMixReturningIdentity() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            ChatGptOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val first = ChatGptOAuth.Attempt("urn:uuid:host", "http://127.0.0.1:12345/auth/callback")
        val url = first.url().toHttpUrl()
        assertEquals(ChatGptOAuth.DYNAMIC, url.queryParameter("client_id"))
        assertEquals("Zero", url.queryParameter("agent_name_hint"))
        assertNotEquals(first.state, first.nonce)
        assertEquals("oaiapp_one", ChatGptOAuth.callback(first.redirect +
            "?state=${first.state}&code=example&client_id=oaiapp_one", first).client)
        val returning = ChatGptOAuth.Attempt(first.host, first.redirect, "oaiapp_one", "hint", "subject")
        assertNull(returning.url().toHttpUrl().queryParameter("agent_name_hint"))
        assertEquals("oaiapp_one", ChatGptOAuth.callback(returning.redirect +
            "?state=${returning.state}&code=example", returning).client)
        assertTrue(runCatching { ChatGptOAuth.callback(returning.redirect +
            "?state=${returning.state}&code=example&client_id=oaiapp_other", returning) }.isFailure)
    }

    @Test fun callbackErrorsDuplicatesWrongStateAndMissingRegistrationAreRejected() {
        val a = ChatGptOAuth.Attempt("host", "http://127.0.0.1:12345/auth/callback")
        assertTrue(ChatGptOAuth.callback(a.redirect + "?state=${a.state}&error=access_denied", a).denied)
        for (query in listOf("state=wrong&error=access_denied", "state=${a.state}&code=x",
            "state=${a.state}&state=${a.state}&code=x&client_id=oaiapp_a")) {
            assertTrue(runCatching { ChatGptOAuth.callback(a.redirect + "?$query", a) }.isFailure)
        }
        assertTrue(runCatching {
            ChatGptOAuth.callback("http://localhost:12345/auth/callback?state=${a.state}", a)
        }.isFailure)
    }

    @Test fun signedIdentityChecksSignatureAudienceIssuerExpiryAndNonce() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pub = pair.public as RSAPublicKey
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val jwks = JSONObject().put("keys", JSONArray().put(JSONObject().put("kid", "test").put("kty", "RSA")
            .put("n", encoder.encodeToString(pub.modulus.toByteArray()))
            .put("e", encoder.encodeToString(pub.publicExponent.toByteArray()))))
        fun token(claims: JSONObject): String {
            val header = encoder.encodeToString("""{"alg":"RS256","kid":"test"}""".toByteArray())
            val data = "$header.${encoder.encodeToString(claims.toString().toByteArray())}"
            val sig = Signature.getInstance("SHA256withRSA").apply {
                initSign(pair.private); update(data.toByteArray())
            }
            return "$data.${encoder.encodeToString(sig.sign())}"
        }
        val claims = JSONObject().put("iss", ChatGptOAuth.ISSUER).put("sub", "user")
            .put("aud", "oaiapp_one").put("exp", 2000).put("nonce", "n")
        val good = token(claims)
        assertEquals("user", ChatGptOAuth.identity(good, jwks, "oaiapp_one", "n", 1000).getString("sub"))
        for ((field, value) in listOf("iss" to "https://wrong", "aud" to "other", "exp" to 999, "nonce" to "bad")) {
            val bad = JSONObject(claims.toString()).put(field, value)
            assertTrue(field, runCatching {
                ChatGptOAuth.identity(token(bad), jwks, "oaiapp_one", "n", 1000)
            }.isFailure)
        }
        assertTrue(runCatching {
            ChatGptOAuth.identity(good.dropLast(8) + "AAAAAAAA", jwks, "oaiapp_one", "n", 1000)
        }.isFailure)
    }
}
