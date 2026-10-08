// Outbox, idempotent consumer, correlation id, error model. Reused by every service.
apply(plugin = "java-library")
apply(plugin = "org.jetbrains.kotlin.plugin.jpa")

dependencies {
    "api"(project(":shared:events"))
    // Optional: consumers bring the starters they use; CommonAutoConfiguration turns on the matching beans.
    val optional = listOf("webmvc", "data-jpa", "kafka", "restclient").map { "org.springframework.boot:spring-boot-starter-$it" }
    optional.forEach { "compileOnly"(it); "testImplementation"(it) }
    "implementation"("tools.jackson.module:jackson-module-kotlin")

    "testImplementation"("org.springframework.boot:spring-boot-starter-webmvc-test")
    "testImplementation"("org.springframework.boot:spring-boot-starter-flyway")
    "testImplementation"("org.springframework.boot:spring-boot-testcontainers")
    "testImplementation"("org.testcontainers:testcontainers-junit-jupiter")
    "testImplementation"("org.testcontainers:testcontainers-postgresql")
    "testImplementation"("org.testcontainers:testcontainers-kafka")
    "testImplementation"("org.awaitility:awaitility-kotlin")
    "testRuntimeOnly"("org.flywaydb:flyway-database-postgresql")
    "testRuntimeOnly"("org.postgresql:postgresql")
}
