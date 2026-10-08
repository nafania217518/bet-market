// Notification Service: pushes live updates from Kafka to the browser over STOMP/WebSocket.
apply(plugin = "org.springframework.boot")

dependencies {
    "implementation"(project(":shared:common"))
    "implementation"(project(":shared:events"))
    "implementation"("org.springframework.boot:spring-boot-starter-websocket")
    "implementation"("org.springframework.boot:spring-boot-starter-actuator")
    "implementation"("org.springframework.boot:spring-boot-starter-kafka")
    // JWT decoding only. A spring-security starter would lock every endpoint.
    "implementation"("org.springframework.security:spring-security-oauth2-jose")
    "implementation"("tools.jackson.module:jackson-module-kotlin")

    "testImplementation"("org.springframework.boot:spring-boot-testcontainers")
    "testImplementation"("org.testcontainers:testcontainers-junit-jupiter")
    "testImplementation"("org.testcontainers:testcontainers-kafka")
    "testImplementation"("org.awaitility:awaitility-kotlin")
}
