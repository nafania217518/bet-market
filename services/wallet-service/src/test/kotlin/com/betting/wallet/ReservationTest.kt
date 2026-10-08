package com.betting.wallet

import com.betting.events.BetV1
import com.betting.events.EventEnvelope
import com.betting.events.EventJson
import com.betting.events.EventTypes
import com.betting.events.UserRegisteredV1
import com.betting.events.WalletMovementV1
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReservationTest : IntegrationTest() {
    @Autowired lateinit var userListener: UserRegisteredListener
    @Autowired lateinit var betListener: BetListener
    @Autowired lateinit var wallets: WalletRepository
    @Autowired lateinit var ledger: LedgerEntryRepository
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var mvc: MockMvc

    private fun newWallet(): UUID {
        val userId = UUID.randomUUID()
        userListener.onUserRegistered(envelope(EventTypes.USER_REGISTERED, userId.toString(), UserRegisteredV1(userId, "a@b.c", "Al")))
        return userId
    }

    private fun envelope(type: String, key: String, payload: Any, eventId: UUID = UUID.randomUUID()) = EventJson.write(
        EventEnvelope(eventId, type, 1, Instant.now(), null, null, key, "test", EventJson.toNode(payload)),
    )

    private fun bet(type: String, userId: UUID, betId: UUID, amount: Long = 100, eventId: UUID = UUID.randomUUID()) =
        envelope(type, UUID.randomUUID().toString(), BetV1(betId, UUID.randomUUID(), userId, "TRUE", amount, "ACCEPTED", Instant.now()), eventId)

    private fun reserve(userId: UUID, betId: UUID, amount: Long) =
        mvc.post("/internal/wallets/$userId/reservations") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"betId":"$betId","amount":$amount}"""
        }

    private fun reserved(userId: UUID, amount: Long = 100): UUID {
        val betId = UUID.randomUUID()
        reserve(userId, betId, amount).andExpect { status { isCreated() } }
        return betId
    }

    private fun wallet(userId: UUID) = wallets.findById(userId).orElseThrow()
    private fun ledgerOf(userId: UUID) = ledger.findAll().filter { it.userId == userId }
    private fun types(userId: UUID) = ledgerOf(userId).sortedBy { it.createdAt }.map { it.type }
    private fun status(betId: UUID) =
        jdbc.sql("SELECT status FROM reservations WHERE bet_id = ?").param(betId).query(String::class.java).single()

    private fun events(type: String, userId: UUID) =
        jdbc.sql("SELECT payload::text FROM outbox_event WHERE event_type = ? AND aggregate_id = ? ORDER BY created_at")
            .params(type, userId.toString()).query(String::class.java).list()
            .map { EventJson.payload(EventJson.read(it!!), WalletMovementV1::class) }

    @Test
    fun `reserve holds tokens - reserved grows, balance stays, a RESERVE ledger entry and wallet reserved are written`() {
        val userId = newWallet()
        val betId = UUID.randomUUID()
        reserve(userId, betId, 300).andExpect {
            status { isCreated() }
            jsonPath("$.reservationId") { value(betId.toString()) }
        }
        assertEquals(1000, wallet(userId).balance)
        assertEquals(300, wallet(userId).reserved)
        assertEquals("RESERVED", status(betId))
        val entry = ledgerOf(userId).single { it.type == "RESERVE" }
        assertEquals(300, entry.amount)
        assertEquals(1000, entry.balanceAfter)
        assertEquals(betId, entry.betId)
        assertEquals(listOf(WalletMovementV1(userId, betId, 300, 1000, 300)), events(EventTypes.WALLET_RESERVED, userId))
    }

    @Test
    fun `reserve returns 409 INSUFFICIENT_FUNDS when available is too low and changes nothing`() {
        val userId = newWallet()
        reserved(userId, 800)
        reserve(userId, UUID.randomUUID(), 201).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("INSUFFICIENT_FUNDS") }
        }
        assertEquals(800, wallet(userId).reserved)
        assertEquals(listOf("CREDIT", "RESERVE"), types(userId))
        assertEquals(1, events(EventTypes.WALLET_RESERVED, userId).size)
    }

    @Test
    fun `reserve returns 404 WALLET_NOT_FOUND for an unknown user and 400 VALIDATION_ERROR for an amount below 1`() {
        reserve(UUID.randomUUID(), UUID.randomUUID(), 10).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("WALLET_NOT_FOUND") }
        }
        reserve(newWallet(), UUID.randomUUID(), 0).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
        }
    }

    @Test
    fun `reserve with the same bet id and amount returns 200 with the same reservation and reserves once, a different amount returns 409 RESERVATION_CONFLICT`() {
        val userId = newWallet()
        val betId = reserved(userId, 100)
        reserve(userId, betId, 100).andExpect {
            status { isOk() }
            jsonPath("$.reservationId") { value(betId.toString()) }
        }
        reserve(userId, betId, 150).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("RESERVATION_CONFLICT") }
        }
        reserve(newWallet(), betId, 100).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("RESERVATION_CONFLICT") }
        }
        assertEquals(100, wallet(userId).reserved)
        assertEquals(1, ledgerOf(userId).count { it.type == "RESERVE" })
    }

    @Test
    fun `concurrent reservations on one wallet never overdraw`() {
        val userId = newWallet()
        val pool = Executors.newFixedThreadPool(20)
        val codes = try {
            pool.invokeAll((1..20).map { Callable { reserve(userId, UUID.randomUUID(), 100).andReturn().response.status } })
                .map { it.get() }
        } finally {
            pool.shutdown()
        }
        assertEquals(10, codes.count { it == 201 })
        assertEquals(10, codes.count { it == 409 })
        assertEquals(1000, wallet(userId).reserved)
        assertEquals(1000, wallet(userId).balance)
    }

    @Test
    fun `bet placed commits the reservation - balance and reserved drop by the amount, a DEBIT entry and wallet debited are written`() {
        val userId = newWallet()
        val betId = reserved(userId, 100)
        betListener.onBetPlaced(bet(EventTypes.BET_PLACED, userId, betId))
        assertEquals(900, wallet(userId).balance)
        assertEquals(0, wallet(userId).reserved)
        assertEquals("COMMITTED", status(betId))
        assertEquals(900, ledgerOf(userId).single { it.type == "DEBIT" }.balanceAfter)
        assertEquals(listOf(WalletMovementV1(userId, betId, 100, 900, 0)), events(EventTypes.WALLET_DEBITED, userId))
    }

    @Test
    fun `duplicate bet placed delivery debits once`() {
        val userId = newWallet()
        val betId = reserved(userId, 100)
        val json = bet(EventTypes.BET_PLACED, userId, betId)
        betListener.onBetPlaced(json)
        betListener.onBetPlaced(json)
        betListener.onBetPlaced(bet(EventTypes.BET_PLACED, userId, betId))
        assertEquals(900, wallet(userId).balance)
        assertEquals(1, ledgerOf(userId).count { it.type == "DEBIT" })
        assertEquals(1, events(EventTypes.WALLET_DEBITED, userId).size)
    }

    @Test
    fun `bet cancelled releases a RESERVED reservation and refunds a COMMITTED one with a CREDIT entry and wallet credited`() {
        val userId = newWallet()
        val open = reserved(userId, 100)
        val placed = reserved(userId, 200)
        betListener.onBetPlaced(bet(EventTypes.BET_PLACED, userId, placed))
        betListener.onBetCancelled(bet(EventTypes.BET_CANCELLED, userId, open))
        betListener.onBetCancelled(bet(EventTypes.BET_CANCELLED, userId, placed))
        betListener.onBetCancelled(bet(EventTypes.BET_CANCELLED, userId, placed))
        assertEquals("RELEASED", status(open))
        assertEquals("REFUNDED", status(placed))
        assertEquals(1000, wallet(userId).balance)
        assertEquals(0, wallet(userId).reserved)
        assertEquals(listOf("CREDIT", "RESERVE", "RESERVE", "DEBIT", "RELEASE", "CREDIT"), types(userId))
        assertEquals(listOf(WalletMovementV1(userId, open, 100, 800, 0)), events(EventTypes.WALLET_RELEASED, userId))
        assertEquals(listOf(WalletMovementV1(userId, placed, 200, 1000, 0)), events(EventTypes.WALLET_CREDITED, userId))
    }

    @Test
    fun `bet placed after bet cancelled does not debit`() {
        val userId = newWallet()
        val betId = reserved(userId, 100)
        betListener.onBetCancelled(bet(EventTypes.BET_CANCELLED, userId, betId))
        betListener.onBetPlaced(bet(EventTypes.BET_PLACED, userId, betId))
        assertEquals("RELEASED", status(betId))
        assertEquals(1000, wallet(userId).balance)
        assertEquals(0, wallet(userId).reserved)
        assertEquals(0, ledgerOf(userId).count { it.type == "DEBIT" })
    }

    @Test
    fun `bet failed releases a RESERVED reservation and is a no-op for other states and unknown bets`() {
        val userId = newWallet()
        val open = reserved(userId, 100)
        val placed = reserved(userId, 200)
        betListener.onBetPlaced(bet(EventTypes.BET_PLACED, userId, placed))
        betListener.onBetFailed(bet(EventTypes.BET_FAILED, userId, open))
        betListener.onBetFailed(bet(EventTypes.BET_FAILED, userId, open))
        betListener.onBetFailed(bet(EventTypes.BET_FAILED, userId, placed))
        betListener.onBetFailed(bet(EventTypes.BET_FAILED, userId, UUID.randomUUID()))
        assertEquals("RELEASED", status(open))
        assertEquals("COMMITTED", status(placed))
        assertEquals(800, wallet(userId).balance)
        assertEquals(0, wallet(userId).reserved)
        assertEquals(1, ledgerOf(userId).count { it.type == "RELEASE" })
    }

    @Test
    fun `release endpoint releases a RESERVED reservation, is idempotent, returns 409 for COMMITTED and 404 for an unknown bet`() {
        val userId = newWallet()
        val open = reserved(userId, 100)
        repeat(2) {
            mvc.post("/internal/wallets/reservations/$open/release").andExpect {
                status { isOk() }
                jsonPath("$.reservationId") { value(open.toString()) }
                jsonPath("$.status") { value("RELEASED") }
            }
        }
        assertEquals(0, wallet(userId).reserved)
        assertEquals(1, ledgerOf(userId).count { it.type == "RELEASE" })
        val placed = reserved(userId, 100)
        betListener.onBetPlaced(bet(EventTypes.BET_PLACED, userId, placed))
        mvc.post("/internal/wallets/reservations/$placed/release").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("INVALID_RESERVATION_STATE") }
        }
        mvc.post("/internal/wallets/reservations/${UUID.randomUUID()}/release").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("RESERVATION_NOT_FOUND") }
        }
    }

    @Test
    fun `bet placed or bet cancelled for an unknown reservation throws so the error handler can retry and dead-letter it`() {
        val userId = newWallet()
        val eventId = UUID.randomUUID()
        assertFailsWith<IllegalStateException> { betListener.onBetPlaced(bet(EventTypes.BET_PLACED, userId, UUID.randomUUID(), eventId = eventId)) }
        assertFailsWith<IllegalStateException> { betListener.onBetCancelled(bet(EventTypes.BET_CANCELLED, userId, UUID.randomUUID())) }
        // The failed event is not marked processed, so a retry runs again.
        assertEquals(0, jdbc.sql("SELECT count(*) FROM processed_event WHERE event_id = ?").param(eventId).query(Int::class.java).single())
    }

    @Test
    fun `the ledger explains the balance - starting credit minus debits plus refunds equals the balance after a mixed sequence`() {
        val userId = newWallet()
        val a = reserved(userId, 100)
        val b = reserved(userId, 250)
        val c = reserved(userId, 300)
        reserved(userId, 50)
        betListener.onBetPlaced(bet(EventTypes.BET_PLACED, userId, a))
        betListener.onBetPlaced(bet(EventTypes.BET_PLACED, userId, b))
        betListener.onBetCancelled(bet(EventTypes.BET_CANCELLED, userId, b))
        betListener.onBetFailed(bet(EventTypes.BET_FAILED, userId, c))
        val entries = ledgerOf(userId)
        val explained = entries.filter { it.type == "CREDIT" }.sumOf { it.amount } - entries.filter { it.type == "DEBIT" }.sumOf { it.amount }
        assertEquals(900, wallet(userId).balance)
        assertEquals(wallet(userId).balance, explained)
        assertEquals(50, wallet(userId).reserved)
    }

    @Test
    fun `bet placed published to Kafka commits the reservation end to end`() {
        val userId = newWallet()
        val betId = reserved(userId, 100)
        val marketId = UUID.randomUUID()
        val json = EventJson.write(
            EventEnvelope(UUID.randomUUID(), EventTypes.BET_PLACED, 1, Instant.now(), null, null, betId.toString(), "bet-service",
                EventJson.toNode(BetV1(betId, marketId, userId, "TRUE", 100, "ACCEPTED", Instant.now()))),
        )
        kafka.send(EventTypes.BET_PLACED, marketId.toString(), json).get()
        await.atMost(Duration.ofSeconds(30)).untilAsserted { assertEquals(900, wallet(userId).balance) }
    }
}
