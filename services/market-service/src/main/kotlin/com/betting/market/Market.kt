package com.betting.market

import com.betting.common.ApiException
import com.betting.common.OutboxWriter
import com.betting.events.EventTypes
import com.betting.events.MarketV1
import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceNotFoundException
import com.github.kagkarlsson.scheduler.task.TaskInstance
import com.github.kagkarlsson.scheduler.task.TaskInstanceId
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import com.betting.common.CorrelationId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "markets")
class Market(
    @Id val id: UUID,
    var title: String,
    var description: String,
    var status: String,
    var winningSide: String?,
    var freezeAt: Instant,
    var endAt: Instant,
    val minBet: Long,
    val maxBet: Long,
    val createdBy: UUID,
    var version: Long = 1,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
) {
    fun toV1() = MarketV1(id, title, description, status, winningSide, freezeAt, endAt, minBet, maxBet, version)
}

interface MarketRepository : JpaRepository<Market, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Market m where m.id = :id")
    fun lockById(id: UUID): Market?

    fun countByStatus(status: String): Long

    fun countByCreatedByAndStatusIn(createdBy: UUID, statuses: Collection<String>): Long

    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(CAST(:userId AS text)))::text", nativeQuery = true)
    fun lockCreator(userId: String): String
}

data class CreateMarketRequest(
    val title: String,
    val description: String = "",
    val freezeAt: Instant,
    val endAt: Instant,
    val minBet: Long,
    val maxBet: Long,
)

data class UpdateMarketRequest(val title: String? = null, val description: String? = null, val freezeAt: Instant? = null, val endAt: Instant? = null)

const val FREEZE_TASK = "freeze-market"
const val END_TASK = "end-market"

@Configuration
class MarketTasks {
    // No task data: the instance id is the market id.
    @Bean
    fun freezeTask(service: MarketService): OneTimeTask<Void> =
        Tasks.oneTime(FREEZE_TASK).execute { inst, _ -> withCorrelationId { service.freezeByScheduler(UUID.fromString(inst.id)) } }

    @Bean
    fun endTask(service: MarketService): OneTimeTask<Void> =
        Tasks.oneTime(END_TASK).execute { inst, _ -> withCorrelationId { service.endByScheduler(UUID.fromString(inst.id)) } }
}

/** Scheduler runs have no request: give the events they emit a fresh correlation id. */
fun withCorrelationId(block: () -> Unit) = MDC.putCloseable(CorrelationId.MDC_KEY, UUID.randomUUID().toString()).use { block() }

const val MAX_ACTIVE_MARKETS = 3
val ACTIVE_STATUSES = listOf("OPEN", "FROZEN", "ENDED")
val STATUSES = listOf("OPEN", "FROZEN", "ENDED", "RESOLVED", "CANCELLED")

