package com.betting.user

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID

@TestConfiguration(proxyBeanMethods = false)
class Containers {
    @Bean @ServiceConnection
    fun postgres() = PostgreSQLContainer("postgres:16-alpine")

    @Bean @ServiceConnection
    fun kafka() = KafkaContainer("apache/kafka:4.3.1")
}

// Same config in every subclass -> Spring caches one context, containers start once.
@SpringBootTest(properties = ["outbox.poll-interval-ms=200"])
@AutoConfigureMockMvc
@AutoConfigureMetrics
@Import(Containers::class)
abstract class IntegrationTest {
    @Autowired lateinit var mvc: MockMvc

    fun uniqueEmail() = "u-${UUID.randomUUID()}@Example.com"

    fun postJson(path: String, body: String): ResultActionsDsl =
        mvc.post(path) { contentType = MediaType.APPLICATION_JSON; content = body }

    fun register(email: String, password: String = "password123", displayName: String = "Al"): ResultActionsDsl =
        postJson("/api/auth/register", """{"email":"$email","password":"$password","displayName":"$displayName"}""")

    fun login(email: String, password: String): ResultActionsDsl =
        postJson("/api/auth/login", """{"email":"$email","password":"$password"}""")
}
