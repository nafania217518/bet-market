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

        private val downstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = if (ex.requestURI.path == "/.well-known/jwks.json") {
                    JWKSet(testKey.toPublicJWK()).toString()
                } else {
                    fun h(n: String) = ex.requestHeaders.getFirst(n)?.let { "\"$it\"" } ?: "null"
                    """{"method":"${ex.requestMethod}","path":"${ex.requestURI.path}","userId":${h("X-User-Id")},""" +
                        """"roles":${h("X-User-Roles")},"correlationId":${h("X-Correlation-Id")}}"""
                }
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
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
            r.add("spring.data.redis.host") { redis.host }
            r.add("spring.data.redis.port") { redis.getMappedPort(6379) }
        }
    }
}
