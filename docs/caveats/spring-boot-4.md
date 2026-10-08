# Spring Boot 4, not 3.x

Checked 2026-10-08.

## Facts
- The Spring Boot 3.x line has no OSS support. Spring Boot 3.5 OSS support ended on 2026-06-30.
- The current release is Spring Boot 4.1.1. Spring Boot 4.0 OSS support ends on 2026-12-31.
- Spring Boot 4.1.1 manages: Kotlin 2.3.21, Spring Framework 7.0.9, Jackson 3.1.5,
  Spring Kafka 4.1.1, Kafka clients 4.2.1, Flyway 12.4.0, Testcontainers 2.0.5, JUnit 6.0.3.

## Impact on this project
- `architecture.md` said "Spring Boot 3.x, Kotlin 1.9+". We use Spring Boot 4.1.1 and Kotlin 2.3.21.
- Jackson 3 uses the package `tools.jackson.*`. Annotations stay in `com.fasterxml.jackson.annotation`.
- Starters are modular: `spring-boot-starter-webmvc`, `spring-boot-starter-kafka`,
  `spring-boot-starter-flyway`. Test slices have their own starters (`spring-boot-starter-webmvc-test`).
- Testcontainers 2 renames artifacts (`testcontainers-postgresql`) and packages
  (`org.testcontainers.postgresql.PostgreSQLContainer`).

## Sources
1. Maven Central `spring-boot` metadata (release list up to 4.2.0-M2):
   https://repo1.maven.org/maven2/org/springframework/boot/spring-boot/maven-metadata.xml
2. `spring-boot-dependencies` 4.1.1 POM (managed versions):
   https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom
3. eosl.date, Spring Boot EOL: https://eosl.date/eol/product/spring-boot/
4. Dan Vega, "Spring Boot End of Life: Every 3.x Branch Is Now Unsupported" (2026-07-14):
   https://www.danvega.dev/blog/spring-boot-end-of-life
5. endoflife.ai, Spring Boot EOL dates: https://endoflife.ai/spring-boot

# Spring Cloud for Boot 4.1

Checked 2026-10-08.

- Spring Cloud 2026.0 (the Boot 4.1 line) is only at 2026.0.0-M1. We do not use milestones.
- Spring Cloud 2025.1.x (Oakwood) supports Boot 4.0.x and Boot 4.1.x from 2025.1.2. We use 2025.1.3
  (Spring Cloud Gateway 5.0.3).
- Gateway 5 uses the artifact `spring-cloud-starter-gateway-server-webflux`.

Sources:
1. https://spring.io/projects/spring-cloud (release train / Boot compatibility table)
2. https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions
3. Maven Central `spring-cloud-dependencies` metadata (latest GA 2025.1.3, 2026.0.0-M1):
   https://repo1.maven.org/maven2/org/springframework/cloud/spring-cloud-dependencies/maven-metadata.xml
4. `spring-cloud-dependencies` 2025.1.3 POM (`spring-cloud-gateway.version` 5.0.3)

## RestClient.Builder needs its own starter (Boot 4.1)
Checked 2026-10-08.
- `RestClient.Builder` auto-configuration lives in the module `spring-boot-restclient`
  (package `org.springframework.boot.restclient.autoconfigure`).
- From Boot 4.1, `spring-boot-starter-webmvc` alone does not give a `RestClient.Builder` bean.
  Add `spring-boot-starter-restclient`. `shared/common` exposes it as an `api` dependency.
- Test starters (for example `spring-boot-starter-webmvc-test`) can supply a builder in tests only.
  Tests then pass, but the app fails at startup. The Docker smoke run catches this.
- `RestClientCustomizer` moved to `org.springframework.boot.restclient`.

Sources:
- https://docs.spring.io/spring-boot/api/java/org/springframework/boot/restclient/autoconfigure/RestClientAutoConfiguration.html
- https://github.com/spring-projects/spring-boot/issues/50768 (no builder after 4.0.6 -> 4.1.0 with the web starter; closed as invalid)
- https://github.com/spring-projects/spring-boot/issues/48253 (add spring-boot-starter-restclient; test starters hide the gap)
- https://www.rickenbazolo.com/en/blog/spring-boot-4-restclient (module relocation)

