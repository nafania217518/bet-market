package com.betting.common

import com.sun.net.httpserver.HttpServer
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest
@Import(Containers::class)
class RestClientCorrelationTest {
    @Autowired lateinit var builder: RestClient.Builder

    private val seen = AtomicReference<String?>()
    private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
        createContext("/") { ex ->
            seen.set(ex.requestHeaders.getFirst(CorrelationId.HEADER))
            ex.sendResponseHeaders(204, -1)
            ex.close()
        }
        start()
    }

    @AfterTest
    fun stop() {
        server.stop(0)
        MDC.remove(CorrelationId.MDC_KEY)
    }

    private fun call() = builder.build().get().uri("http://localhost:${server.address.port}/").retrieve().toBodilessEntity()

    @Test
    fun `RestClient from the auto-configured builder sends the current correlation id`() {
        MDC.put(CorrelationId.MDC_KEY, "cid-rest")
        call()
        assertEquals("cid-rest", seen.get())
    }

    @Test
    fun `RestClient from the auto-configured builder sends no correlation header when MDC has none`() {
        call()
        assertNull(seen.get())
    }

    @Test
    fun `RestClient keeps a correlation header the caller already set`() {
        MDC.put(CorrelationId.MDC_KEY, "cid-mdc")
        builder.build().get().uri("http://localhost:${server.address.port}/").header(CorrelationId.HEADER, "cid-caller").retrieve().toBodilessEntity()
        assertEquals("cid-caller", seen.get())
    }
}
