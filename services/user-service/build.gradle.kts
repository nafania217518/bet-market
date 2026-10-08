dependencies {
    // No security starter: the gateway authenticates (M2). We need only BCrypt and Nimbus JOSE
    // (RS256 signing via NimbusJwtEncoder + JWKS).
    "implementation"("org.springframework.security:spring-security-crypto")
    "implementation"("org.springframework.security:spring-security-oauth2-jose")
}
