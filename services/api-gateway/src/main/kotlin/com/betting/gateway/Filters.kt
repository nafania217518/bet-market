package com.betting.gateway

import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.util.UUID

const val CORRELATION_ID = "X-Correlation-Id"

@Component
class CorrelationIdFilter : GlobalFilter, Ordered {
    override fun getOrder() = Ordered.HIGHEST_PRECEDENCE

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val id = exchange.request.headers.getFirst(CORRELATION_ID)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        exchange.response.headers.set(CORRELATION_ID, id)
        return chain.filter(exchange.mutate().request { it.headers { h -> h.set(CORRELATION_ID, id) } }.build())
    }
}

@Component
class IdentityHeadersFilter : GlobalFilter, Ordered {
    override fun getOrder() = 0

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> =
        exchange.getPrincipal<JwtAuthenticationToken>()
            .map { it as Any }
            .defaultIfEmpty(Unit)
            .flatMap { principal ->
                val request = exchange.request.mutate().headers { h ->
                    h.remove("X-User-Id")
                    h.remove("X-User-Roles")
                    if (principal is JwtAuthenticationToken) {
                        h.set("X-User-Id", principal.token.subject)
                        h.set("X-User-Roles", principal.token.getClaimAsStringList("roles").orEmpty().joinToString(","))
                    }
                }.build()
                chain.filter(exchange.mutate().request(request).build())
            }
}
