package com.betting.loadgen

import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["loadgen.enabled=false"])
@AutoConfigureMetrics
class LoadGeneratorTest {
    @Autowired lateinit var gateway: Gateway
    @Autowired lateinit var catalog: MarketCatalog
    @Autowired lateinit var registry: MeterRegistry
    @LocalServerPort var port = 0

    data class Req(val method: String, val path: String, val auth: String?, val body: String, val idem: String?)

    companion object {
        val log = CopyOnWriteArrayList<Req>()
        /** "METHOD /path" (path without query) -> status to body. */
        val script = ConcurrentHashMap<String, Pair<Int, String>>()
        private var logins = 0

        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.readAllBytes().decodeToString()
                log += Req(ex.requestMethod, ex.requestURI.toString(), ex.requestHeaders.getFirst("Authorization"), body, ex.requestHeaders.getFirst("Idempotency-Key"))
                val key = "${ex.requestMethod} ${ex.requestURI.path}"
                val (status, out) = script[key] ?: when {
                    key == "POST /api/auth/login" -> 200 to """{"accessToken":"t${++logins}","tokenType":"Bearer","expiresIn":900}"""
                    key == "POST /api/auth/register" -> 201 to """{"userId":"u1"}"""
                    key.startsWith("DELETE") -> 204 to ""
                    else -> 200 to "{}"
                }
                val bytes = out.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            r.add("loadgen.gateway-url") { "http://127.0.0.1:${server.address.port}" }
        }

        fun market(id: String, status: String = "OPEN", min: Long = 10, max: Long = 100) =
            """{"marketId":"$id","title":"M $id","status":"$status","minBet":$min,"maxBet":$max}"""

