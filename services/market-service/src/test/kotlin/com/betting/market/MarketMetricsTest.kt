package com.betting.market

import io.micrometer.core.instrument.MeterRegistry
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarketMetricsTest : IntegrationTest() {
    @Autowired lateinit var registry: MeterRegistry

    private fun count(from: String, to: String) = registry.find("market.transitions").tags("from", from, "to", to).counter()?.count() ?: 0.0
    private fun request(freezeAt: Instant = Instant.now().plusSeconds(3600)) = CreateMarketRequest("t", "d", freezeAt, freezeAt.plusSeconds(3600), 1, 10)

    @Test
    fun `an admin freeze counts OPEN to FROZEN`() {
        val before = count("OPEN", "FROZEN")
        service.freeze(service.create(request(), admin).marketId)
        assertEquals(before + 1, count("OPEN", "FROZEN"))
    }

    @Test
    fun `the end task counts FROZEN to ENDED`() {
        val id = service.create(request(), admin).marketId
        val (openFrozen, frozenEnded) = count("OPEN", "FROZEN") to count("FROZEN", "ENDED")
        service.endByScheduler(id) // OPEN market: counts OPEN to FROZEN and FROZEN to ENDED
        assertEquals(openFrozen + 1, count("OPEN", "FROZEN"))
        assertEquals(frozenEnded + 1, count("FROZEN", "ENDED"))
    }

    @Test
    fun `a cancel counts the transition to CANCELLED`() {
        val before = count("OPEN", "CANCELLED")
        service.cancel(service.create(request(), admin).marketId)
        assertEquals(before + 1, count("OPEN", "CANCELLED"))
    }

    @Test
    fun `markets by status reports the count per status`() {
        service.freeze(service.create(request(), admin).marketId)
        listOf("OPEN", "FROZEN", "ENDED", "RESOLVED", "CANCELLED").forEach { s ->
            val rows = jdbc.sql("SELECT count(*) FROM markets WHERE status = ?").param(s).query(Long::class.java).single()
            assertEquals(rows.toDouble(), registry.get("markets.by.status").tag("status", s).gauge().value())
        }
    }

    @Test
    fun `actuator prometheus exposes market transitions, markets by status and db-scheduler task completions`() {
        val id = service.create(request(Instant.now().plusSeconds(1)), admin).marketId
        await.atMost(timeout).untilAsserted { assertEquals("FROZEN", status(id)) }
        val body = mvc.get("/actuator/prometheus").andReturn().response.contentAsString
        assertTrue("market_transitions_total{" in body)
        assertTrue("markets_by_status{status=\"FROZEN\"}" in body)
        assertTrue("dbscheduler_task_completions_total{" in body, body.lines().filter { "dbscheduler" in it }.joinToString("\n"))
    }

    @Test
    fun `health includes db-scheduler`() {
        val body = mvc.get("/actuator/health").andReturn().response.contentAsString
        assertTrue("\"dbScheduler\"" in body, body)
    }

    @Test
    fun `an event emitted by a scheduler task carries a correlation id`() {
        val id = service.create(request(Instant.now().plusSeconds(1)), admin).marketId
        await.atMost(timeout).untilAsserted { assertEquals("FROZEN", status(id)) }
        val corr = jdbc.sql("SELECT payload->>'correlationId' FROM outbox_event WHERE aggregate_id = ? AND event_type = 'market.frozen'")
            .param(id.toString()).query(String::class.java).single()
        assertNotNull(UUID.fromString(corr))
    }
}
