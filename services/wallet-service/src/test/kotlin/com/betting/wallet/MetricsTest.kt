package com.betting.wallet

import com.betting.events.EventJson
import com.betting.events.EventEnvelope
import com.betting.events.EventTypes
import com.betting.events.UserRegisteredV1
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsTest : IntegrationTest() {
    @Autowired lateinit var userListener: UserRegisteredListener
    @Autowired lateinit var registry: MeterRegistry
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var mvc: MockMvc

    private fun newWallet(): UUID {
        val userId = UUID.randomUUID()
        userListener.onUserRegistered(EventJson.write(EventEnvelope(UUID.randomUUID(), EventTypes.USER_REGISTERED, 1, Instant.now(), null, null,
            userId.toString(), "test", EventJson.toNode(UserRegisteredV1(userId, "a@b.c", "Al")))))
        return userId
    }

    private fun reserve(userId: UUID, betId: UUID, amount: Long) = mvc.post("/internal/wallets/$userId/reservations") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"betId":"$betId","amount":$amount}"""
    }

    private fun count(result: String) = registry.find("wallet.reservations").tag("result", result).counter()?.count() ?: 0.0
    private fun mismatch() = registry.get("wallet.balance.mismatch").gauge().value()

    @Test
    fun `a new reservation counts as reserved`() {
        val before = count("reserved")
        reserve(newWallet(), UUID.randomUUID(), 10)
        assertEquals(before + 1, count("reserved"))
    }

    @Test
    fun `a repeated reservation counts as replay`() {
        val userId = newWallet()
        val betId = UUID.randomUUID()
        reserve(userId, betId, 10)
        val before = count("replay")
        reserve(userId, betId, 10).andExpect { status { isOk() } }
        assertEquals(before + 1, count("replay"))
    }

    @Test
    fun `a reservation without funds counts as INSUFFICIENT_FUNDS`() {
        val before = count("INSUFFICIENT_FUNDS")
        reserve(newWallet(), UUID.randomUUID(), 1_000_000)
        assertEquals(before + 1, count("INSUFFICIENT_FUNDS"))
    }

    @Test
    fun `wallet balance mismatch is 0 when every balance matches its ledger`() {
        // Other tests insert raw rows; repair every wallet so the check starts clean.
        jdbc.sql("""UPDATE wallets w SET balance = COALESCE((SELECT SUM(CASE type WHEN 'CREDIT' THEN amount WHEN 'DEBIT' THEN -amount ELSE 0 END)
                    FROM ledger_entries l WHERE l.user_id = w.user_id), 0), reserved = 0""").update()
        reserve(newWallet(), UUID.randomUUID(), 10)
        assertEquals(0.0, mismatch())
    }

    @Test
    fun `wallet balance mismatch counts a wallet whose balance was changed without a ledger entry`() {
        val userId = newWallet()
        val before = mismatch()
        jdbc.sql("UPDATE wallets SET balance = balance + 1 WHERE user_id = ?").param(userId).update()
        assertEquals(before + 1, mismatch())
    }

    @Test
    fun `actuator prometheus exposes wallet reservations total and wallet balance mismatch`() {
        reserve(newWallet(), UUID.randomUUID(), 10)
        val text = mvc.get("/actuator/prometheus").andReturn().response.contentAsString
        assertTrue("wallet_reservations_total" in text)
        assertTrue("wallet_balance_mismatch" in text)
    }
}
