package com.betting.events

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant
import java.util.UUID
import kotlin.reflect.KClass

object EventTypes {
    const val USER_REGISTERED = "user.registered"
    const val WALLET_CREATED = "wallet.created"
    const val MARKET_CREATED = "market.created"
    const val MARKET_UPDATED = "market.updated"
    const val MARKET_FROZEN = "market.frozen"
    const val MARKET_ENDED = "market.ended"
    const val MARKET_RESOLVED = "market.resolved"
    const val MARKET_CANCELLED = "market.cancelled"
    const val BET_PLACED = "bet.placed"
    const val BET_CANCELLED = "bet.cancelled"
    const val BET_FAILED = "bet.failed"
    const val WALLET_RESERVED = "wallet.reserved"
    const val WALLET_DEBITED = "wallet.debited"
    const val WALLET_RELEASED = "wallet.released"
    const val WALLET_CREDITED = "wallet.credited"
}

data class UserRegisteredV1(val userId: UUID, val email: String, val displayName: String)

data class WalletCreatedV1(val userId: UUID, val balance: Long)

// Payload of every market.* event: the full market state after the change. Consumers upsert and drop stale versions.
data class MarketV1(
    val marketId: UUID,
    val title: String,
    val description: String,
    val status: String,
    val winningSide: String?,
    val freezeAt: Instant,
    val endAt: Instant,
    val minBet: Long,
    val maxBet: Long,
    val version: Long,
)

// Payload of bet.placed, bet.cancelled and bet.failed. The wallet reservation id equals betId.
// side: TRUE|FALSE. status: ACCEPTED|CANCELLED|FAILED. reason: set on bet.failed only.
data class BetV1(
    val betId: UUID,
    val marketId: UUID,
    val userId: UUID,
    val side: String,
    val amount: Long,
    val status: String,
    val placedAt: Instant,
    val reason: String? = null,
)

// Payload of wallet.reserved, wallet.debited, wallet.released and wallet.credited:
// the moved amount and the wallet state after the move.
data class WalletMovementV1(val userId: UUID, val betId: UUID?, val amount: Long, val balance: Long, val reserved: Long)

data class EventEnvelope(
    val eventId: UUID,
    val eventType: String,
    val eventVersion: Int,
    val occurredAt: Instant,
    val correlationId: String?,
    val causationId: UUID?,
    val aggregateId: String,
    val producer: String,
    val payload: JsonNode,
)

object EventJson {
    val mapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    fun write(envelope: EventEnvelope): String = mapper.writeValueAsString(envelope)

    fun read(json: String): EventEnvelope = mapper.readValue(json, EventEnvelope::class.java)

    fun <T : Any> payload(envelope: EventEnvelope, type: KClass<T>): T = mapper.treeToValue(envelope.payload, type.java)

    fun toNode(payload: Any): JsonNode = mapper.valueToTree(payload)
}
