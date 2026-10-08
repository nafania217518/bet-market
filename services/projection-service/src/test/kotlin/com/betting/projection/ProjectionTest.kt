package com.betting.projection

import com.betting.events.BetV1
import com.betting.events.EventEnvelope
import com.betting.events.EventJson
import com.betting.events.EventTypes
import com.betting.events.MarketV1
import com.betting.events.SettlementCompletedV1
import com.betting.events.UserRegisteredV1
import com.betting.events.WalletMovementV1
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.bson.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.mongodb.MongoDBContainer
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@TestConfiguration(proxyBeanMethods = false)
class Containers {
    @Bean @ServiceConnection
    fun kafkaContainer() = KafkaContainer("apache/kafka:4.3.1")

    @Bean @ServiceConnection
    fun mongoContainer() = MongoDBContainer("mongo:7")

    @Bean @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(6379)
}

@org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@SpringBootTest(properties = ["spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer"])
@Import(Containers::class)
class ProjectionTest {
    @Autowired lateinit var projector: Projector
    @Autowired lateinit var mongo: MongoTemplate
    @Autowired lateinit var redis: StringRedisTemplate
    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var registry: io.micrometer.core.instrument.MeterRegistry
    @Autowired lateinit var mvc: org.springframework.test.web.servlet.MockMvc

    fun processed(type: String) = registry.find("projection.events.processed").tag("event_type", type).counter()?.count() ?: 0.0

    @Test
    fun `each applied event increments projection events processed for its type`() {
        val m = UUID.randomUUID()
        val (created, placed) = processed(EventTypes.MARKET_CREATED) to processed(EventTypes.BET_PLACED)
        val e = env(EventTypes.MARKET_CREATED, market(m, 1))
        projector.apply(e)
        projector.apply(e) // duplicates count too
        projector.apply(env(EventTypes.BET_PLACED, bet(m, UUID.randomUUID(), "TRUE", 10)))
        assertEquals(created + 2, processed(EventTypes.MARKET_CREATED))
        assertEquals(placed + 1, processed(EventTypes.BET_PLACED))
    }

    @Test
    fun `actuator prometheus exposes projection events processed total`() {
        projector.apply(env(EventTypes.MARKET_CREATED, market(UUID.randomUUID(), 1)))
        val body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/prometheus")).andReturn().response.contentAsString
        kotlin.test.assertTrue("projection_events_processed_total{event_type=\"market.created\"}" in body)
    }

    val day: Instant = Instant.parse("2026-10-08T12:00:00Z")
    val freezeAt: Instant = Instant.parse("2026-10-09T00:00:00Z")

    @BeforeEach
    fun clean() {
        mongo.db.drop()
        redis.keys("*").let { if (it.isNotEmpty()) redis.delete(it) }
    }

    fun env(type: String, payload: Any, at: Instant = day, id: UUID = UUID.randomUUID()) =
        EventJson.write(EventEnvelope(id, type, 1, at, "c", null, "agg", "test", EventJson.toNode(payload)))

    fun market(id: UUID, version: Long, status: String = "OPEN", title: String = "t$version", winningSide: String? = null) =
        MarketV1(id, title, "d", status, winningSide, freezeAt, freezeAt.plusSeconds(3600), 1, 100, version)

    fun bet(market: UUID, user: UUID, side: String, amount: Long, status: String = "ACCEPTED",
            placedAt: Instant = day, id: UUID = UUID.randomUUID()) = BetV1(id, market, user, side, amount, status, placedAt)

    fun doc(coll: String, id: UUID): Document? = mongo.getCollection(coll).find(Document("_id", id.toString())).first()

    fun board(key: String): Map<String, Double> =
        redis.opsForZSet().rangeWithScores(key, 0, -1)!!.associate { it.value!! to it.score!! }

    @Test
    fun `market events build market_view and an older version never overwrites a newer one`() {
        val m = UUID.randomUUID()
        projector.apply(env(EventTypes.MARKET_CREATED, market(m, 1)))
        projector.apply(env(EventTypes.MARKET_FROZEN, market(m, 3, "FROZEN")))
        projector.apply(env(EventTypes.MARKET_UPDATED, market(m, 2)))
        val d = doc("market_view", m)!!
        assertEquals("FROZEN", d.getString("status"))
        assertEquals("t3", d.getString("title"))
        assertEquals(3L, d.getLong("version"))
        assertEquals(Date.from(freezeAt), d.getDate("freezeAt"))
        assertEquals(100L, d.getLong("maxBet"))
        assertNull(d["winningSide"])
    }