@Service
@Transactional
class MarketService(
    private val markets: MarketRepository,
    private val outbox: OutboxWriter,
    @Lazy private val scheduler: SchedulerClient, // @Lazy breaks the cycle: scheduler -> tasks -> service.
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        STATUSES.forEach { s -> Gauge.builder("markets.by.status", markets) { it.countByStatus(s).toDouble() }.tag("status", s).register(registry) }
    }

    fun create(req: CreateMarketRequest, createdBy: UUID, admin: Boolean = true): MarketV1 {
        if (!admin) {
            markets.lockCreator(createdBy.toString()) // Serializes creates of one user until commit.
            if (markets.countByCreatedByAndStatusIn(createdBy, ACTIVE_STATUSES) >= MAX_ACTIVE_MARKETS)
                throw ApiException(HttpStatus.CONFLICT, "MARKET_LIMIT_REACHED", "You can have at most $MAX_ACTIVE_MARKETS active markets")
        }
        val m = Market(UUID.randomUUID(), req.title.trim(), req.description, "OPEN", null, req.freezeAt, req.endAt, req.minBet, req.maxBet, createdBy)
        validate(m)
        markets.save(m)
        outbox.append(EventTypes.MARKET_CREATED, m.id.toString(), m.toV1())
        scheduler.scheduleIfNotExists(TaskInstance<Void>(FREEZE_TASK, m.id.toString()), m.freezeAt)
        scheduler.scheduleIfNotExists(TaskInstance<Void>(END_TASK, m.id.toString()), m.endAt)
        return m.toV1()
    }

    fun update(id: UUID, req: UpdateMarketRequest): MarketV1 {
        val m = load(id)
        require(m, "OPEN")
        val oldFreeze = m.freezeAt
        val oldEnd = m.endAt
        req.title?.let { m.title = it.trim() }
        req.description?.let { m.description = it }
        req.freezeAt?.let { m.freezeAt = it }
        req.endAt?.let { m.endAt = it }
        validate(m)
        if (m.freezeAt != oldFreeze) reschedule(FREEZE_TASK, id, m.freezeAt)
        if (m.endAt != oldEnd) reschedule(END_TASK, id, m.endAt)
        return change(m, EventTypes.MARKET_UPDATED)
    }

    fun freeze(id: UUID): MarketV1 = load(id).let { require(it, "OPEN"); move(it, "FROZEN"); change(it, EventTypes.MARKET_FROZEN) }

    fun resolve(id: UUID, winningSide: String): MarketV1 {
        if (winningSide !in setOf("TRUE", "FALSE")) invalid("winningSide must be TRUE or FALSE")
        val m = load(id)
        require(m, "ENDED")
        move(m, "RESOLVED")
        m.winningSide = winningSide
        return change(m, EventTypes.MARKET_RESOLVED)
    }

    fun cancel(id: UUID) {
        val m = load(id)
        require(m, "OPEN", "FROZEN", "ENDED")
        move(m, "CANCELLED")
        change(m, EventTypes.MARKET_CANCELLED)
    }

    // Scheduler handlers are idempotent: a market outside the source state, or a missing market, is a no-op.
    fun freezeByScheduler(id: UUID) {
        val m = lockOrWarn(id) ?: return
        if (m.status != "OPEN") return
        move(m, "FROZEN")
        change(m, EventTypes.MARKET_FROZEN)
    }

    fun endByScheduler(id: UUID) {
        val m = lockOrWarn(id) ?: return
        if (m.status == "OPEN") { move(m, "FROZEN"); change(m, EventTypes.MARKET_FROZEN) } // Freeze task was late.
        if (m.status != "FROZEN") return
        move(m, "ENDED")
        change(m, EventTypes.MARKET_ENDED)
    }

    private fun lockOrWarn(id: UUID): Market? = markets.lockById(id) ?: null.also { log.warn("Scheduled task for unknown market {}", id) }

    private fun load(id: UUID): Market = markets.lockById(id) ?: throw ApiException(HttpStatus.NOT_FOUND, "MARKET_NOT_FOUND", "Market not found")

    private fun require(m: Market, vararg allowed: String) {
        if (m.status !in allowed) throw ApiException(HttpStatus.CONFLICT, "INVALID_MARKET_STATE", "Market is ${m.status}")
    }

    private fun move(m: Market, to: String) {
        registry.counter("market.transitions", "from", m.status, "to", to).increment()
        m.status = to
    }

    private fun change(m: Market, eventType: String): MarketV1 {
        m.version++
        m.updatedAt = Instant.now()
        outbox.append(eventType, m.id.toString(), m.toV1())
        return m.toV1()
    }

    private fun reschedule(task: String, id: UUID, at: Instant) {
        try {
            scheduler.reschedule(TaskInstanceId.of(task, id.toString()), at)
        } catch (e: TaskInstanceNotFoundException) {
            scheduler.scheduleIfNotExists(TaskInstance<Void>(task, id.toString()), at)
        }
    }

    private fun validate(m: Market) {
        if (m.title.isEmpty() || m.title.length > 200) invalid("title must have 1..200 characters")
        if (m.description.length > 2000) invalid("description must have at most 2000 characters")
        if (!m.freezeAt.isAfter(Instant.now())) invalid("freezeAt must be in the future")
        if (!m.endAt.isAfter(m.freezeAt)) invalid("endAt must be after freezeAt")
        if (m.minBet < 1) invalid("minBet must be at least 1")
        if (m.maxBet < m.minBet) invalid("maxBet must be at least minBet")
    }
}

fun invalid(message: String): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message)
