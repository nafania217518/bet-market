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

    @Test
    fun `all six market events share the MarketV1 payload and round-trip through the envelope`() {
        val market = MarketV1(UUID.randomUUID(), "T", "D", "RESOLVED", "TRUE", Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"), 1, 10, 4)
        val types = listOf(EventTypes.MARKET_CREATED, EventTypes.MARKET_UPDATED, EventTypes.MARKET_FROZEN, EventTypes.MARKET_ENDED, EventTypes.MARKET_RESOLVED, EventTypes.MARKET_CANCELLED)
        assertEquals(listOf("market.created", "market.updated", "market.frozen", "market.ended", "market.resolved", "market.cancelled"), types)
        types.forEach { type ->
            val e = envelope.copy(eventType = type, aggregateId = market.marketId.toString(), payload = EventJson.toNode(market))
            assertEquals(market, EventJson.payload(EventJson.read(EventJson.write(e)), MarketV1::class))
        }
    }

    @Test
    fun `bet events share the BetV1 payload and wallet movements share the WalletMovementV1 payload`() {
        assertEquals(listOf("bet.placed", "bet.cancelled", "bet.failed"), listOf(EventTypes.BET_PLACED, EventTypes.BET_CANCELLED, EventTypes.BET_FAILED))
        assertEquals(
            listOf("wallet.reserved", "wallet.debited", "wallet.released", "wallet.credited"),
            listOf(EventTypes.WALLET_RESERVED, EventTypes.WALLET_DEBITED, EventTypes.WALLET_RELEASED, EventTypes.WALLET_CREDITED),
        )
        val bet = BetV1(UUID.randomUUID(), UUID.randomUUID(), userId, "TRUE", 50, "FAILED", Instant.parse("2026-01-01T00:00:00Z"), "persist failed")
        assertEquals(bet, EventJson.payload(EventJson.read(EventJson.write(envelope.copy(payload = EventJson.toNode(bet)))), BetV1::class))
        val noReason = EventJson.mapper.readTree(EventJson.write(envelope.copy(payload = EventJson.toNode(bet.copy(reason = null)))))
        assertEquals(null, EventJson.payload(EventJson.read(noReason.toString()), BetV1::class).reason)
        val move = WalletMovementV1(userId, bet.betId, 50, 1000, 50)
        assertEquals(move, EventJson.payload(EventJson.read(EventJson.write(envelope.copy(payload = EventJson.toNode(move)))), WalletMovementV1::class))
    }

    @Test
    fun `settlement events carry the payout list and the market totals`() {
        assertEquals(listOf("payout.calculated", "settlement.completed"), listOf(EventTypes.PAYOUT_CALCULATED, EventTypes.SETTLEMENT_COMPLETED))
        val marketId = UUID.randomUUID()
        val payouts = PayoutCalculatedV1(marketId, listOf(PayoutV1(userId, 380, "WIN"), PayoutV1(UUID.randomUUID(), 10, "REFUND")))
        assertEquals(payouts, EventJson.payload(EventJson.read(EventJson.write(envelope.copy(payload = EventJson.toNode(payouts)))), PayoutCalculatedV1::class))
        val done = SettlementCompletedV1(marketId, "RESOLVED", "TRUE", 100, 300, 380, 20, 2)
        assertEquals(done, EventJson.payload(EventJson.read(EventJson.write(envelope.copy(payload = EventJson.toNode(done)))), SettlementCompletedV1::class))
    }
}
