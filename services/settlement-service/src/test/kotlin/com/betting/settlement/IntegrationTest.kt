package com.betting.settlement

import com.betting.events.BetV1
import com.betting.events.EventEnvelope
import com.betting.events.EventJson
import com.betting.events.MarketV1
import com.betting.events.PayoutCalculatedV1
import com.betting.events.SettlementCompletedV1
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@TestConfiguration(proxyBeanMethods = false)
class Containers {
    @Bean @ServiceConnection
    fun postgres() = PostgreSQLContainer("postgres:16-alpine")

    @Bean @ServiceConnection
    fun kafka() = KafkaContainer("apache/kafka:4.3.1")
}

// Same config in every subclass -> Spring caches one context, containers start once.
@org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@SpringBootTest(properties = ["outbox.poll-interval-ms=200", "db-scheduler.polling-interval=200ms", "db-scheduler.heartbeat-interval=1s"])
@Import(Containers::class)
abstract class IntegrationTest {
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var listener: SettlementListener
    @MockitoSpyBean lateinit var bets: SettlementBets

    val freezeAt: Instant = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS)
    val timeout: Duration = Duration.ofSeconds(15)

    fun envelope(type: String, aggregateId: UUID, payload: Any): String = EventJson.write(
        EventEnvelope(UUID.randomUUID(), type, 1, Instant.now(), null, null, aggregateId.toString(), "test", EventJson.toNode(payload)),
    )

    fun bet(marketId: UUID, user: UUID, side: String, amount: Long, status: String = "ACCEPTED", betId: UUID = UUID.randomUUID()) =
        BetV1(betId, marketId, user, side, amount, status, freezeAt.minusSeconds(60))

    fun market(id: UUID, status: String, winningSide: String?) =
        MarketV1(id, "t", "d", status, winningSide, freezeAt, freezeAt.plusSeconds(30), 1, 1000, 5)

    fun status(marketId: UUID): String? =
        jdbc.sql("SELECT status FROM settlements WHERE market_id = ?").param(marketId).query(String::class.java).optional().orElse(null)

    fun taskCount(marketId: UUID): Long =
        jdbc.sql("SELECT count(*) FROM scheduled_tasks WHERE task_instance = ?").param(marketId.toString()).query(Long::class.java).single()

    /** Outbox rows of one aggregate, oldest first: (eventType, envelope json). */
    fun outbox(aggregateId: UUID): List<Pair<String, String>> =
        jdbc.sql("SELECT event_type, payload::text FROM outbox_event WHERE aggregate_id = ? ORDER BY created_at")
            .param(aggregateId.toString()).query { rs, _ -> rs.getString(1) to rs.getString(2) }.list()

    fun payoutEvents(id: UUID) = outbox(id).filter { it.first == "payout.calculated" }
        .map { EventJson.payload(EventJson.read(it.second), PayoutCalculatedV1::class) }

    fun completedEvents(id: UUID) = outbox(id).filter { it.first == "settlement.completed" }
        .map { EventJson.payload(EventJson.read(it.second), SettlementCompletedV1::class) }

    fun auditRows(id: UUID): List<Triple<UUID, Long, String>> =
        jdbc.sql("SELECT user_id, amount, type FROM settlement_payouts WHERE market_id = ? ORDER BY user_id, type").param(id)
            .query { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getLong(2), rs.getString(3)) }.list()
}
