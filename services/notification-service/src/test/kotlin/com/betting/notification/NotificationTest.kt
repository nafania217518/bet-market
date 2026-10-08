package com.betting.notification

import com.betting.events.BetV1
import com.betting.events.EventEnvelope
import com.betting.events.EventJson
import com.betting.events.EventTypes
import com.betting.events.MarketV1
import com.betting.events.PayoutCalculatedV1
import com.betting.events.PayoutV1
import com.betting.events.SettlementCompletedV1
import com.betting.events.WalletMovementV1
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.messaging.converter.JacksonJsonMessageConverter
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.web.client.RestClient
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import org.testcontainers.kafka.KafkaContainer
import java.lang.reflect.Type
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val testKey: RSAKey = RSAKeyGenerator(2048).keyID("test").generate()

@TestConfiguration(proxyBeanMethods = false)
class TestBeans {
    @Bean @ServiceConnection
    fun kafkaContainer() = KafkaContainer("apache/kafka:4.3.1")

    @Bean @Primary
    fun testJwtDecoder(): JwtDecoder = NimbusJwtDecoder.withPublicKey(testKey.toRSAPublicKey()).build()
}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.consumer.auto-offset-reset=earliest",
    ],
)
@Import(TestBeans::class)
@AutoConfigureMetrics
class NotificationTest {
    @LocalServerPort var port = 0
    @Autowired lateinit var kafka: KafkaTemplate<String, String>

    private val sessions = mutableListOf<StompSession>()
    private val errors = ConcurrentLinkedQueue<String>()

    @AfterEach
    fun close() = sessions.forEach { runCatching { it.disconnect() } }

