package com.betting.gateway

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.containers.GenericContainer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
abstract class GatewayTestBase {
    @Autowired
    lateinit var client: WebTestClient

    fun token(
        sub: String,
        roles: List<String>,
        issuer: String = "bet-market",
        expiresAt: Instant = Instant.now().plusSeconds(900),
        key: RSAKey = testKey,
    ): String {
        val claims = JWTClaimsSet.Builder().subject(sub).issuer(issuer).claim("roles", roles)
            .issueTime(Date()).expirationTime(Date.from(expiresAt)).build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims)
        jwt.sign(RSASSASigner(key))
        return jwt.serialize()
    }

    companion object {
        val testKey: RSAKey = RSAKeyGenerator(2048).keyID("test").generate()
        val otherKey: RSAKey = RSAKeyGenerator(2048).keyID("test").generate()

        private val redis = GenericContainer("redis:7-alpine").withExposedPorts(6379).also { it.start() }

        /** Calls per X-Fake-Key header value. */
        val calls = ConcurrentHashMap<String, AtomicInteger>()

        private val downstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/") { ex ->
                // Test behaviour: X-Fake-Sleep (ms), X-Fake-Fail (answer 503 for the first N calls of X-Fake-Key).
                val n = ex.requestHeaders.getFirst("X-Fake-Key")?.let { calls.computeIfAbsent(it) { AtomicInteger() }.incrementAndGet() } ?: 0
                ex.requestHeaders.getFirst("X-Fake-Sleep")?.let { Thread.sleep(it.toLong()) }
                val fail = ex.requestHeaders.getFirst("X-Fake-Fail")?.toInt() ?: 0
                val body = if (ex.requestURI.path == "/.well-known/jwks.json") {
                    JWKSet(testKey.toPublicJWK()).toString()
                } else {
                    fun h(name: String) = ex.requestHeaders.getFirst(name)?.let { "\"$it\"" } ?: "null"
                    """{"method":"${ex.requestMethod}","path":"${ex.requestURI.path}","userId":${h("X-User-Id")},""" +
                        """"roles":${h("X-User-Roles")},"correlationId":${h("X-Correlation-Id")},""" +
                        """"idempotencyKey":${h("Idempotency-Key")}}"""
                }
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(if (n in 1..fail) 503 else 200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            val url = "http://127.0.0.1:${downstream.address.port}"
            r.add("USER_SERVICE_URL") { url }
            r.add("WALLET_SERVICE_URL") { url }
            r.add("MARKET_SERVICE_URL") { url }
            r.add("BET_SERVICE_URL") { url }
            r.add("spring.data.redis.host") { redis.host }
            r.add("spring.data.redis.port") { redis.getMappedPort(6379) }
        }
    }
}
