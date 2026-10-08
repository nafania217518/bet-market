package com.betting.notification

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import java.security.Principal

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(private val jwtDecoder: JwtDecoder) : WebSocketMessageBrokerConfigurer {
    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        // Any origin: auth uses a token in the CONNECT frame, not cookies, so cross-site hijacking gains nothing.
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*")
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        // ponytail: in-memory simple broker, single replica only; >1 replica needs a Redis/broker relay.
        registry.enableSimpleBroker("/topic", "/queue")
        registry.setUserDestinationPrefix("/user")
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(object : ChannelInterceptor {
            override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
                val stomp = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java) ?: return message
                when (stomp.command) {
                    // No header: anonymous session. Bad token: JwtException rejects the CONNECT.
                    StompCommand.CONNECT -> stomp.getFirstNativeHeader("Authorization")?.removePrefix("Bearer ")?.let {
                        val sub = jwtDecoder.decode(it).subject
                        stomp.user = Principal { sub }
                    }
                    // Raw /queue/... would let a client eavesdrop on another user's queue.
                    StompCommand.SUBSCRIBE -> require(stomp.destination.let { it != null && (it.startsWith("/topic/public.") || it.startsWith("/user/queue/")) }) {
                        "Forbidden destination ${stomp.destination}"
                    }
                    else -> {}
                }
                return message
            }
        })
    }
}

@Configuration
class JwtConfig {
    @Bean
    fun jwtDecoder(@Value("\${notification.jwks-uri}") jwksUri: String): JwtDecoder =
        NimbusJwtDecoder.withJwkSetUri(jwksUri).build().apply { setJwtValidator(JwtValidators.createDefaultWithIssuer("bet-market")) }
}
