package com.betting.gateway

import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsConfigurationSource
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource
import reactor.core.publisher.Mono

@Configuration
class SecurityConfig {
    @Bean
    fun securityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        val authorities = JwtGrantedAuthoritiesConverter().apply {
            setAuthoritiesClaimName("roles")
            setAuthorityPrefix("ROLE_")
        }
        val converter = JwtAuthenticationConverter().apply { setJwtGrantedAuthoritiesConverter(authorities) }
        return http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .cors { }
            .authorizeExchange {
                it.pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .pathMatchers("/actuator/health/**", "/actuator/health").permitAll()
                    // nginx and the future ingress expose only /api and /ws, so metrics stay internal.
                    .pathMatchers("/actuator/prometheus").permitAll()
                    .pathMatchers(HttpMethod.POST, "/api/auth/**").permitAll()
                    // The browser cannot send Authorization on the WebSocket upgrade; Notification Service checks the JWT in the STOMP CONNECT frame.
                    .pathMatchers("/ws", "/ws/**").permitAll()
                    .pathMatchers(HttpMethod.GET, "/api/markets/**", "/api/leaderboard", "/api/stats/**").permitAll()
                    .pathMatchers(HttpMethod.POST, "/api/markets/**").hasRole("ADMIN")
                    .pathMatchers(HttpMethod.PATCH, "/api/markets/**").hasRole("ADMIN")
                    .pathMatchers(HttpMethod.DELETE, "/api/markets/**").hasRole("ADMIN")
                    .anyExchange().authenticated()
            }
            .oauth2ResourceServer { rs ->
                rs.jwt { it.jwtAuthenticationConverter(ReactiveJwtAuthenticationConverterAdapter(converter)) }
            }
            .build()
    }

    @Bean
    fun corsConfigurationSource(@Value("\${CORS_ALLOWED_ORIGINS:http://localhost:5173}") origins: String): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = origins.split(",").map { it.trim() }
            allowedMethods = listOf("GET", "POST", "PATCH", "DELETE", "OPTIONS")
            addAllowedHeader("*")
            exposedHeaders = listOf("X-Correlation-Id")
            allowCredentials = true
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", config) }
    }

    @Bean
    fun userOrIpKeyResolver() = KeyResolver { exchange ->
        exchange.getPrincipal<JwtAuthenticationToken>()
            .map { "user:${it.name}" }
            .switchIfEmpty(Mono.fromSupplier { "ip:${exchange.request.remoteAddress?.hostString ?: "unknown"}" })
    }
}
