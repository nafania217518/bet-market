package com.betting.common

import com.betting.events.EventEnvelope
import com.betting.events.EventJson
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

@Transactional(propagation = Propagation.MANDATORY)
class OutboxWriter(private val jdbc: JdbcClient, private val producer: String) {
    fun append(eventType: String, aggregateId: String, payload: Any, eventVersion: Int = 1, causationId: UUID? = null): UUID {
        val envelope = EventEnvelope(
            eventId = UUID.randomUUID(),
            eventType = eventType,
            eventVersion = eventVersion,
            occurredAt = Instant.now(),
            correlationId = CorrelationId.current(),
            causationId = causationId,
            aggregateId = aggregateId,
            producer = producer,
            payload = EventJson.toNode(payload),
        )
        jdbc.sql("INSERT INTO outbox_event(id, aggregate_id, event_type, payload) VALUES (?, ?, ?, ?::jsonb)")
            .params(envelope.eventId, aggregateId, eventType, EventJson.write(envelope))
            .update()
        return envelope.eventId
    }
}

class OutboxPoller(
    private val jdbc: JdbcClient,
    private val kafka: KafkaTemplate<String, String>,
    private val tx: TransactionTemplate,
) {
    private class Row(val id: UUID, val aggregateId: String, val eventType: String, val payload: String)

    @Scheduled(fixedDelayString = "\${outbox.poll-interval-ms:500}")
    fun publishBatch(): Int = tx.execute {
        val rows = jdbc.sql(
            """SELECT id, aggregate_id, event_type, payload::text AS payload FROM outbox_event
               WHERE published_at IS NULL ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED""",
        ).query { rs, _ ->
            Row(rs.getObject("id", UUID::class.java), rs.getString("aggregate_id"), rs.getString("event_type"), rs.getString("payload"))
        }.list()
        for (row in rows) {
            val record = ProducerRecord<String, String>(row.eventType, row.aggregateId, row.payload)
            EventJson.read(row.payload).correlationId?.let { record.headers().add(CorrelationId.HEADER, it.toByteArray()) }
            kafka.send(record).get(10, TimeUnit.SECONDS)
            jdbc.sql("UPDATE outbox_event SET published_at = now() WHERE id = ?").param(row.id).update()
        }
        rows.size
    }
}

@Transactional
class IdempotentConsumer(private val jdbc: JdbcClient) {
    fun runOnce(eventId: UUID, consumer: String, block: () -> Unit): Boolean {
        val inserted = jdbc.sql("INSERT INTO processed_event(event_id, consumer) VALUES (?, ?) ON CONFLICT DO NOTHING")
            .params(eventId, consumer)
            .update()
        if (inserted == 0) return false
        block()
        return true
    }
}
