// Projection Service: builds the MongoDB/Redis read model from Kafka events. No Postgres.
apply(plugin = "org.springframework.boot")

dependencies {
    "implementation"(project(":shared:common"))
    "implementation"(project(":shared:events"))
    "implementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "implementation"("org.springframework.boot:spring-boot-starter-actuator")
    "implementation"("org.springframework.boot:spring-boot-starter-kafka")
    "implementation"("org.springframework.boot:spring-boot-starter-data-mongodb")
    "implementation"("org.springframework.boot:spring-boot-starter-data-redis")
    "implementation"("tools.jackson.module:jackson-module-kotlin")

    "testImplementation"("org.springframework.boot:spring-boot-starter-webmvc-test")
    "testImplementation"("org.springframework.boot:spring-boot-testcontainers")
    "testImplementation"("org.testcontainers:testcontainers-junit-jupiter")
    "testImplementation"("org.testcontainers:testcontainers-kafka")
    "testImplementation"("org.testcontainers:testcontainers-mongodb")
    "testImplementation"("org.awaitility:awaitility-kotlin")
}
