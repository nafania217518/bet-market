package com.betting.bet

import com.betting.events.BetV1
import com.betting.events.EventJson
import com.betting.events.EventTypes
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class CancelBetTest : IntegrationTest() {
    private fun cancel(id: Any, userId: UUID = user): ResultActionsDsl = mvc.delete("/api/bets/$id") { header("X-User-Id", userId.toString()) }

    private fun ResultActionsDsl.expectError(status: Int, code: String) = andExpect {
        status { isEqualTo(status) }
        jsonPath("$.code") { value(code) }
    }

    @Test
    fun `cancel an ACCEPTED bet on an OPEN market sets CANCELLED and writes bet cancelled`() {
        val market = snapshot()
        val betId = betId(place(body(market, amount = 7)))
        cancel(betId).andExpect {
            status { isOk() }
            jsonPath("$.betId") { value(betId.toString()) }
            jsonPath("$.status") { value("CANCELLED") }
        }
        assertEquals("CANCELLED", jdbc.sql("SELECT status FROM bets WHERE id = ?").param(betId).query(String::class.java).single())
        val (type, json) = outbox(market).last()
        assertEquals(EventTypes.BET_CANCELLED, type)
        val event = EventJson.payload(EventJson.read(json), BetV1::class)
        assertEquals(listOf<Any>(betId, user, 7L, "CANCELLED"), listOf(event.betId, event.userId, event.amount, event.status))
    }

    @Test
    fun `cancel returns 404 BET_NOT_FOUND for another user's bet, 422 BET_ALREADY_CANCELLED the second time, and 409 MARKET_NOT_OPEN after freezeAt`() {
        val market = snapshot()
        val betId = betId(place(body(market)))
        cancel(betId, userId = UUID.randomUUID()).expectError(404, "BET_NOT_FOUND")
        cancel(UUID.randomUUID()).expectError(404, "BET_NOT_FOUND")
        cancel(betId).andExpect { status { isOk() } }
        cancel(betId).expectError(422, "BET_ALREADY_CANCELLED")

        val other = betId(place(body(market)))
        setSnapshot(market, "OPEN", freezeAt = Instant.now().minusSeconds(1))
        cancel(other).expectError(409, "MARKET_NOT_OPEN")
        setSnapshot(market, "FROZEN")
        cancel(other).expectError(409, "MARKET_NOT_OPEN")
    }
}
