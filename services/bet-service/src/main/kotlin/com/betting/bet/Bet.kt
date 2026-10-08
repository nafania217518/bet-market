package com.betting.bet

import com.betting.common.ApiException
import com.betting.common.OutboxWriter
import com.betting.events.BetV1
import com.betting.events.EventJson
import com.betting.events.EventTypes
import com.betting.events.MarketV1
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.http.HttpStatus
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "bets")
class Bet(
    @Id val id: UUID,
    val marketId: UUID,
    val userId: UUID,
    val side: String,
    val amount: Long,
    var status: String,
    val idempotencyKey: String,
    val placedAt: Instant,
    var updatedAt: Instant,
) {
    fun toEvent(status: String = this.status, reason: String? = null) = BetV1(id, marketId, userId, side, amount, status, placedAt, reason)
}

interface BetRepository : JpaRepository<Bet, UUID> {
    fun findByUserIdAndIdempotencyKey(userId: UUID, idempotencyKey: String): Bet?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Bet b WHERE b.id = :id")
    fun findForUpdate(id: UUID): Bet?
}

class MarketSnapshot(val status: String, val freezeAt: Instant, val minBet: Long, val maxBet: Long) {
    fun isOpen() = status == "OPEN" && Instant.now() < freezeAt
}

@Component
class MarketSnapshots(private val jdbc: JdbcClient) {
    fun find(marketId: UUID): MarketSnapshot? =
        jdbc.sql("SELECT status, freeze_at, min_bet, max_bet FROM market_snapshot WHERE market_id = ?").param(marketId)
            .query { rs, _ -> MarketSnapshot(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getLong(3), rs.getLong(4)) }
            .optional().orElse(null)

    // The version guard makes the upsert idempotent and drops stale (out-of-order) events.
    fun upsert(m: MarketV1) {
        jdbc.sql(
            """INSERT INTO market_snapshot(market_id, status, freeze_at, min_bet, max_bet, version, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, now())
               ON CONFLICT (market_id) DO UPDATE SET status = EXCLUDED.status, freeze_at = EXCLUDED.freeze_at,
                 min_bet = EXCLUDED.min_bet, max_bet = EXCLUDED.max_bet, version = EXCLUDED.version, updated_at = now()
               WHERE market_snapshot.version < EXCLUDED.version""",
        ).params(m.marketId, m.status, Timestamp.from(m.freezeAt), m.minBet, m.maxBet, m.version).update()
    }
}

@Component
class MarketSnapshotListener(private val snapshots: MarketSnapshots) {
    @KafkaListener(
        topics = [EventTypes.MARKET_CREATED, EventTypes.MARKET_UPDATED, EventTypes.MARKET_FROZEN,
            EventTypes.MARKET_ENDED, EventTypes.MARKET_RESOLVED, EventTypes.MARKET_CANCELLED],
    )
    fun onMarketEvent(json: String) = snapshots.upsert(EventJson.payload(EventJson.read(json), MarketV1::class))
}

@Component
class WalletClient(builder: RestClient.Builder, @Value("\${wallet.url}") walletUrl: String) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client = builder.baseUrl(walletUrl).requestFactory(
        SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(1))
            setReadTimeout(Duration.ofSeconds(3))
        },
    ).build()

    fun reserve(userId: UUID, betId: UUID, amount: Long) {
        try {
            client.post().uri("/internal/wallets/{userId}/reservations", userId)
                .body(mapOf("betId" to betId, "amount" to amount)).retrieve().toBodilessEntity()
        } catch (e: RestClientResponseException) {
            when (e.statusCode.value()) {
                409 -> throw ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS", "Not enough tokens")
                404 -> throw ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found")
                else -> unavailable(betId, e)
            }
        } catch (e: ResourceAccessException) {
            unavailable(betId, e)
        }
    }

    // A timeout may still have reserved the stake, so release it before giving up.
    private fun unavailable(betId: UUID, cause: Exception): Nothing {
        log.warn("Wallet reserve failed for bet {}", betId, cause)
        release(betId)
        throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WALLET_UNAVAILABLE", "Wallet is unavailable")
    }

    /** Best effort; 404 RESERVATION_NOT_FOUND and all other errors are logged and ignored. */
    fun release(betId: UUID) {
        runCatching { client.post().uri("/internal/wallets/reservations/{betId}/release", betId).retrieve().toBodilessEntity() }
            .onFailure { log.warn("Wallet release failed for bet {}", betId, it) }
    }
}

data class PlaceBetRequest(val marketId: UUID, val side: String, val amount: Long)
data class BetResponse(val betId: UUID, val status: String)