    @Test
    fun `bet placed and bet cancelled build bet_view in any order and the market pools are recomputed`() {
        val m = UUID.randomUUID()
        val u = UUID.randomUUID()
        val b = bet(m, u, "TRUE", 10)
        projector.apply(env(EventTypes.BET_CANCELLED, b.copy(status = "CANCELLED")))
        projector.apply(env(EventTypes.BET_PLACED, b))
        val d = doc("bet_view", b.betId)!!
        assertEquals("CANCELLED", d.getString("status"))
        assertEquals(m.toString(), d.getString("marketId"))
        assertEquals(u.toString(), d.getString("userId"))
        assertEquals(10L, d.getLong("amount"))
        assertEquals(0L, doc("market_view", m)!!.getLong("totalPool"))

        val b2 = bet(m, u, "FALSE", 7)
        projector.apply(env(EventTypes.BET_PLACED, b2))
        assertEquals("ACCEPTED", doc("bet_view", b2.betId)!!.getString("status"))
        assertEquals(7L, doc("market_view", m)!!.getLong("falsePool"))
    }

    @Test
    fun `pools count only non-cancelled bets and uniqueBettors counts distinct users`() {
        val m = UUID.randomUUID()
        val (u1, u2) = UUID.randomUUID() to UUID.randomUUID()
        projector.apply(env(EventTypes.BET_PLACED, bet(m, u1, "TRUE", 10)))
        projector.apply(env(EventTypes.BET_PLACED, bet(m, u1, "TRUE", 5)))
        projector.apply(env(EventTypes.BET_PLACED, bet(m, u2, "FALSE", 20)))
        val c = bet(m, UUID.randomUUID(), "FALSE", 99)
        projector.apply(env(EventTypes.BET_PLACED, c))
        projector.apply(env(EventTypes.BET_CANCELLED, c.copy(status = "CANCELLED")))
        val d = doc("market_view", m)!!
        assertEquals(15L, d.getLong("truePool"))
        assertEquals(20L, d.getLong("falsePool"))
        assertEquals(35L, d.getLong("totalPool"))
        assertEquals(3, d.getInteger("betCount"))
        assertEquals(2, d.getInteger("uniqueBettors"))
    }

    @Test
    fun `bet events before market created still produce a full market_view`() {
        val m = UUID.randomUUID()
        projector.apply(env(EventTypes.BET_PLACED, bet(m, UUID.randomUUID(), "TRUE", 10)))
        projector.apply(env(EventTypes.MARKET_CREATED, market(m, 1)))
        val d = doc("market_view", m)!!
        assertEquals("OPEN", d.getString("status"))
        assertEquals(1L, d.getLong("version"))
        assertEquals(10L, d.getLong("truePool"))
        assertEquals(1, d.getInteger("betCount"))
    }

    @Test
    fun `settlement completed marks winners WON, losers LOST, late bets and full refunds REFUNDED`() {
        val m = UUID.randomUUID()
        projector.apply(env(EventTypes.MARKET_RESOLVED, market(m, 5, "RESOLVED", winningSide = "TRUE")))
        val win = bet(m, UUID.randomUUID(), "TRUE", 10)
        val lose = bet(m, UUID.randomUUID(), "FALSE", 10)
        val late = bet(m, UUID.randomUUID(), "TRUE", 10, placedAt = freezeAt)
        val cancelled = bet(m, UUID.randomUUID(), "TRUE", 10)
        listOf(win, lose, late, cancelled).forEach { projector.apply(env(EventTypes.BET_PLACED, it)) }
        projector.apply(env(EventTypes.BET_CANCELLED, cancelled.copy(status = "CANCELLED")))
        projector.apply(env(EventTypes.SETTLEMENT_COMPLETED, SettlementCompletedV1(m, "RESOLVED", "TRUE", 10, 10, 19, 1, 2)))
        assertEquals("WON", doc("bet_view", win.betId)!!.getString("status"))
        assertEquals("LOST", doc("bet_view", lose.betId)!!.getString("status"))
        assertEquals("REFUNDED", doc("bet_view", late.betId)!!.getString("status"))
        assertEquals("CANCELLED", doc("bet_view", cancelled.betId)!!.getString("status"))

        val m2 = UUID.randomUUID()
        val only = bet(m2, UUID.randomUUID(), "TRUE", 10)
        projector.apply(env(EventTypes.BET_PLACED, only))
        projector.apply(env(EventTypes.SETTLEMENT_COMPLETED, SettlementCompletedV1(m2, "RESOLVED", "TRUE", 0, 0, 0, 0, 0)))
        assertEquals("REFUNDED", doc("bet_view", only.betId)!!.getString("status"))

        val m3 = UUID.randomUUID()
        val c3 = bet(m3, UUID.randomUUID(), "TRUE", 10)
        projector.apply(env(EventTypes.BET_PLACED, c3))
        projector.apply(env(EventTypes.SETTLEMENT_COMPLETED, SettlementCompletedV1(m3, "CANCELLED", null, 10, 0, 0, 0, 1)))
        assertEquals("REFUNDED", doc("bet_view", c3.betId)!!.getString("status"))
    }

