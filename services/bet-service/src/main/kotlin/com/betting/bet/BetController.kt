package com.betting.bet

import com.betting.common.ApiException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class BetController(private val service: BetService) {
    @PostMapping("/api/bets")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun place(
        @RequestHeader("X-User-Id") userId: String,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody req: PlaceBetRequest,
    ): BetResponse {
        if (key.isBlank() || key.length > 100) throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Idempotency-Key must be 1..100 chars")
        return service.place(uuid(userId), key, req)
    }

    @DeleteMapping("/api/bets/{id}")
    fun cancel(@RequestHeader("X-User-Id") userId: String, @PathVariable id: UUID): BetResponse = service.cancel(uuid(userId), id)

    private fun uuid(raw: String): UUID = runCatching { UUID.fromString(raw) }
        .getOrElse { throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "X-User-Id must be a UUID") }
}
