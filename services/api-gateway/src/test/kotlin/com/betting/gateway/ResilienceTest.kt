package com.betting.gateway

import org.springframework.test.context.TestPropertySource
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

@TestPropertySource(
    properties = [
        "DOWNSTREAM_TIMEOUT=500ms",
        "resilience4j.circuitbreaker.configs.default.minimum-number-of-calls=3",
        "resilience4j.circuitbreaker.configs.default.sliding-window-size=3",
        "resilience4j.circuitbreaker.configs.default.wait-duration-in-open-state=60s",
    ],
)
class ResilienceTest : GatewayTestBase() {
    private val user get() = token(UUID.randomUUID().toString(), listOf("USER"))
    private fun key() = UUID.randomUUID().toString()

    @Test
    fun `a downstream slower than the timeout returns 504`() {
        client.get().uri("/api/wallets/me/ledger").headers { it.setBearerAuth(user); it.set("X-Fake-Sleep", "1500") }
            .exchange().expectStatus().isEqualTo(504)
    }

    @Test
    fun `repeated downstream failures open the breaker of that route only and it answers 503 without calling the downstream`() {
        val k = key()
        val t = user
        repeat(3) {
            client.post().uri("/api/bets").headers { it.setBearerAuth(t); it.set("X-Fake-Key", k); it.set("X-Fake-Sleep", "1500") }
                .exchange().expectStatus().isEqualTo(504)
        }
        client.post().uri("/api/bets").headers { it.setBearerAuth(t); it.set("X-Fake-Key", k) }
            .exchange().expectStatus().isEqualTo(503)
        assertEquals(3, calls[k]!!.get())
        client.get().uri("/api/wallets/me").headers { it.setBearerAuth(t) }.exchange().expectStatus().isOk
    }

    @Test
    fun `a GET to the wallet route is retried after a 503 and succeeds`() {
        val k = key()
        client.get().uri("/api/wallets/me").headers { it.setBearerAuth(user); it.set("X-Fake-Key", k); it.set("X-Fake-Fail", "1") }
            .exchange().expectStatus().isOk
        assertEquals(2, calls[k]!!.get())
    }

    @Test
    fun `a POST is never retried`() {
        val k = key()
        client.post().uri("/api/auth/login").headers { it.set("X-Fake-Key", k); it.set("X-Fake-Fail", "1") }
            .exchange().expectStatus().isEqualTo(503)
        assertEquals(1, calls[k]!!.get())
    }
}
