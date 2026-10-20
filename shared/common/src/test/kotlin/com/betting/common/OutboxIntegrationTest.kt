package com.betting.common

import com.betting.events.EventJson
import com.betting.events.UserRegisteredV1
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@Import(Containers::class)
class OutboxIntegrationTest {
    @Autowired lateinit var writer: OutboxWriter
    @Autowired lateinit var poller: OutboxPoller
    @Autowired lateinit var idempotent: IdempotentConsumer
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var kafka: KafkaConnectionDetails

    private fun append(aggregateId: String, correlationId: String? = "corr-${UUID.randomUUID()}"): UUID {
        correlationId?.let { MDC.put(CorrelationId.MDC_KEY, it) }
        try {
            return tx.execute { writer.append("user.registered", aggregateId, UserRegisteredV1(UUID.randomUUID(), "a@b.c", "Al")) }
        } finally {
            MDC.clear()
        }
    }

    private fun payloadOf(id: UUID) =
        jdbc.sql("SELECT payload::text FROM outbox_event WHERE id = ?").param(id).query(String::class.java).single()

    private fun publishedAt(id: UUID) =
        jdbc.sql("SELECT published_at FROM outbox_event WHERE id = ?").param(id).query().singleValue()

    @Test
    fun `outbox writer stores the event envelope with correlation id from MDC`() {
        val id = append("agg-1", "corr-xyz")
        val envelope = EventJson.read(payloadOf(id))
        assertEquals(id, envelope.eventId)
        assertEquals("corr-xyz", envelope.correlationId)
        assertEquals("test-service", envelope.producer)
        assertEquals("agg-1", envelope.aggregateId)
        assertEquals("a@b.c", EventJson.payload(envelope, UserRegisteredV1::class).email)
    }

    private fun correlationColumn(id: UUID) =
        jdbc.sql("SELECT correlation_id FROM outbox_event WHERE id = ?").param(id).query { rs, _ -> rs.getString(1) }.list().single()

    @Test
    fun `the writer stores the correlation id in its own column`() {
        assertEquals("corr-col", correlationColumn(append("agg-c", "corr-col")))
        assertNull(correlationColumn(append("agg-c", null)))
    }

    @Test
    fun `outbox writer fails outside a transaction`() {
        assertFailsWith<IllegalTransactionStateException> { writer.append("user.registered", "x", mapOf("a" to 1)) }
    }

    @Test
    fun `outbox poller publishes pending events to the Kafka topic named by event type, keyed by aggregate id, with the correlation id header`() {
        val aggregateId = "agg-${UUID.randomUUID()}"
        val id = append(aggregateId, "corr-pub")
        assertTrue(poller.publishBatch() >= 1)
        KafkaConsumer<String, String>(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers.joinToString(","),
                ConsumerConfig.GROUP_ID_CONFIG to UUID.randomUUID().toString(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ),
            StringDeserializer(), StringDeserializer(),
        ).use { consumer ->
            consumer.subscribe(listOf("user.registered"))
            val deadline = System.currentTimeMillis() + 20_000
            var found: org.apache.kafka.clients.consumer.ConsumerRecord<String, String>? = null
            while (found == null && System.currentTimeMillis() < deadline) {
                found = consumer.poll(Duration.ofMillis(500)).firstOrNull { it.key() == aggregateId }
            }
            assertNotNull(found)
            assertEquals(id, EventJson.read(found.value()).eventId)
            assertEquals("corr-pub", String(found.headers().lastHeader(CorrelationId.HEADER).value()))
        }
    }

    @Test
    fun `outbox poller marks published rows and does not publish them again`() {
        val id = append("agg-2", null)
        poller.publishBatch()
        assertNotNull(publishedAt(id))
        assertEquals(0, poller.publishBatch())
    }

    @Test
    fun `idempotent consumer runs the block once per event id and consumer`() {
        val eventId = UUID.randomUUID()
        var runs = 0
        assertTrue(idempotent.runOnce(eventId, "c1") { runs++ })
        assertFalse(idempotent.runOnce(eventId, "c1") { runs++ })
        assertTrue(idempotent.runOnce(eventId, "c2") { runs++ })
        assertEquals(2, runs)
    }

    @Test
    fun `idempotent consumer rolls back the processed marker when the block fails`() {
        val eventId = UUID.randomUUID()
        assertFailsWith<IllegalStateException> { idempotent.runOnce(eventId, "c1") { error("boom") } }
        var ran = false
        assertTrue(idempotent.runOnce(eventId, "c1") { ran = true })
        assertTrue(ran)
    }
}
