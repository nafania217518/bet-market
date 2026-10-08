package com.betting.common

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.MDC
import org.springframework.kafka.listener.RecordInterceptor
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

object CorrelationId {
    const val HEADER = "X-Correlation-Id"
    const val MDC_KEY = "correlationId"

    fun current(): String? = MDC.get(MDC_KEY)
}

class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val id = request.getHeader(CorrelationId.HEADER)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        MDC.put(CorrelationId.MDC_KEY, id)
        response.setHeader(CorrelationId.HEADER, id)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove(CorrelationId.MDC_KEY)
        }
    }
}

// <Any, Any>: Boot's listener factory injects only a RecordInterceptor<Object, Object> bean.
class CorrelationIdRecordInterceptor : RecordInterceptor<Any, Any> {
    override fun intercept(record: ConsumerRecord<Any, Any>, consumer: Consumer<Any, Any>): ConsumerRecord<Any, Any> {
        record.headers().lastHeader(CorrelationId.HEADER)?.let { MDC.put(CorrelationId.MDC_KEY, String(it.value())) }
        return record
    }

    override fun afterRecord(record: ConsumerRecord<Any, Any>, consumer: Consumer<Any, Any>) {
        MDC.remove(CorrelationId.MDC_KEY)
    }
}
