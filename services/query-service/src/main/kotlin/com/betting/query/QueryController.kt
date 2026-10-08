package com.betting.query

import com.betting.common.ApiException
import org.bson.Document
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID

private val MARKET_STATUSES = setOf("OPEN", "FROZEN", "ENDED", "RESOLVED", "CANCELLED")
private val BET_STATUSES = setOf("ACCEPTED", "CANCELLED", "WON", "LOST", "REFUNDED")
private val WINDOW_DAYS = mapOf("7d" to 7L, "30d" to 30L)

private fun bad(message: String): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message)
private fun check(ok: Boolean, message: String) { if (!ok) bad(message) }
private fun ratio(a: Long, b: Long): Double? =
    if (b == 0L) null else BigDecimal(a).divide(BigDecimal(b), 2, RoundingMode.HALF_UP).toDouble()
private fun byId(id: String) = Query(Criteria.where("_id").`is`(id))

/** Read-only API over read_db. Writes to Redis only cache keys and leaderboard union keys. */
@RestController
class QueryController(
    private val mongo: MongoTemplate,
    private val redis: StringRedisTemplate,
    private val cache: Cache,
    @Value("\${query.house-edge-bps}") private val edgeBps: Long,
) {
    @GetMapping("/api/markets")
    fun markets(
        @RequestParam status: String?, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int, @RequestParam(defaultValue = "endAt") sort: String,
    ): Map<String, Any?> {
        check(status == null || status in MARKET_STATUSES, "Unknown status")
        check(sort == "endAt" || sort == "pool", "sort must be endAt or pool")
        checkPage(page, size)
        return cache.getOrLoad("cache:markets:${status ?: "all"}:$sort:$page:$size", Duration.ofSeconds(5)) {
            val criteria = Criteria.where("title").exists(true)
            status?.let { criteria.and("status").`is`(it) }
            val order = if (sort == "pool") Sort.by(Sort.Direction.DESC, "totalPool") else Sort.by("endAt")
            page(Query(criteria).with(order), page, size, "market_view", ::marketItem)
        }
    }

    @GetMapping("/api/markets/{id}")
    fun market(@PathVariable id: String): Map<String, Any?> =
        cache.getOrLoad("cache:market:$id", Duration.ofSeconds(30)) {
            val doc = mongo.findOne(byId(id), Document::class.java, "market_view")
            if (doc?.get("title") == null) throw ApiException(HttpStatus.NOT_FOUND, "MARKET_NOT_FOUND", "Market $id not found")
            marketItem(doc)
        }

    @GetMapping("/api/markets/{id}/stats")
    fun stats(@PathVariable id: String): Map<String, Any?> =
        market(id).filterKeys { it in setOf("truePool", "falsePool", "betCount", "uniqueBettors") }

    @GetMapping("/api/me/bets")
    fun myBets(
        @RequestHeader("X-User-Id") userId: String, @RequestParam status: String?,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int,
    ): Map<String, Any?> {
        check(runCatching { UUID.fromString(userId) }.isSuccess, "X-User-Id must be a UUID")
        check(status == null || status in BET_STATUSES, "Unknown status")
        checkPage(page, size)
        val criteria = Criteria.where("userId").`is`(userId)
        status?.let { criteria.and("status").`is`(it) }
        return page(Query(criteria).with(Sort.by(Sort.Direction.DESC, "placedAt")), page, size, "bet_view") { doc ->
            plain(doc).toMutableMap().apply { put("betId", remove("_id")) }
        }
    }

    @GetMapping("/api/leaderboard")
    fun leaderboard(@RequestParam(defaultValue = "all") window: String, @RequestParam(defaultValue = "100") limit: Int): Map<String, Any?> {
        check(window == "all" || window in WINDOW_DAYS, "window must be all, 7d or 30d")
        check(limit in 1..100, "limit must be 1..100")
        val key = if (window == "all") "leaderboard:all" else "leaderboard:$window".also { unionIfMissing(it, WINDOW_DAYS.getValue(window)) }
        val top = redis.opsForZSet().reverseRangeWithScores(key, 0, limit - 1L).orEmpty().toList()
        val names = mongo.find(Query(Criteria.where("_id").`in`(top.map { it.value })), Document::class.java, "user_view")
            .associate { it.getString("_id") to it.getString("displayName") }
        val entries = top.mapIndexed { i, t ->
            mapOf("rank" to i + 1, "userId" to t.value, "displayName" to names[t.value], "netProfit" to t.score!!.toLong())
        }
        return mapOf("window" to window, "entries" to entries)
    }

    @GetMapping("/api/users/{id}/stats")
    fun userStats(@PathVariable id: String): Map<String, Any?> {
        fun count(criteria: Criteria) = mongo.count(Query(Criteria.where("userId").`is`(id).andOperator(criteria)), "bet_view")
        val wins = count(Criteria.where("status").`is`("WON"))
        val losses = count(Criteria.where("status").`is`("LOST"))
        return mapOf(
            "userId" to id,
            "totalBets" to count(Criteria.where("status").ne("CANCELLED")),
            "wins" to wins, "losses" to losses,
            "winRate" to ratio(wins, wins + losses),
            "netProfit" to (redis.opsForZSet().score("leaderboard:all", id)?.toLong() ?: 0L),
        )
    }

    private fun checkPage(page: Int, size: Int) {
        check(page >= 0, "page must be >= 0")
        check(size in 1..100, "size must be 1..100")
    }

    private fun page(query: Query, page: Int, size: Int, collection: String, item: (Document) -> Map<String, Any?>): Map<String, Any?> {
        val total = mongo.count(Query.of(query).limit(0).skip(0), collection)
        val items = mongo.find(query.skip(page.toLong() * size).limit(size), Document::class.java, collection).map(item)
        return mapOf("items" to items, "page" to page, "size" to size, "total" to total)
    }

    /** BSON dates become Instants so JSON shows ISO strings. */
    private fun plain(doc: Document): Map<String, Any?> = doc.mapValues { (_, v) -> if (v is Date) v.toInstant() else v }

    private fun marketItem(doc: Document): Map<String, Any?> {
        val m = plain(doc).toMutableMap()
        m["marketId"] = m.remove("_id")
        for (f in listOf("truePool", "falsePool", "totalPool", "betCount", "uniqueBettors")) m[f] = (m[f] as Number?)?.toLong() ?: 0L
        val total = m["totalPool"] as Long
        fun odds(side: Long) = ratio(total * (10000 - edgeBps), 10000 * side)
        m["oddsTrue"] = odds(m["truePool"] as Long)
        m["oddsFalse"] = odds(m["falsePool"] as Long)
        return m
    }

    /** ZUNIONSTORE the last [days] UTC daily keys (today and earlier) into [key] with TTL 60 s; reuse it while it exists. */
    private fun unionIfMissing(key: String, days: Long) {
        if (redis.hasKey(key)) return
        val today = LocalDate.now(ZoneOffset.UTC)
        val dayKeys = (0 until days).map { "leaderboard:day:${today.minusDays(it)}" }
        redis.opsForZSet().unionAndStore(dayKeys.first(), dayKeys.drop(1), key)
        redis.expire(key, Duration.ofSeconds(60))
    }
}
