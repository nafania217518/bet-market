package com.betting.settlement

import com.betting.events.EventJson
import com.betting.events.EventTypes
import io.micrometer.core.instrument.MeterRegistry
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SettlementMetricsTest : IntegrationTest() {
    @Autowired lateinit var registry: MeterRegistry
    @Autowired lateinit var mvc: MockMvc

    private fun payouts(type: String) = registry.find("payouts").tag("type", type).counter()?.count() ?: 0.0
    private fun settleCount() = registry.find("settlement.duration").timer()?.count() ?: 0L

    private fun settleOne(): UUID {
        val m = UUID.randomUUID()
        val (a, b, c) = Triple(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        listOf(bet(m, a, "TRUE", 100), bet(m, b, "TRUE", 50), bet(m, c, "FALSE", 300))
            .forEach { listener.onBetPlaced(envelope(EventTypes.BET_PLACED, m, it)) }
        listener.onMarketClosed(envelope(EventTypes.MARKET_RESOLVED, m, market(m, "RESOLVED", "TRUE")))
        await.atMost(timeout).untilAsserted { assertEquals("COMPLETED", status(m)) }
        return m
    }

    @Test
    fun `a settled market records settlement duration`() {
        val before = settleCount()
        settleOne()
        await.atMost(timeout).untilAsserted { assertTrue(settleCount() > before) }
    }

    @Test
    fun `a settled market counts its payouts by type`() {
        val before = payouts("WIN")
        settleOne()
        await.atMost(timeout).untilAsserted { assertEquals(before + 2, payouts("WIN")) }
    }

    @Test
    fun `actuator prometheus exposes settlement duration and payouts total`() {
        settleOne()
        await.atMost(timeout).untilAsserted {
            val body = mvc.get("/actuator/prometheus").andReturn().response.contentAsString
            assertTrue("settlement_duration_seconds_bucket{" in body)
            assertTrue("payouts_total{type=\"WIN\"}" in body)
        }
    }

    @Test
    fun `events emitted by the settle task carry a correlation id`() {
        val m = settleOne()
        val ids = outbox(m).map { EventJson.read(it.second).correlationId }
        assertEquals(2, ids.size)
        ids.forEach { assertNotNull(UUID.fromString(it)) }
    }
}
