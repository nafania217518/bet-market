# Debezium outbox relay caveats

Checked 2026-10-13. Debezium 3.7.0.Final (2026-09-29), image `quay.io/debezium/connect:3.7.0.Final`.

## The publication publishes INSERT only
- A `FOR ALL TABLES` publication covers every table, also tables created later by Flyway.
- If a publication publishes UPDATE or DELETE, every table in it needs a replica identity. Otherwise
  Postgres rejects UPDATE and DELETE on that table.
- `dbz_outbox` is `WITH (publish = 'insert')`. No table needs a replica identity, and the outbox
  purge DELETEs never reach Kafka. The outbox router expects INSERT-only tables anyway.

Sources:
- https://www.postgresql.org/docs/16/sql-createpublication.html (`publish` parameter, replica identity rule)
- https://www.postgresql.org/docs/16/logical-replication-publication.html (replica identity for UPDATE/DELETE)
- https://debezium.io/documentation/reference/3.7/transformations/outbox-event-router.html (INSERT-only, DELETEs filtered)
- https://debezium.io/documentation/reference/3.7/connectors/postgresql.html (`publication.autocreate.mode`)

## Slots are created at database init, not by the connector
- `infra/postgres/init/02-debezium.sql` creates one slot per database when the volume is empty.
  The slot keeps every outbox INSERT from that moment, so no event is lost before Connect starts.
- The init script needs `wal_level=logical` already. The Postgres image passes the container command
  options to its temporary init server (tested in `DebeziumOutboxContractTest`).
- An existing data volume skips the init script. Run `docker compose down -v` once.
  The shared migration `V1_1__outbox_correlation_id.sql` also needs a new database: Flyway rejects
  it as out of order on a database that already has `V2`.
- `snapshot.mode=no_data`: the connector reads from the slot and never snapshots the table.

## A slot keeps WAL while nobody reads it
- A replication slot stops Postgres from removing WAL until the consumer confirms it. This is
  true also when the consumer is down. Disk use grows without a limit.
- All 5 databases share one server and one WAL. A quiet database does not confirm new positions,
  so its slot holds WAL from the busy databases. `heartbeat.action.query` writes a logical message
  (`pg_logical_emit_message`) in each database every 10 s, and the slot moves on (tested).
- Alerts `OutboxSlotInactive` and `OutboxSlotLagHigh` watch the slots.

Sources:
- https://www.postgresql.org/docs/16/logicaldecoding-explanation.html (slots keep WAL with no consumer)
- https://www.postgresql.org/docs/16/warm-standby.html (slots can keep so many WAL segments that the disk fills)
- https://debezium.io/documentation/reference/3.7/connectors/postgresql.html ("WAL disk space consumption", `heartbeat.action.query`)
- `DebeziumOutboxContractTest`: "the slot of a quiet database keeps up through the heartbeat"

## Delivery is at least once
- After a crash, Connect restarts from the last stored offset. Events after that offset come again.
- Every consumer already skips duplicates by `eventId` (`processed_event`, idempotent projection).

Source: https://debezium.io/documentation/reference/3.7/connectors/postgresql.html ("Kafka Connect process crashes")

## The Connect image
- It needs `BOOTSTRAP_SERVERS`, `GROUP_ID` and the 3 storage topic names. Its default worker
  config already uses replication factor 1.
- `HEAP_OPTS` sets the JVM heap. The default allows up to 2 GB; the stack sets 512 MB.
- The image turns every `CONNECT_*` variable into worker config. In Kubernetes, a Service named
  `connect` injects `CONNECT_PORT` and similar variables, so the pod sets `enableServiceLinks: false`.
  This is the same problem as the Kafka pods (see kubernetes.md).
- The broker has topic auto-create off. Connect creates its own topics and the heartbeat topics
  (`topic.creation.default.*`) through the admin API.

Sources:
- https://github.com/debezium/container-images/blob/main/connect/3.7/README.md (env vars, `HEAP_OPTS`, `CONNECT_*` mapping)
- https://kubernetes.io/docs/tutorials/services/connect-applications-service/ (Service environment variables)
- `DebeziumOutboxContractTest` (the image runs with only those env vars)
