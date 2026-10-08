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
}

data class UserRegisteredV1(val userId: UUID, val email: String, val displayName: String)

data class WalletCreatedV1(val userId: UUID, val balance: Long)

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
