package com.betting.common

import org.assertj.core.api.Assertions.assertThat
import org.mockito.Mockito.mock
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.restclient.RestClientCustomizer
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource
import kotlin.test.Test

class CommonAutoConfigurationConditionsTest {
    private val config = AutoConfigurations.of(CommonAutoConfiguration::class.java)

    @Test
    fun `without a DataSource the outbox and idempotent consumer beans are not created and the context starts`() {
        ApplicationContextRunner().withConfiguration(config)
            .withBean(KafkaTemplate::class.java, { mock(KafkaTemplate::class.java) })
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).doesNotHaveBean(OutboxWriter::class.java)
                assertThat(ctx).doesNotHaveBean(OutboxPoller::class.java)
                assertThat(ctx).doesNotHaveBean(IdempotentConsumer::class.java)
            }
    }

    @Test
    fun `without Kafka on the classpath the record interceptor and the error handler are not created and the context starts`() {
        ApplicationContextRunner().withConfiguration(config)
            .withClassLoader(FilteredClassLoader(KafkaTemplate::class.java))
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).doesNotHaveBean(CorrelationIdRecordInterceptor::class.java)
                assertThat(ctx).doesNotHaveBean(CommonErrorHandler::class.java)
            }
    }

    @Test
    fun `in a non-web context the correlation filter and the exception handler are not created`() {
        ApplicationContextRunner().withConfiguration(config)
            .withClassLoader(FilteredClassLoader(KafkaTemplate::class.java))
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).doesNotHaveBean(CorrelationIdFilter::class.java)
                assertThat(ctx).doesNotHaveBean(GlobalExceptionHandler::class.java)
            }
    }

    private fun outboxContext(vararg props: String) = ApplicationContextRunner().withConfiguration(config)
        .withPropertyValues("spring.application.name=test", *props)
        .withBean(DataSource::class.java, { mock(DataSource::class.java) })
        .withBean(KafkaTemplate::class.java, { mock(KafkaTemplate::class.java) })
        .withBean(PlatformTransactionManager::class.java, { mock(PlatformTransactionManager::class.java) })

    @Test
    fun `with outbox relay debezium the poller bean is not created`() {
        outboxContext("outbox.relay=debezium").run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).doesNotHaveBean(OutboxPoller::class.java)
            assertThat(ctx).hasSingleBean(OutboxPurger::class.java)
        }
    }

    @Test
    fun `the poller stays the default relay`() {
        outboxContext().run { ctx -> assertThat(ctx).hasSingleBean(OutboxPoller::class.java) }
        outboxContext("outbox.relay=poller").run { ctx -> assertThat(ctx).hasSingleBean(OutboxPoller::class.java) }
    }

    @Test
    fun `with a DataSource and Kafka all common beans are created`() {
        WebApplicationContextRunner().withConfiguration(config)
            .withPropertyValues("spring.application.name=test")
            .withBean(DataSource::class.java, { mock(DataSource::class.java) })
            .withBean(KafkaTemplate::class.java, { mock(KafkaTemplate::class.java) })
            .withBean(PlatformTransactionManager::class.java, { mock(PlatformTransactionManager::class.java) })
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                listOf(
                    OutboxWriter::class.java, OutboxPoller::class.java, OutboxPurger::class.java, IdempotentConsumer::class.java,
                    CorrelationIdRecordInterceptor::class.java, CommonErrorHandler::class.java,
                    CorrelationIdFilter::class.java, GlobalExceptionHandler::class.java, RestClientCustomizer::class.java,
                ).forEach { assertThat(ctx).hasSingleBean(it) }
            }
    }
}
