package com.betting.projection

import com.betting.events.BetV1
import com.betting.events.EventJson
import com.betting.events.EventTypes
import com.betting.events.MarketV1
import com.betting.events.SettlementCompletedV1
import com.betting.events.UserRegisteredV1
import com.betting.events.WalletMovementV1
import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Filters.lt
import com.mongodb.client.model.Filters.ne
import com.mongodb.client.model.Filters.or
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates.combine
import com.mongodb.client.model.Updates.set
import com.mongodb.client.model.Updates.setOnInsert
import jakarta.annotation.PostConstruct
import org.bson.Document
import org.bson.conversions.Bson
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID

/** Builds the read model (Mongo read_db + Redis leaderboards). Every write is idempotent and order-tolerant. */
@Component
class Projector(private val mongo: MongoTemplate, private val redis: StringRedisTemplate) {
    private val upsert = UpdateOptions().upsert(true)
    private val markets get() = mongo.getCollection("market_view")
    private val bets get() = mongo.getCollection("bet_view")

    @PostConstruct
    fun ensureIndexes() {
        markets.createIndex(Indexes.ascending("status", "endAt"))
        bets.createIndex(Indexes.compoundIndex(Indexes.ascending("userId"), Indexes.descending("placedAt")))
        bets.createIndex(Indexes.ascending("marketId"))
    }

    @KafkaListener(
        topics = [
            EventTypes.USER_REGISTERED, EventTypes.MARKET_CREATED, EventTypes.MARKET_UPDATED, EventTypes.MARKET_FROZEN,
            EventTypes.MARKET_ENDED, EventTypes.MARKET_RESOLVED, EventTypes.MARKET_CANCELLED, EventTypes.BET_PLACED,
            EventTypes.BET_CANCELLED, EventTypes.SETTLEMENT_COMPLETED, EventTypes.WALLET_DEBITED, EventTypes.WALLET_CREDITED,
        ],
    )
    fun apply(json: String) {
        val e = EventJson.read(json)
        when (e.eventType) {
            EventTypes.USER_REGISTERED -> EventJson.payload(e, UserRegisteredV1::class).let {
                mongo.getCollection("user_view").updateOne(id(it.userId), set("displayName", it.displayName), upsert)
            }
            EventTypes.BET_PLACED -> bet(EventJson.payload(e, BetV1::class), setOnInsert("status", "ACCEPTED"))
            EventTypes.BET_CANCELLED -> bet(EventJson.payload(e, BetV1::class), set("status", "CANCELLED"))
            EventTypes.SETTLEMENT_COMPLETED -> EventJson.payload(e, SettlementCompletedV1::class).let {
                val settlement = Document(mapOf("outcome" to it.outcome, "winningSide" to it.winningSide,
                    "truePool" to it.truePool, "falsePool" to it.falsePool))
                markets.updateOne(id(it.marketId), set("settlement", settlement), upsert)
                applySettlement(it.marketId)
            }
            EventTypes.WALLET_DEBITED, EventTypes.WALLET_CREDITED -> EventJson.payload(e, WalletMovementV1::class).let {
                val delta = if (e.eventType == EventTypes.WALLET_DEBITED) -it.amount else it.amount
                val dayKey = "leaderboard:day:${e.occurredAt.atZone(ZoneOffset.UTC).toLocalDate()}"
                redis.execute(LEADERBOARD, listOf("projection:processed:${e.eventId}", dayKey, "leaderboard:all"),
                    TTL.seconds.toString(), delta.toString(), it.userId.toString())
            }
            else -> if (e.eventType.startsWith("market.")) market(EventJson.payload(e, MarketV1::class))
        }
    }

    private fun market(m: MarketV1) {
        val fields = combine(
            set("title", m.title), set("description", m.description), set("status", m.status),
            set("winningSide", m.winningSide), set("freezeAt", Date.from(m.freezeAt)), set("endAt", Date.from(m.endAt)),
            set("minBet", m.minBet), set("maxBet", m.maxBet), set("version", m.version), set("updatedAt", Date()),
        )
        try {
            markets.updateOne(and(id(m.marketId), or(lt("version", m.version), exists("version", false))), fields, upsert)
        } catch (ex: MongoWriteException) {
            // The doc exists with a newer or equal version: the filter missed and the upsert hit the _id. Stale event, drop it.
            if (ex.error.category != ErrorCategory.DUPLICATE_KEY) throw ex
        }
        applySettlement(m.marketId)
        redis.delete("cache:market:${m.marketId}")
    }

    private fun bet(b: BetV1, status: Bson) {
        bets.updateOne(id(b.betId), combine(
            set("marketId", b.marketId.toString()), set("userId", b.userId.toString()), set("side", b.side),
            set("amount", b.amount), set("placedAt", Date.from(b.placedAt)), set("updatedAt", Date()), status,
        ), upsert)
        // ponytail: recompute is O(bets per market); switch to increments + dedupe if markets get huge.
        val live = bets.find(and(eq("marketId", b.marketId.toString()), ne("status", "CANCELLED"))).toList()
        val pool = { side: String -> live.filter { it["side"] == side }.sumOf { it.getLong("amount") } }
        markets.updateOne(id(b.marketId), combine(
            set("truePool", pool("TRUE")), set("falsePool", pool("FALSE")), set("totalPool", live.sumOf { it.getLong("amount") }),
            set("betCount", live.size), set("uniqueBettors", live.map { it["userId"] }.toSet().size), set("updatedAt", Date()),
        ), upsert)
        applySettlement(b.marketId)
        redis.delete("cache:market:${b.marketId}")
    }

    /** Same rules as Settlement Service's Payouts.compute. Idempotent; runs after every market-touching event so order does not matter. */
    private fun applySettlement(marketId: UUID) {
        val market = markets.find(id(marketId)).first() ?: return
        val s = market.get("settlement", Document::class.java) ?: return
        val freezeAt = market.getDate("freezeAt")
        val refundAll = s["outcome"] == "CANCELLED" || (s.getLong("truePool") == 0L && s.getLong("falsePool") == 0L)
        bets.find(and(eq("marketId", marketId.toString()), ne("status", "CANCELLED"))).forEach { b ->
            val status = when {
                refundAll || (freezeAt != null && !b.getDate("placedAt").before(freezeAt)) -> "REFUNDED"
                b["side"] == s["winningSide"] -> "WON"
                else -> "LOST"
            }
            if (b["status"] != status) bets.updateOne(eq("_id", b["_id"]), combine(set("status", status), set("updatedAt", Date())))
        }
    }

    private fun id(uuid: UUID): Bson = eq("_id", uuid.toString())

    companion object {
        val TTL: Duration = Duration.ofDays(35)

        // KEYS: processed marker, daily board, all-time board. ARGV: ttl seconds, delta, userId.
        val LEADERBOARD: RedisScript<Long> = RedisScript.of(
            """
            if redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1]) then
              redis.call('ZINCRBY', KEYS[2], ARGV[2], ARGV[3])
              redis.call('ZINCRBY', KEYS[3], ARGV[2], ARGV[3])
              return 1
            end
            return 0
            """.trimIndent(),
            Long::class.java,
        )
    }
}
