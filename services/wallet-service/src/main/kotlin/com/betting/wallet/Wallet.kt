package com.betting.wallet

import com.betting.common.ApiException
import com.betting.common.IdempotentConsumer
import com.betting.common.OutboxWriter
import com.betting.events.EventJson
import com.betting.events.EventTypes
import com.betting.events.UserRegisteredV1
import com.betting.events.WalletCreatedV1
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
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

interface WalletRepository : JpaRepository<Wallet, UUID>
interface LedgerEntryRepository : JpaRepository<LedgerEntry, UUID>

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

@RestController
class WalletController(private val wallets: WalletRepository) {
    @GetMapping("/api/wallets/me")
    fun me(@RequestHeader("X-User-Id") rawUserId: String): WalletResponse {
        val userId = runCatching { UUID.fromString(rawUserId) }
            .getOrElse { throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "X-User-Id must be a UUID") }
        val w = wallets.findById(userId)
            .orElseThrow { ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found") }
        return WalletResponse(w.userId, w.balance, w.reserved, w.balance - w.reserved)
    }
}
