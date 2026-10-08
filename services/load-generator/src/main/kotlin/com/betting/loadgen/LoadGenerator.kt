package com.betting.loadgen

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.SmartLifecycle
import org.springframework.http.HttpMethod
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.util.Random
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class LoadGeneratorApplication

fun main(args: Array<String>) {
    runApplication<LoadGeneratorApplication>(*args)
}

@ConfigurationProperties("loadgen")
data class LoadgenProperties(
    val gatewayUrl: String = "http://localhost:8080",
    val users: Int = 10000,
    val minDelay: Duration = Duration.ofSeconds(10),
    val maxDelay: Duration = Duration.ofSeconds(60),
    val rampUp: Duration = Duration.ofMinutes(10),
    val enabled: Boolean = true,
)

data class Market(val marketId: String, val status: String, val minBet: Long, val maxBet: Long)

/** HTTP status (-1 on I/O error) and the JSON body, if any. */
data class Resp(val status: Int, val body: JsonNode?)

@Component
class Gateway(builder: RestClient.Builder, props: LoadgenProperties, private val registry: MeterRegistry) {
    private val client = builder.baseUrl(props.gatewayUrl)
        .requestFactory(SimpleClientHttpRequestFactory().apply { setConnectTimeout(2000); setReadTimeout(10000) })
        .build()

    /** Never throws on 4xx/5xx or I/O. Counts `loadgen.actions` when [action] is set. */
    fun call(action: String?, method: HttpMethod, uri: String, token: String? = null, body: Any? = null, idempotencyKey: String? = null): Resp {
        val resp = try {
            client.method(method).uri(uri).headers {
                token?.let { t -> it.setBearerAuth(t) }
                idempotencyKey?.let { k -> it.set("Idempotency-Key", k) }
            }.apply { body?.let { body(it) } }
                .exchange { _, res -> Resp(res.statusCode.value(), runCatching { res.bodyTo(JsonNode::class.java) }.getOrNull()) }!!
        } catch (e: Exception) {
            Resp(-1, null)
        }
        action?.let { registry.counter("loadgen.actions", "action", it, "outcome", if (resp.status < 0) "error" else "${resp.status}").increment() }
        return resp
    }
}

@Component
class MarketCatalog(private val gateway: Gateway) {
    @Volatile private var markets = emptyList<Market>()

    fun open() = markets

    fun refresh() {
        val r = gateway.call(null, HttpMethod.GET, "/api/markets?status=OPEN&size=100")
        if (r.status != 200) return
        markets = (r.body!!.path("items") as Iterable<JsonNode>).map { n: JsonNode ->
            Market(n.path("marketId").asString(), n.path("status").asString(), n.path("minBet").asLong(), n.path("maxBet").asLong())
        }.filter { it.status == "OPEN" }
    }
}

/** One simulated user. [step] does one action and never sleeps or throws. */
class Bot(private val gateway: Gateway, private val catalog: MarketCatalog, private val random: Random, private val registry: MeterRegistry) {
    private var email: String? = null
    private var password = ""
    private var token: String? = null
    private var expiresAt = Instant.MIN
    private var retired = false

    fun step(action: String = pickAction()) {
        try {
            if (email == null || retired) {
                if (!register()) return
            }
            if (token == null || expiresAt.isBefore(Instant.now().plusSeconds(60))) {
                if (!login()) return
            }
            when (action) {
                "bet" -> bet()
                "browse" -> browse()
                "cancel" -> cancel()
            }
        } catch (e: Exception) {
            // Never crash the thread: the next step tries again.
        }
    }

    private fun pickAction() = random.nextInt(100).let { if (it < 70) "bet" else if (it < 95) "browse" else "cancel" }

    private fun register(): Boolean {
        val id = UUID.randomUUID().toString()
        val newEmail = "bot-$id@load.bet-market.local"
        val newPassword = UUID.randomUUID().toString()
        val r = gateway.call("register", HttpMethod.POST, "/api/auth/register", body = mapOf("email" to newEmail, "password" to newPassword, "displayName" to "Bot ${id.take(8)}"))
        if (r.status != 201) return false
        email = newEmail
        password = newPassword
        token = null
        retired = false
        registry.counter("loadgen.bots.registered").increment()
        return true
    }

