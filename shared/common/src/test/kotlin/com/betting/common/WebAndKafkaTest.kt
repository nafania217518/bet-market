package com.betting.common

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WebAndKafkaTest {
    data class Body(val name: String)

    @RestController
    class TestController {
        @PostMapping("/fail")
        fun fail(): String = throw ApiException(HttpStatus.CONFLICT, "ALREADY_EXISTS", "taken")

        @PostMapping("/body")
        fun body(@RequestBody body: Body) = body.name

        @PostMapping("/header")
        fun header(@RequestHeader("X-Need") need: String) = need
    }

    private val mvc = MockMvcBuilders.standaloneSetup(TestController())
        .setControllerAdvice(GlobalExceptionHandler()).build()

    @Test
    fun `correlation filter reuses the incoming header and echoes it in the response`() {
        val request = MockHttpServletRequest().apply { addHeader(CorrelationId.HEADER, "abc") }
        val response = MockHttpServletResponse()
        var seen: String? = null
        CorrelationIdFilter().doFilter(request, response) { _, _ -> seen = CorrelationId.current() }
        assertEquals("abc", seen)
        assertEquals("abc", response.getHeader(CorrelationId.HEADER))
    }

    @Test
    fun `correlation filter generates an id when the header is missing and clears MDC after the request`() {
        val response = MockHttpServletResponse()
        var seen: String? = null
        CorrelationIdFilter().doFilter(MockHttpServletRequest(), response) { _, _ -> seen = CorrelationId.current() }
        assertNotNull(seen)
        assertEquals(seen, response.getHeader(CorrelationId.HEADER))
        assertNull(CorrelationId.current())
    }

    @Test
    fun `kafka record interceptor puts the correlation header into MDC and clears it after the record`() {
        val record = ConsumerRecord<Any, Any>("t", 0, 0L, "k", "v").apply { headers().add(CorrelationId.HEADER, "k-corr".toByteArray()) }
        val interceptor = CorrelationIdRecordInterceptor()
        interceptor.intercept(record, org.mockito.Mockito.mock())
        assertEquals("k-corr", MDC.get(CorrelationId.MDC_KEY))
        interceptor.afterRecord(record, org.mockito.Mockito.mock())
        assertNull(MDC.get(CorrelationId.MDC_KEY))
    }

    @Test
    fun `exception handler maps ApiException to its status and error code`() {
        mvc.perform(post("/fail")).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("ALREADY_EXISTS"))
            .andExpect(jsonPath("$.message").value("taken"))
    }

    @Test
    fun `exception handler maps invalid request bodies to 400 VALIDATION_ERROR`() {
        mvc.perform(post("/body").contentType("application/json").content("{bad"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        mvc.perform(post("/header")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
    }
}
