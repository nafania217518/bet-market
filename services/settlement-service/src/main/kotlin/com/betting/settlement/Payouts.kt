package com.betting.settlement

import com.betting.events.PayoutV1
import java.time.Instant
import java.util.UUID

/** One row of settlement_bet. */
data class SettlementBet(val betId: UUID, val userId: UUID, val side: String, val amount: Long, val placedAt: Instant, val cancelled: Boolean = false)

data class Settlement(val truePool: Long, val falsePool: Long, val paidOut: Long, val houseAmount: Long, val betCount: Int, val payouts: List<PayoutV1>)

/** Pari-mutuel payout math (architecture §7). Pure: no I/O. */
object Payouts {
    fun compute(bets: List<SettlementBet>, outcome: String, winningSide: String?, freezeAt: Instant, edgeBps: Int): Settlement {
        val live = bets.filter { !it.cancelled } // 1. the wallet already refunded cancelled bets
        if (outcome == "CANCELLED") return refundAll(live) // 2.
        val (late, eligible) = live.partition { it.placedAt >= freezeAt } // 3. freeze race
        val truePool = eligible.filter { it.side == "TRUE" }.sumOf { it.amount } // 4.
        val falsePool = eligible.filter { it.side == "FALSE" }.sumOf { it.amount }
        val total = truePool + falsePool
        val winPool = if (winningSide == "TRUE") truePool else falsePool
        if (winPool == 0L || winPool == total) return refundAll(live) // 5.

        // 6. Stakes per user are added before rounding. BigInteger: stake x total x 10000 overflows Long on big pools.
        val wins = eligible.filter { it.side == winningSide }.groupBy { it.userId }.map { (user, userBets) ->
            val stake = userBets.sumOf { it.amount }.toBigInteger()
            val amount = stake * total.toBigInteger() * (10000 - edgeBps).toBigInteger() / (winPool.toBigInteger() * 10000.toBigInteger())
            PayoutV1(user, amount.toLong(), "WIN")
        }
        val paidOut = wins.sumOf { it.amount }
        val refunds = late.map { PayoutV1(it.userId, it.amount, "REFUND") }
        return Settlement(truePool, falsePool, paidOut, total - paidOut, live.size, merge(wins + refunds)) // 7.
    }

    // Pools exclude refunded bets (SettlementCompletedV1), so a full refund reports empty pools.
    private fun refundAll(bets: List<SettlementBet>) =
        Settlement(0, 0, 0, 0, bets.size, merge(bets.map { PayoutV1(it.userId, it.amount, "REFUND") }))

    // 8. One entry per (user, type), no zero amounts, sorted for stable output.
    private fun merge(payouts: List<PayoutV1>): List<PayoutV1> =
        payouts.groupBy { it.userId to it.type }
            .map { (key, list) -> PayoutV1(key.first, list.sumOf { it.amount }, key.second) }
            .filter { it.amount > 0 }
            .sortedWith(compareBy({ it.userId }, { it.type }))
}