    private fun login(): Boolean {
        val r = gateway.call("login", HttpMethod.POST, "/api/auth/login", body = mapOf("email" to email, "password" to password))
        if (r.status != 200) return false
        token = r.body!!.path("accessToken").asString()
        expiresAt = Instant.now().plusSeconds(r.body.path("expiresIn").asLong())
        return true
    }

    private fun authed(action: String, method: HttpMethod, uri: String, body: Any? = null, idempotencyKey: String? = null): Resp =
        gateway.call(action, method, uri, token, body, idempotencyKey).also { if (it.status == 401) token = null }

    private fun bet() {
        val w = authed("bet", HttpMethod.GET, "/api/wallets/me")
        val open = catalog.open()
        if (w.status != 200 || open.isEmpty()) return
        val available = w.body!!.path("available").asLong()
        if (available < open.minOf { it.minBet }) {
            retired = true
            registry.counter("loadgen.bots.retired").increment()
            return
        }
        val fits = open.filter { it.minBet <= available }
        val m = fits[random.nextInt(fits.size)]
        val amount = m.minBet + random.nextLong(minOf(m.maxBet, available) - m.minBet + 1)
        val side = if (random.nextBoolean()) "TRUE" else "FALSE"
        authed("bet", HttpMethod.POST, "/api/bets", mapOf("marketId" to m.marketId, "side" to side, "amount" to amount), UUID.randomUUID().toString())
    }

    private fun browse() {
        val open = catalog.open()
        val uri = when (random.nextInt(5)) {
            0 -> if (open.isEmpty()) return else "/api/markets/${open[random.nextInt(open.size)].marketId}"
            1 -> "/api/me/bets"
            2 -> "/api/wallets/me"
            3 -> "/api/wallets/me/ledger"
            else -> "/api/leaderboard"
        }
        authed("browse", HttpMethod.GET, uri)
    }

    private fun cancel() {
        val r = authed("cancel", HttpMethod.GET, "/api/me/bets?status=ACCEPTED&size=20")
        if (r.status != 200) return
        val open = catalog.open().map { it.marketId }.toSet()
        val bet = (r.body!!.path("items") as Iterable<JsonNode>).firstOrNull { it: JsonNode -> it.path("marketId").asString() in open } ?: return
        authed("cancel", HttpMethod.DELETE, "/api/bets/${bet.path("betId").asString()}")
    }
}

/** Starts one bot per slot on virtual threads, spread over the ramp-up time. */
@Component
class LoadRunner(
    private val props: LoadgenProperties,
    private val gateway: Gateway,
    private val catalog: MarketCatalog,
    private val registry: MeterRegistry,
) : SmartLifecycle {
    private val active = registry.gauge("loadgen.bots.active", AtomicInteger())!!
    private var executor: ExecutorService? = null

    init {
        registry.counter("loadgen.bots.registered")
        registry.counter("loadgen.bots.retired")
    }

    @Scheduled(fixedDelay = 10_000)
    fun refreshCatalog() {
        if (props.enabled) catalog.refresh()
    }

    override fun start() {
        if (!props.enabled) return
        val ex = Executors.newVirtualThreadPerTaskExecutor()
        executor = ex
        repeat(props.users) { i -> ex.submit { runSlot(i) } }
    }

    private fun runSlot(i: Int) {
        val random = Random()
        try {
            Thread.sleep(startDelay(i, props.users, props.rampUp))
            active.incrementAndGet()
            try {
                val bot = Bot(gateway, catalog, random, registry)
                val spread = props.maxDelay.toMillis() - props.minDelay.toMillis()
                while (true) {
                    bot.step()
                    Thread.sleep(props.minDelay.toMillis() + random.nextLong(spread + 1))
                }
            } finally {
                active.decrementAndGet()
            }
        } catch (e: InterruptedException) {
            // Shutdown.
        }
    }

    override fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    override fun isRunning() = executor != null

    companion object {
        fun startDelay(i: Int, users: Int, rampUp: Duration): Duration = rampUp.multipliedBy(i.toLong()).dividedBy(users.toLong())
    }
}
