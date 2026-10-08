package com.betting.bet

import com.betting.events.BetV1
import com.betting.events.EventJson
import com.betting.events.EventTypes
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.springframework.test.web.servlet.ResultActionsDsl
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaceBetTest : IntegrationTest() {
    private fun ResultActionsDsl.expectError(status: Int, code: String) = andExpect {
        status { isEqualTo(status) }
        jsonPath("$.code") { value(code) }
    }

    @Test
    fun `place bet reserves the stake in the wallet, stores an ACCEPTED bet, writes bet placed keyed by market id, and returns 202`() {
        val market = snapshot()
        val result = place(body(market, "FALSE", 25)).andExpect {
            status { isAccepted() }
            jsonPath("$.status") { value("ACCEPTED") }
        }
        val betId = betId(result)

        val reserve = FakeWallet.reserves().single()
        assertEquals("/internal/wallets/$user/reservations", reserve.path)
        val sent = EventJson.mapper.readTree(reserve.body)
        assertEquals(betId.toString(), sent.get("betId").asString())
        assertEquals(25L, sent.get("amount").asLong())

        val row = jdbc.sql("SELECT market_id, user_id, side, amount, status FROM bets WHERE id = ?").param(betId)
            .query { rs, _ -> listOf(rs.getObject(1), rs.getObject(2), rs.getString(3), rs.getLong(4), rs.getString(5)) }.single()
        assertEquals(listOf<Any>(market, user, "FALSE", 25L, "ACCEPTED"), row)

        val (type, json) = outbox(market).single()
        assertEquals(EventTypes.BET_PLACED, type)
        val event = EventJson.payload(EventJson.read(json), BetV1::class)
        assertEquals(listOf<Any?>(betId, market, user, "FALSE", 25L, "ACCEPTED", null),
            listOf(event.betId, event.marketId, event.userId, event.side, event.amount, event.status, event.reason))
    }

    @Test
    fun `place bet returns 400 VALIDATION_ERROR for a bad side, an amount outside minBet and maxBet, or a missing Idempotency-Key or X-User-Id`() {
        val market = snapshot(minBet = 5, maxBet = 50)
        place(body(market, side = "MAYBE")).expectError(400, "VALIDATION_ERROR")
        place(body(market, amount = 4)).expectError(400, "VALIDATION_ERROR")
        place(body(market, amount = 51)).expectError(400, "VALIDATION_ERROR")
        place(body(market), key = null).expectError(400, "VALIDATION_ERROR")
        place(body(market), key = "x".repeat(101)).expectError(400, "VALIDATION_ERROR")
        place(body(market), userId = null).expectError(400, "VALIDATION_ERROR")
        place(body(market), userId = "not-a-uuid").expectError(400, "VALIDATION_ERROR")
        place("""{"side":"TRUE"}""").expectError(400, "VALIDATION_ERROR")
        assertEquals(0, FakeWallet.calls.size)
        assertEquals(0, betCount(market))
    }

    @Test
    fun `place bet returns 404 MARKET_NOT_FOUND without a snapshot and 409 MARKET_NOT_OPEN when the market is not OPEN or freezeAt has passed, without calling the wallet`() {
        place(body(UUID.randomUUID())).expectError(404, "MARKET_NOT_FOUND")
        place(body(snapshot(status = "FROZEN"))).expectError(409, "MARKET_NOT_OPEN")
        place(body(snapshot(freezeAt = Instant.now().minusSeconds(1)))).expectError(409, "MARKET_NOT_OPEN")
        assertEquals(0, FakeWallet.calls.size)
    }

    @Test
    fun `place bet passes 409 INSUFFICIENT_FUNDS and 404 WALLET_NOT_FOUND from the wallet and stores nothing`() {
        val market = snapshot()
        FakeWallet.reserveReply = Reply(409, """{"code":"INSUFFICIENT_FUNDS","message":"no"}""")
        place(body(market)).expectError(409, "INSUFFICIENT_FUNDS")
        FakeWallet.reserveReply = Reply(404, """{"code":"WALLET_NOT_FOUND","message":"no"}""")
        place(body(market)).expectError(404, "WALLET_NOT_FOUND")
        assertEquals(0, betCount(market))
        assertEquals(0, outbox(market).size)
    }

    @Test
    fun `a wallet timeout or 5xx returns 503 WALLET_UNAVAILABLE and releases the reservation`() {
        val market = snapshot()
        FakeWallet.reserveReply = Reply(500)
        place(body(market)).expectError(503, "WALLET_UNAVAILABLE")
        FakeWallet.reserveReply = Reply(201, delayMs = 4000)
        place(body(market)).expectError(503, "WALLET_UNAVAILABLE")

        val reserved = FakeWallet.reserves().map { EventJson.mapper.readTree(it.body).get("betId").asString() }
        val released = FakeWallet.releases().map { it.path.removePrefix("/internal/wallets/reservations/").removeSuffix("/release") }
        assertEquals(reserved, released)
        assertEquals(0, betCount(market))
    }

    @Test
    fun `replaying an Idempotency-Key with the same payload returns the original bet without a second reservation`() {
        val market = snapshot()
        val first = betId(place(body(market), key = "k1"))
        val again = place(body(market), key = "k1").andExpect {
            status { isAccepted() }
            jsonPath("$.status") { value("ACCEPTED") }
        }
        assertEquals(first, betId(again))
        assertEquals(1, FakeWallet.reserves().size)
        assertEquals(1, betCount(market))
    }

    @Test
    fun `an Idempotency-Key reused with a different payload returns 409 DUPLICATE_BET`() {
        val market = snapshot()
        place(body(market, amount = 10), key = "k2").andExpect { status { isAccepted() } }
        place(body(market, amount = 11), key = "k2").expectError(409, "DUPLICATE_BET")
        place(body(market, side = "FALSE"), key = "k2").expectError(409, "DUPLICATE_BET")
        place(body(snapshot()), key = "k2").expectError(409, "DUPLICATE_BET")
        assertEquals(1, FakeWallet.reserves().size)
    }

    @Test
    fun `persist failure after reserve releases the tokens and writes bet failed`() {
        val market = snapshot()
        doThrow(IllegalStateException("db down")).`when`(bets).saveAndFlush(any())
        place(body(market)).expectError(503, "BET_NOT_PLACED")

        val betId = EventJson.mapper.readTree(FakeWallet.reserves().single().body).get("betId").asString()
        assertEquals("/internal/wallets/reservations/$betId/release", FakeWallet.releases().single().path)
        assertEquals(0, betCount(market))
        val (type, json) = outbox(market).single()
        assertEquals(EventTypes.BET_FAILED, type)
        val event = EventJson.payload(EventJson.read(json), BetV1::class)
        assertEquals(betId, event.betId.toString())
        assertEquals("FAILED", event.status)
        assertTrue(event.reason!!.isNotBlank())
    }

    @Test
    fun `concurrent requests with one Idempotency-Key create one bet and release every losing reservation`() {
        val market = snapshot()
        // The delay lets every request pass the replay check before any bet is stored.
        FakeWallet.reserveReply = Reply(201, delayMs = 300)
        val pool = Executors.newFixedThreadPool(5)
        val results = pool.invokeAll((1..5).map { Callable { place(body(market), key = "race").andReturn().response } }).map { it.get() }
        pool.shutdown()

        assertTrue(results.all { it.status == 202 }, results.map { it.status }.toString())
        val ids = results.map { EventJson.mapper.readTree(it.contentAsString).get("betId").asString() }.toSet()
        assertEquals(1, ids.size)
        assertEquals(1, betCount(market))
        val reserved = FakeWallet.reserves().map { EventJson.mapper.readTree(it.body).get("betId").asString() }.toSet()
        val released = FakeWallet.releases().map { it.path.removePrefix("/internal/wallets/reservations/").removeSuffix("/release") }.toSet()
        assertEquals(reserved - ids, released)
        assertTrue(FakeWallet.reserves().size > 1)
    }
}
