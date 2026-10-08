package com.betting.gateway

import org.springframework.http.HttpMethod
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertNotNull

class GatewayTest : GatewayTestBase() {
    private val wallet = "/api/wallets/me"

    @Test
    fun `POST api auth register is routed to the user service without a token`() {
        client.post().uri("/api/auth/register").exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.path").isEqualTo("/api/auth/register").jsonPath("$.method").isEqualTo("POST")
    }

    @Test
    fun `GET api wallets me without a token returns 401`() {
        client.get().uri(wallet).exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `GET api wallets me with a valid token is routed to the wallet service with X-User-Id and X-User-Roles from the token`() {
        val id = UUID.randomUUID().toString()
        client.get().uri(wallet).headers { it.setBearerAuth(token(id, listOf("USER", "ADMIN"))) }.exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.path").isEqualTo(wallet)
            .jsonPath("$.userId").isEqualTo(id).jsonPath("$.roles").isEqualTo("USER,ADMIN")
    }

    @Test
    fun `client-supplied X-User-Id and X-User-Roles are replaced by the token values`() {
        val id = UUID.randomUUID().toString()
        client.get().uri("/api/wallets/me/ledger").headers {
            it.setBearerAuth(token(id, listOf("USER")))
            it.set("X-User-Id", "evil")
            it.set("X-User-Roles", "ADMIN")
        }.exchange().expectStatus().isOk
            .expectBody().jsonPath("$.userId").isEqualTo(id).jsonPath("$.roles").isEqualTo("USER")
    }

    @Test
    fun `client-supplied X-User-Id and X-User-Roles are removed on public routes`() {
        client.post().uri("/api/auth/login").header("X-User-Id", "evil").header("X-User-Roles", "ADMIN").exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.userId").doesNotExist().jsonPath("$.roles").doesNotExist()
    }

    @Test
    fun `a token with the wrong issuer, an expired token, or a token signed by another key returns 401`() {
        val id = UUID.randomUUID().toString()
        listOf(
            token(id, listOf("USER"), issuer = "other"),
            token(id, listOf("USER"), expiresAt = Instant.now().minusSeconds(600)),
            token(id, listOf("USER"), key = otherKey),
        ).forEach { t ->
            client.get().uri(wallet).headers { it.setBearerAuth(t) }.exchange().expectStatus().isUnauthorized
        }
    }

    @Test
    fun `market write routes return 403 for a USER token and pass security for an ADMIN token`() {
        val user = token(UUID.randomUUID().toString(), listOf("USER"))
        val admin = token(UUID.randomUUID().toString(), listOf("USER", "ADMIN"))
        client.post().uri("/api/markets/1").headers { it.setBearerAuth(user) }.exchange().expectStatus().isForbidden
        client.patch().uri("/api/markets/1").headers { it.setBearerAuth(user) }.exchange().expectStatus().isForbidden
        client.delete().uri("/api/markets/1").headers { it.setBearerAuth(user) }.exchange().expectStatus().isForbidden
        client.post().uri("/api/markets/1").headers { it.setBearerAuth(admin) }.exchange().expectStatus().isOk
    }

    @Test
    fun `admin POST api markets, PATCH and DELETE api markets id, and POST freeze and resolve are routed to the market service with identity headers`() {
        val id = UUID.randomUUID().toString()
        val admin = token(id, listOf("USER", "ADMIN"))
        listOf(
            HttpMethod.POST to "/api/markets",
            HttpMethod.PATCH to "/api/markets/42",
            HttpMethod.DELETE to "/api/markets/42",
            HttpMethod.POST to "/api/markets/42/freeze",
            HttpMethod.POST to "/api/markets/42/resolve",
        ).forEach { (method, path) ->
            client.method(method).uri(path).headers { it.setBearerAuth(admin) }.exchange()
                .expectStatus().isOk
                .expectBody()
                .jsonPath("$.path").isEqualTo(path)
                .jsonPath("$.method").isEqualTo(method.name())
                .jsonPath("$.userId").isEqualTo(id)
                .jsonPath("$.roles").isEqualTo("USER,ADMIN")
        }
    }

    @Test
    fun `POST api bets and DELETE api bets id are routed to the bet service with identity headers and the Idempotency-Key header`() {
        val id = UUID.randomUUID().toString()
        val user = token(id, listOf("USER"))
        listOf(HttpMethod.POST to "/api/bets", HttpMethod.DELETE to "/api/bets/7").forEach { (method, path) ->
            client.method(method).uri(path).headers { it.setBearerAuth(user); it.set("Idempotency-Key", "k-1") }.exchange()
                .expectStatus().isOk
                .expectBody()
                .jsonPath("$.path").isEqualTo(path)
                .jsonPath("$.method").isEqualTo(method.name())
                .jsonPath("$.userId").isEqualTo(id)
                .jsonPath("$.roles").isEqualTo("USER")
                .jsonPath("$.idempotencyKey").isEqualTo("k-1")
        }
        client.get().uri("/api/bets").headers { it.setBearerAuth(user) }.exchange().expectStatus().isNotFound
    }

    @Test
    fun `api bets without a token returns 401`() {
        client.post().uri("/api/bets").exchange().expectStatus().isUnauthorized
        client.delete().uri("/api/bets/7").exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `GET api markets is not routed yet and returns 404`() {
        client.get().uri("/api/markets").exchange().expectStatus().isNotFound
    }

    @Test
    fun `the correlation id from the request is forwarded downstream and returned in the response`() {
        client.post().uri("/api/auth/login").header("X-Correlation-Id", "abc-123").exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("X-Correlation-Id", "abc-123")
            .expectBody().jsonPath("$.correlationId").isEqualTo("abc-123")
    }

    @Test
    fun `a correlation id is generated when the request has none`() {
        val result = client.post().uri("/api/auth/login").exchange().expectStatus().isOk
            .returnResult(String::class.java)
        val id = assertNotNull(result.responseHeaders.getFirst("X-Correlation-Id"))
        UUID.fromString(id)
        assert(result.responseBody.blockFirst()!!.contains(id))
    }

    @Test
    fun `a CORS preflight from the allowed origin succeeds and from another origin is rejected`() {
        client.options().uri(wallet).header("Origin", "http://localhost:5173")
            .header("Access-Control-Request-Method", "GET").exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173")
        client.options().uri(wallet).header("Origin", "http://evil.example")
            .header("Access-Control-Request-Method", "GET").exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `actuator health is public`() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk
    }
}
