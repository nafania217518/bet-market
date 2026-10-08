// Spring Cloud 2025.1.x supports Boot 4.1 from 2025.1.2 (https://spring.io/projects/spring-cloud).
apply(plugin = "org.springframework.boot")

dependencies {
    "implementation"(platform("org.springframework.cloud:spring-cloud-dependencies:2025.1.3"))
    "implementation"("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    "implementation"("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    "implementation"("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    "implementation"("org.springframework.boot:spring-boot-starter-actuator")
    "implementation"("org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j")

    "testImplementation"("org.springframework.boot:spring-boot-starter-webflux-test")
    "testImplementation"("org.springframework.boot:spring-boot-starter-security-test")
    "testImplementation"("org.springframework.boot:spring-boot-testcontainers")
    "testImplementation"("org.testcontainers:testcontainers-junit-jupiter")
    "testImplementation"("org.awaitility:awaitility-kotlin")
}
