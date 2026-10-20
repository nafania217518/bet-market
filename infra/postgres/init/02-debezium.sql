-- Debezium outbox relay (architecture.md §13.5). Needs wal_level=logical (container command).
-- Insert-only publication: UPDATE/DELETE on tables without a replica identity do not fail,
-- and outbox purge DELETEs are never streamed.
-- The slot is created here, before any service writes, so Connect starts from the first event.
\c user_db
CREATE PUBLICATION dbz_outbox FOR ALL TABLES WITH (publish = 'insert');
SELECT pg_create_logical_replication_slot('outbox_user_db', 'pgoutput');
\c wallet_db
CREATE PUBLICATION dbz_outbox FOR ALL TABLES WITH (publish = 'insert');
SELECT pg_create_logical_replication_slot('outbox_wallet_db', 'pgoutput');
\c market_db
CREATE PUBLICATION dbz_outbox FOR ALL TABLES WITH (publish = 'insert');
SELECT pg_create_logical_replication_slot('outbox_market_db', 'pgoutput');
\c bet_db
CREATE PUBLICATION dbz_outbox FOR ALL TABLES WITH (publish = 'insert');
SELECT pg_create_logical_replication_slot('outbox_bet_db', 'pgoutput');
\c settlement_db
CREATE PUBLICATION dbz_outbox FOR ALL TABLES WITH (publish = 'insert');
SELECT pg_create_logical_replication_slot('outbox_settlement_db', 'pgoutput');
