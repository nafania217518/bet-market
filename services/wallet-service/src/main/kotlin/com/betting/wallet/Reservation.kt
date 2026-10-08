package com.betting.wallet

import com.betting.common.ApiException
import com.betting.common.IdempotentConsumer
import com.betting.common.OutboxWriter
import com.betting.events.BetV1
import com.betting.events.EventJson
import com.betting.events.EventTypes
import com.betting.events.WalletMovementV1
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "reservations")
class Reservation(
    @Id val betId: UUID,
    val userId: UUID,
    val amount: Long,
    var status: String,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)

interface ReservationRepository : JpaRepository<Reservation, UUID> {
    // Scalar query: does not put the entity in the persistence context, so the re-read after the wallet lock is fresh.
    @Query("select r.userId from Reservation r where r.betId = :betId")
    fun userIdOf(betId: UUID): UUID?
}

// All wallet moves. Every move locks the wallet row first, then reads the reservation (fixed lock order, no deadlocks).
@Service
@Transactional
class WalletMoves(
    private val wallets: WalletRepository,
    private val reservations: ReservationRepository,
    private val ledger: LedgerEntryRepository,
    private val outbox: OutboxWriter,
) {
    /** Returns true when a new reservation is created, false for an identical retry. */
    fun reserve(userId: UUID, betId: UUID, amount: Long): Boolean {
        if (amount < 1) throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "amount must be at least 1")
        val w = wallets.lockById(userId) ?: throw ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found")
        reservations.findById(betId).orElse(null)?.let {
            if (it.userId == userId && it.amount == amount) return false
            throw ApiException(HttpStatus.CONFLICT, "RESERVATION_CONFLICT", "Bet $betId already has a different reservation")
        }
        if (w.balance - w.reserved < amount) throw ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS", "Insufficient funds")
        w.reserved += amount
        reservations.save(Reservation(betId, userId, amount, RESERVED))
        move(w, betId, amount, "RESERVE", EventTypes.WALLET_RESERVED)
        return true
    }

    fun release(betId: UUID): String {
        val (w, r) = locked(betId) ?: throw ApiException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "Reservation not found")
        if (r.status == COMMITTED) throw ApiException(HttpStatus.CONFLICT, "INVALID_RESERVATION_STATE", "Reservation is committed")
        if (r.status == RESERVED) doRelease(w, r)
        return r.status
    }

    fun commit(betId: UUID) {
        val (w, r) = locked(betId) ?: throw IllegalStateException("Unknown reservation $betId")
        if (r.status != RESERVED) return
        w.balance -= r.amount
        w.reserved -= r.amount
        r.status = COMMITTED
        move(w, betId, r.amount, "DEBIT", EventTypes.WALLET_DEBITED)
    }

    fun cancel(betId: UUID) {
        val (w, r) = locked(betId) ?: throw IllegalStateException("Unknown reservation $betId")
        when (r.status) {
            RESERVED -> doRelease(w, r)
            COMMITTED -> {
                w.balance += r.amount
                r.status = REFUNDED
                move(w, betId, r.amount, "CREDIT", EventTypes.WALLET_CREDITED)
            }
        }
    }

    fun fail(betId: UUID) {
        val (w, r) = locked(betId) ?: return
        if (r.status == RESERVED) doRelease(w, r)
    }

    private fun doRelease(w: Wallet, r: Reservation) {
        w.reserved -= r.amount
        r.status = RELEASED
        move(w, r.betId, r.amount, "RELEASE", EventTypes.WALLET_RELEASED)
    }

    private fun locked(betId: UUID): Pair<Wallet, Reservation>? {
        val userId = reservations.userIdOf(betId) ?: return null
        val w = wallets.lockById(userId)!!
        return w to reservations.findById(betId).orElseThrow()
    }

    private fun move(w: Wallet, betId: UUID, amount: Long, type: String, eventType: String) {
        val now = Instant.now()
        w.updatedAt = now
        reservations.findById(betId).ifPresent { it.updatedAt = now }
        ledger.save(LedgerEntry(userId = w.userId, betId = betId, type = type, amount = amount, balanceAfter = w.balance))
        outbox.append(eventType, w.userId.toString(), WalletMovementV1(w.userId, betId, amount, w.balance, w.reserved))
    }

    companion object {
        const val RESERVED = "RESERVED"
        const val COMMITTED = "COMMITTED"
        const val RELEASED = "RELEASED"
        const val REFUNDED = "REFUNDED"
    }
}

data class ReserveRequest(val betId: UUID, val amount: Long)

// Internal API for Bet Service. The gateway does not route /internal/**.
@RestController
class ReservationController(private val moves: WalletMoves) {
    @PostMapping("/internal/wallets/{userId}/reservations")
    fun reserve(@PathVariable userId: UUID, @RequestBody req: ReserveRequest): ResponseEntity<Map<String, UUID>> {
        val created = moves.reserve(userId, req.betId, req.amount)
        return ResponseEntity.status(if (created) HttpStatus.CREATED else HttpStatus.OK).body(mapOf("reservationId" to req.betId))
    }

    @PostMapping("/internal/wallets/reservations/{betId}/release")
    fun release(@PathVariable betId: UUID) = mapOf("reservationId" to betId.toString(), "status" to moves.release(betId))
}

@Component
class BetListener(private val idempotentConsumer: IdempotentConsumer, private val moves: WalletMoves) {
    @KafkaListener(topics = [EventTypes.BET_PLACED])
    fun onBetPlaced(json: String) = handle(json, moves::commit)

    @KafkaListener(topics = [EventTypes.BET_CANCELLED])
    fun onBetCancelled(json: String) = handle(json, moves::cancel)

    @KafkaListener(topics = [EventTypes.BET_FAILED])
    fun onBetFailed(json: String) = handle(json, moves::fail)

    private fun handle(json: String, action: (UUID) -> Unit) {
        val envelope = EventJson.read(json)
        val betId = EventJson.payload(envelope, BetV1::class).betId
        idempotentConsumer.runOnce(envelope.eventId, "wallet-service") { action(betId) }
    }
}
