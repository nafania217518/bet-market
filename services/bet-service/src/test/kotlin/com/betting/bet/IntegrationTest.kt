package com.betting.bet

import com.betting.events.EventJson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.InetSocketAddress
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

@TestConfiguration(proxyBeanMethods = false)
class Containers {
    @Bean @ServiceConnection
    fun postgres() = PostgreSQLContainer("postgres:16-alpine")

    @Bean @ServiceConnection
    fun kafka() = KafkaContainer("apache/kafka:4.3.1")
}

/** One answer of the fake wallet. delayMs > read timeout simulates a wallet timeout. */
data class Reply(val status: Int, val body: String = "", val delayMs: Long = 0)

data class WalletCall(val method: String, val path: String, val body: String)

/** Fake wallet: records every request; tests choose the reservation reply. Release always answers 200. */
object FakeWallet {
    val calls = CopyOnWriteArrayList<WalletCall>()

    @Volatile var reserveReply = Reply(201)

    val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/") { ex -> handle(ex) }
        start()
    }

    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.readAllBytes().decodeToString()
        calls += WalletCall(ex.requestMethod, ex.requestURI.path, body)
        val reply = if (ex.requestURI.path.endsWith("/release")) Reply(200) else reserveReply
        Thread.sleep(reply.delayMs)
        val bytes = reply.body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.write(bytes)
        ex.close()
    }

    fun reserves() = calls.filter { it.path.endsWith("/reservations") }
    fun releases() = calls.filter { it.path.endsWith("/release") }
}

// Same config in every subclass -> Spring caches one context, containers start once.
@SpringBootTest(properties = ["outbox.poll-interval-ms=200"])
@AutoConfigureMockMvc
@Import(Containers::class)
abstract class IntegrationTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @MockitoSpyBean lateinit var bets: BetRepository

    val user: UUID = UUID.randomUUID()

    @BeforeEach
    fun resetWallet() {
        FakeWallet.calls.clear()
        FakeWallet.reserveReply = Reply(201)
    }

    fun snapshot(marketId: UUID = UUID.randomUUID(), status: String = "OPEN", freezeAt: Instant = Instant.now().plusSeconds(3600),
                 minBet: Long = 1, maxBet: Long = 100, version: Long = 1): UUID {
        jdbc.sql("INSERT INTO market_snapshot(market_id, status, freeze_at, min_bet, max_bet, version) VALUES (?, ?, ?, ?, ?, ?)")
            .params(marketId, status, Timestamp.from(freezeAt), minBet, maxBet, version).update()
        return marketId
    }

    fun setSnapshot(marketId: UUID, status: String, freezeAt: Instant = Instant.now().plusSeconds(3600)) {
        jdbc.sql("UPDATE market_snapshot SET status = ?, freeze_at = ? WHERE market_id = ?")
            .params(status, Timestamp.from(freezeAt), marketId).update()
    }

    fun body(marketId: UUID, side: String = "TRUE", amount: Long = 10) =
        """{"marketId":"$marketId","side":"$side","amount":$amount}"""

    fun place(json: String, key: String? = UUID.randomUUID().toString(), userId: String? = user.toString()): ResultActionsDsl =
        mvc.post("/api/bets") {
            userId?.let { header("X-User-Id", it) }
            key?.let { header("Idempotency-Key", it) }
            contentType = MediaType.APPLICATION_JSON
            content = json
        }

    fun betId(result: ResultActionsDsl): UUID =
        UUID.fromString(EventJson.mapper.readTree(result.andReturn().response.contentAsString).get("betId").asString())

    fun betCount(marketId: UUID): Int =
        jdbc.sql("SELECT count(*) FROM bets WHERE market_id = ?").param(marketId).query(Int::class.java).single()

    /** Outbox rows of one aggregate, oldest first: (eventType, envelope json). */
    fun outbox(aggregateId: UUID): List<Pair<String, String>> =
        jdbc.sql("SELECT event_type, payload::text FROM outbox_event WHERE aggregate_id = ? ORDER BY created_at")
            .param(aggregateId.toString()).query { rs, _ -> rs.getString(1) to rs.getString(2) }.list()

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun walletUrl(registry: DynamicPropertyRegistry) {
            registry.add("wallet.url") { "http://127.0.0.1:${FakeWallet.server.address.port}" }
        }
    }
}