    @Test
    fun `wallet debits and credits update the daily and all-time leaderboard once per event id`() {
        val u = UUID.randomUUID()
        val debit = env(EventTypes.WALLET_DEBITED, WalletMovementV1(u, UUID.randomUUID(), 10, 90, 0))
        projector.apply(debit)
        projector.apply(debit)
        projector.apply(env(EventTypes.WALLET_CREDITED, WalletMovementV1(u, null, 25, 115, 0), at = day.plusSeconds(86400)))
        assertEquals(mapOf(u.toString() to 15.0), board("leaderboard:all"))
        assertEquals(mapOf(u.toString() to -10.0), board("leaderboard:day:2026-10-08"))
        assertEquals(mapOf(u.toString() to 25.0), board("leaderboard:day:2026-10-09"))
        val ttl = redis.keys("projection:processed:*").map { redis.getExpire(it) }
        assertEquals(2, ttl.size)
        ttl.forEach { assert(it > Duration.ofDays(34).seconds && it <= Duration.ofDays(35).seconds) }
    }

    @Test
    fun `user registered stores the display name`() {
        val u = UUID.randomUUID()
        projector.apply(env(EventTypes.USER_REGISTERED, UserRegisteredV1(u, "a@b.c", "Alice")))
        assertEquals("Alice", doc("user_view", u)!!.getString("displayName"))
    }

    fun sequence(): List<String> {
        val m = UUID.randomUUID()
        val (u1, u2) = UUID.randomUUID() to UUID.randomUUID()
        val b1 = bet(m, u1, "TRUE", 10)
        val b2 = bet(m, u2, "FALSE", 20)
        val b3 = bet(m, u2, "TRUE", 5)
        return listOf(
            env(EventTypes.USER_REGISTERED, UserRegisteredV1(u1, "1@x", "One")),
            env(EventTypes.USER_REGISTERED, UserRegisteredV1(u2, "2@x", "Two")),
            env(EventTypes.MARKET_CREATED, market(m, 1)),
            env(EventTypes.BET_PLACED, b1),
            env(EventTypes.WALLET_DEBITED, WalletMovementV1(u1, b1.betId, 10, 90, 0)),
            env(EventTypes.BET_PLACED, b2),
            env(EventTypes.WALLET_DEBITED, WalletMovementV1(u2, b2.betId, 20, 80, 0)),
            env(EventTypes.BET_PLACED, b3),
            env(EventTypes.BET_CANCELLED, b3.copy(status = "CANCELLED")),
            env(EventTypes.MARKET_FROZEN, market(m, 2, "FROZEN")),
            env(EventTypes.MARKET_ENDED, market(m, 3, "ENDED")),
            env(EventTypes.MARKET_RESOLVED, market(m, 4, "RESOLVED", winningSide = "TRUE")),
            env(EventTypes.SETTLEMENT_COMPLETED, SettlementCompletedV1(m, "RESOLVED", "TRUE", 10, 20, 29, 1, 2)),
            env(EventTypes.WALLET_CREDITED, WalletMovementV1(u1, null, 29, 119, 0)),
        )
    }

    fun snapshot(): Map<String, Any> {
        val docs = listOf("market_view", "bet_view", "user_view").associateWith { c ->
            mongo.getCollection(c).find().map { it.apply { remove("updatedAt") } }.toList().sortedBy { it.getString("_id") }
        }
        val boards = redis.keys("leaderboard:*").associateWith { board(it) }
        return docs + boards
    }

