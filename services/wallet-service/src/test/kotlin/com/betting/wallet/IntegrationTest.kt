package com.betting.wallet

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer

@TestConfiguration(proxyBeanMethods = false)
class Containers {
    @Bean @ServiceConnection
    fun postgres() = PostgreSQLContainer("postgres:16-alpine")

    @Bean @ServiceConnection
    fun kafka() = KafkaContainer("apache/kafka:4.3.1")
}

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
@Import(Containers::class)
abstract class IntegrationTest
