package com.betting.gateway

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class MetricsTest : GatewayTestBase() {
    @Test
    fun `actuator prometheus is reachable without a token and exposes the HTTP latency histogram`() {
        client.get().uri("/actuator/health").exchange()
        val body = client.get().uri("/actuator/prometheus").exchange()
            .expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!
        assertContains(body, "http_server_requests_seconds_bucket")
    }

    @Test
    fun `other actuator endpoints still need a token or are not exposed`() {
        val status = client.get().uri("/actuator/env").exchange().returnResult(String::class.java).status.value()
        assertTrue(status == 401 || status == 404, "status $status")
    }
}
