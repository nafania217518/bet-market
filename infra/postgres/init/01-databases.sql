-- One Postgres container, one logical database per service (architecture.md §10, §13.5).
-- The app user owns each database. Since Postgres 15, GRANT ALL ON DATABASE does not allow
-- CREATE in the public schema; ownership does.
CREATE USER app WITH PASSWORD 'app';

CREATE DATABASE user_db OWNER app;
CREATE DATABASE wallet_db OWNER app;
CREATE DATABASE market_db OWNER app;
CREATE DATABASE bet_db OWNER app;
CREATE DATABASE settlement_db OWNER app;
