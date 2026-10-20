package com.zero.v2.services.chatgpt

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

internal object ChatGptOAuth {
    const val ISSUER = "https://auth.openai.com"
    const val RESOURCE = "https://api.openai.com/v1"
    const val DYNAMIC = "dynamic_agent_client"
    const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    private const val RANDOM_BYTES = 32
    private const val MAX_JWT_LENGTH = 65536
    private const val JWT_PARTS = 3
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    fun random(): String = encoder.encodeToString(ByteArray(RANDOM_BYTES).also(SecureRandom()::nextBytes))
    fun challenge(verifier: String): String = encoder.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    class Attempt(val host: String, val redirect: String, val client: String = DYNAMIC,
        val hint: String? = null, val subject: String? = null) {
        val state = random()
        val nonce = random()
        val verifier = random()
        fun url(): String = "$ISSUER/api/accounts/authorize".toHttpUrl().newBuilder().apply {
            addQueryParameter("client_id", client)
            if (client == DYNAMIC) addQueryParameter("agent_name_hint", "Zero")
            addQueryParameter("ext_agent_host_id", host)
            hint?.let { addQueryParameter("id_token_hint", it) }
            addQueryParameter("response_type", "code")
            addQueryParameter("redirect_uri", redirect)
            addQueryParameter("resource", RESOURCE)
            addQueryParameter("scope", "openid profile email offline_access resource.invoke $PLAN_SCOPE")
            addQueryParameter("state", state)
            addQueryParameter("nonce", nonce)
            addQueryParameter("code_challenge_method", "S256")
            addQueryParameter("code_challenge", challenge(verifier))
        }.build().toString()
    }

    class Callback(val client: String, val code: String?, val denied: Boolean)
    fun callback(url: String, attempt: Attempt): Callback {
        val actual = url.toHttpUrl()
        val expected = attempt.redirect.toHttpUrl()
        require(actual.newBuilder().query(null).build() == expected) { "Invalid callback target" }
        for (key in listOf("state", "code", "client_id", "error")) {
            require(actual.queryParameterValues(key).size <= 1) { "Duplicate callback field" }
        }
        require(MessageDigest.isEqual(actual.queryParameter("state").orEmpty().toByteArray(),
            attempt.state.toByteArray())) { "Invalid callback state" }
        if (actual.queryParameter("error") != null) return Callback(attempt.client, null, true)
        val client = actual.queryParameter("client_id") ?: attempt.client
        require(client.startsWith("oaiapp_") && (attempt.client == DYNAMIC || client == attempt.client)) {
            "Invalid registration identity"
        }
        val code = requireNotNull(actual.queryParameter("code")).also { require(it.isNotBlank()) }
        return Callback(client, code, false)
    }

    fun identity(jwt: String, jwks: JSONObject, client: String, nonce: String?, now: Long): JSONObject {
        require(jwt.length <= MAX_JWT_LENGTH)
        val parts = jwt.split('.')
        require(parts.size == JWT_PARTS)
        val decoder = Base64.getUrlDecoder()
        val header = JSONObject(String(decoder.decode(parts[0]), Charsets.UTF_8))
        require(header.getString("alg") == "RS256")
        val kid = header.getString("kid")
        val keys = jwks.getJSONArray("keys")
        val candidates = (0 until keys.length()).map(keys::getJSONObject).filter {
            it.optString("kid") == kid && it.optString("kty") == "RSA" &&
                it.optString("use", "sig") == "sig" && it.optString("alg", "RS256") == "RS256"
        }
        val key = candidates.single()
        val spec = RSAPublicKeySpec(BigInteger(1, decoder.decode(key.getString("n"))),
            BigInteger(1, decoder.decode(key.getString("e"))))
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(KeyFactory.getInstance("RSA").generatePublic(spec))
        signature.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        require(signature.verify(decoder.decode(parts[2]))) { "Invalid ID token signature" }
        val claims = JSONObject(String(decoder.decode(parts[1]), Charsets.UTF_8))
        require(claims.getString("iss") == ISSUER && claims.getString("sub").isNotBlank())
        val audiences = claims.optJSONArray("aud")?.let { a -> (0 until a.length()).map(a::getString) }
            ?: listOf(claims.getString("aud"))
        require(client in audiences && (audiences.size == 1 || claims.optString("azp") == client))
        require(claims.getLong("exp") > now && claims.optLong("nbf", 0) <= now)
        if (nonce != null) require(claims.optString("nonce") == nonce)
        return claims
    }
}
