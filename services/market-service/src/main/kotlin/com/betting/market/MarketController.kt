package com.betting.market

import com.betting.common.ApiException
import com.betting.events.MarketV1
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ResolveRequest(val winningSide: String)

@RestController
@RequestMapping("/api/markets")
class MarketController(private val service: MarketService) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestHeader("X-User-Id") userId: String, @RequestHeader("X-User-Roles") roles: String, @RequestBody req: CreateMarketRequest): MarketV1 =
        service.create(req, admin(userId, roles))

    @PatchMapping("/{id}")
    fun update(@RequestHeader("X-User-Id") userId: String, @RequestHeader("X-User-Roles") roles: String, @PathVariable id: UUID, @RequestBody req: UpdateMarketRequest): MarketV1 {
        admin(userId, roles)
        return service.update(id, req)
    }

    @PostMapping("/{id}/freeze")
    fun freeze(@RequestHeader("X-User-Id") userId: String, @RequestHeader("X-User-Roles") roles: String, @PathVariable id: UUID): MarketV1 {
        admin(userId, roles)
        return service.freeze(id)
    }

    @PostMapping("/{id}/resolve")
    fun resolve(@RequestHeader("X-User-Id") userId: String, @RequestHeader("X-User-Roles") roles: String, @PathVariable id: UUID, @RequestBody req: ResolveRequest): MarketV1 {
        admin(userId, roles)
        return service.resolve(id, req.winningSide)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancel(@RequestHeader("X-User-Id") userId: String, @RequestHeader("X-User-Roles") roles: String, @PathVariable id: UUID) {
        admin(userId, roles)
        service.cancel(id)
    }

    private fun admin(rawUserId: String, roles: String): UUID {
        val userId = runCatching { UUID.fromString(rawUserId) }.getOrElse { invalid("X-User-Id must be a UUID") }
        if ("ADMIN" !in roles.split(',').map { it.trim() }) throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "ADMIN role required")
        return userId
    }
}
