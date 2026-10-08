package com.betting.query

import org.bson.Document
import org.junit.jupiter.api.Test
import org.hamcrest.Matchers.contains
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import java.time.Instant
import java.util.UUID
import kotlin.test.assertTrue

class MarketsTest : IntegrationTest() {
    @Test
    fun `markets list filters by status, sorts by endAt or pool, and pages`() {
        val late = market(endAt = Instant.parse("2031-01-01T00:00:00Z"), truePool = 100, falsePool = 100)
        val early = market(endAt = Instant.parse("2030-01-01T00:00:00Z"), truePool = 10, falsePool = 0)
        val mid = market(endAt = Instant.parse("2030-06-01T00:00:00Z"))
        market(status = "RESOLVED")

        get("/api/markets?status=OPEN").andExpect {
            status { isOk() }
            jsonPath("$.total") { value(3) }
            jsonPath("$.page") { value(0) }
            jsonPath("$.size") { value(20) }
            jsonPath("$.items[*].marketId") { value(contains(*listOf(early, mid, late).map { it.toString() }.toTypedArray())) }
        }
        get("/api/markets?status=OPEN&sort=pool").andExpect {
            jsonPath("$.items[*].marketId") { value(contains(*listOf(late, early, mid).map { it.toString() }.toTypedArray())) }
        }
        get("/api/markets?status=OPEN&page=1&size=2").andExpect {
            jsonPath("$.total") { value(3) }
            jsonPath("$.items[*].marketId") { value(contains(late.toString())) }
        }
        get("/api/markets").andExpect { jsonPath("$.total") { value(4) } }
    }

    @Test
    fun `markets list rejects an unknown status, sort, or size with 400 VALIDATION_ERROR`() {
        get("/api/markets?status=MAYBE").expectError(400, "VALIDATION_ERROR")
        get("/api/markets?sort=title").expectError(400, "VALIDATION_ERROR")
        get("/api/markets?size=0").expectError(400, "VALIDATION_ERROR")
        get("/api/markets?size=101").expectError(400, "VALIDATION_ERROR")
        get("/api/markets?page=-1").expectError(400, "VALIDATION_ERROR")
    }

    @Test
    fun `markets list hides market views that have pools but no market data yet`() {
        val orphan = market(title = null, truePool = 50, falsePool = 0)
        val real = market()
        get("/api/markets").andExpect {
            jsonPath("$.total") { value(1) }
            jsonPath("$.items[*].marketId") { value(contains(real.toString())) }
        }
        get("/api/markets/$orphan").expectError(404, "MARKET_NOT_FOUND")
    }

    @Test
    fun `market detail returns pools and decimal odds after the house edge, null odds for an empty side`() {
        val id = market(truePool = 300, falsePool = 0)
        get("/api/markets/$id").andExpect {
            status { isOk() }
            jsonPath("$.marketId") { value(id.toString()) }
            jsonPath("$.title") { value("Will it rain?") }
            jsonPath("$.status") { value("OPEN") }
            jsonPath("$.truePool") { value(300) }
            jsonPath("$.totalPool") { value(300) }
            jsonPath("$.oddsTrue") { value(0.95) }
            jsonPath("$.oddsFalse") { value(null) }
        }
        val split = market(truePool = 100, falsePool = 200)
        get("/api/markets/$split").andExpect {
            jsonPath("$.oddsTrue") { value(2.85) } // 300 * 0.95 / 100
            jsonPath("$.oddsFalse") { value(1.43) } // 300 * 0.95 / 200 = 1.425
        }
        get("/api/markets/$split/stats").andExpect {
            status { isOk() }
            jsonPath("$.truePool") { value(100) }
            jsonPath("$.falsePool") { value(200) }
            jsonPath("$.betCount") { value(3) }
            jsonPath("$.uniqueBettors") { value(2) }
        }
        val empty = market()
        get("/api/markets/$empty").andExpect {
            jsonPath("$.totalPool") { value(0) }
            jsonPath("$.oddsTrue") { value(null) }
        }
    }

    @Test
    fun `market detail and stats return 404 MARKET_NOT_FOUND for an unknown market`() {
        get("/api/markets/${UUID.randomUUID()}").expectError(404, "MARKET_NOT_FOUND")
        get("/api/markets/${UUID.randomUUID()}/stats").expectError(404, "MARKET_NOT_FOUND")
    }

    @Test
    fun `market detail is served from Redis while the cache key lives and from Mongo after it is deleted`() {
        val id = market(title = "Old")
        get("/api/markets/$id").andExpect { jsonPath("$.title") { value("Old") } }
        val ttl = redis.getExpire("cache:market:$id")
        assertTrue(ttl in 1..30, "ttl $ttl")
        mongo.updateFirst(Query(Criteria.where("_id").`is`(id.toString())), Update().set("title", "New"), "market_view")
        get("/api/markets/$id").andExpect { jsonPath("$.title") { value("Old") } }
        redis.delete("cache:market:$id")
        get("/api/markets/$id").andExpect { jsonPath("$.title") { value("New") } }
    }

    @Test
    fun `markets list is cached for 5 seconds`() {
        market()
        get("/api/markets?status=OPEN&sort=pool&page=0&size=10").andExpect { jsonPath("$.total") { value(1) } }
        val ttl = redis.getExpire("cache:markets:OPEN:pool:0:10")
        assertTrue(ttl in 1..5, "ttl $ttl")
        market()
        get("/api/markets?status=OPEN&sort=pool&page=0&size=10").andExpect { jsonPath("$.total") { value(1) } }
        get("/api/markets").andExpect { jsonPath("$.total") { value(2) } }
        assertTrue(redis.hasKey("cache:markets:all:endAt:0:20"))
    }
}
