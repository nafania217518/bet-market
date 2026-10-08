# db-scheduler on Spring Boot 4

Checked 2026-10-08.

## Facts
- Spring Boot 4 needs the artifact `com.github.kagkarlsson:db-scheduler-spring-boot-4-starter`.
  The old `db-scheduler-spring-boot-starter` is for Boot 3 only.
- The Boot 4 starter first shipped in 16.7.0 (2026-01-06). The latest version is 16.12.0 (2026-05-29).
- The Boot 4 starter uses Jackson 3 (`tools.jackson`).
- The starter wraps the `DataSource` in `TransactionAwareDataSourceProxy`.
  So `scheduler.schedule(...)` inside a `@Transactional` method joins that transaction.
  Without the starter, `SchedulerClient` calls use a new connection and do not join the transaction.
- The default task-data serializer is Java serialization. Market Service avoids task data:
  the task instance id is the market id.
- PostgreSQL supports the `lock-and-fetch` polling strategy (`SELECT ... FOR UPDATE SKIP LOCKED`).
- All replicas must use the same heartbeat settings. Otherwise a replica can mark a live execution as dead.
- Default one-time task failure handling: retry after 5 minutes. Default dead-execution handling: reschedule to now.

## Sources
- https://github.com/kagkarlsson/db-scheduler/releases (v16.7.0 "spring boot 4 starter", v16.12.0 latest)
- https://repo.maven.apache.org/maven2/com/github/kagkarlsson/db-scheduler-spring-boot-4-starter/ (versions 16.7.0 to 16.12.0)
- https://github.com/kagkarlsson/db-scheduler/blob/master/README.md (Boot 4 artifact; transaction note; properties)
- https://www.baeldung.com/java-db-scheduler (Boot 4 vs Boot 3 starter; heartbeat settings must match)
- https://deepwiki.com/kagkarlsson/db-scheduler/4-spring-boot-integration (TransactionAwareDataSourceProxy wrapping)
