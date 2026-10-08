package com.betting.common

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootApplication
class TestApplication

@TestConfiguration(proxyBeanMethods = false)
class Containers {
    @Bean
    @ServiceConnection
    fun postgres() = PostgreSQLContainer("postgres:16-alpine")

    @Bean
    @ServiceConnection
    fun kafka() = KafkaContainer("apache/kafka:4.3.1")
}
