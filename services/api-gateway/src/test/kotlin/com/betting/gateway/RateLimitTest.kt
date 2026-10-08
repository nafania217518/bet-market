package com.betting.gateway

import org.springframework.test.context.TestPropertySource
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

@TestPropertySource(properties = ["RATE_LIMIT_REPLENISH=1", "RATE_LIMIT_BURST=2"])
class RateLimitTest : GatewayTestBase() {
    private fun call(t: String) = client.get().uri("/api/wallets/me").headers { it.setBearerAuth(t) }
        .exchange().returnResult(String::class.java).status.value()

    @Test
    fun `the rate limiter returns 429 when a user exceeds the burst capacity`() {
        val t = token(UUID.randomUUID().toString(), listOf("USER"))
        val codes = (1..5).map { call(t) }
        assertEquals(429, codes.last(), codes.toString())
    }

    @Test
    fun `the rate limiter counts each user separately`() {
        val a = token(UUID.randomUUID().toString(), listOf("USER"))
        val b = token(UUID.randomUUID().toString(), listOf("USER"))
        repeat(5) { call(a) }
        assertEquals(429, call(a))
        assertEquals(200, call(b))
    }
}
