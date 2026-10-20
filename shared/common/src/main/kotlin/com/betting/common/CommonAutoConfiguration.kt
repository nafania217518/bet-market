package com.betting.common

import org.apache.kafka.common.TopicPartition
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.convert.DurationStyle
import org.springframework.boot.restclient.RestClientCustomizer
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Import
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

// Each group loads only when its optional dependencies are present, so read-side services can reuse this module.
@AutoConfiguration(
    afterName = [
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
    ],
)
// Groups are imported, not annotated with @Configuration, so component scan in com.betting.* never picks them up early.
@Import(CommonAutoConfiguration.Outbox::class, CommonAutoConfiguration.Kafka::class, CommonAutoConfiguration.Web::class, CommonAutoConfiguration.RestClient::class)
class CommonAutoConfiguration {
    @ConditionalOnClass(name = ["org.springframework.jdbc.core.simple.JdbcClient", "org.springframework.kafka.core.KafkaTemplate"])
    @ConditionalOnBean(DataSource::class)
    @EnableScheduling
    class Outbox {
        @Bean
        fun outboxWriter(dataSource: DataSource, @Value("\${spring.application.name}") producer: String) =
            OutboxWriter(JdbcClient.create(dataSource), producer)

        @Bean
        @ConditionalOnProperty(name = ["outbox.relay"], havingValue = "poller", matchIfMissing = true)
        fun outboxPoller(dataSource: DataSource, kafka: KafkaTemplate<String, String>, txManager: PlatformTransactionManager) =
            OutboxPoller(JdbcClient.create(dataSource), kafka, TransactionTemplate(txManager))

        @Bean
        fun outboxPurger(
            dataSource: DataSource,
            @Value("\${outbox.relay:poller}") relay: String,
            @Value("\${outbox.retention:7d}") retention: String,
        ) = OutboxPurger(JdbcClient.create(dataSource), relay, DurationStyle.detectAndParse(retention))

        @Bean
        fun idempotentConsumer(dataSource: DataSource) = IdempotentConsumer(JdbcClient.create(dataSource))
    }

    @ConditionalOnClass(name = ["org.springframework.kafka.core.KafkaTemplate"])
    class Kafka {
        @Bean
        fun correlationIdRecordInterceptor() = CorrelationIdRecordInterceptor()

        // Every consumer: 3 retries with exponential backoff, then <topic>.DLT (partition -1 = Kafka picks).
        @Bean
        fun kafkaErrorHandler(
            kafka: KafkaTemplate<String, String>,
            @Value("\${kafka.retry.initial-interval-ms:1000}") initialMs: Long,
        ): CommonErrorHandler = DefaultErrorHandler(
            DeadLetterPublishingRecoverer(kafka) { record, _ -> TopicPartition(record.topic() + ".DLT", -1) },
            ExponentialBackOff(initialMs, 2.0).apply { maxAttempts = 3 }, // 3 retries after the first try
        )
    }

    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = ["org.springframework.web.servlet.DispatcherServlet"])
    class Web {
        @Bean
        fun correlationIdFilter() = CorrelationIdFilter()

        @Bean
        fun globalExceptionHandler() = GlobalExceptionHandler()
    }

    @ConditionalOnClass(name = ["org.springframework.boot.restclient.RestClientCustomizer"])
    class RestClient {
        @Bean
        fun correlationIdRestClientCustomizer() = RestClientCustomizer { builder ->
            builder.requestInterceptor { request, body, execution ->
                val id = CorrelationId.current()
                if (id != null && !request.headers.containsHeader(CorrelationId.HEADER)) request.headers.set(CorrelationId.HEADER, id)
                execution.execute(request, body)
            }
        }
    }
}
