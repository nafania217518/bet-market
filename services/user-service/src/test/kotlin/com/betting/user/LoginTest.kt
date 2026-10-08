package com.betting.user

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginTest : IntegrationTest() {
    private fun tokenFor(email: String, password: String = "password123"): SignedJWT {
        val body = login(email, password).andExpect {
            status { isOk() }
            jsonPath("$.tokenType") { value("Bearer") }
            jsonPath("$.expiresIn") { value(900) }
        }.andReturn().response.contentAsString
        return SignedJWT.parse(JsonMapper().readTree(body).get("accessToken").asString())
    }

    private fun registered(): Pair<String, String> {
        val email = uniqueEmail()
        val body = register(email).andReturn().response.contentAsString
        return email to JsonMapper().readTree(body).get("userId").asString()
    }

    private fun jwks(): String = mvc.get("/.well-known/jwks.json").andExpect { status { isOk() } }.andReturn().response.contentAsString

    @Test
    fun `login returns an RS256 access token for valid credentials`() {
        val (email, _) = registered()
        assertEquals(JWSAlgorithm.RS256, tokenFor(email.uppercase()).header.algorithm)
    }

    @Test
    fun `login rejects a wrong password and an unknown email with 401 INVALID_CREDENTIALS`() {
        val (email, _) = registered()
        listOf(login(email, "wrong-password"), login(uniqueEmail(), "password123")).forEach {
            it.andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("INVALID_CREDENTIALS") } }
        }
    }

    @Test
    fun `access token has subject = user id, issuer bet-market, roles claim, and expires in 15 minutes`() {
        val (email, id) = registered()
        val claims = tokenFor(email).jwtClaimsSet
        assertEquals(id, claims.subject)
        assertEquals("bet-market", claims.issuer)
        assertEquals(listOf("USER"), claims.getStringListClaim("roles"))
        assertEquals(15 * 60 * 1000L, claims.expirationTime.time - claims.issueTime.time)
    }

    @Test
    fun `access token verifies against the public key published at well-known jwks json`() {
        val (email, _) = registered()
        val token = tokenFor(email)
        val key = JWKSet.parse(jwks()).getKeyByKeyId(token.header.keyID) as RSAKey
        assertTrue(token.verify(RSASSAVerifier(key)))
    }

    @Test
    fun `jwks endpoint exposes no private key material`() {
        val key = JWKSet.parse(jwks()).keys.single()
        assertEquals("RSA", key.keyType.value)
        assertFalse(key.isPrivate)
        listOf("d", "p", "q", "dp", "dq", "qi").forEach { assertFalse(jwks().contains("\"$it\"")) }
    }
}