    fun token(sub: String, expiresAt: Instant = Instant.now().plusSeconds(900)): String {
        val claims = JWTClaimsSet.Builder().subject(sub).issuer("bet-market")
            .issueTime(Date()).expirationTime(Date.from(expiresAt)).build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(testKey.keyID).build(), claims)
            .apply { sign(RSASSASigner(testKey)) }.serialize()
    }

    fun connect(token: String? = null): StompSession {
        val client = WebSocketStompClient(StandardWebSocketClient()).apply { messageConverter = JacksonJsonMessageConverter() }
        val headers = StompHeaders().apply { token?.let { add("Authorization", "Bearer $it") } }
        val handler = object : StompSessionHandlerAdapter() {
            override fun handleFrame(headers: StompHeaders, payload: Any?) { errors.add("ERROR ${headers.getFirst("message")}") }
            override fun handleTransportError(session: StompSession, exception: Throwable) { errors.add("transport $exception") }
        }
        return client.connectAsync("ws://localhost:$port/ws", WebSocketHttpHeaders(), headers, handler)
            .get(10, TimeUnit.SECONDS).also { sessions.add(it) }
    }

    fun StompSession.listen(destination: String): ConcurrentLinkedQueue<Map<String, Any?>> {
        val received = ConcurrentLinkedQueue<Map<String, Any?>>()
        subscribe(destination, object : StompFrameHandler {
            override fun getPayloadType(headers: StompHeaders): Type = Map::class.java
            @Suppress("UNCHECKED_CAST")
            override fun handleFrame(headers: StompHeaders, payload: Any?) { received.add(payload as Map<String, Any?>) }
        })
        return received
    }

    fun publish(type: String, payload: Any) =
        kafka.send(type, EventJson.write(EventEnvelope(UUID.randomUUID(), type, 1, Instant.now(), "c", null, "agg", "test",
            EventJson.toNode(payload)))).get()

    /** The subscription is async: publish again until the message arrives. */
    fun publishUntil(type: String, payload: Any, check: () -> Unit) =
        await.atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).ignoreExceptions().untilAsserted { publish(type, payload); check() }

    val now: Instant = Instant.parse("2026-10-08T12:00:00Z")

    @Test
    fun `a market event reaches public markets subscribers without a token`() {
        val m = UUID.randomUUID()
        val markets = connect().listen("/topic/public.markets")
        publishUntil(EventTypes.MARKET_CREATED, MarketV1(m, "t", "d", "OPEN", null, now, now, 1, 100, 1)) {
            val msg = markets.first { it["marketId"] == m.toString() }
            assertEquals(EventTypes.MARKET_CREATED, msg["type"])
            assertEquals("t", (msg["market"] as Map<*, *>)["title"])
        }
    }

    @Test
    fun `a wallet event reaches only its owner on user queue wallet`() {
        val owner = UUID.randomUUID()
        val bet = UUID.randomUUID()
        val mine = connect(token(owner.toString())).listen("/user/queue/wallet")
        val other = connect(token(UUID.randomUUID().toString())).listen("/user/queue/wallet")
        publishUntil(EventTypes.WALLET_RESERVED, WalletMovementV1(owner, bet, 10, 100, 30)) {
            assertEquals(
                mapOf("type" to EventTypes.WALLET_RESERVED, "betId" to bet.toString(), "amount" to 10, "balance" to 100,
                    "reserved" to 30, "available" to 70),
                mine.first(),
            )
        }
        assertTrue(other.isEmpty())
    }

    @Test
    fun `a bet placed event notifies the bettor and tells public markets the pool changed`() {
        val user = UUID.randomUUID()
        val m = UUID.randomUUID()
        val b = UUID.randomUUID()
        val mine = connect(token(user.toString())).listen("/user/queue/notifications")
        val markets = connect().listen("/topic/public.markets")
        publishUntil(EventTypes.BET_PLACED, BetV1(b, m, user, "TRUE", 5, "ACCEPTED", now)) {
            assertEquals(
                mapOf("type" to EventTypes.BET_PLACED, "betId" to b.toString(), "marketId" to m.toString(), "side" to "TRUE",
                    "amount" to 5, "status" to "ACCEPTED"),
                mine.first(),
            )
            assertEquals(mapOf("type" to EventTypes.BET_PLACED, "marketId" to m.toString()), markets.first { it["marketId"] == m.toString() })
        }
    }

    @Test
    fun `payout calculated notifies each paid user`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val m = UUID.randomUUID()
        val qa = connect(token(a.toString())).listen("/user/queue/notifications")
        val qb = connect(token(b.toString())).listen("/user/queue/notifications")
        publishUntil(EventTypes.PAYOUT_CALCULATED, PayoutCalculatedV1(m, listOf(PayoutV1(a, 7, "WIN"), PayoutV1(b, 3, "REFUND")))) {
            assertEquals(mapOf("type" to EventTypes.PAYOUT_CALCULATED, "marketId" to m.toString(), "amount" to 7, "payoutType" to "WIN"), qa.first())
            assertEquals(mapOf("type" to EventTypes.PAYOUT_CALCULATED, "marketId" to m.toString(), "amount" to 3, "payoutType" to "REFUND"), qb.first())
        }
    }

    @Test
    fun `settlement completed reaches public leaderboard and public markets`() {
        val m = UUID.randomUUID()
        val session = connect()
        val board = session.listen("/topic/public.leaderboard")
        val markets = session.listen("/topic/public.markets")
        publishUntil(EventTypes.SETTLEMENT_COMPLETED, SettlementCompletedV1(m, "RESOLVED", "TRUE", 1, 1, 2, 0, 2)) {
            val expected = mapOf("type" to EventTypes.SETTLEMENT_COMPLETED, "marketId" to m.toString())
            assertEquals(expected, board.first { it["marketId"] == m.toString() })
            assertEquals(expected, markets.first { it["marketId"] == m.toString() })
        }
    }

    @Test
    fun `connect with an invalid token is rejected`() {
        val expired = token("u", expiresAt = Instant.now().minusSeconds(3600))
        runCatching { connect(expired) }
        await.atMost(Duration.ofSeconds(10)).untilAsserted { assertTrue(errors.any { it.startsWith("ERROR") }) }
    }

    @Test
    fun `subscribe to a raw queue destination is rejected`() {
        val session = connect(token(UUID.randomUUID().toString()))
        session.listen("/queue/wallet-user123")
        await.atMost(Duration.ofSeconds(10)).untilAsserted { assertTrue(errors.any { it.startsWith("ERROR") }) }
    }

    @Test
    fun `health is UP`() {
        val body = RestClient.create().get().uri("http://localhost:$port/actuator/health").retrieve().body(String::class.java)
        assertTrue(body!!.contains("\"UP\""))
    }

    @Test
    fun `actuator prometheus exposes JVM metrics and the HTTP latency histogram`() {
        val http = RestClient.create("http://localhost:$port")
        http.get().uri("/actuator/health").retrieve().toBodilessEntity()
        val body = http.get().uri("/actuator/prometheus").retrieve().body(String::class.java)!!
        assertTrue(body.contains("jvm_memory_used_bytes"))
        assertTrue(body.contains("http_server_requests_seconds_bucket"))
    }
}
