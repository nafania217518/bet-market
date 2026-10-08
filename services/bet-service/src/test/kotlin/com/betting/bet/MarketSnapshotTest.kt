package com.betting.bet

import com.betting.events.EventEnvelope
import com.betting.events.EventJson
import com.betting.events.EventTypes
import com.betting.events.MarketV1
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.core.KafkaTemplate
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals

class MarketSnapshotTest : IntegrationTest() {
    @Autowired lateinit var listener: MarketSnapshotListener
    @Autowired lateinit var kafka: KafkaTemplate<String, String>

    private val freezeAt: Instant = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS)

    private fun event(type: String, id: UUID, status: String, version: Long, maxBet: Long = 100) = EventJson.write(
        EventEnvelope(UUID.randomUUID(), type, 1, Instant.now(), null, null, id.toString(), "market-service",
            EventJson.toNode(MarketV1(id, "t", "d", status, null, freezeAt, freezeAt.plusSeconds(60), 1, maxBet, version))),
    )

    private fun row(id: UUID): List<Any> =
        jdbc.sql("SELECT status, freeze_at, min_bet, max_bet, version FROM market_snapshot WHERE market_id = ?").param(id)
            .query { rs, _ -> listOf(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getLong(3), rs.getLong(4), rs.getLong(5)) }
            .single()

    @Test
    fun `market events upsert the local snapshot and an older version never overwrites a newer one`() {
        val id = UUID.randomUUID()
        listener.onMarketEvent(event(EventTypes.MARKET_CREATED, id, "OPEN", 1))
        assertEquals(listOf("OPEN", freezeAt, 1L, 100L, 1L), row(id))

        listener.onMarketEvent(event(EventTypes.MARKET_FROZEN, id, "FROZEN", 3, maxBet = 50))
        listener.onMarketEvent(event(EventTypes.MARKET_UPDATED, id, "OPEN", 2))
        listener.onMarketEvent(event(EventTypes.MARKET_FROZEN, id, "FROZEN", 3, maxBet = 50))
        assertEquals(listOf("FROZEN", freezeAt, 1L, 50L, 3L), row(id))
    }

    @Test
    fun `market events from Kafka reach the snapshot end to end`() {
        val id = UUID.randomUUID()
        kafka.send(EventTypes.MARKET_CREATED, id.toString(), event(EventTypes.MARKET_CREATED, id, "OPEN", 1)).get()
        await atMost Duration.ofSeconds(30) untilAsserted { assertEquals(listOf("OPEN", freezeAt, 1L, 100L, 1L), row(id)) }
    }
}