    @Test
    fun `applying every event twice gives the same read model`() {
        val events = sequence()
        events.forEach(projector::apply)
        val once = snapshot()
        events.forEach(projector::apply)
        assertEquals(once, snapshot())
        assertEquals("WON", (once["bet_view"] as List<*>).map { (it as Document).getString("status") }.sorted().last())
    }

    @Test
    fun `replaying all events into an empty read model gives the same read model`() {
        val events = sequence()
        events.forEach(projector::apply)
        val first = snapshot()
        mongo.db.drop()
        redis.delete(redis.keys("leaderboard:*") + redis.keys("projection:*"))
        events.forEach(projector::apply)
        assertEquals(first, snapshot())
    }

    fun statuses(vararg ids: UUID) = ids.map { doc("bet_view", it)!!.getString("status") }

    @Test
    fun `settlement completed before the bet events still marks the bets WON, LOST, or REFUNDED`() {
        fun run(order: (market: String, settle: String, bets: List<String>) -> List<String>): List<String> {
            clean()
            val m = UUID.randomUUID()
            val win = bet(m, UUID.randomUUID(), "TRUE", 10)
            val lose = bet(m, UUID.randomUUID(), "FALSE", 10)
            val late = bet(m, UUID.randomUUID(), "TRUE", 10, placedAt = freezeAt)
            val events = order(
                env(EventTypes.MARKET_CREATED, market(m, 1)),
                env(EventTypes.SETTLEMENT_COMPLETED, SettlementCompletedV1(m, "RESOLVED", "TRUE", 10, 10, 19, 1, 2)),
                listOf(win, lose, late).map { env(EventTypes.BET_PLACED, it) },
            )
            events.forEach(projector::apply)
            return statuses(win.betId, lose.betId, late.betId)
        }
        val natural = run { m, s, b -> listOf(m) + b + s }
        assertEquals(listOf("WON", "LOST", "REFUNDED"), natural)
        assertEquals(natural, run { m, s, b -> listOf(m, s) + b })
        assertEquals(natural, run { m, s, b -> listOf(s) + b + m })
    }

    @Test
    fun `any order of the events gives the same read model`() {
        val events = sequence()
        events.forEach(projector::apply)
        val natural = snapshot()
        listOf(1L, 2L, 3L, 4L, 5L).forEach { seed ->
            clean()
            events.shuffled(java.util.Random(seed)).forEach(projector::apply)
            assertEquals(natural, snapshot(), "seed $seed")
        }
    }

    @Test
    fun `events from Kafka reach the read model end to end`() {
        val u = UUID.randomUUID()
        val m = UUID.randomUUID()
        kafka.send(EventTypes.USER_REGISTERED, u.toString(), env(EventTypes.USER_REGISTERED, UserRegisteredV1(u, "k@x", "Kaf")))
        kafka.send(EventTypes.MARKET_CREATED, m.toString(), env(EventTypes.MARKET_CREATED, market(m, 1)))
        kafka.send(EventTypes.WALLET_DEBITED, u.toString(), env(EventTypes.WALLET_DEBITED, WalletMovementV1(u, null, 3, 97, 0)))
        await.atMost(Duration.ofSeconds(30)) untilAsserted {
            assertEquals("Kaf", doc("user_view", u)?.getString("displayName"))
            assertEquals("OPEN", doc("market_view", m)?.getString("status"))
            assertEquals(-3.0, redis.opsForZSet().score("leaderboard:all", u.toString()))
        }
    }

    @Test
    fun `a market view change deletes the cached market key`() {
        val m = UUID.randomUUID()
        redis.opsForValue().set("cache:market:$m", "x")
        projector.apply(env(EventTypes.MARKET_CREATED, market(m, 1)))
        assertEquals(false, redis.hasKey("cache:market:$m"))
        redis.opsForValue().set("cache:market:$m", "x")
        projector.apply(env(EventTypes.BET_PLACED, bet(m, UUID.randomUUID(), "TRUE", 1)))
        assertEquals(false, redis.hasKey("cache:market:$m"))
    }

    @Test
    fun `the read model has the query indexes`() {
        projector.ensureIndexes()
        val keys = { c: String -> mongo.getCollection(c).listIndexes().map { it.get("key", Document::class.java) }.toList() }
        assert(Document(mapOf("status" to 1, "endAt" to 1)) in keys("market_view"))
        assert(Document(mapOf("userId" to 1, "placedAt" to -1)) in keys("bet_view"))
        assert(Document("marketId", 1) in keys("bet_view"))
    }
}
