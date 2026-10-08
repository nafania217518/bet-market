package com.betting.common

import org.apache.kafka.common.TopicPartition
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.restclient.RestClientCustomizer
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.backoff.ExponentialBackOff
import javax.sql.DataSource

@AutoConfiguration
@EnableScheduling
class CommonAutoConfiguration {
    @Bean
    fun outboxWriter(dataSource: DataSource, @Value("\${spring.application.name}") producer: String) =
        OutboxWriter(JdbcClient.create(dataSource), producer)

    @Bean
    fun outboxPoller(dataSource: DataSource, kafka: KafkaTemplate<String, String>, txManager: PlatformTransactionManager) =
        OutboxPoller(JdbcClient.create(dataSource), kafka, TransactionTemplate(txManager))

    @Bean
    fun idempotentConsumer(dataSource: DataSource) = IdempotentConsumer(JdbcClient.create(dataSource))

    @Bean
    fun correlationIdFilter() = CorrelationIdFilter()

    @Bean
    fun correlationIdRecordInterceptor() = CorrelationIdRecordInterceptor()

    @Bean
    fun globalExceptionHandler() = GlobalExceptionHandler()

    // Every consumer: 3 retries with exponential backoff, then <topic>.DLT (partition -1 = Kafka picks).
    @Bean
    fun kafkaErrorHandler(
        kafka: KafkaTemplate<String, String>,
        @Value("\${kafka.retry.initial-interval-ms:1000}") initialMs: Long,
    ): CommonErrorHandler = DefaultErrorHandler(
        DeadLetterPublishingRecoverer(kafka) { record, _ -> TopicPartition(record.topic() + ".DLT", -1) },
        ExponentialBackOff(initialMs, 2.0).apply { maxAttempts = 3 } // 3 retries after the first try,
    )

    @Bean
    fun correlationIdRestClientCustomizer() = RestClientCustomizer { builder ->
        builder.requestInterceptor { request, body, execution ->
            val id = CorrelationId.current()
            if (id != null && !request.headers.containsHeader(CorrelationId.HEADER)) request.headers.set(CorrelationId.HEADER, id)
            execution.execute(request, body)
        }
    }
}
