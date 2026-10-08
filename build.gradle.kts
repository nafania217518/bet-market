import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.spring") version "2.3.21" apply false
    kotlin("plugin.jpa") version "2.3.21" apply false
    id("org.springframework.boot") version "4.1.1" apply false
}

subprojects {
    group = "com.betting"

    repositories { mavenCentral() }

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.spring")
    apply(plugin = "jacoco")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        }
    }

    dependencies {
        "implementation"(platform(SpringBootPlugin.BOM_COORDINATES))
        "testImplementation"(platform(SpringBootPlugin.BOM_COORDINATES))
        "implementation"("org.jetbrains.kotlin:kotlin-reflect")
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testImplementation"("org.jetbrains.kotlin:kotlin-test-junit5")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        finalizedBy("jacocoTestReport")
    }

    // Rule 5: coverage >= 90% per module. Application entry points are excluded.
    val coverageExcludes = listOf("**/*Application*", "**/*ApplicationKt*")
    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn("test")
        classDirectories.setFrom(files(classDirectories.files.map { fileTree(it) { exclude(coverageExcludes) } }))
    }
    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        dependsOn("test")
        classDirectories.setFrom(files(classDirectories.files.map { fileTree(it) { exclude(coverageExcludes) } }))
        violationRules { rule { limit { minimum = "0.90".toBigDecimal() } } }
    }
    tasks.named("check") { dependsOn("jacocoTestCoverageVerification") }
}

// Write-side services: runnable Spring Boot apps with JPA, Flyway, Kafka, Testcontainers.
// The gateway (WebFlux) and the read side (MongoDB/Redis, no Postgres) have their own builds.
val ownBuild = setOf("api-gateway", "projection-service", "query-service")
configure(subprojects.filter { it.path.startsWith(":services:") && it.name !in ownBuild }) {
    apply(plugin = "org.springframework.boot")
    apply(plugin = "org.jetbrains.kotlin.plugin.jpa")

    dependencies {
        "implementation"(project(":shared:common"))
        "implementation"(project(":shared:events"))
        "implementation"("org.springframework.boot:spring-boot-starter-webmvc")
        "implementation"("org.springframework.boot:spring-boot-starter-actuator")
        "implementation"("org.springframework.boot:spring-boot-starter-data-jpa")
        "implementation"("org.springframework.boot:spring-boot-starter-flyway")
        "implementation"("org.springframework.boot:spring-boot-starter-kafka")
        "implementation"("tools.jackson.module:jackson-module-kotlin")
        "runtimeOnly"("org.flywaydb:flyway-database-postgresql")
        "runtimeOnly"("org.postgresql:postgresql")

        "testImplementation"("org.springframework.boot:spring-boot-starter-webmvc-test")
        "testImplementation"("org.springframework.boot:spring-boot-testcontainers")
        "testImplementation"("org.testcontainers:testcontainers-junit-jupiter")
        "testImplementation"("org.testcontainers:testcontainers-postgresql")
        "testImplementation"("org.testcontainers:testcontainers-kafka")
        "testImplementation"("org.awaitility:awaitility-kotlin")
    }
}
