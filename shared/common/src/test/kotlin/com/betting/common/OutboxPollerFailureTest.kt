package com.betting.common

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.CompletableFuture
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

@SpringBootTest
@Import(Containers::class)
class OutboxPollerFailureTest {
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var writer: OutboxWriter

    @Test
    fun `outbox poller leaves rows unpublished when Kafka send fails`() {
        val jdbc = JdbcClient.create(dataSource)
        val id = tx.execute { writer.append("user.registered", "agg-fail", mapOf("a" to 1)) }
        @Suppress("UNCHECKED_CAST")
        val kafka = mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>
        `when`(kafka.send(any<ProducerRecord<String, String>>()))
            .thenReturn(CompletableFuture.failedFuture<SendResult<String, String>>(RuntimeException("down")))
        assertFails { OutboxPoller(jdbc, kafka, tx).publishBatch() }
        assertTrue(jdbc.sql("SELECT published_at IS NULL FROM outbox_event WHERE id = ?").param(id).query(Boolean::class.java).single())
    }
}
