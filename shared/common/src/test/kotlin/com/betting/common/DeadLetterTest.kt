package com.betting.common

import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(properties = ["kafka.retry.initial-interval-ms=50"])
@Import(Containers::class, DeadLetterTest.Listeners::class)
class DeadLetterTest {
    @TestConfiguration(proxyBeanMethods = false)
    class Listeners {
        val alwaysFails = AtomicInteger()
        val failsOnce = AtomicInteger()

        @KafkaListener(topics = ["dlt.always"], groupId = "dlt-always")
        fun always(@Suppress("UNUSED_PARAMETER") value: String) {
            alwaysFails.incrementAndGet()
            error("boom")
        }

        @KafkaListener(topics = ["dlt.once"], groupId = "dlt-once")
        fun once(@Suppress("UNUSED_PARAMETER") value: String) {
            if (failsOnce.incrementAndGet() == 1) error("boom once")
        }
    }

    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var listeners: Listeners
    @Autowired lateinit var consumers: ConsumerFactory<String, String>

    private fun readAll(topic: String, wait: Duration) = consumers.createConsumer("reader-$topic-${System.nanoTime()}", null).use { c ->
        c.subscribe(listOf(topic))
        val deadline = System.nanoTime() + wait.toNanos()
        val out = mutableListOf<Pair<String?, String?>>()
        while (System.nanoTime() < deadline && out.isEmpty()) c.poll(Duration.ofMillis(500)).forEach { out += it.key() to it.value() }
        out
    }

    @Test
    fun `a listener that always fails is retried 3 times and the record lands on the topic DLT with its key and value`() {
        kafka.send("dlt.always", "k1", "v1").get()

        assertEquals(listOf<Pair<String?, String?>>("k1" to "v1"), readAll("dlt.always.DLT", Duration.ofSeconds(30)))
        assertEquals(4, listeners.alwaysFails.get())
    }

    @Test
    fun `a listener that fails once and then succeeds does not publish to the DLT`() {
        kafka.send("dlt.once", "k2", "v2").get()

        await atMost Duration.ofSeconds(30) untilAsserted { assertEquals(2, listeners.failsOnce.get()) }
        assertTrue(readAll("dlt.once.DLT", Duration.ofSeconds(3)).isEmpty())
    }
}
