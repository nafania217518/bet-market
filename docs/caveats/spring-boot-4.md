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
