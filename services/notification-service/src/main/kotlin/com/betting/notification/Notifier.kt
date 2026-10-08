package com.betting.notification

import com.betting.events.BetV1
import com.betting.events.EventJson
import com.betting.events.EventTypes
import com.betting.events.MarketV1
import com.betting.events.PayoutCalculatedV1
import com.betting.events.SettlementCompletedV1
import com.betting.events.WalletMovementV1
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/** Maps Kafka events to STOMP destinations. Every message carries the event type. */
@Component
class Notifier(private val ws: SimpMessagingTemplate) {
    @KafkaListener(
        topics = [
            EventTypes.MARKET_CREATED, EventTypes.MARKET_UPDATED, EventTypes.MARKET_FROZEN, EventTypes.MARKET_ENDED,
            EventTypes.MARKET_RESOLVED, EventTypes.MARKET_CANCELLED, EventTypes.BET_PLACED, EventTypes.BET_CANCELLED,
            EventTypes.WALLET_RESERVED, EventTypes.WALLET_DEBITED, EventTypes.WALLET_RELEASED, EventTypes.WALLET_CREDITED,
            EventTypes.PAYOUT_CALCULATED, EventTypes.SETTLEMENT_COMPLETED,
        ],
    )
    fun push(json: String) {
        val e = EventJson.read(json)
        val type = e.eventType
        when (type) {
            EventTypes.BET_PLACED, EventTypes.BET_CANCELLED -> EventJson.payload(e, BetV1::class).let {
                toTopic(MARKETS, mapOf("type" to type, "marketId" to it.marketId))
                ws.convertAndSendToUser(it.userId.toString(), "/queue/notifications", mapOf("type" to type, "betId" to it.betId,
                    "marketId" to it.marketId, "side" to it.side, "amount" to it.amount, "status" to it.status))
            }
            EventTypes.WALLET_RESERVED, EventTypes.WALLET_DEBITED, EventTypes.WALLET_RELEASED, EventTypes.WALLET_CREDITED ->
                EventJson.payload(e, WalletMovementV1::class).let {
                    ws.convertAndSendToUser(it.userId.toString(), "/queue/wallet", mapOf("type" to type, "betId" to it.betId,
                        "amount" to it.amount, "balance" to it.balance, "reserved" to it.reserved, "available" to it.balance - it.reserved))
                }
            EventTypes.PAYOUT_CALCULATED -> EventJson.payload(e, PayoutCalculatedV1::class).let { calc ->
                calc.payouts.forEach {
                    ws.convertAndSendToUser(it.userId.toString(), "/queue/notifications",
                        mapOf("type" to type, "marketId" to calc.marketId, "amount" to it.amount, "payoutType" to it.type))
                }
            }
            EventTypes.SETTLEMENT_COMPLETED -> EventJson.payload(e, SettlementCompletedV1::class).let {
                val msg = mapOf("type" to type, "marketId" to it.marketId)
                toTopic("/topic/public.leaderboard", msg)
                toTopic(MARKETS, msg)
            }
            else -> EventJson.payload(e, MarketV1::class).let {
                toTopic(MARKETS, mapOf("type" to type, "marketId" to it.marketId, "market" to it))
            }
        }
    }

    // A Map payload is ambiguous with convertAndSend(payload, headers) in Kotlin: pin the String overload.
    private fun toTopic(destination: String, payload: Map<String, Any?>) = ws.convertAndSend(destination, payload as Any)

    companion object {
        const val MARKETS = "/topic/public.markets"
    }
}
