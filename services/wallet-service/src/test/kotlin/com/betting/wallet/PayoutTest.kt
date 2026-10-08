package com.betting.wallet

import com.betting.events.BetV1
import com.betting.events.EventEnvelope
import com.betting.events.EventJson
import com.betting.events.EventTypes
import com.betting.events.PayoutCalculatedV1
import com.betting.events.PayoutV1
import com.betting.events.UserRegisteredV1
import com.betting.events.WalletMovementV1
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PayoutTest : IntegrationTest() {
    @Autowired lateinit var userListener: UserRegisteredListener
    @Autowired lateinit var betListener: BetListener
    @Autowired lateinit var payoutListener: PayoutListener
    @Autowired lateinit var moves: WalletMoves
    @Autowired lateinit var wallets: WalletRepository
    @Autowired lateinit var ledger: LedgerEntryRepository
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var kafka: KafkaTemplate<String, String>

    private val marketId = UUID.randomUUID()

    private fun envelope(type: String, key: String, payload: Any, eventId: UUID = UUID.randomUUID()) = EventJson.write(
        EventEnvelope(eventId, type, 1, Instant.now(), null, null, key, "test", EventJson.toNode(payload)),
    )

    private fun newWallet(): UUID {
        val userId = UUID.randomUUID()
        userListener.onUserRegistered(envelope(EventTypes.USER_REGISTERED, userId.toString(), UserRegisteredV1(userId, "a@b.c", "Al")))
        return userId
    }

    private fun payout(vararg payouts: PayoutV1, eventId: UUID = UUID.randomUUID()) =
        envelope(EventTypes.PAYOUT_CALCULATED, marketId.toString(), PayoutCalculatedV1(marketId, payouts.toList()), eventId)

    private fun wallet(userId: UUID) = wallets.findById(userId).orElseThrow()
    private fun ledgerOf(userId: UUID) = ledger.findAll().filter { it.userId == userId }
    private fun credited(userId: UUID) =
        jdbc.sql("SELECT payload::text FROM outbox_event WHERE event_type = ? AND aggregate_id = ? ORDER BY created_at")
            .params(EventTypes.WALLET_CREDITED, userId.toString()).query(String::class.java).list()
            .map { EventJson.payload(EventJson.read(it!!), WalletMovementV1::class) }

    @Test
    fun `payout calculated credits every user with a CREDIT entry and wallet credited`() {
        val a = newWallet()
        val b = newWallet()
        val zero = newWallet()
        payoutListener.onPayoutCalculated(payout(PayoutV1(a, 250, "WIN"), PayoutV1(b, 40, "REFUND"), PayoutV1(zero, 0, "WIN")))
        assertEquals(1250, wallet(a).balance)
        assertEquals(1040, wallet(b).balance)
        assertEquals(1000, wallet(zero).balance)
        val entry = ledgerOf(a).single { it.amount == 250L }
        assertEquals("CREDIT", entry.type)
        assertEquals(null, entry.betId)
        assertEquals(1250, entry.balanceAfter)
        assertEquals(listOf(WalletMovementV1(a, null, 250, 1250, 0)), credited(a))
        assertEquals(listOf(WalletMovementV1(b, null, 40, 1040, 0)), credited(b))
        assertEquals(emptyList(), credited(zero))
        assertEquals(1, ledgerOf(zero).size)
    }

    @Test
    fun `a WIN and a REFUND for the same user are both credited`() {
        val a = newWallet()
        payoutListener.onPayoutCalculated(payout(PayoutV1(a, 300, "WIN"), PayoutV1(a, 50, "REFUND")))
        assertEquals(1350, wallet(a).balance)
        assertEquals(listOf(300L, 50L), credited(a).map { it.amount })
        assertEquals(listOf(1300L, 1350L), credited(a).map { it.balance })
    }

    @Test
    fun `duplicate payout calculated delivery credits once`() {
        val a = newWallet()
        val json = payout(PayoutV1(a, 100, "WIN"))
        payoutListener.onPayoutCalculated(json)
        payoutListener.onPayoutCalculated(json)
        assertEquals(1100, wallet(a).balance)
        assertEquals(1, credited(a).size)
    }

    @Test
    fun `payout calculated for a user without a wallet throws and credits nobody`() {
        val a = newWallet()
        val eventId = UUID.randomUUID()
        assertFailsWith<IllegalStateException> {
            payoutListener.onPayoutCalculated(payout(PayoutV1(a, 100, "WIN"), PayoutV1(UUID.randomUUID(), 100, "WIN"), eventId = eventId))
        }
        assertEquals(1000, wallet(a).balance)
        assertEquals(emptyList(), credited(a))
        assertEquals(0, jdbc.sql("SELECT count(*) FROM processed_event WHERE event_id = ?").param(eventId).query(Int::class.java).single())
    }

    @Test
    fun `payout calculated published to Kafka credits the wallets end to end`() {
        val a = newWallet()
        kafka.send(EventTypes.PAYOUT_CALCULATED, marketId.toString(), payout(PayoutV1(a, 70, "WIN"))).get()
        await.atMost(Duration.ofSeconds(30)).untilAsserted { assertEquals(1070, wallet(a).balance) }
    }

    @Test
    fun `the ledger explains the balance after a payout`() {
        val a = newWallet()
        val won = UUID.randomUUID()
        val refunded = UUID.randomUUID()
        moves.reserve(a, won, 200)
        moves.reserve(a, refunded, 100)
        betListener.onBetPlaced(envelope(EventTypes.BET_PLACED, won.toString(), BetV1(won, marketId, a, "TRUE", 200, "ACCEPTED", Instant.now())))
        betListener.onBetPlaced(envelope(EventTypes.BET_PLACED, refunded.toString(), BetV1(refunded, marketId, a, "TRUE", 100, "ACCEPTED", Instant.now())))
        betListener.onBetCancelled(envelope(EventTypes.BET_CANCELLED, refunded.toString(), BetV1(refunded, marketId, a, "TRUE", 100, "CANCELLED", Instant.now())))
        payoutListener.onPayoutCalculated(payout(PayoutV1(a, 380, "WIN")))
        val entries = ledgerOf(a)
        val explained = entries.filter { it.type == "CREDIT" }.sumOf { it.amount } - entries.filter { it.type == "DEBIT" }.sumOf { it.amount }
        assertEquals(1180, wallet(a).balance)
        assertEquals(wallet(a).balance, explained)
    }
}
