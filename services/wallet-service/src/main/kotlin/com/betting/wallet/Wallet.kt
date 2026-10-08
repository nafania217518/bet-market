package com.betting.wallet

import com.betting.common.ApiException
import com.betting.common.IdempotentConsumer
import com.betting.common.OutboxWriter
import com.betting.events.EventJson
import com.betting.events.EventTypes
import com.betting.events.UserRegisteredV1
import com.betting.events.WalletCreatedV1
import jakarta.persistence.Entity
import jakarta.persistence.LockModeType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.http.HttpStatus
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "wallets")
class Wallet(
    @Id val userId: UUID,
    var balance: Long,
    var reserved: Long = 0,
    @Version var version: Long = 0,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)

@Entity
@Table(name = "ledger_entries")
class LedgerEntry(
    @Id val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val betId: UUID?,
    val type: String,
    val amount: Long,
    val balanceAfter: Long,
    val createdAt: Instant = Instant.now(),
)

interface WalletRepository : JpaRepository<Wallet, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.userId = :userId")
    fun lockById(userId: UUID): Wallet?
}
interface LedgerEntryRepository : JpaRepository<LedgerEntry, UUID> {
    fun findByUserIdOrderByCreatedAtDesc(userId: UUID, pageable: Pageable): List<LedgerEntry>
}

@Component
class UserRegisteredListener(
    private val idempotentConsumer: IdempotentConsumer,
    private val wallets: WalletRepository,
    private val ledger: LedgerEntryRepository,
    private val outboxWriter: OutboxWriter,
    @Value("\${wallet.starting-tokens}") private val startingTokens: Long,
) {
    @KafkaListener(topics = [EventTypes.USER_REGISTERED])
    fun onUserRegistered(json: String) {
        val envelope = EventJson.read(json)
        val userId = EventJson.payload(envelope, UserRegisteredV1::class).userId
        idempotentConsumer.runOnce(envelope.eventId, "wallet-service") {
            if (wallets.existsById(userId)) return@runOnce
            wallets.save(Wallet(userId, startingTokens))
            ledger.save(LedgerEntry(userId = userId, betId = null, type = "CREDIT", amount = startingTokens, balanceAfter = startingTokens))
            outboxWriter.append(EventTypes.WALLET_CREATED, userId.toString(), WalletCreatedV1(userId, startingTokens))
        }
    }
}

data class WalletResponse(val userId: UUID, val balance: Long, val reserved: Long, val available: Long)

data class LedgerEntryResponse(val id: UUID, val betId: UUID?, val type: String, val amount: Long, val balanceAfter: Long, val createdAt: Instant)
data class LedgerPage(val entries: List<LedgerEntryResponse>, val page: Int, val size: Int)

private fun badRequest(message: String) = ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message)

private fun parseUserId(raw: String) = runCatching { UUID.fromString(raw) }.getOrElse { throw badRequest("X-User-Id must be a UUID") }

@RestController
class WalletController(private val wallets: WalletRepository, private val ledger: LedgerEntryRepository) {
    @GetMapping("/api/wallets/me")
    fun me(@RequestHeader("X-User-Id") rawUserId: String): WalletResponse {
        val userId = parseUserId(rawUserId)
        val w = wallets.findById(userId)
            .orElseThrow { ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found") }
        return WalletResponse(w.userId, w.balance, w.reserved, w.balance - w.reserved)
    }

    @GetMapping("/api/wallets/me/ledger")
    fun ledger(
        @RequestHeader("X-User-Id") rawUserId: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): LedgerPage {
        val userId = parseUserId(rawUserId)
        if (page < 0) throw badRequest("page must be >= 0")
        if (size !in 1..100) throw badRequest("size must be in 1..100")
        val entries = ledger.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
            .map { LedgerEntryResponse(it.id, it.betId, it.type, it.amount, it.balanceAfter, it.createdAt) }
        return LedgerPage(entries, page, size)
    }
}
