package com.betting.common

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
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
}
