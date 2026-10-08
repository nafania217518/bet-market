package com.betting.gateway

import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServer
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class WebSocketRouteTest : GatewayTestBase() {
    @LocalServerPort var port = 0

    @Test
    fun `a WebSocket on ws is proxied to Notification Service without a token`() {
        var echo: String? = null
        ReactorNettyWebSocketClient().execute(URI("ws://localhost:$port/ws")) { session ->
            session.send(Mono.just(session.textMessage("hello")))
                .thenMany(session.receive().take(1).doOnNext { echo = it.payloadAsText })
                .then()
        }.block(Duration.ofSeconds(10))
        assertEquals("hello", echo)
    }

    companion object {
        private val upstream = HttpServer.create().port(0)
            .route { it.ws("/ws") { inbound, outbound -> outbound.send(inbound.receive().retain()) } }
            .bindNow()

        @JvmStatic
        @DynamicPropertySource
        fun ws(r: DynamicPropertyRegistry) {
            r.add("NOTIFICATION_SERVICE_URL") { "ws://localhost:${upstream.port()}" }
        }
    }
}
