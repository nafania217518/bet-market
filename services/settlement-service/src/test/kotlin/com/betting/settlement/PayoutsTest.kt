package com.betting.settlement

import com.betting.events.PayoutV1
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PayoutsTest {
    private val freezeAt: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val before: Instant = freezeAt.minusSeconds(60)
    private val a = UUID(0, 1)
    private val b = UUID(0, 2)
    private val c = UUID(0, 3)

    private fun bet(user: UUID, side: String, amount: Long, at: Instant = before, cancelled: Boolean = false) =
        SettlementBet(UUID.randomUUID(), user, side, amount, at, cancelled)

    private fun resolved(vararg bets: SettlementBet, side: String = "TRUE", edge: Int = 500) =
        Payouts.compute(bets.toList(), "RESOLVED", side, freezeAt, edge)

    private fun win(user: UUID, amount: Long) = PayoutV1(user, amount, "WIN")
    private fun refund(user: UUID, amount: Long) = PayoutV1(user, amount, "REFUND")

    @Test
    fun `winners share the pool after the house edge in proportion to their stakes rounded down`() {
        // Worked example: P_total 400, pool after edge 380, A gets 380, house 20.
        val s = resolved(bet(a, "TRUE", 100), bet(b, "FALSE", 300))
        assertEquals(Settlement(100, 300, 380, 20, 2, listOf(win(a, 380))), s)

        // A 100 + C 200 on TRUE, B 100 on FALSE: P_total 400 -> 380 after edge. A: floor(126.66) = 126, C: floor(253.33) = 253.
        val two = resolved(bet(a, "TRUE", 100), bet(c, "TRUE", 200), bet(b, "FALSE", 100))
        assertEquals(listOf(win(a, 126), win(c, 253)), two.payouts)
    }

    @Test
    fun `payouts plus the house amount always equal the eligible pool`() {
        val s = resolved(bet(a, "TRUE", 7), bet(c, "TRUE", 11), bet(b, "FALSE", 13), bet(b, "TRUE", 5, at = freezeAt))
        assertEquals(31, s.truePool + s.falsePool)
        assertEquals(s.truePool + s.falsePool, s.paidOut + s.houseAmount)
        assertEquals(s.paidOut, s.payouts.filter { it.type == "WIN" }.sumOf { it.amount })
    }

    @Test
    fun `the house keeps the edge and the rounding remainder`() {
        // P_total 31 -> 29.45 after edge. A: floor(7*31*0.95/18) = 11, C: floor(11*31*0.95/18) = 17. Paid 28, house 3 = 1.55 edge + 1.45 rounding.
        val s = resolved(bet(a, "TRUE", 7), bet(c, "TRUE", 11), bet(b, "FALSE", 13))
        assertEquals(listOf(win(a, 11), win(c, 17)), s.payouts)
        assertEquals(28, s.paidOut)
        assertEquals(3, s.houseAmount)
    }

    @Test
    fun `nobody on the winning side refunds every stake and the house takes nothing`() {
        val s = resolved(bet(a, "FALSE", 100), bet(b, "FALSE", 50))
        // Pools exclude refunded bets, so a full refund reports empty pools.
        assertEquals(Settlement(0, 0, 0, 0, 2, listOf(refund(a, 100), refund(b, 50))), s)
    }

    @Test
    fun `nobody on the losing side refunds every stake and the house takes nothing`() {
        val s = resolved(bet(a, "TRUE", 100), bet(b, "TRUE", 50))
        assertEquals(Settlement(0, 0, 0, 0, 2, listOf(refund(a, 100), refund(b, 50))), s)
    }

    @Test
    fun `a single bettor gets the stake back`() {
        assertEquals(listOf(refund(a, 42)), resolved(bet(a, "TRUE", 42)).payouts)
        assertEquals(listOf(refund(a, 42)), resolved(bet(a, "FALSE", 42)).payouts)
    }

    @Test
    fun `bets placed at or after freezeAt are refunded and left out of the pools`() {
        val s = resolved(bet(a, "TRUE", 100), bet(b, "FALSE", 300), bet(c, "FALSE", 50, at = freezeAt), bet(a, "TRUE", 20, at = freezeAt.plusSeconds(1)))
        assertEquals(100, s.truePool)
        assertEquals(300, s.falsePool)
        assertEquals(4, s.betCount)
        assertEquals(listOf(refund(a, 20), win(a, 380), refund(c, 50)), s.payouts)
        assertEquals(380, s.paidOut)
        assertEquals(20, s.houseAmount)
    }

    @Test
    fun `cancelled bets are ignored`() {
        val s = resolved(bet(a, "TRUE", 100), bet(b, "FALSE", 300), bet(c, "TRUE", 999, cancelled = true), bet(b, "FALSE", 5, at = freezeAt, cancelled = true))
        assertEquals(Settlement(100, 300, 380, 20, 2, listOf(win(a, 380))), s)
    }

    @Test
    fun `stakes of one user on the winning side are added before rounding`() {
        // A bets 1 + 1 on TRUE, B 1 on FALSE, edge 0. Per bet: floor(1*3/2) * 2 = 2. Added first: floor(2*3/2) = 3.
        val s = resolved(bet(a, "TRUE", 1), bet(a, "TRUE", 1), bet(b, "FALSE", 1), edge = 0)
        assertEquals(listOf(win(a, 3)), s.payouts)
        assertEquals(0, s.houseAmount)
    }

    @Test
    fun `a user with bets on both sides gets only the winning-side payout`() {
        // A: 100 TRUE + 50 FALSE, B: 250 FALSE. P_total 400 -> 380, P_win 100. A gets 380, no entry for the losing stake.
        val s = resolved(bet(a, "TRUE", 100), bet(a, "FALSE", 50), bet(b, "FALSE", 250))
        assertEquals(listOf(win(a, 380)), s.payouts)
        assertEquals(300, s.falsePool)
    }

    @Test
    fun `a cancelled market refunds every non-cancelled stake per user`() {
        val s = Payouts.compute(
            listOf(bet(a, "TRUE", 10), bet(a, "FALSE", 5), bet(b, "FALSE", 7, at = freezeAt), bet(c, "TRUE", 100, cancelled = true)),
            "CANCELLED", null, freezeAt, 500,
        )
        assertEquals(listOf(refund(a, 15), refund(b, 7)), s.payouts)
        assertEquals(0, s.paidOut)
        assertEquals(0, s.houseAmount)
        assertEquals(3, s.betCount)
    }

    @Test
    fun `a zero house edge pays out the whole pool when the stakes divide evenly`() {
        val s = resolved(bet(a, "TRUE", 100), bet(c, "TRUE", 300), bet(b, "FALSE", 400), edge = 0)
        assertEquals(listOf(win(a, 200), win(c, 600)), s.payouts)
        assertEquals(800, s.paidOut)
        assertEquals(0, s.houseAmount)
    }

    @Test
    fun `large pools do not overflow`() {
        val big = Long.MAX_VALUE / 4
        val s = resolved(bet(a, "TRUE", big), bet(c, "TRUE", big), bet(b, "FALSE", big))
        val total = 3 * big
        assertEquals(total, s.paidOut + s.houseAmount)
        // Each winner: floor(big * 3big * 0.95 / 2big) = floor(1.425 * big).
        val expected = (big.toBigInteger() * 1425.toBigInteger() / 1000.toBigInteger()).toLong()
        assertEquals(listOf(win(a, expected), win(c, expected)), s.payouts)
    }

    @Test
    fun `random markets always balance - payouts plus house equal the pool and no winner gets less than the stake times one minus the edge rounded down`() {
        val rnd = Random(42)
        repeat(1000) {
            val users = List(rnd.nextInt(1, 6)) { UUID(0, it.toLong()) }
            val bets = List(rnd.nextInt(0, 12)) {
                bet(users.random(rnd), if (rnd.nextBoolean()) "TRUE" else "FALSE", rnd.nextLong(1, 1_000_000),
                    if (rnd.nextInt(10) == 0) freezeAt else before, rnd.nextInt(10) == 0)
            }
            val edge = rnd.nextInt(0, 2001)
            val side = if (rnd.nextBoolean()) "TRUE" else "FALSE"
            val s = Payouts.compute(bets, "RESOLVED", side, freezeAt, edge)
            val eligible = bets.filter { !it.cancelled && it.placedAt < freezeAt }
            val settled = s.payouts.any { it.type == "WIN" } // else a full refund with empty pools
            assertEquals(if (settled) eligible.sumOf { it.amount } else 0, s.truePool + s.falsePool)
            assertEquals(s.truePool + s.falsePool, s.paidOut + s.houseAmount)
            assertTrue(s.houseAmount >= 0)
            assertEquals(bets.count { !it.cancelled }, s.betCount)
            s.payouts.filter { it.type == "WIN" }.forEach { w ->
                val stake = eligible.filter { it.userId == w.userId && it.side == side }.sumOf { it.amount }
                assertTrue(w.amount >= stake * (10000 - edge) / 10000, "winner gets at least stake x (1 - edge)")
            }
            val refunded = s.payouts.filter { it.type == "REFUND" }.sumOf { it.amount }
            assertEquals(bets.filter { !it.cancelled }.sumOf { it.amount }, s.paidOut + s.houseAmount + refunded)
        }
    }
}