## MongoDB properties moved to `spring.mongodb.*` (Boot 4)
Checked 2026-10-08.
- Boot 4 uses `spring.mongodb.uri` (and `spring.mongodb.database`, ...). The Boot 3 prefix
  `spring.data.mongodb.*` is ignored without an error: the app connects to localhost defaults.
- The auto-configuration moved to `org.springframework.boot.mongodb.autoconfigure`.
- Actuator: `management.health.mongo.*` became `management.health.mongodb.*`.
- Redis keeps `spring.data.redis.*`.
- The read model stores UUIDs as strings, so the changed default UUID representation does not apply.

Sources:
- https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide (properties now begin with `spring.mongodb`)
- https://docs.spring.io/spring-boot/reference/data/nosql.html (`spring.mongodb.uri`)
- https://noregressions.dev/guides/migration/cards/mongodb-property-renames.html (old prefix silently ignored)
- https://stackoverflow.com/questions/23515295/spring-boot-and-how-to-configure-connection-details-to-mongodb (`spring.mongodb.uri`)
- Boot 4.1.1 jar metadata (checked by the projection and query agents)

## Metrics in tests: `@AutoConfigureMetrics` (Boot 4)
Checked 2026-10-09.
- Boot 4.0 removed `@AutoConfigureObservability`. The replacement for metrics is
  `@AutoConfigureMetrics` in the module `spring-boot-micrometer-metrics-test`
  (package `org.springframework.boot.micrometer.metrics.test.autoconfigure`). Tracing has its own
  `spring-boot-micrometer-tracing-test` module.
- `spring-boot-starter-test` does not include it. The root build adds it to every service.
- Without the annotation, a `@SpringBootTest` disables metrics export, so `/actuator/prometheus`
  is missing in tests. With it, export is on by default (`export = true`).
- One migration guide says the annotation has "no replacement". The 4.1.1 Javadoc and jar show
  that `@AutoConfigureMetrics` exists and works (all `MetricsTest` classes use it).

Sources:
- https://docs.spring.io/spring-boot/api/java/org/springframework/boot/micrometer/metrics/test/autoconfigure/AutoConfigureMetrics.html (since 4.0.0, `export` default true)
- Maven Central `spring-boot-micrometer-metrics-test-4.1.1.jar` (contains `AutoConfigureMetrics` and `DisableMetricsExportContextCustomizer`)
- https://stackoverflow.com/questions/79867953 (dependency for `@AutoConfigureMetrics` after the 4.0 upgrade)
- https://noregressions.dev/guides/migration/cards/auto-configure-observability-removed.html (old annotation removed)
- https://docs.spring.io/spring-boot/3.4/api/java/org/springframework/boot/test/autoconfigure/actuate/observability/AutoConfigureObservability.html (the Boot 3 annotation)

## Structured JSON logs are built in (Boot 3.4+)
Checked 2026-10-09.
- `logging.structured.format.console=logstash` writes Logstash JSON. No `logstash-logback-encoder`
  dependency is needed. MDC entries (our `correlationId`) appear as JSON fields.
- docker-compose sets it with `LOGGING_STRUCTURED_FORMAT_CONSOLE`. Tests and local runs keep plain logs.

Sources:
- https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.structured (4.1.1)
- https://www.baeldung.com/spring-boot-structured-logging
- https://springframework.guru/how-to-structured-logging-with-spring-boot/
- https://howtodoinjava.com/spring-boot/structured-logging-example/

## db-scheduler metrics and health on Boot 4 (starter 16.12.0)
Checked 2026-10-09.
- With Micrometer present, the starter registers `MicrometerStatsRegistry`. It exports
  `dbscheduler_task_completions_total{task,result="ok|failed"}`, `dbscheduler_task_duration_seconds{task}`,
  `dbscheduler_task_last_run_duration` and `dbscheduler_task_last_run_timestamp_seconds`.
- The health component is named `dbScheduler`, not `db-scheduler`.

Sources:
- String constants in `MicrometerStatsRegistry.class` and `DbSchedulerActuatorAutoConfiguration.class` (16.12.0 jars)
- https://deepwiki.com/kagkarlsson/db-scheduler/7.2-monitoring-and-metrics
- market-service tests `actuator prometheus exposes ... db-scheduler task completions` and `health includes db-scheduler`
