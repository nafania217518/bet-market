package com.betting.wallet

import com.betting.events.EventEnvelope
import com.betting.events.EventJson
import com.betting.events.EventTypes
import com.betting.events.UserRegisteredV1
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class WalletTest : IntegrationTest() {
    @Autowired lateinit var listener: UserRegisteredListener
    @Autowired lateinit var wallets: WalletRepository
    @Autowired lateinit var ledger: LedgerEntryRepository
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var mvc: MockMvc

    private fun event(userId: UUID, eventId: UUID = UUID.randomUUID()) = EventJson.write(
        EventEnvelope(eventId, EventTypes.USER_REGISTERED, 1, Instant.now(), null, null, userId.toString(), "user-service",
            EventJson.toNode(UserRegisteredV1(userId, "a@b.c", "Al"))),
    )

    private fun ledgerOf(userId: UUID) = ledger.findAll().filter { it.userId == userId }

    @Test
    fun `user registered creates a wallet with the starting balance`() {
        val userId = UUID.randomUUID()
        listener.onUserRegistered(event(userId))
        val wallet = wallets.findById(userId).orElseThrow()
        assertEquals(1000, wallet.balance)
        assertEquals(0, wallet.reserved)
    }

    @Test
    fun `user registered appends one CREDIT ledger entry equal to the starting balance`() {
        val userId = UUID.randomUUID()
        listener.onUserRegistered(event(userId))
        val entry = ledgerOf(userId).single()
        assertEquals("CREDIT", entry.type)
        assertEquals(1000, entry.amount)
        assertEquals(1000, entry.balanceAfter)
        assertEquals(null, entry.betId)
    }

    @Test
    fun `user registered emits wallet created through the outbox`() {
        val userId = UUID.randomUUID()
        listener.onUserRegistered(event(userId))
        val payload = jdbc.sql("SELECT payload::text FROM outbox_event WHERE event_type = ? AND aggregate_id = ?")
            .params(EventTypes.WALLET_CREATED, userId.toString()).query(String::class.java).single()
        val created = EventJson.payload(EventJson.read(payload), com.betting.events.WalletCreatedV1::class)
        assertEquals(com.betting.events.WalletCreatedV1(userId, 1000), created)
    }

    @Test
    fun `duplicate user registered delivery creates only one wallet and one ledger entry`() {
        val userId = UUID.randomUUID()
        val json = event(userId)
        listener.onUserRegistered(json)
        listener.onUserRegistered(json)
        assertEquals(1, wallets.findAll().count { it.userId == userId })
        assertEquals(1, ledgerOf(userId).size)
    }

    @Test
    fun `a second user registered event with a new event id for an existing user does not change the wallet`() {
        val userId = UUID.randomUUID()
        listener.onUserRegistered(event(userId))
        listener.onUserRegistered(event(userId))
        assertEquals(1000, wallets.findById(userId).orElseThrow().balance)
        assertEquals(1, ledgerOf(userId).size)
    }

    @Test
    fun `user registered published to Kafka creates the wallet end to end`() {
        val userId = UUID.randomUUID()
        kafka.send(EventTypes.USER_REGISTERED, userId.toString(), event(userId)).get()
        await.atMost(Duration.ofSeconds(30)).untilAsserted { assertNotNull(wallets.findById(userId).orElse(null)) }
    }

    @Test
    fun `GET wallets me returns balance, reserved, and available for the X-User-Id user`() {
        val userId = UUID.randomUUID()
        listener.onUserRegistered(event(userId))
        jdbc.sql("UPDATE wallets SET reserved = 300 WHERE user_id = ?").param(userId).update()
        mvc.get("/api/wallets/me") { header("X-User-Id", userId.toString()) }.andExpect {
            status { isOk() }
            jsonPath("$.userId") { value(userId.toString()) }
            jsonPath("$.balance") { value(1000) }
            jsonPath("$.reserved") { value(300) }
            jsonPath("$.available") { value(700) }
        }
    }

    @Test
    fun `GET wallets me returns 404 WALLET_NOT_FOUND when the user has no wallet`() {
        mvc.get("/api/wallets/me") { header("X-User-Id", UUID.randomUUID().toString()) }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("WALLET_NOT_FOUND") }
        }
    }

    @Test
    fun `GET wallets me returns 400 VALIDATION_ERROR when X-User-Id is missing or not a UUID`() {
        mvc.get("/api/wallets/me").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
        }
        mvc.get("/api/wallets/me") { header("X-User-Id", "nope") }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
        }
    }

    @Test
    fun `the database rejects a negative balance`() {
        val userId = UUID.randomUUID()
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.sql("INSERT INTO wallets(user_id, balance) VALUES (?, -1)").param(userId).update()
        }
        listener.onUserRegistered(event(userId))
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.sql("UPDATE wallets SET balance = -5 WHERE user_id = ?").param(userId).update()
        }
    }

    private fun ledgerRow(userId: UUID, amount: Long, at: String) {
        jdbc.sql("INSERT INTO wallets(user_id, balance) VALUES (?, 0) ON CONFLICT DO NOTHING").param(userId).update()
        jdbc.sql("INSERT INTO ledger_entries(id, user_id, bet_id, type, amount, balance_after, created_at) VALUES (?, ?, NULL, 'CREDIT', ?, ?, ?::timestamptz)")
            .params(UUID.randomUUID(), userId, amount, amount, at).update()
    }

    @Test
    fun `GET wallets me ledger returns the caller's entries newest first`() {
        val userId = UUID.randomUUID()
        ledgerRow(userId, 1, "2026-01-01T00:00:00Z")
        ledgerRow(userId, 2, "2026-01-03T00:00:00Z")
        ledgerRow(userId, 3, "2026-01-02T00:00:00Z")
        ledgerRow(UUID.randomUUID(), 9, "2026-01-04T00:00:00Z")
        mvc.get("/api/wallets/me/ledger") { header("X-User-Id", userId.toString()) }.andExpect {
            status { isOk() }
            jsonPath("$.entries.length()") { value(3) }
            jsonPath("$.entries[0].amount") { value(2) }
            jsonPath("$.entries[1].amount") { value(3) }
            jsonPath("$.entries[2].amount") { value(1) }
            jsonPath("$.entries[0].id") { exists() }
            jsonPath("$.entries[0].type") { value("CREDIT") }
            jsonPath("$.entries[0].balanceAfter") { value(2) }
            jsonPath("$.entries[0].createdAt") { exists() }
            jsonPath("$.page") { value(0) }
            jsonPath("$.size") { value(20) }
        }
    }

    @Test
    fun `GET wallets me ledger pages with page and size`() {
        val userId = UUID.randomUUID()
        (1..5).forEach { ledgerRow(userId, it.toLong(), "2026-01-0${it}T00:00:00Z") }
        mvc.get("/api/wallets/me/ledger?page=1&size=2") { header("X-User-Id", userId.toString()) }.andExpect {
            status { isOk() }
            jsonPath("$.entries.length()") { value(2) }
            jsonPath("$.entries[0].amount") { value(3) }
            jsonPath("$.entries[1].amount") { value(2) }
            jsonPath("$.page") { value(1) }
            jsonPath("$.size") { value(2) }
        }
    }

    @Test
    fun `GET wallets me ledger returns an empty list for a user without entries`() {
        mvc.get("/api/wallets/me/ledger") { header("X-User-Id", UUID.randomUUID().toString()) }.andExpect {
            status { isOk() }
            jsonPath("$.entries.length()") { value(0) }
        }
    }

    @Test
    fun `GET wallets me ledger returns 400 VALIDATION_ERROR for a bad X-User-Id, page below 0, or size outside 1-100`() {
        val id = UUID.randomUUID().toString()
        listOf("nope" to "", id to "?page=-1", id to "?size=0", id to "?size=101").forEach { (h, q) ->
            mvc.get("/api/wallets/me/ledger$q") { header("X-User-Id", h) }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("VALIDATION_ERROR") }
            }
        }
    }
}
