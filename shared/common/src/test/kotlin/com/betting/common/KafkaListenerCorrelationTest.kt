package com.betting.common

import org.apache.kafka.clients.producer.ProducerRecord
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilNotNull
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

@SpringBootTest
@Import(Containers::class, KafkaListenerCorrelationTest.Listener::class)
class KafkaListenerCorrelationTest {
    @TestConfiguration(proxyBeanMethods = false)
    class Listener {
        val seen = AtomicReference<String?>()

        @KafkaListener(topics = ["correlation.test"], groupId = "correlation-test")
        fun on(@Suppress("UNUSED_PARAMETER") value: String) = seen.set(MDC.get(CorrelationId.MDC_KEY))
    }

    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var listener: Listener

    @Test
    fun `kafka listeners see the correlation header in MDC through the auto-configured container factory`() {
        val record = ProducerRecord<String, String>("correlation.test", "k", "v")
        record.headers().add(CorrelationId.HEADER, "cid-listener".toByteArray())
        kafka.send(record).get()

        assertEquals("cid-listener", await atMost Duration.ofSeconds(30) untilNotNull { listener.seen.get() })
    }
}
