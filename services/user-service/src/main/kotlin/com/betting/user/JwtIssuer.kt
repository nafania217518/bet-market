package com.betting.user

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class JwtIssuer {
    // ponytail: key generated at startup; tokens die on restart and replicas disagree. Load the key from a Secret before running >1 replica (M9).
    private val key: RSAKey = RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).keyUse(KeyUse.SIGNATURE).generate()
    private val encoder = NimbusJwtEncoder(ImmutableJWKSet<SecurityContext>(JWKSet(key)))

    fun issue(userId: UUID, roles: List<String>): String {
        val now = Instant.now()
        val claims = JwtClaimsSet.builder()
            .issuer("bet-market")
            .subject(userId.toString())
            .claim("roles", roles)
            .issuedAt(now)
            .expiresAt(now.plus(TTL))
            .build()
        val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.keyID).build()
        return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }

    fun publicJwks(): Map<String, Any> = JWKSet(key.toPublicJWK()).toJSONObject()

    companion object {
        val TTL: Duration = Duration.ofMinutes(15)
    }
}
