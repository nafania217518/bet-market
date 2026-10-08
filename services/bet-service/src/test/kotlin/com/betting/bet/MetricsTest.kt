package com.betting.bet

import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsTest : IntegrationTest() {
    @Autowired lateinit var registry: MeterRegistry

    private fun count(side: String, status: String) =
        registry.find("bets.placed").tags("side", side, "status", status).counter()?.count() ?: 0.0
    private fun timed() = registry.find("bet.placement.duration").timer()?.count() ?: 0L

    @Test
    fun `an accepted bet increments bets placed with status ACCEPTED`() {
        val before = count("FALSE", "ACCEPTED")
        place(body(snapshot(), "FALSE")).andExpect { status { isAccepted() } }
        assertEquals(before + 1, count("FALSE", "ACCEPTED"))
    }

    @Test
    fun `a rejected bet increments bets placed with its error code`() {
        val before = count("TRUE", "MARKET_NOT_OPEN")
        place(body(snapshot(status = "FROZEN"))).andExpect { status { isConflict() } }
        assertEquals(before + 1, count("TRUE", "MARKET_NOT_OPEN"))
    }

    @Test
    fun `an idempotent replay does not count a second bet`() {
        val market = snapshot()
        place(body(market), key = "metrics-replay")
        val before = count("TRUE", "ACCEPTED")
        place(body(market), key = "metrics-replay").andExpect { status { isAccepted() } }
        assertEquals(before, count("TRUE", "ACCEPTED"))
    }

    @Test
    fun `every placement records bet placement duration`() {
        val before = timed()
        place(body(snapshot()))
        place(body(snapshot(status = "FROZEN")))
        assertEquals(before + 2, timed())
    }

    @Test
    fun `actuator prometheus exposes bets placed total and the bet placement duration histogram`() {
        place(body(snapshot()))
        val text = mvc.get("/actuator/prometheus").andReturn().response.contentAsString
        assertTrue("bets_placed_total" in text)
        assertTrue("bet_placement_duration_seconds_bucket" in text)
    }
}
