package com.betting.market

import com.betting.events.EventJson
import com.betting.events.MarketV1
import com.github.kagkarlsson.scheduler.event.ExecutionChain
import com.github.kagkarlsson.scheduler.task.ExecutionContext
import com.github.kagkarlsson.scheduler.event.ExecutionInterceptor
import com.github.kagkarlsson.scheduler.task.TaskInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Counts task executions per "taskName/instanceId" across every scheduler that uses it. */
class ExecutionCounter : ExecutionInterceptor {
    val counts = ConcurrentHashMap<String, AtomicInteger>()

    override fun execute(taskInstance: TaskInstance<*>, executionContext: ExecutionContext, chain: ExecutionChain) =
        chain.proceed(taskInstance, executionContext).also {
            counts.computeIfAbsent("${taskInstance.taskName}/${taskInstance.id}") { AtomicInteger() }.incrementAndGet()
        }
}

@TestConfiguration(proxyBeanMethods = false)
class Containers {
    @Bean @ServiceConnection
    fun postgres() = PostgreSQLContainer("postgres:16-alpine")

    @Bean @ServiceConnection
    fun kafka() = KafkaContainer("apache/kafka:4.3.1")

    @Bean
    fun executionCounter() = ExecutionCounter()
}

// Same config in every subclass -> Spring caches one context, containers start once.
@SpringBootTest(properties = ["outbox.poll-interval-ms=200", "db-scheduler.polling-interval=200ms", "db-scheduler.heartbeat-interval=1s"])
@AutoConfigureMockMvc
@Import(Containers::class)
abstract class IntegrationTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var service: MarketService

    val admin: UUID = UUID.randomUUID()

    fun MockHttpServletRequestDsl.asAdmin(roles: String = "USER,ADMIN") {
        header("X-User-Id", admin.toString()); header("X-User-Roles", roles)
    }

    fun body(title: String = "Will it rain?", description: String = "d", freezeAt: Instant = Instant.now().plusSeconds(3600),
             endAt: Instant = freezeAt.plusSeconds(3600), minBet: Long = 1, maxBet: Long = 100) =
        """{"title":"$title","description":"$description","freezeAt":"$freezeAt","endAt":"$endAt","minBet":$minBet,"maxBet":$maxBet}"""

    fun create(json: String = body(), roles: String = "USER,ADMIN"): ResultActionsDsl =
        mvc.post("/api/markets") { asAdmin(roles); contentType = MediaType.APPLICATION_JSON; content = json }

    fun createId(json: String = body()): UUID =
        UUID.fromString(EventJson.mapper.readTree(create(json).andReturn().response.contentAsString).get("marketId").asString())

    fun patch(id: Any, json: String): ResultActionsDsl =
        mvc.patch("/api/markets/$id") { asAdmin(); contentType = MediaType.APPLICATION_JSON; content = json }

    fun freeze(id: Any): ResultActionsDsl = mvc.post("/api/markets/$id/freeze") { asAdmin() }

    fun resolve(id: Any, side: String): ResultActionsDsl =
        mvc.post("/api/markets/$id/resolve") { asAdmin(); contentType = MediaType.APPLICATION_JSON; content = """{"winningSide":"$side"}""" }

    fun cancel(id: Any): ResultActionsDsl = mvc.delete("/api/markets/$id") { asAdmin() }

    fun status(id: UUID): String = jdbc.sql("SELECT status FROM markets WHERE id = ?").param(id).query(String::class.java).single()

    fun setStatus(id: UUID, status: String) {
        jdbc.sql("UPDATE markets SET status = ? WHERE id = ?").params(status, id).update()
    }

    /** Outbox events of one market, oldest first: (eventType, payload). */
    fun events(id: UUID): List<Pair<String, MarketV1>> =
        jdbc.sql("SELECT event_type, payload::text FROM outbox_event WHERE aggregate_id = ? ORDER BY (payload->'payload'->>'version')::bigint, created_at")
            .param(id.toString())
            .query { rs, _ -> rs.getString(1) to EventJson.payload(EventJson.read(rs.getString(2)), MarketV1::class) }
            .list()

    fun taskTime(name: String, id: UUID): Instant? =
        jdbc.sql("SELECT execution_time FROM scheduled_tasks WHERE task_name = ? AND task_instance = ?")
            .params(name, id.toString()).query { rs, _ -> rs.getTimestamp(1).toInstant() }.optional().orElse(null)

    val timeout: Duration = Duration.ofSeconds(15)
}
