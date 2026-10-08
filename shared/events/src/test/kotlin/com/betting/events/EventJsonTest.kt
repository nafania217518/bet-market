package com.betting.events

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class EventJsonTest {
    private val userId = UUID.randomUUID()
    private val envelope = EventEnvelope(
        eventId = UUID.randomUUID(),
        eventType = EventTypes.USER_REGISTERED,
        eventVersion = 1,
        occurredAt = Instant.parse("2026-01-02T03:04:05.123456Z"),
        correlationId = "corr-1",
        causationId = UUID.randomUUID(),
        aggregateId = userId.toString(),
        producer = "user-service",
        payload = EventJson.toNode(UserRegisteredV1(userId, "a@b.c", "Al")),
    )

    @Test
    fun `envelope round-trips through JSON without data loss`() {
        assertEquals(envelope, EventJson.read(EventJson.write(envelope)))
    }

    @Test
    fun `envelope JSON uses the field names from the architecture and occurredAt is an ISO-8601 string`() {
        val node = EventJson.mapper.readTree(EventJson.write(envelope))
        val names = node.propertyNames().toSet()
        assertEquals(
            setOf("eventId", "eventType", "eventVersion", "occurredAt", "correlationId", "causationId", "aggregateId", "producer", "payload"),
            names,
        )
        assertEquals("2026-01-02T03:04:05.123456Z", node.get("occurredAt").stringValue())
    }

    @Test
    fun `payload converts to its typed V1 data class`() {
        assertEquals(UserRegisteredV1(userId, "a@b.c", "Al"), EventJson.payload(envelope, UserRegisteredV1::class))
        val wallet = envelope.copy(eventType = EventTypes.WALLET_CREATED, payload = EventJson.toNode(WalletCreatedV1(userId, 0)))
        assertEquals(WalletCreatedV1(userId, 0), EventJson.payload(wallet, WalletCreatedV1::class))
    }

    @Test
    fun `unknown payload fields are ignored when reading`() {
        val json = """{"eventId":"${envelope.eventId}","eventType":"wallet.created","eventVersion":1,"occurredAt":"2026-01-02T03:04:05Z",
            "correlationId":null,"causationId":null,"aggregateId":"a","producer":"p","extraTop":1,
            "payload":{"userId":"$userId","balance":5,"newField":"x"}}"""
        val read = EventJson.read(json)
        assertEquals(WalletCreatedV1(userId, 5), EventJson.payload(read, WalletCreatedV1::class))
    }
}