@Service
class BetService(
    private val bets: BetRepository,
    private val snapshots: MarketSnapshots,
    private val wallet: WalletClient,
    private val outbox: OutboxWriter,
    txManager: PlatformTransactionManager,
    private val meters: MeterRegistry,
) {
    private val timer = Timer.builder("bet.placement.duration").publishPercentileHistogram().register(meters)
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager)
    private val newTx = TransactionTemplate(txManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    fun place(userId: UUID, key: String, req: PlaceBetRequest): BetResponse = timer.recordCallable {
        try {
            doPlace(userId, key, req)?.also { count(req, "ACCEPTED") } ?: replay(userId, key, req)
        } catch (e: ApiException) {
            count(req, e.code)
            throw e
        }
    }!!

    // The side tag is user input: clamp it so a bad side cannot create unbounded series.
    private fun count(req: PlaceBetRequest, status: String) = meters.counter("bets.placed", "side", req.side.takeIf { it == "TRUE" || it == "FALSE" } ?: "INVALID", "status", status).increment()

    private fun replay(userId: UUID, key: String, req: PlaceBetRequest) = replay(bets.findByUserIdAndIdempotencyKey(userId, key)!!, req)

    /** Returns null when the Idempotency-Key was already used (a replay, not counted). */
    private fun doPlace(userId: UUID, key: String, req: PlaceBetRequest): BetResponse? {
        bets.findByUserIdAndIdempotencyKey(userId, key)?.let { return null }
        if (req.side != "TRUE" && req.side != "FALSE") throw invalid("side must be TRUE or FALSE")
        val market = snapshots.find(req.marketId) ?: throw ApiException(HttpStatus.NOT_FOUND, "MARKET_NOT_FOUND", "Market not found")
        if (!market.isOpen()) throw notOpen()
        if (req.amount !in market.minBet..market.maxBet) throw invalid("amount must be between ${market.minBet} and ${market.maxBet}")

        val now = Instant.now()
        val bet = Bet(UUID.randomUUID(), req.marketId, userId, req.side, req.amount, "ACCEPTED", key, now, now)
        wallet.reserve(userId, bet.id, bet.amount) // no DB transaction is open during the HTTP call
        try {
            tx.executeWithoutResult {
                bets.saveAndFlush(bet)
                // aggregateId = marketId: the Kafka key gives per-market ordering (architecture §9).
                outbox.append(EventTypes.BET_PLACED, bet.marketId.toString(), bet.toEvent())
            }
        } catch (e: Exception) {
            // ponytail: if both the wallet release and bet-db fail, the reservation stays held; add a reservation sweeper when this matters.
            wallet.release(bet.id)
            if (e is DataIntegrityViolationException) {
                bets.findByUserIdAndIdempotencyKey(userId, key)?.let { return null }
            }
            log.error("Bet {} not stored", bet.id, e)
            runCatching {
                newTx.executeWithoutResult {
                    outbox.append(EventTypes.BET_FAILED, bet.marketId.toString(), bet.toEvent("FAILED", "persist failed"))
                }
            }.onFailure { log.error("bet.failed for {} not written", bet.id, it) }
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BET_NOT_PLACED", "Bet not placed, try again")
        }
        return BetResponse(bet.id, bet.status)
    }

    fun cancel(userId: UUID, betId: UUID): BetResponse = tx.execute {
        val bet = bets.findForUpdate(betId)?.takeIf { it.userId == userId }
            ?: throw ApiException(HttpStatus.NOT_FOUND, "BET_NOT_FOUND", "Bet not found")
        if (bet.status == "CANCELLED") throw ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "BET_ALREADY_CANCELLED", "Bet already cancelled")
        if (snapshots.find(bet.marketId)?.isOpen() != true) throw notOpen()
        bet.status = "CANCELLED"
        bet.updatedAt = Instant.now()
        // aggregateId = marketId: the Kafka key gives per-market ordering (architecture §9).
        outbox.append(EventTypes.BET_CANCELLED, bet.marketId.toString(), bet.toEvent())
        BetResponse(bet.id, bet.status)
    }!!

    private fun replay(bet: Bet, req: PlaceBetRequest): BetResponse {
        if (bet.marketId != req.marketId || bet.side != req.side || bet.amount != req.amount) {
            throw ApiException(HttpStatus.CONFLICT, "DUPLICATE_BET", "Idempotency-Key already used with a different bet")
        }
        return BetResponse(bet.id, bet.status)
    }

    private fun invalid(message: String) = ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message)
    private fun notOpen() = ApiException(HttpStatus.CONFLICT, "MARKET_NOT_OPEN", "Market is not open for bets")
}
