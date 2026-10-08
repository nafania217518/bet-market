package com.betting.query

import org.bson.Document
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.testcontainers.containers.GenericContainer
import org.testcontainers.mongodb.MongoDBContainer
import java.time.Instant
import java.util.Date
import java.util.UUID

@TestConfiguration(proxyBeanMethods = false)
class Containers {
    @Bean @ServiceConnection
    fun mongoContainer() = MongoDBContainer("mongo:7")

    @Bean @ServiceConnection(name = "redis")
    fun redisContainer() = GenericContainer("redis:7-alpine").withExposedPorts(6379)
}

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
@Import(Containers::class)
abstract class IntegrationTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mongo: MongoTemplate
    @Autowired lateinit var redis: StringRedisTemplate

    @BeforeEach
    fun clean() {
        listOf("market_view", "bet_view", "user_view").forEach { mongo.dropCollection(it) }
        redis.connectionFactory!!.connection.use { it.serverCommands().flushAll() }
    }

    fun get(url: String, userId: String? = null): ResultActionsDsl = mvc.get(url) { userId?.let { header("X-User-Id", it) } }

    fun ResultActionsDsl.expectError(status: Int, code: String) = andExpect {
        status { isEqualTo(status) }
        jsonPath("$.code") { value(code) }
    }

    fun market(id: UUID = UUID.randomUUID(), title: String? = "Will it rain?", status: String = "OPEN",
               endAt: Instant = Instant.parse("2030-01-01T00:00:00Z"), truePool: Long? = null, falsePool: Long? = null): UUID {
        val doc = Document("_id", id.toString())
        if (title != null) doc.append("title", title).append("description", "desc").append("status", status)
            .append("winningSide", null).append("freezeAt", Date.from(endAt.minusSeconds(60))).append("endAt", Date.from(endAt))
            .append("minBet", 1L).append("maxBet", 100L).append("version", 1L)
        if (truePool != null || falsePool != null) {
            val t = truePool ?: 0; val f = falsePool ?: 0
            doc.append("truePool", t).append("falsePool", f).append("totalPool", t + f).append("betCount", 3L).append("uniqueBettors", 2L)
        }
        mongo.insert(doc, "market_view")
        return id
    }

    fun bet(userId: UUID, status: String = "ACCEPTED", placedAt: Instant = Instant.now(), amount: Long = 10): UUID {
        val id = UUID.randomUUID()
        mongo.insert(Document(mapOf("_id" to id.toString(), "marketId" to UUID.randomUUID().toString(), "userId" to userId.toString(),
            "side" to "TRUE", "amount" to amount, "status" to status, "placedAt" to Date.from(placedAt), "updatedAt" to Date.from(placedAt))), "bet_view")
        return id
    }

    fun user(id: UUID, name: String) = mongo.insert(Document(mapOf("_id" to id.toString(), "displayName" to name)), "user_view")
}
