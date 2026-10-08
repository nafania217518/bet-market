package com.betting.query

import org.junit.jupiter.api.Test
import org.hamcrest.Matchers.contains
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeAndLeaderboardTest : IntegrationTest() {
    @Test
    fun `me bets returns only the caller's bets newest first with an optional status filter and is not cached`() {
        val me = UUID.randomUUID()
        val old = bet(me, "WON", Instant.parse("2030-01-01T00:00:00Z"))
        val new = bet(me, "ACCEPTED", Instant.parse("2030-02-01T00:00:00Z"))
        bet(UUID.randomUUID())
        get("/api/me/bets", me.toString()).andExpect {
            status { isOk() }
            jsonPath("$.total") { value(2) }
            jsonPath("$.items[*].betId") { value(contains(*listOf(new, old).map { it.toString() }.toTypedArray())) }
            jsonPath("$.items[0].amount") { value(10) }
            jsonPath("$.items[0].side") { value("TRUE") }
        }
        get("/api/me/bets?status=WON", me.toString()).andExpect {
            jsonPath("$.items[*].betId") { value(contains(old.toString())) }
        }
        get("/api/me/bets?page=1&size=1", me.toString()).andExpect {
            jsonPath("$.items[*].betId") { value(contains(old.toString())) }
        }
        val newest = bet(me, placedAt = Instant.parse("2030-03-01T00:00:00Z"))
        get("/api/me/bets", me.toString()).andExpect { jsonPath("$.items[0].betId") { value(newest.toString()) } }
        assertTrue(redis.keys("cache:*").none { it.contains("bets") })
    }

    @Test
    fun `me bets requires a valid X-User-Id`() {
        get("/api/me/bets").expectError(400, "VALIDATION_ERROR")
        get("/api/me/bets", "nope").expectError(400, "VALIDATION_ERROR")
        get("/api/me/bets?size=500", UUID.randomUUID().toString()).expectError(400, "VALIDATION_ERROR")
    }

    @Test
    fun `leaderboard all ranks users by net profit with display names`() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val c = UUID.randomUUID()
        user(a, "Alice"); user(b, "Bob")
        redis.opsForZSet().add("leaderboard:all", a.toString(), 50.0)
        redis.opsForZSet().add("leaderboard:all", b.toString(), -20.0)
        redis.opsForZSet().add("leaderboard:all", c.toString(), 90.0)
        get("/api/leaderboard").andExpect {
            status { isOk() }
            jsonPath("$.window") { value("all") }
            jsonPath("$.entries[*].userId") { value(contains(*listOf(c, a, b).map { it.toString() }.toTypedArray())) }
            jsonPath("$.entries[*].rank") { value(contains(1, 2, 3)) }
            jsonPath("$.entries[*].netProfit") { value(contains(90, 50, -20)) }
            jsonPath("$.entries[1].displayName") { value("Alice") }
            jsonPath("$.entries[0].displayName") { value(null) }
        }
        get("/api/leaderboard?window=all&limit=1").andExpect { jsonPath("$.entries.length()") { value(1) } }
    }

    @Test
    fun `leaderboard 7d sums only the last 7 daily buckets`() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        val today = LocalDate.now(ZoneOffset.UTC)
        fun day(daysAgo: Long, user: UUID, score: Double) = redis.opsForZSet().add("leaderboard:day:${today.minusDays(daysAgo)}", user.toString(), score)
        day(0, a, 10.0); day(6, a, 5.0); day(7, a, 1000.0)
        day(3, b, 12.0); day(20, b, 100.0)
        get("/api/leaderboard?window=7d").andExpect {
            jsonPath("$.window") { value("7d") }
            jsonPath("$.entries[*].userId") { value(contains(*listOf(a, b).map { it.toString() }.toTypedArray())) }
            jsonPath("$.entries[*].netProfit") { value(contains(15, 12)) }
        }
        val ttl = redis.getExpire("leaderboard:7d")
        assertTrue(ttl in 1..60, "ttl $ttl")
        get("/api/leaderboard?window=30d").andExpect { jsonPath("$.entries[*].netProfit") { value(contains(1015, 112)) } }
        day(1, b, 100.0) // union key still lives -> reused
        get("/api/leaderboard?window=7d").andExpect { jsonPath("$.entries[0].userId") { value(a.toString()) } }
    }

    @Test
    fun `leaderboard rejects an unknown window or limit`() {
        get("/api/leaderboard?window=1y").expectError(400, "VALIDATION_ERROR")
        get("/api/leaderboard?limit=0").expectError(400, "VALIDATION_ERROR")
        get("/api/leaderboard?limit=101").expectError(400, "VALIDATION_ERROR")
    }

    @Test
    fun `user stats count bets, wins, losses, win rate, and net profit`() {
        val u = UUID.randomUUID()
        listOf("WON", "WON", "LOST", "ACCEPTED", "CANCELLED", "REFUNDED").forEach { bet(u, it) }
        redis.opsForZSet().add("leaderboard:all", u.toString(), 42.0)
        get("/api/users/$u/stats").andExpect {
            status { isOk() }
            jsonPath("$.userId") { value(u.toString()) }
            jsonPath("$.totalBets") { value(5) }
            jsonPath("$.wins") { value(2) }
            jsonPath("$.losses") { value(1) }
            jsonPath("$.winRate") { value(0.67) }
            jsonPath("$.netProfit") { value(42) }
        }
        get("/api/users/${UUID.randomUUID()}/stats").andExpect {
            jsonPath("$.totalBets") { value(0) }
            jsonPath("$.winRate") { value(null) }
            jsonPath("$.netProfit") { value(0) }
        }
        assertEquals(0, redis.keys("cache:*").size)
    }
}
