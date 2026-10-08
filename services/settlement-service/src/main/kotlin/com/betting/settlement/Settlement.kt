package com.betting.settlement

import com.betting.common.OutboxWriter
import com.betting.events.BetV1
import com.betting.events.EventJson
import com.betting.events.EventTypes
import com.betting.events.MarketV1
import com.github.kagkarlsson.scheduler.SchedulerClient
import com.betting.events.PayoutCalculatedV1
import com.betting.events.SettlementCompletedV1
import com.github.kagkarlsson.scheduler.task.FailureHandler
import com.github.kagkarlsson.scheduler.task.TaskInstance
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.slf4j.LoggerFactory
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

const val SETTLE_TASK = "settle-market"

@Component
class SettlementBets(private val jdbc: JdbcClient) {
    fun placed(bet: BetV1) {
        jdbc.sql(
            """INSERT INTO settlement_bet(bet_id, market_id, user_id, side, amount, placed_at, cancelled)
               VALUES (?, ?, ?, ?, ?, ?, false) ON CONFLICT (bet_id) DO NOTHING""",
        ).params(bet.betId, bet.marketId, bet.userId, bet.side, bet.amount, Timestamp.from(bet.placedAt)).update()
    }

    // bet.cancelled can arrive before bet.placed: insert the row, a later bet.placed then does nothing.
    fun cancelled(bet: BetV1) {
        jdbc.sql(
            """INSERT INTO settlement_bet(bet_id, market_id, user_id, side, amount, placed_at, cancelled)
               VALUES (?, ?, ?, ?, ?, ?, true) ON CONFLICT (bet_id) DO UPDATE SET cancelled = true""",
        ).params(bet.betId, bet.marketId, bet.userId, bet.side, bet.amount, Timestamp.from(bet.placedAt)).update()
    }

    fun forMarket(marketId: UUID): List<SettlementBet> =
        jdbc.sql("SELECT bet_id, user_id, side, amount, placed_at, cancelled FROM settlement_bet WHERE market_id = ?").param(marketId)
            .query { rs, _ ->
                SettlementBet(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getLong(4),
                    rs.getTimestamp(5).toInstant(), rs.getBoolean(6))
            }.list()
}

@Configuration
class SettlementTasks {
    @Bean
    fun settleTask(service: SettlementService): OneTimeTask<Void> =
        Tasks.oneTime(SETTLE_TASK)
            // A thrown exception rolls back the settlement transaction; the scheduler runs it again after 1s, 2s, 4s, ...
            .onFailure(FailureHandler.ExponentialBackoffFailureHandler(Duration.ofSeconds(1), 2.0))
            .execute { inst, _ -> service.settle(UUID.fromString(inst.id)) }
}

private data class Pending(val outcome: String, val winningSide: String?, val freezeAt: Instant, val status: String)

@Service
@Transactional
class SettlementService(
    private val jdbc: JdbcClient,
    private val bets: SettlementBets,
    private val outbox: OutboxWriter,
    @Lazy private val scheduler: SchedulerClient, // @Lazy breaks the cycle: scheduler -> tasks -> service.
    @Value("\${settlement.house-edge-bps}") private val edgeBps: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // The settlements primary key is the idempotency guard: a duplicate market event inserts nothing and schedules nothing.
    fun start(market: MarketV1) {
        val inserted = jdbc.sql(
            """INSERT INTO settlements(market_id, outcome, winning_side, freeze_at, status)
               VALUES (?, ?, ?, ?, 'PENDING') ON CONFLICT (market_id) DO NOTHING""",
        ).params(listOf(market.marketId, market.status, market.winningSide, Timestamp.from(market.freezeAt))).update()
        if (inserted == 1) scheduler.scheduleIfNotExists(TaskInstance<Void>(SETTLE_TASK, market.marketId.toString()), Instant.now())
    }

    // ponytail: bets that arrive after the settlement ran are not paid; admins resolve long after endAt,
    // so all bets have arrived (architecture §8.6). Add a late-bet refund sweep if that changes.
    fun settle(marketId: UUID) {
        val row = jdbc.sql("SELECT outcome, winning_side, freeze_at, status FROM settlements WHERE market_id = ? FOR UPDATE").param(marketId)
            .query { rs, _ -> Pending(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getString(4)) }
            .optional().orElse(null)
        if (row == null) return log.warn("Settle task for unknown market {}", marketId)
        if (row.status == "COMPLETED") return
        val (outcome, winningSide, freezeAt) = row

        val s = Payouts.compute(bets.forMarket(marketId), outcome, winningSide, freezeAt, edgeBps)
        s.payouts.forEach {
            jdbc.sql("INSERT INTO settlement_payouts(market_id, user_id, amount, type) VALUES (?, ?, ?, ?)")
                .params(marketId, it.userId, it.amount, it.type).update()
        }
        jdbc.sql(
            """UPDATE settlements SET true_pool = ?, false_pool = ?, paid_out = ?, house_amount = ?, bet_count = ?,
               status = 'COMPLETED', completed_at = now() WHERE market_id = ?""",
        ).params(s.truePool, s.falsePool, s.paidOut, s.houseAmount, s.betCount, marketId).update()
        outbox.append(EventTypes.PAYOUT_CALCULATED, marketId.toString(), PayoutCalculatedV1(marketId, s.payouts))
        outbox.append(EventTypes.SETTLEMENT_COMPLETED, marketId.toString(),
            SettlementCompletedV1(marketId, outcome, winningSide, s.truePool, s.falsePool, s.paidOut, s.houseAmount, s.betCount))
    }
}

@Component
class SettlementListener(private val bets: SettlementBets, private val service: SettlementService) {
    @KafkaListener(topics = [EventTypes.BET_PLACED])
    fun onBetPlaced(json: String) = bets.placed(EventJson.payload(EventJson.read(json), BetV1::class))

    @KafkaListener(topics = [EventTypes.BET_CANCELLED])
    fun onBetCancelled(json: String) = bets.cancelled(EventJson.payload(EventJson.read(json), BetV1::class))

    @KafkaListener(topics = [EventTypes.MARKET_RESOLVED, EventTypes.MARKET_CANCELLED])
    fun onMarketClosed(json: String) = service.start(EventJson.payload(EventJson.read(json), MarketV1::class))
}