        fun markets(vararg m: String) = 200 to """{"items":[${m.joinToString(",")}],"page":0,"size":100,"total":${m.size}}"""
        fun wallet(available: Long) = 200 to """{"userId":"u1","balance":$available,"reserved":0,"available":$available}"""
    }

    @BeforeEach
    fun reset() {
        script.clear()
        script["GET /api/markets"] = markets()
        catalog.refresh()
        log.clear()
    }

    fun openMarkets(vararg m: String) {
        script["GET /api/markets"] = markets(*m)
        catalog.refresh()
    }

    /** A random that always picks [action] (0..69 bet, 70..94 browse, 95..99 cancel) first. */
    fun bot(seed: Long = 1) = Bot(gateway, catalog, Random(seed), registry)

    fun Bot.stepAs(action: String) = step(action)

    fun count(action: String, outcome: String) =
        registry.find("loadgen.actions").tag("action", action).tag("outcome", outcome).counter()?.count() ?: 0.0

    @Test
    fun `a new bot registers, logs in and keeps its token`() {
        val b = bot()
        b.stepAs("none")
        b.stepAs("none")
        val calls = log.map { "${it.method} ${it.path}" }
        assertEquals(listOf("POST /api/auth/register", "POST /api/auth/login"), calls)
        assertTrue(log[0].body.contains("@load.bet-market.local") && log[0].body.contains("\"displayName\":\"Bot "))
    }

    @Test
    fun `a bet picks an open market and an amount between minBet and the smaller of maxBet and available`() {
        openMarkets(market("m1", min = 10, max = 100), market("m2", min = 500, max = 900))
        script["GET /api/wallets/me"] = wallet(50)
        script["POST /api/bets"] = 202 to """{"betId":"b1","status":"PENDING"}"""
        val b = bot()
        repeat(30) { b.stepAs("bet") }
        val bets = log.filter { it.method == "POST" && it.path == "/api/bets" }
        assertEquals(30, bets.size)
        bets.forEach {
            assertTrue(it.body.contains("\"marketId\":\"m1\""), it.body)
            val amount = Regex("\"amount\":(\\d+)").find(it.body)!!.groupValues[1].toLong()
            assertTrue(amount in 10..50, "amount $amount")
            assertTrue(it.idem != null && it.auth == "Bearer t${it.auth!!.removePrefix("Bearer t")}")
        }
        assertEquals(30, bets.map { it.idem }.toSet().size)
    }

    @Test
    fun `a bot without enough tokens for any open market retires and its slot registers a new user`() {
        openMarkets(market("m1", min = 10))
        script["GET /api/wallets/me"] = wallet(5)
        val b = bot()
        val retired = registry.counter("loadgen.bots.retired").count()
        b.stepAs("bet")
        assertEquals(retired + 1, registry.counter("loadgen.bots.retired").count())
        b.stepAs("none")
        val registers = log.filter { it.path == "/api/auth/register" }.map { it.body }
        assertEquals(2, registers.size)
        assertTrue(registers[0] != registers[1])
        assertTrue(log.none { it.path == "/api/bets" })
    }

    @Test
    fun `a wallet that does not exist yet means no bet this step`() {
        openMarkets(market("m1"))
        script["GET /api/wallets/me"] = 404 to """{"code":"WALLET_NOT_FOUND","message":"x"}"""
        bot().stepAs("bet")
        assertTrue(log.none { it.path == "/api/bets" })
        assertTrue(log.any { it.path == "/api/wallets/me" })
    }

    @Test
    fun `no open markets means no bet`() {
        script["GET /api/wallets/me"] = wallet(1000)
        bot().stepAs("bet")
        assertTrue(log.none { it.path == "/api/bets" })
    }

    @Test
    fun `a 401 makes the bot log in again`() {
        script["GET /api/wallets/me"] = 401 to """{"code":"UNAUTHORIZED","message":"x"}"""
        val b = bot()
        b.stepAs("bet")
        b.stepAs("bet")
        assertEquals(2, log.count { it.path == "/api/auth/login" })
        assertEquals(1, log.count { it.path == "/api/auth/register" })
    }

    @Test
    fun `a token close to expiry is renewed before the next call`() {
        script["POST /api/auth/login"] = 200 to """{"accessToken":"short","tokenType":"Bearer","expiresIn":30}"""
        val b = bot()
        b.stepAs("none")
        b.stepAs("none")
        assertEquals(2, log.count { it.path == "/api/auth/login" })
    }

    @Test
    fun `cancel deletes an ACCEPTED bet on an open market`() {
        openMarkets(market("m1"))
        script["GET /api/me/bets"] = 200 to
            """{"items":[{"betId":"b0","marketId":"closed","side":"TRUE","amount":10,"status":"ACCEPTED"},""" +
            """{"betId":"b1","marketId":"m1","side":"TRUE","amount":10,"status":"ACCEPTED"}]}"""
        bot().stepAs("cancel")
        assertTrue(log.any { it.path == "/api/me/bets?status=ACCEPTED&size=20" })
        val deletes = log.filter { it.method == "DELETE" }
        assertEquals(listOf("/api/bets/b1"), deletes.map { it.path })
        assertTrue(deletes[0].auth!!.startsWith("Bearer "))
    }

    @Test
    fun `browse calls a read endpoint with the bot's token`() {
        openMarkets(market("m1"))
        log.clear()
        val b = bot()
        repeat(40) { b.stepAs("browse") }
        val reads = log.filter { !it.path.startsWith("/api/auth") }
        assertEquals(40, reads.size)
        assertTrue(reads.all { it.method == "GET" && it.auth == "Bearer t${it.auth!!.removePrefix("Bearer t")}" })
        assertEquals(
            setOf("/api/markets/m1", "/api/me/bets", "/api/wallets/me", "/api/wallets/me/ledger", "/api/leaderboard"),
            reads.map { it.path.substringBefore("?") }.toSet(),
        )
    }

    @Test
    fun `the market catalog keeps only OPEN markets`() {
        openMarkets(market("m1"), market("m2", status = "FROZEN"), market("m3", status = "SETTLED"))
        assertEquals(listOf("m1"), catalog.open().map { it.marketId })
        assertTrue(log.any { it.path == "/api/markets?status=OPEN&size=100" && it.auth == null })
    }

    @Test
    fun `bots start spread evenly over the ramp-up time`() {
        val d = (0 until 4).map { LoadRunner.startDelay(it, 4, Duration.ofMinutes(10)) }
        assertEquals(listOf(0L, 150L, 300L, 450L).map { Duration.ofSeconds(it) }, d)
    }

    @Test
    fun `the runner starts every bot, keeps them stepping and stops cleanly`() {
        val reg = io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        val props = LoadgenProperties(users = 2, minDelay = Duration.ofMillis(1), maxDelay = Duration.ofMillis(5), rampUp = Duration.ZERO)
        val runner = LoadRunner(props, gateway, catalog, reg)
        runner.refreshCatalog()
        assertTrue(log.any { it.path.startsWith("/api/markets?") })
        runner.start()
        assertTrue(runner.isRunning)
        val deadline = System.currentTimeMillis() + 5000
        while (log.count { it.path == "/api/auth/register" } < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(2.0, reg.get("loadgen.bots.active").gauge().value())
        runner.stop()
        assertTrue(!runner.isRunning)
        while (reg.get("loadgen.bots.active").gauge().value() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(0.0, reg.get("loadgen.bots.active").gauge().value())
    }

    @Test
    fun `errors are counted and do not stop the bot`() {
        openMarkets(market("m1"))
        script["GET /api/wallets/me"] = wallet(1000)
        script["POST /api/bets"] = 429 to """{"code":"RATE_LIMITED","message":"x"}"""
        val before = count("bet", "429")
        val b = bot()
        b.stepAs("bet")
        b.stepAs("bet")
        assertEquals(before + 2, count("bet", "429"))
        val badGateway = Gateway(RestClient.builder(), LoadgenProperties(gatewayUrl = "http://127.0.0.1:1"), registry)
        val ioBefore = count("register", "error")
        Bot(badGateway, catalog, Random(1), registry).step()
        assertEquals(ioBefore + 1, count("register", "error"))
    }

    @Test
    fun `actions are counted by action and outcome`() {
        val reg = count("register", "201")
        val login = count("login", "200")
        bot().stepAs("none")
        assertEquals(reg + 1, count("register", "201"))
        assertEquals(login + 1, count("login", "200"))
    }

    @Test
    fun `the load generator never creates markets`() {
        openMarkets(market("m1"), market("m2"), market("m3", min = 1, max = 5))
        script["GET /api/wallets/me"] = wallet(1000)
        script["GET /api/me/bets"] = 200 to """{"items":[{"betId":"b1","marketId":"m2","side":"FALSE","amount":10,"status":"ACCEPTED"}]}"""
        val bots = (1..5).map { bot(it.toLong()) }
        repeat(100) { bots.forEach { it.step() } }
        assertTrue(log.size > 500)
        assertTrue(log.none { it.method == "POST" && it.path.startsWith("/api/markets") })
    }

    @Test
    fun `actuator prometheus exposes loadgen metrics`() {
        bot().stepAs("none")
        val text = RestClient.create("http://localhost:$port").get().uri("/actuator/prometheus").retrieve().body(String::class.java)!!
        assertTrue(text.contains("loadgen_actions_total{action=\"register\",outcome=\"201\""), text)
        assertTrue(text.contains("loadgen_bots_active"))
        assertTrue(text.contains("loadgen_bots_registered_total"))
        assertTrue(text.contains("loadgen_bots_retired_total"))
    }
}
