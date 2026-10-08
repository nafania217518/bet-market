package com.betting.query

import org.hamcrest.Matchers.containsString
import org.springframework.test.web.servlet.get
import kotlin.test.Test

class MetricsTest : IntegrationTest() {
    @Test
    fun `actuator prometheus exposes JVM metrics and the HTTP latency histogram`() {
        mvc.get("/actuator/health")
        mvc.get("/actuator/prometheus").andExpect {
            status { isOk() }
            content { string(containsString("jvm_memory_used_bytes")) }
            content { string(containsString("http_server_requests_seconds_bucket")) }
        }
    }
}
