# architecture.md

> **Project:** Token-based online betting platform (study / pet project)
> **Goal:** `docker-compose up` produces a fully working local system.
> **Additional goal:** `kubernetes_manifest.yaml` contains valid kubernetes setup
> **Style:** Microservices + CQRS + event-driven core, with full observability.

---

## Table of contents

1. [Overview](#1-overview)
2. [Goals and non-goals](#2-goals-and-non-goals)
3. [Tech stack](#3-tech-stack)
4. [High-level architecture](#4-high-level-architecture)
5. [CQRS split](#5-cqrs-split)
6. [Request and event flows](#6-request-and-event-flows)
7. [Domain model](#7-domain-model)
8. [Service catalog](#8-service-catalog)
9. [Kafka topic design](#9-kafka-topic-design)
10. [Data stores](#10-data-stores)
11. [Consistency patterns](#11-consistency-patterns)
12. [Observability](#12-observability)
13. [Local deployment topology](#13-local-deployment-topology)
14. [Repository layout](#14-repository-layout)
15. [Design notes and trade-offs](#15-design-notes-and-trade-offs)

---

## 1. Overview

The platform lets users bet virtual **tokens** on boolean **markets** (a claim that resolves to
`TRUE` or `FALSE`).

Core lifecycle of a market:

```text
  OPEN ──(freezeAt, scheduled)──> FROZEN ──(endAt, scheduled)──> ENDED ──(admin: winningSide)──> RESOLVED
    │                                │                              │                                │
    │ bets accepted                  │ no new bets                  │ awaiting outcome               │ market.resolved
    │ bets cancellable               │                              │                                │ -> settlement pays out
    └────────────────────────────────┴──────────────────────────────┘
                     │ admin cancel (any non-terminal state)
                     v
                 CANCELLED  (all stakes refunded)
```

`ENDED` only means "the event happened"; nobody knows the outcome yet. An admin supplies
`winningSide` via `POST /api/markets/{id}/resolve`, which moves the market to `RESOLVED` and
triggers settlement. `RESOLVED` and `CANCELLED` are terminal.

Each user starts with a fixed token balance. Tokens are never real money — this keeps the
project legally trivial and lets you focus on distributed-systems concerns.

> **Naming note.** The domain concept is called a **market**, never a "topic". The word "topic"
> is reserved exclusively for **Kafka topics** and the STOMP destination prefix `/topic/...`.
> This eliminates the ambiguity that plagued earlier drafts of this document.

---

## 2. Goals and non-goals

### Goals

- Demonstrate a realistic **microservice** decomposition with clear bounded contexts.
- Demonstrate **CQRS**: separate write model (PostgreSQL, normalized, transactional) from
  read model (MongoDB + Redis, denormalized, fast).
- Demonstrate **event-driven integration** via Kafka with a transactional outbox.
- Demonstrate **saga / compensation** for the bet-placement flow.
- Demonstrate **cluster-safe task scheduling** using `db-scheduler`, which guarantees
  single execution per task instance across all application replicas.
- Demonstrate **observability**: metrics, dashboards, health checks.
- One command to boot the whole thing locally.
- Valid kubernetes manifest for cluster.

### Non-goals

- Real money, KYC, payments, regulatory compliance.
- Multi-region, sharding, or production-grade HA.
- Machine-learning odds. Odds are pari-mutuel (derived from pool sizes).

---

## 3. Tech stack

| Layer | Technology | Notes |
|---|---|---|
| Frontend | TypeScript, React 19, Vite 8 | SPA, REST + STOMP over WebSocket |
| Frontend state | TanStack Query + Zustand | Server cache + small UI store |
| Frontend styling | Tailwind CSS | Optional, keeps focus on architecture |
| Gateway | Spring Cloud Gateway (WebFlux) | Routing, JWT, rate limiting |
| Backend language | Kotlin 2.3 (JVM 21) | Coroutines where useful |
| Backend framework | Spring Boot 4.1 | Web, Data JPA, Data Redis, Data MongoDB, Kafka, Actuator. 3.x is end-of-life (see `docs/caveats/spring-boot-4.md`) |
| Inter-service sync | Spring `RestClient` / `WebClient` | Only for reservation calls |
| Inter-service async | Spring for Apache Kafka | Manual ack + idempotent consumers |
| SQL store | PostgreSQL 16 | One instance, one database per service |
| NoSQL store | MongoDB 7 | Read models, event audit log |
| Cache | Redis 7 | Cache, rate limit, leaderboard ZSET, WebSocket session registry |
| **Cluster scheduler** | **`db-scheduler` (kagkarlsson) + `db-scheduler-spring-boot-4-starter`** | **Persistent `scheduled_tasks` table; guarantees single execution per task instance across all replicas. Boot 4 needs the `-4-` starter (see `docs/caveats/db-scheduler.md`)** |
| Event schema | JSON envelope + versioned Kotlin data classes in `shared/events` | No Schema Registry (stretch goal) |
| Migrations | Flyway | One migration set per service |
| Metrics | Micrometer + Spring Boot Actuator | `/actuator/prometheus` |
| Dashboards | Prometheus + Grafana | Plus kafka/redis/postgres exporters |
| Build | Gradle (Kotlin DSL), multi-module | One repo, many modules |
| Containerization | Docker + Docker Compose | One Dockerfile per service |

---

## 4. High-level architecture

```text
+--------------------------------------------------------------------------+
|                              CLIENT LAYER                                |
|                                                                          |
|      React + Vite + TypeScript SPA  (browser)                            |
|        - REST/JSON  : commands + queries                                 |
|        - WebSocket/STOMP : live market + wallet updates                  |
+------------------------------------+-------------------------------------+
                                     |
                                     | HTTP :8080
                                     v
+--------------------------------------------------------------------------+
|                        EDGE / API GATEWAY  :8080                         |
|                                                                          |
|  Spring Cloud Gateway (WebFlux)                                          |
|    - path + method routing        - JWT validation                       |
|    - CORS                         - Redis rate limiting                  |
|    - correlation-id propagation   - no business logic                    |
+--+--------------+--------------+--------------+--------------+----------+
   |              |              |              |              |
   | /api/auth    | /api/wallets | /api/markets | /api/bets    | /api/** GET
   | /api/users   |              | (write verbs)|              | /ws
   v              v              v              v              v
+---------+  +---------+  +--------------+  +-----------+  +-------------+
|  USER   |  | WALLET  |  |   MARKET     |  |    BET    |  |   QUERY     |
| SERVICE |  | SERVICE |  |   SERVICE    |  |  SERVICE  |  |  SERVICE    |
|  :8081  |  |  :8082  |  |    :8083     |  |   :8084   |  |   :8087     |
+----+----+  +----+----+  +------+-------+  +-----+-----+  +------+------+
     |            |              |                |               |
     |            |              |                |               |
     |  Postgres  |  Postgres    |  Postgres      |  Postgres     |
     v            v              v                v               |
+---------+  +---------+  +--------------+  +-----------+        |
| user-db |  |wallet-db|  |  market-db   |  |  bet-db   |        |
+---------+  +---------+  +--------------+  +-----------+        |
                                                                  |
     +------------------------------------------------------------+
     |            (read models only)
     v
+----------------------+        +----------------------+
|  MongoDB  read-db    |        |  Redis  read-cache   |
|  market_view         |        |  hot markets         |
|  user_bet_view       |        |  leaderboard ZSET    |
|  leaderboard_snap    |        |  rate limits         |
+----------+-----------+        +----------+-----------+
           ^                               ^
           |                               |
           |        +----------------------+-------------------+
           |        |         PROJECTION SERVICE  :8086        |
           |        |   consumes ALL events, builds read models |
           |        +----------------------+-------------------+
           |                               ^
           |                               |
+----------+-------------------------------+---------------------------+
|                          EVENT BACKBONE                              |
|                                                                      |
|   Apache Kafka (KRaft mode, no ZooKeeper)  :9092                     |
+----+---------------------------+--------------------------+----------+
     ^                           ^                          ^
     |                           |                          |
     | produce                   | consume                  | consume
     |                           |                          |
+----+------+            +-------+---------+        +-------+----------+
|  MARKET   |            |   SETTLEMENT    |        |  NOTIFICATION    |
|  SERVICE  |            |    SERVICE      |        |     SERVICE      |
|  BET SVC  |            |     :8085       |        |      :8088       |
|  WALLET   |            |                 |        |                  |
|  USER SVC |            |  db-scheduler   |        |  WebSocket push  |
|  BET SVC  |            |  payout engine  |        |  to SPA          |
+-----------+            +-----------------+        +------------------+
```

---

## 5. CQRS split

The system is split into a **write side** (commands, strong consistency, PostgreSQL) and a
**read side** (queries, denormalized, MongoDB + Redis). The two sides communicate **only**
through Kafka events.

```text
                        +---------------------------+
                        |       API GATEWAY         |
                        +--+---------------------+--+
                           |                     |
             write verbs   |                     |   read verbs
         (POST/PATCH/DEL)  |                     |   (GET)
                           v                     v
        +----------------------------+   +----------------------------+
        |      COMMAND SIDE          |   |        QUERY SIDE          |
        |                            |   |                            |
        |  User Svc                  |   |  Query Svc                 |
        |  Wallet Svc                |   |                            |
        |  Market Svc                |   |  reads only, never writes  |
        |  Bet Svc                   |   |  to the write databases    |
        |  Settlement Svc            |   |                            |
        |                            |   |  Postgres: NO access       |
        |  Postgres per service      |   |  Mongo + Redis: read-only  |
        |  Transactional, ACID       |   |  Eventually consistent     |
        +-------------+--------------+   +-------------+--------------+
                      |                                ^
                      |  domain events                 |  projections
                      v                                |
        +-----------------------------------------------------------+
        |                  KAFKA  (event log / source of truth)      |
        +----------------------------+------------------------------+
                                     |
                                     v
                      +-------------------------------+
                      |     PROJECTION SERVICE        |
                      |  event -> denormalized doc    |
                      +-------------------------------+
```

**Rules enforced by convention (and review):**

1. No service ever reads another service's PostgreSQL database.
2. Query Service never issues a write to PostgreSQL.
3. All cross-service state propagation happens through Kafka events.
4. Synchronous HTTP between services is allowed only for the token-reservation step of the
   bet-placement saga (latency-sensitive, needs immediate user feedback).

---

## 6. Request and event flows

### 6.1 Place a bet (happy path)

```text
 SPA        Gateway     Bet Svc      Wallet Svc     Kafka      Projection    Query Svc   Mongo/Redis
  |            |           |             |            |            |            |            |
  | POST       |           |             |            |            |            |            |
  | /api/bets  |           |             |            |            |            |            |
  |----------->|           |             |            |            |            |            |
  |            | command   |             |            |            |            |            |
  |            |---------->|             |            |            |            |            |
  |            |           | validate vs local market_snapshot      |            |            |
  |            |           | (status=OPEN && now < freezeAt)        |            |            |
  |            |           |             |            |            |            |            |
  |            |           | reserve ----|            |            |            |            |
  |            |           |------------>|            |            |            |            |
  |            |           |             | lock + check balance    |            |            |
  |            |           |<--201 resv--|            |            |            |            |
  |            |           |             |            |            |            |            |
  |            |           | BEGIN TX    |            |            |            |            |
  |            |           | insert bet  |            |            |            |            |
  |            |           | insert outbox row        |            |            |            |
  |            |           | COMMIT      |            |            |            |            |
  |            |<--202-----|             |            |            |            |            |
  |<--accepted-|           |             |            |            |            |            |
  |            |           | outbox poller            |            |            |            |
  |            |           |---bet.placed------------>|            |            |            |
  |            |           |             |<--bet.placed|           |            |            |
  |            |           |             | commit debit|           |            |            |
  |            |           |             |---wallet.debited-------->|            |            |
  |            |           |             |            |            | upsert --->|            |
  |            |           |             |            |            |            |            |
  |            |           |             |            |            | ws push    |            |
  |<================= live update via Notification Service =========|            |            |
  |            |           |             |            |            |            |            |
  | GET /api/markets?status=active       |            |            |            |            |
  |----------->|-----------------------------------------------> query svc ---->|            |
  |<---------- fast read from Mongo/Redis -------------------------------------------------|
```

### 6.2 Bet placement failure (compensation)

```text
 Bet Svc                     Wallet Svc                 Kafka
    |                             |                       |
    | reserve ------------------> |                       |
    |<------------------ 201 -----|                       |
    |                             |                       |
    | persist bet FAILS (DB down / validation race)      |
    |                             |                       |
    | release reservation ------->|                       |
    |                             |---wallet.released---->|
    |                             |                       |
    |---bet.failed--------------------------------------->|
```

### 6.3 Market freeze and settlement

```text
db-scheduler       Market Svc        Kafka          Bet Svc       Settlement Svc     Wallet Svc
 (persistent           |              |               |                |                |
  scheduled_tasks)     |              |               |                |                |
    |                  |              |               |                |                |
    | fire freeze task |              |               |                |                |
    |----------------->|              |               |                |                |
    |                  | tx: status=FROZEN + outbox   |                |                |
    |                  |---market.frozen------------->|                |                |
    |                  |              |               | reject late    |                |
    |                  |              |               | bets           |                |
    |                  |              |               |                |                |
    | fire end task    |              |               |                |                |
    |----------------->|              |               |                |                |
    |                  | tx: status=ENDED + outbox    |                |                |
    |                  |---market.ended (no winner)-->|                |                |
    |                  |              |               |                |                |
  Admin                |              |               |                |                |
    | POST /resolve    |              |               |                |                |
    | {winningSide}    |              |               |                |                |
    |----------------->|              |               |                |                |
    |                  | tx: status=RESOLVED + outbox |                |                |
    |                  |---market.resolved---------------------------->|                |
    |                  |              |               |                | read own bet   |
    |                  |              |               |                | table (built   |
    |                  |              |               |                | from bet.*)    |
    |                  |              |               |                | compute pools  |
    |                  |              |               |                |                |
    |                  |              |               |                |--payout.calc-->|
    |                  |              |               |                |                |
    |                  |              |               |                | credit winners |
    |                  |              |               |                |---wallet.credited-->|
    |                  |              |               |                |                |
    |                  |              |               |                |--settlement.completed->|
```

> **db-scheduler note.** The freeze and end tasks are **one-time tasks** scheduled at market
> creation time. `db-scheduler` persists them in its `scheduled_tasks` table and uses
> optimistic locking or `SELECT ... FOR UPDATE SKIP LOCKED` to guarantee that exactly one
> application replica picks and executes each task instance — even when Market Service runs
> with multiple replicas.

### 6.4 Read query

```text
 SPA --GET /api/markets?status=OPEN&sort=pool--> Gateway --> Query Svc
                                                             |
                                                             +--> Redis (hit?)  --> return
                                                             |
                                                             +--> MongoDB       --> cache in Redis
                                                                                 --> return
```

---

## 7. Domain model

### Aggregates (write side, PostgreSQL)

```text
Market (aggregate root)                        Bet (aggregate root)
  id            UUID                            id             UUID (= wallet reservation id)
  title         text                            marketId       UUID
  description   text                            userId         UUID
  status        enum(OPEN|FROZEN|ENDED|         side           text(TRUE|FALSE)
                     RESOLVED|CANCELLED)        amount         bigint (tokens)
  winningSide   text NULL (TRUE|FALSE, set on RESOLVED)
  freezeAt      timestamptz                     status         enum(ACCEPTED|CANCELLED|
  endAt         timestamptz                                      WON|LOST|REFUNDED)
  minBet        bigint                          idempotencyKey text, UNIQUE per user
  maxBet        bigint                          placedAt       timestamptz
  createdBy     UUID                            updatedAt      timestamptz
  version       bigint (+1 per change; writes
                take a row lock)
  createdAt     timestamptz
  updatedAt     timestamptz
                                              OutboxEvent (per service)
Wallet (aggregate root)                         id            UUID
  userId        UUID (PK)                       aggregateId   UUID
  balance       bigint                          type          text
  reserved      bigint                          payload       jsonb
  version       bigint                          createdAt     timestamptz
                                                publishedAt   timestamptz NULL
LedgerEntry (append-only)
  id            UUID
  userId        UUID
  betId         UUID NULL
  type          enum(RESERVE|DEBIT|CREDIT|RELEASE)
  amount        bigint
  balanceAfter  bigint
  createdAt     timestamptz
```

### Read models (MongoDB)

```text
market_view                       (one doc per market; ids are UUID strings)
  _id           marketId
  title, description, status, winningSide, freezeAt, endAt, minBet, maxBet, version
  truePool, falsePool, totalPool, betCount, uniqueBettors   (recomputed from bet_view)
  settlement    { outcome, winningSide, truePool, falsePool }  (from settlement.completed)
  updatedAt

bet_view                          (one doc per bet; index {userId, placedAt desc}, {marketId})
  _id           betId
  marketId, userId, side, amount, placedAt
  status        ACCEPTED | CANCELLED | WON | LOST | REFUNDED
  updatedAt

user_view
  _id           userId
  displayName
```

Odds are not stored: Query Service computes them from the pools and the house edge.
The leaderboard lives in Redis only (`leaderboard:day:*`, `leaderboard:all`), not in MongoDB.
`bet_view` is one document per bet instead of an embedded array per user: upserts stay simple
and idempotent, and `/api/me/bets` pages with an index.

### Payout formula (pari-mutuel with house edge)

Payout per winning user:

$$
\text{payout}_u = \left\lfloor \text{stake}_u \times \frac{P_{\text{total}} \times (1 - e)}{P_{\text{win}}} \right\rfloor
$$

where:

- $P_{\text{total}}$ — total pool for the market (both sides)
- $P_{\text{win}}$ — pool on the winning side
- $e$ — house edge, e.g. `0.05`
- rounding remainder is kept by the house (recorded as a ledger entry for auditability)

If $P_{\text{win}} = 0$ (nobody picked the winning side), all stakes are refunded.
If the losing pool is 0 (nobody to win from; this includes a single bettor), all stakes are
refunded too. Otherwise winners would get less than their stake.

Implementation rules (`Payouts.compute` in Settlement Service, a pure function):
- Integer math only (`BigInteger`), edge in basis points (`HOUSE_EDGE_BPS`, default 500 = 5%):
  `payout_u = floor(stake_u × P_total × (10000 − edgeBps) / (P_win × 10000))`.
- A user's winning-side stakes are added before rounding.
- Cancelled bets are ignored (the wallet refunded them on `bet.cancelled`).
- Bets with `placedAt >= freezeAt` are refunded and left out of the pools.
- `houseAmount = P_total − Σ payouts` (edge + rounding remainder). A full refund reports empty pools.

---

## 8. Service catalog

### 8.1 API Gateway — `:8080`

| Aspect | Detail |
|---|---|
| **Tech** | Spring Cloud Gateway (WebFlux), Spring Security OAuth2 Resource Server, Resilience4j, Lettuce (Redis) |
| **Store** | Redis (rate-limit buckets, JWT deny-list) |
| **Role** | Single entry point. Routes, authenticates, throttles. Zero business logic. |

**Responsibilities**

- Path + method based routing to downstream services.
- JWT signature and expiry validation; injects `X-User-Id`, `X-User-Roles` headers.
- Redis token-bucket rate limiting (per user and per IP).
- CORS for the Vite dev server.
- Propagates `X-Correlation-Id` (generates if missing) into every downstream call and into MDC.
- Circuit breakers + retries for idempotent downstream calls. One breaker per route (a failing
  service does not open another route). Timeout `DOWNSTREAM_TIMEOUT` (5s) -> 504; open breaker
  -> 503. Only timeouts and connection errors count as failures. Only `GET` routes retry.

**Routing table**

```text
POST   /api/auth/**                -> user-service      :8081
GET    /api/users/me               -> user-service      :8081
PATCH  /api/users/me               -> user-service      :8081

GET    /api/wallets/me             -> wallet-service    :8082
GET    /api/wallets/me/ledger      -> wallet-service    :8082

POST   /api/markets                -> market-service    :8083
PATCH  /api/markets/{id}           -> market-service    :8083
POST   /api/markets/{id}/freeze    -> market-service    :8083
POST   /api/markets/{id}/resolve   -> market-service    :8083
DELETE /api/markets/{id}           -> market-service    :8083

POST   /api/bets                   -> bet-service       :8084
DELETE /api/bets/{id}              -> bet-service       :8084

GET    /api/markets/**             -> query-service     :8087
GET    /api/me/bets                -> query-service     :8087
GET    /api/leaderboard            -> query-service     :8087
GET    /api/stats/**               -> query-service     :8087

WS     /ws                         -> notification-svc  :8088
```

---

### 8.2 User Service — `:8081`

| Aspect | Detail |
|---|---|
| **Tech** | Spring Boot Web, Spring Security Crypto (BCrypt), Nimbus JOSE (via `spring-security-oauth2-jose`), Spring Data JPA, Flyway, PostgreSQL |
| **Store** | `user-db` (PostgreSQL) |
| **Produces** | `user.registered`, `user.updated` |
| **Consumes** | — |

**Responsibilities**

- Registration, login, refresh token rotation.
- Password hashing (BCrypt, strength 12).
- Issues JWTs signed with an RSA key pair (public key shared with the gateway via JWKS endpoint).
- Grants the starting token balance by emitting `user.registered`, which Wallet Service consumes
  to create the initial wallet with `STARTING_TOKENS`.
- Owns display names used by the leaderboard projection.

**API**

```text
POST   /api/auth/register          { email, password, displayName } -> 201 { userId }
POST   /api/auth/login             { email, password }              -> 200 { accessToken, refreshToken }
POST   /api/auth/refresh           { refreshToken }                 -> 200 { accessToken }
POST   /api/auth/logout            (Bearer)                         -> 204
GET    /api/users/me               (Bearer)                         -> 200 { user }
PATCH  /api/users/me               (Bearer) { displayName }         -> 200 { user }

GET    /.well-known/jwks.json                                       -> 200 JWKS
GET    /internal/users/{id}                                         -> 200 { userId, displayName }
GET    /actuator/prometheus                                         -> 200
```

---

### 8.3 Wallet Service — `:8082`

| Aspect | Detail |
|---|---|
| **Tech** | Spring Boot Web, Spring Data JPA, Spring Kafka, Flyway, PostgreSQL |
| **Store** | `wallet-db` (PostgreSQL) |
| **Produces** | `wallet.created`, `wallet.reserved`, `wallet.debited`, `wallet.debit_failed`, `wallet.credited`, `wallet.released` |
| **Consumes** | `user.registered`, `bet.placed`, `bet.cancelled`, `bet.failed`, `payout.calculated` |

**Responsibilities**

- Append-only ledger: every balance change is a `LedgerEntry` row. `Wallet.balance` is a
  materialized projection of the ledger, updated in the same transaction.
- Two-phase token movement:
  1. `reserve` — holds tokens: `reserved += amount`. `balance` is unchanged; `available = balance - reserved`.
     Called synchronously by Bet Service.
  2. `commit` — converts a reservation into a real debit. Triggered by `bet.placed`.
  3. `release` — returns reserved tokens to balance. Triggered by `bet.cancelled` / `bet.failed`.
- Credits payouts on `payout.calculated`: one transaction per event, wallets locked in
  `userId` order (no deadlocks). A missing wallet rolls back the whole event (retry, then DLT).
- Guards concurrency with `SELECT ... FOR UPDATE` on the wallet row. No distributed lock: the
  row lock is already authoritative.
- Enforces non-negative balance; reservation fails with `409 INSUFFICIENT_FUNDS` otherwise.
- A `reservations` row per bet (id = `betId`, status `RESERVED|COMMITTED|RELEASED|REFUNDED`).
  Every move locks the wallet row first, then changes the reservation, the wallet, one ledger
  row and one outbox event in one transaction.
- `bet.cancelled` releases a `RESERVED` reservation and refunds a `COMMITTED` one (`CREDIT`,
  `wallet.credited`). `bet.placed` after `bet.cancelled` (topics are not ordered against each
  other) finds `RELEASED` and does nothing.
- `bet.placed`/`bet.cancelled` for an unknown reservation throws: 3 retries, then `<topic>.DLT`.
- All `wallet.*` move events carry `WalletMovementV1` (amount + wallet state after the move).

**API**

```text
GET    /api/wallets/me                        (Bearer) -> 200 { balance, reserved, available }
GET    /api/wallets/me/ledger?page=&size=     (Bearer) -> 200 { entries[] }

POST   /internal/wallets/{userId}/reservations
       { betId, amount }                                  -> 201 { reservationId = betId }
                                    (same betId + amount again -> 200, no second hold)
POST   /internal/wallets/reservations/{betId}/release     -> 200 (idempotent; 404 if unknown)
       (no commit endpoint: commit happens only on bet.placed)

GET    /actuator/prometheus                           -> 200
```

---

### 8.4 Market Service — `:8083`

| Aspect | Detail |
|---|---|
| **Tech** | Spring Boot Web, Spring Data JPA, Spring Kafka, **db-scheduler-spring-boot-4-starter**, Flyway, PostgreSQL |
| **Store** | `market-db` (PostgreSQL), `scheduled_tasks` (db-scheduler table) |
| **Produces** | `market.created`, `market.updated`, `market.frozen`, `market.ended`, `market.resolved`, `market.cancelled` |
| **Consumes** | — |

**Responsibilities**

- Owns the **Market** aggregate and its lifecycle state machine.
- Validates transitions: `OPEN -> FROZEN -> ENDED -> RESOLVED`, and `OPEN|FROZEN|ENDED -> CANCELLED`.
- Every transition (API or scheduled task) updates the market and inserts the outbox row in
  **one transaction**. Nothing in this service calls `KafkaTemplate` directly.
- On market creation, schedules two **one-time tasks** with `db-scheduler`:
  - a `freeze-market` task at `freezeAt`
  - an `end-market` task at `endAt`
- `db-scheduler` persists these tasks in the `scheduled_tasks` table and guarantees that only
  one application replica executes each task instance, using optimistic locking or
  `SELECT ... FOR UPDATE SKIP LOCKED` depending on the polling strategy.
- The task handler is idempotent: if the market is already `FROZEN`/`ENDED`, it is a no-op.
- `PATCH` of `freezeAt`/`endAt` reschedules both tasks (same task instance id, new execution time).
- The `end-market` task sets `ENDED` with no winner. An admin later calls `resolve` with
  `winningSide`; that emits `market.resolved`, which is what triggers settlement.
- Publishes `market.created`/`market.updated` with `freezeAt`/`endAt` so Bet Service can keep its
  local `market_snapshot`.
- Every `market.*` event carries the full market state (`MarketV1`, including `version`).
  Consumers upsert it and drop events with an older `version`.
- Every write locks the market row (`SELECT ... FOR UPDATE`). The API and the scheduler tasks
  cannot interleave on one market.
- The task instance id is the market id. The tasks carry no data, so no Java serialization.
- The `end-market` task on a market that is still `OPEN` (the freeze task was late) emits
  `market.frozen` and then `market.ended`.
- Errors: `404 MARKET_NOT_FOUND`, `409 INVALID_MARKET_STATE`, `400 VALIDATION_ERROR`,
  `403 FORBIDDEN` when `X-User-Roles` has no `ADMIN` (defence in depth behind the gateway).

**API**

```text
POST   /api/markets            (ADMIN) { title, description, freezeAt, endAt, minBet, maxBet }
                                                                 -> 201 { marketId }
PATCH  /api/markets/{id}       (ADMIN) { title?, description?, freezeAt?, endAt? }
                                                                 -> 200   (OPEN only)
POST   /api/markets/{id}/freeze (ADMIN)                          -> 200
POST   /api/markets/{id}/resolve (ADMIN) { winningSide }         -> 200   (ENDED only)
DELETE /api/markets/{id}        (ADMIN)                          -> 204   (cancels + refunds; not RESOLVED)

GET    /actuator/prometheus                                      -> 200
GET    /actuator/health                                          -> 200 (includes db-scheduler health)
```

---

### 8.5 Bet Service — `:8084`

| Aspect | Detail |
|---|---|
| **Tech** | Spring Boot Web, Spring Data JPA, Spring Kafka, RestClient, Flyway, PostgreSQL |
| **Store** | `bet-db` (PostgreSQL: bets, `market_snapshot`, outbox) |
| **Produces** | `bet.placed`, `bet.cancelled`, `bet.failed` |
| **Consumes** | `market.created`, `market.updated`, `market.frozen`, `market.ended`, `market.cancelled` |

**Responsibilities**

- Orchestrates the bet-placement saga (synchronous reservation, asynchronous commit).
- Idempotency: `Idempotency-Key` header is stored with a unique constraint; replays return the
  original result instead of double-charging.
- Validates against its own `market_snapshot` table (status, `freezeAt`, `minBet`, `maxBet`),
  built from `market.*` events. No call to Market Service, no read of the query-side cache.
- Freeze rule: reject with `409 MARKET_NOT_OPEN` if `status != OPEN` **or** `now >= freezeAt`
  (Bet Service clock). The time check covers the window before `market.frozen` arrives. Any bet
  that still slips through (`placedAt >= freezeAt`) is refunded by Settlement.
- Cancellation is allowed only while the market is `OPEN` and before `freezeAt`.
- Writes bet + outbox row in one PostgreSQL transaction (transactional outbox). No database
  transaction is open during the wallet HTTP call.
- No `PENDING` status: the bet is stored only after the reservation succeeds, and the async
  commit cannot fail for business reasons (§11.3). So the bet is `ACCEPTED` at once.
- Bet events use `marketId` as the outbox aggregate id, so the Kafka key is `marketId` (§9).
  All `bet.*` events carry `BetV1`.
- The market snapshot is upserted from `MarketV1` and ignores events with an older `version`.

**API**

```text
POST   /api/bets              (Bearer, Idempotency-Key)
       { marketId, side: "TRUE"|"FALSE", amount }   -> 202 { betId, status: "ACCEPTED" }
DELETE /api/bets/{id}         (Bearer)              -> 200 { status: "CANCELLED" }

GET    /internal/bets/by-user/{userId}?page=        -> 200 { bets[] }

GET    /actuator/prometheus                         -> 200
```

**Error contract**

```text
400 VALIDATION_ERROR          amount < minBet or > maxBet, bad side
404 MARKET_NOT_FOUND
404 WALLET_NOT_FOUND
404 BET_NOT_FOUND             also for another user's bet
409 MARKET_NOT_OPEN           freezeAt passed or status != OPEN
409 INSUFFICIENT_FUNDS
409 DUPLICATE_BET             same idempotency key with different payload
422 BET_ALREADY_CANCELLED
503 WALLET_UNAVAILABLE        reserve timed out / failed; the stake is released
503 BET_NOT_PLACED            bet not stored after reserve; the stake is released, bet.failed written
```

---

### 8.6 Settlement Service — `:8085`

| Aspect | Detail |
|---|---|
| **Tech** | Spring Boot, Spring Kafka, Spring Data JPA, **db-scheduler-spring-boot-4-starter**, Flyway, PostgreSQL |
| **Store** | `settlement-db` (PostgreSQL: `settlement_bet`, settlement audit log, outbox), `scheduled_tasks` (db-scheduler) |
| **Produces** | `payout.calculated`, `settlement.completed` (`settlement.started`: not emitted yet, no consumer) |
| **Consumes** | `market.resolved`, `market.cancelled`, `bet.placed`, `bet.cancelled` |

**Responsibilities**

- Single source of truth for payouts.
- Keeps its own `settlement_bet` table (`betId`, `marketId`, `userId`, `side`, `amount`,
  `placedAt`, `cancelled`) built from `bet.placed` / `bet.cancelled`. It never calls Bet Service.
  `market.resolved` carries `winningSide` and `freezeAt`.
- On `market.resolved`:
  1. Reads the market's non-cancelled bets from `settlement_bet`.
  2. Bets with `placedAt >= freezeAt` are refunded and excluded from the pools (freeze race).
  3. Computes `P_total`, `P_true`, `P_false`, odds, and per-user payouts.
  4. Emits one `payout.calculated` event containing the full payout list (refunds included).
  5. Emits `settlement.completed` with market-level aggregates.
- On `market.cancelled`: emits `payout.calculated` with `type = REFUND` for every stake.
- Idempotent: re-processing `market.resolved` for an already-settled market is a no-op (unique
  constraint on `marketId` in `settlement-db`).
- Flow: the `market.resolved`/`market.cancelled` listener inserts a `PENDING` settlement row and
  schedules the one-time task `settle-market` (instance id = marketId) in one transaction. The
  task locks the row, computes payouts, writes `settlement_payouts` (audit), marks it
  `COMPLETED`, and appends both events to the outbox in one transaction. A failed run rolls
  back and retries with exponential backoff.
- Ordering: `bet.*` and `market.resolved` are different topics, so ordering between them is
  not guaranteed. Admin resolution happens after `endAt`,
  long after the last bet, so in practice all bets have arrived. A **db-scheduler** one-time
  retry task (exponential backoff) covers transient DB failures during computation.

**API**

```text
GET    /actuator/health                    -> 200
```

`GET /internal/settlements/{marketId}` is deferred: nothing reads it yet. The `settlements` and
`settlement_payouts` tables hold the audit data.

No public API — this service is purely event-driven.

---

### 8.7 Projection Service — `:8086`

| Aspect | Detail |
|---|---|
| **Tech** | Spring Boot, Spring Kafka (batch listener), Spring Data MongoDB, Spring Data Redis |
| **Store** | MongoDB `read-db` (write), Redis (write) |
| **Produces** | — |
| **Consumes** | **all** domain events |

**Responsibilities**

- The only writer of the read model. Nothing else may write to `read-db` or `read-cache`.
- Every handler is idempotent and independent of the order across topics, so a duplicate, a
  reordering, or a full replay gives the same read model (tests apply events twice and shuffled):
  - `market.*` → upsert `market_view` only if the stored `version` is lower.
  - `bet.*` → upsert `bet_view` (a `bet.cancelled` that came first wins), then recompute the
    market's pools from `bet_view` (no increments).
  - `settlement.completed` → store the result on `market_view`; bet statuses (WON/LOST/REFUNDED)
    are derived from it after every event of that market.
  - `wallet.debited`/`wallet.credited` → one Lua script: `SET projection:processed:{eventId} NX`
    then `ZINCRBY` on `leaderboard:day:{date}` (date of `occurredAt`) and `leaderboard:all`.
- Rebuild: `scripts/rebuild-read-model.sh` stops the projection, drops `read_db` and its Redis
  keys, resets the consumer group to the earliest offsets, replays, and diffs the result.
  Replayed topics keep their events forever (§9).
- Original plan per event type (still the intent; details above):
  - `market.created` / `market.updated` / `market.frozen` / `market.ended` / `market.resolved` /
    `market.cancelled` → upsert `market_view`.
  - `bet.placed` / `bet.cancelled` → increment `market_view` pools and `betCount`, upsert
    `user_bet_view`.
  - `wallet.credited` / `wallet.debited` → update `user_bet_view` payout fields, update the
    Redis leaderboard: `ZINCRBY leaderboard:day:{yyyy-mm-dd}` and `ZINCRBY leaderboard:all`.
  - `wallet.debit_failed` → mark the bet failed in `user_bet_view`.
  - `settlement.completed` → refresh `leaderboard_snapshot` in Mongo.
- Deletes stale cache keys in Redis after writing. It does not use pub/sub; Notification Service
  reads Kafka directly.

**API**

```text
GET    /actuator/health               -> 200
```

Lag comes from `kafka-consumer-groups.sh --describe` (the rebuild script uses it);
`/internal/projections/status` is deferred.

---

### 8.8 Query Service — `:8087`

| Aspect | Detail |
|---|---|
| **Tech** | Spring Boot Web, Spring Data MongoDB, Spring Data Redis (Lettuce) |
| **Store** | MongoDB `read-db` (read-only), Redis (read-only + cache writes) |
| **Produces** | — |
| **Consumes** | — |

**Responsibilities**

- Serves all read traffic for the SPA. **Never** touches PostgreSQL.
- Cache-aside: Redis (shared, TTL 5–30 s) in front of MongoDB. Add an in-process cache only if a
  load test shows Redis is the bottleneck.
- Pagination and sorting on denormalized documents.
- Leaderboard reads come from Redis: `all` reads `leaderboard:all`; `7d`/`30d` run
  `ZUNIONSTORE` over the daily buckets into `leaderboard:7d|30d` (TTL 60 s) and read that.

**API**

```text
GET /api/markets?status=OPEN|FROZEN|ENDED&page=&size=&sort=pool|endAt
                                 -> 200 { items[], page, size, total }
GET /api/markets/{id}             -> 200 { market view with pools and odds }
GET /api/markets/{id}/stats       -> 200 { truePool, falsePool, betCount, uniqueBettors }
GET /api/me/bets?status=&page=   (Bearer) -> 200 { items[] }
GET /api/leaderboard?window=7d|30d|all&limit=100
                                 -> 200 { entries[] }
GET /api/users/{id}/stats        -> 200 { totalBets, winRate, netProfit }

GET /actuator/prometheus         -> 200
```

---

### 8.9 Notification Service — `:8088`

| Aspect | Detail |
|---|---|
| **Tech** | Spring Boot WebSocket (STOMP), Spring Kafka, Spring Data Redis |
| **Store** | Redis (session registry, only needed with more than one replica) |
| **Produces** | — |
| **Consumes** | all domain events (Kafka only) |

**Responsibilities**

- Terminates WebSocket connections from the SPA at `/ws`.
- Authenticates the STOMP `CONNECT` frame using the same JWT.
- Maintains a user → session map in Redis so any replica can route a message to the right socket.
- Fans out events to STOMP destinations:

```text
/topic/public.markets            every market lifecycle change + pool update
/topic/public.leaderboard        leaderboard deltas
/user/queue/notifications        personal: bet accepted, bet won, payout credited
/user/queue/wallet               wallet balance changes
```

**API**

```text
WS   /ws                                  STOMP endpoint (plain WebSocket, no SockJS)
GET  /actuator/prometheus                 -> 200
GET  /actuator/health                     -> 200
```

**Implementation notes (M7)**

- The gateway routes `/ws` without a token check. A browser cannot send `Authorization` on the
  WebSocket upgrade, so the JWT travels in the STOMP `CONNECT` frame.
- `CONNECT` without a token gives an anonymous session: public topics only. An invalid or expired
  token gets a STOMP `ERROR` frame.
- `SUBSCRIBE` is allowed only to `/topic/public.*` and `/user/queue/*`. A raw `/queue/...`
  destination is rejected, so a client cannot read another user's queue.
- Messages are JSON with a `type` field (the event type):
  - `market.*` → `/topic/public.markets` `{type, marketId, market}`.
  - `bet.placed`/`bet.cancelled` → `/topic/public.markets` `{type, marketId}` and the bettor's
    `/user/queue/notifications`.
  - `wallet.*` → the owner's `/user/queue/wallet` `{type, betId, amount, balance, reserved, available}`.
  - `payout.calculated` → each paid user's `/user/queue/notifications`.
  - `settlement.completed` → `/topic/public.leaderboard` and `/topic/public.markets`.
- The consumer group starts at the latest offset: pushes are live only, never replayed.
- Single replica with the in-memory simple broker. Redis is not used yet. More replicas need a
  broker relay or a Redis session registry.
- A push can arrive before the projection updates the read model. The SPA refetches 1 s after a
  push. There is no polling fallback: a lost push shows on the next page load.

---

## 9. Kafka topic design

| Kafka topic | Key | Partitions | Retention | Producer | Consumers |
|---|---|---|---|---|---|
| `user.registered` | `userId` | 3 | 7d | user-service | wallet-service, projection |
| `user.updated` | `userId` | 3 | 7d | user-service | projection |
| `wallet.created` | `userId` | 3 | 7d | wallet-service | projection |
| `wallet.reserved` | `userId` | 6 | 3d | wallet-service | projection |
| `wallet.debited` | `userId` | 6 | 7d | wallet-service | projection, notification |
| `wallet.credited` | `userId` | 6 | 30d | wallet-service | projection, notification |
| `wallet.released` | `userId` | 6 | 3d | wallet-service | projection |
| `wallet.debit_failed` | `userId` | 6 | 7d | wallet-service | projection |
| `market.created` | `marketId` | 6 | 30d | market-service | bet-service, projection, notification |
| `market.updated` | `marketId` | 6 | 30d | market-service | bet-service, projection, notification |
| `market.frozen` | `marketId` | 6 | 30d | market-service | bet-service, projection, notification |
| `market.ended` | `marketId` | 6 | 90d | market-service | bet-service, projection, notification |
| `market.resolved` | `marketId` | 6 | 90d | market-service | settlement, projection, notification |
| `market.cancelled` | `marketId` | 6 | 90d | market-service | bet-service, settlement, projection, notification |
| `bet.placed` | `marketId` | 6 | 90d | bet-service | wallet-service, settlement, projection, notification |
| `bet.cancelled` | `marketId` | 6 | 90d | bet-service | wallet-service, settlement, projection, notification |
| `bet.failed` | `marketId` | 6 | 3d | bet-service | wallet-service, projection |
| `settlement.started` | `marketId` | 3 | 30d | settlement-service | projection |
| `payout.calculated` | `marketId` | 6 | 90d | settlement-service | wallet-service, projection, notification |
| `settlement.completed` | `marketId` | 3 | 90d | settlement-service | projection, notification |

**Retention for replay:** the Projection Service rebuilds the read model by replaying every topic
it consumes (`user.registered`, `market.*`, `bet.placed`, `bet.cancelled`, `wallet.debited`,
`wallet.credited`, `settlement.completed`). Those topics use `retention.ms=-1` (keep forever) in
`infra/kafka/create-topics.sh`; the retention column above is overridden for them. With finite
retention a replay after that period silently loses data. Compacted snapshot topics are the
alternative if storage grows.

**Dead-letter:** every consumer has a `.DLT` counterpart (e.g. `bet.placed.DLT`) after 3 retries
with exponential backoff.

**Partitioning rationale:**

- Keying by `marketId` on bet topics guarantees per-market ordering, which settlement relies on.
- Keying by `userId` on wallet topics guarantees per-user ordering, which balance correctness relies on.

**Envelope** (plain JSON; each `eventType` + `eventVersion` maps to a Kotlin data class in
`shared/events`; additive changes only, a breaking change means a new `eventVersion`):

```json
{
  "eventId": "uuid",
  "eventType": "bet.placed",
  "eventVersion": 1,
  "occurredAt": "2026-10-08T12:00:00Z",
  "correlationId": "uuid",
  "causationId": "uuid",
  "aggregateId": "uuid",
  "producer": "bet-service",
  "payload": { }
}
```

---

## 10. Data stores

| Store | Owner | Purpose | Access pattern |
|---|---|---|---|
| PostgreSQL `user-db` | user-service | users, credentials, refresh tokens | transactional |
| PostgreSQL `wallet-db` | wallet-service | wallets, ledger, reservations | transactional, `FOR UPDATE` |
| PostgreSQL `market-db` | market-service | market aggregate | transactional |
| PostgreSQL `bet-db` | bet-service | bet aggregate, `market_snapshot`, outbox | transactional |
| PostgreSQL `settlement-db` | settlement-service | `settlement_bet`, settlement audit log, outbox | append-only |
| **`scheduled_tasks`** | **market-service, settlement-service** | **db-scheduler persistent task store** | **optimistic lock / select-for-update** |
| MongoDB `read-db` | projection (write) / query (read) | denormalized views | upsert, indexed reads |
| Redis | gateway, projection, query, notification | cache, rate limit, leaderboard, WS session registry | low-latency |

**Local shortcut:** one PostgreSQL container, five logical databases plus the `scheduled_tasks`
table created per service that needs it. This preserves logical isolation (no cross-database
joins, no shared schema) without the cost of six containers. In a real deployment each service
gets its own instance.

**db-scheduler table DDL (PostgreSQL):**

```sql
CREATE TABLE scheduled_tasks (
    task_name            TEXT NOT NULL,
    task_instance        TEXT NOT NULL,
    task_data            BYTEA,
    execution_time       TIMESTAMPTZ NOT NULL,
    picked               BOOLEAN NOT NULL,
    picked_by            TEXT,
    last_success         TIMESTAMPTZ,
    last_failure         TIMESTAMPTZ,
    consecutive_failures INT,
    last_heartbeat       TIMESTAMPTZ,
    version              BIGINT NOT NULL,
    priority             SMALLINT,
    PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX scheduled_tasks_execution_time_idx
    ON scheduled_tasks (execution_time ASC, priority DESC);
```

Each service that uses `db-scheduler` (Market Service, Settlement Service) runs its own copy of
this table inside its own PostgreSQL database.

**Redis key conventions**

```text
cache:market:{marketId}          STRING   JSON market view, TTL 30s
cache:markets:open               STRING   JSON list, TTL 5s
rate:user:{userId}:{route}       STRING   token bucket, TTL 60s
leaderboard:day:{yyyy-mm-dd}     ZSET     member=userId, score=netProfit, TTL 31d
leaderboard:all                  ZSET     member=userId, score=netProfit
leaderboard:7d|30d               ZSET     ZUNIONSTORE of daily buckets, TTL 60s
ws:session:{sessionId}           STRING   userId, TTL 1h
ws:user:{userId}:sessions        SET      sessionIds
```

**Indexes**

```text
market-db:     (status, freezeAt), (status, endAt)
bet-db:        (marketId, status), (userId, placedAt DESC), UNIQUE (userId, idempotencyKey)
wallet-db:     (userId), (betId), (userId, createdAt DESC)
settlement-db: UNIQUE (marketId), settlement_bet (marketId)
mongo:         market_view { status:1, endAt:1 }, user_bet_view { "bets.marketId":1 }
scheduled_tasks: (execution_time ASC, priority DESC)
```

---

## 11. Consistency patterns

### 11.1 Transactional Outbox

Every command-side service writes its domain change and an `outbox_event` row in **one**
PostgreSQL transaction. A poller (or Debezium, if you want to level up later) reads unpublished
rows and produces them to Kafka, then marks them published.

```text
BEGIN
  INSERT INTO bet (...)
  INSERT INTO outbox_event (aggregateId, type, payload, createdAt, publishedAt=NULL)
COMMIT
      |
      v
  @Scheduled poller -> SELECT ... WHERE publishedAt IS NULL FOR UPDATE SKIP LOCKED
                    -> kafkaTemplate.send(...)
                    -> UPDATE outbox_event SET publishedAt = now()
```

This removes the classic "DB committed but Kafka publish failed" dual-write problem.

### 11.2 Idempotent Consumer

Every consumer keeps a `processed_event` table (or Redis set) keyed by `eventId`. Kafka is
at-least-once; the consumer must be effectively-once.

```text
if (processedEventRepo.exists(eventId)) { ack(); return; }
handle(event);
processedEventRepo.save(eventId);   // same transaction as the side effect
ack();
```

### 11.3 Saga with compensation

The bet-placement flow is a **choreographed saga**:

```text
 1. Bet Svc        -> Wallet Svc   : reserve(betId, amount)          [sync]
 2. Bet Svc        -> bet-db       : persist bet + outbox            [tx]
 3. Bet Svc        -> Kafka        : bet.placed
 4. Wallet Svc     -> wallet-db    : commit reservation -> debit     [tx]
 5. Wallet Svc     -> Kafka        : wallet.debited

 Failure at step 1 (timeout/5xx) -> Bet Svc -> Wallet Svc : release(betId), best effort
 Failure at step 2 -> Bet Svc  -> Wallet Svc : release(betId)
                   -> Bet Svc  -> Kafka      : bet.failed (new tx, best effort; Wallet releases again, idempotent)
 Failure at step 4 -> retries, then bet.placed.DLT (alert in M8)
```

Because step 4 only ever *confirms* an already-reserved amount, it cannot fail for
insufficient-funds reasons — only infrastructure reasons, which the DLT covers.

Known gap: if the release call and bet-db both fail at step 2, the stake stays reserved.
A reservation sweeper fixes this; it is deferred until it matters.

### 11.4 Cluster-safe scheduling with db-scheduler

Market freeze/end and settlement retry are driven by **`db-scheduler`** (kagkarlsson), not by
ShedLock or `@Scheduled`. The distinction matters:

| | ShedLock | db-scheduler |
|---|---|---|
| **What it is** | A distributed **lock** for `@Scheduled` methods | A persistent **scheduler** |
| **Guarantee** | "At most one node runs this tick" | "Exactly one node picks and runs this task instance" |
| **Persistence** | Lock table only | Full task state in `scheduled_tasks` |
| **Missed executions** | Lost if all nodes are down | Recovered from the database on restart |
| **Ad-hoc scheduling** | Not supported | Supported via `SchedulerClient.schedule(...)` |
| **Failure handling** | Manual | Built-in retry, backoff, dead-execution detection |

**How it works in this project:**

1. When a market is created, Market Service calls `scheduler.schedule(...)` to persist two
   one-time tasks:
   - `freeze-market` at `freezeAt`
   - `end-market` at `endAt`
2. `db-scheduler` writes both rows into the `scheduled_tasks` table.
3. At the scheduled time, every replica polls the table. `db-scheduler` uses optimistic locking
   or `SELECT ... FOR UPDATE SKIP LOCKED` (PostgreSQL) so that **exactly one** replica wins the
   claim and executes the task.
4. If the winning replica crashes mid-execution, the heartbeat expires and another replica
   revives the dead execution (default: reschedule to `now()`).
5. If all replicas are down at `freezeAt`, the task is **not lost** — it runs as soon as any
   replica comes back up.

**Configuration (per service using db-scheduler):**

```properties
db-scheduler.enabled=true
db-scheduler.threads=5
db-scheduler.polling-interval=10s
db-scheduler.table-name=scheduled_tasks
db-scheduler.immediate-execution-enabled=true
db-scheduler.polling-strategy=lock-and-fetch
db-scheduler.polling-strategy-lower-limit-fraction-of-threads=0.5
db-scheduler.polling-strategy-upper-limit-fraction-of-threads=1.0
```

**Kotlin task definition (Market Service):**

```kotlin
@Configuration
class MarketTasks {
    // No task data: the instance id is the market id.
    // Each handler locks the market, updates it and writes the outbox row in one tx.
    // It is a no-op if the market already left the source state (safe on re-run).
    @Bean
    fun freezeTask(service: MarketService): OneTimeTask<Void> =
        Tasks.oneTime("freeze-market").execute { inst, _ -> service.freezeByScheduler(UUID.fromString(inst.id)) }

    @Bean
    fun endTask(service: MarketService): OneTimeTask<Void> =
        Tasks.oneTime("end-market").execute { inst, _ -> service.endByScheduler(UUID.fromString(inst.id)) }
}
```

**Scheduling the tasks when a market is created** (inside the create transaction; the starter
wraps the `DataSource` in `TransactionAwareDataSourceProxy`, so the task rows commit or roll back
with the market row):

```kotlin
scheduler.scheduleIfNotExists(TaskInstance<Void>("freeze-market", market.id.toString()), market.freezeAt)
scheduler.scheduleIfNotExists(TaskInstance<Void>("end-market", market.id.toString()), market.endAt)
```

`PATCH` of `freezeAt`/`endAt` calls `scheduler.reschedule(TaskInstanceId.of(name, id), newTime)`.

**Why `lock-and-fetch` for PostgreSQL:** the `lock-and-fetch` polling strategy uses
`SELECT ... FOR UPDATE SKIP LOCKED`, which is a single-statement fetch-and-lock. This reduces
database round-trips and is the recommended strategy for PostgreSQL at moderate throughput.

### 11.5 Read-your-own-writes

The SPA cannot immediately query the read model after placing a bet — projections are async.
Mitigation: the SPA inserts a `PENDING` bet into its TanStack Query cache at once and lowers the
cached `available` balance. `POST /api/bets` returns `{betId, status}`; the SPA swaps in the real
`betId` and refetches. On an error it rolls both changes back. Live pushes then reconcile the
cache with the projection.

---

## 12. Observability

```text
+---------------------+     scrape /actuator/prometheus     +--------------+
| gateway    :8080    |<------------------------------------|              |
| user-svc   :8081    |                                     |              |
| wallet-svc :8082    |                                     |              |
| market-svc :8083    |                                     |  Prometheus  |
| bet-svc    :8084    |                                     |    :9090     |
| settlement :8085    |                                     |              |
| projection :8086    |                                     |              |
| query-svc  :8087    |                                     |              |
| notif-svc  :8088    |                                     |              |
+---------------------+                                     |              |
                                                            |              |
+---------------------+                                     |              |
| kafka-exporter      |<------------------------------------|              |
| redis-exporter      |<------------------------------------|              |
| postgres-exporter   |<------------------------------------|              |
+---------------------+                                     +------+-------+
                                                                   |
                                                                   v
                                                            +--------------+
                                                            |   Grafana    |
                                                            |    :3000     |
                                                            +--------------+
```

### Metrics per service (custom, via Micrometer)

| Metric | Type | Labels |
|---|---|---|
| `bets_placed_total` | counter | `side`, `status` |
| `bet_placement_duration_seconds` | histogram | — |
| `wallet_reservations_total` | counter | `result` |
| `wallet_balance_tokens` | gauge | `userId` (sampled) |
| `markets_by_status` | gauge | `status` |
| `market_transitions_total` | counter | `from`, `to` |
| `settlement_duration_seconds` | histogram | — |
| `payouts_total` | counter | — |
| `kafka_consumer_lag` | gauge | `topic`, `group` |
| `projection_events_processed_total` | counter | `eventType` |
| `db_scheduler_executions_total` | counter | `taskName`, `result` |
| `db_scheduler_execution_duration_seconds` | histogram | `taskName` |
| `db_scheduler_scheduled_tasks` | gauge | `taskName` |

Standard auto-instrumented metrics (`http_server_requests_seconds`, `hikaricp_connections`,
`lettuce_command_completion`, `spring_kafka_listener_seconds`) cover the rest.

### Grafana dashboards

1. **Platform overview** — request rate, error rate, p95 latency per service, JVM heap, pod up/down.
2. **Betting funnel** — bets placed/sec, rejection reasons, reservation success rate, saga failures.
3. **Market lifecycle** — markets by status, freeze/end lag (scheduled vs actual), settlement duration.
4. **Scheduler health** — db-scheduler executions per task, failures, scheduled task backlog.
5. **Kafka health** — consumer lag per group, producer throughput, DLT depth.
6. **Wallet integrity** — sum of ledger deltas vs. materialized balances, credit/debit ratio.

### Alert rules (Prometheus)

```text
- Kafka consumer lag > 1000 for 5m                 -> warning
- bet_placement_duration_seconds p99 > 1s for 5m   -> warning
- any *.DLT topic non-empty                        -> critical
- wallet_balance_mismatch > 0                      -> critical
- db_scheduler_executions_total{result="failed"} > 0 for 5m  -> warning
- service /actuator/health DOWN for 1m             -> critical
```

### Logging and tracing

- Structured JSON logs via `logstash-logback-encoder`.
- `X-Correlation-Id` propagated through gateway, HTTP clients, Kafka headers, and MDC.
- Micrometer Tracing with OTLP exporter is a stretch goal (add Tempo + Grafana to the compose file).
- Schema Registry with compatibility checks is also a stretch goal; until then events are plain JSON.

---

## 13. Local deployment topology

### 13.1 Port map

| Component | Port |
|---|---|
| frontend (Vite dev) | 5173 |
| frontend (nginx, prod build) | 80 |
| api-gateway | 8080 |
| user-service | 8081 |
| wallet-service | 8082 |
| market-service | 8083 |
| bet-service | 8084 |
| settlement-service | 8085 |
| projection-service | 8086 |
| query-service | 8087 |
| notification-service | 8088 |
| kafka-ui (optional) | 8090 |
| mongo-express (optional) | 8091 |
| kafka (host listener) | 9092 |
| kafka (internal listener) | 29092 |
| postgres | 5432 |
| mongodb | 27017 |
| redis | 6379 |
| prometheus | 9090 |
| grafana | 3000 |
| kafka-exporter | 9308 |
| redis-exporter | 9121 |
| postgres-exporter | 9187 |

### 13.2 Compose service groups

```text
[ infrastructure ]   postgres, mongodb, redis, kafka, kafka-init
[ backend ]          gateway, user-svc, wallet-svc, market-svc, bet-svc,
                     settlement-svc, projection-svc, query-svc, notification-svc
[ frontend ]         frontend
[ observability ]    prometheus, grafana, kafka-exporter, redis-exporter, postgres-exporter
[ dev tools ]        kafka-ui, mongo-express        (profile: tools)
```

### 13.3 Startup ordering

```text
postgres, mongodb, redis, kafka  (healthchecks)
        |
        v
kafka-init  (creates topics)
        |
        v
user-svc, wallet-svc, market-svc, bet-svc, settlement-svc,
projection-svc, query-svc, notification-svc   (depend_on: healthy)
        |
        v
gateway
        |
        v
frontend
        |
        v
prometheus -> grafana
```

### 13.4 Kafka topic bootstrap

A one-shot `kafka-init` container runs `kafka-topics.sh --create` for every topic in the table
in section 9, including `.DLT` variants, then exits. Compose uses `service_completed_successfully`
as the dependency condition.

### 13.5 PostgreSQL bootstrap

A single `postgres` container with an init script:

```sql
CREATE DATABASE user_db;
CREATE DATABASE wallet_db;
CREATE DATABASE market_db;
CREATE DATABASE bet_db;
CREATE DATABASE settlement_db;

CREATE USER app WITH PASSWORD 'app';
GRANT ALL PRIVILEGES ON DATABASE user_db TO app;
GRANT ALL PRIVILEGES ON DATABASE wallet_db TO app;
GRANT ALL PRIVILEGES ON DATABASE market_db TO app;
GRANT ALL PRIVILEGES ON DATABASE bet_db TO app;
GRANT ALL PRIVILEGES ON DATABASE settlement_db TO app;
```

Each service runs its own Flyway migrations against its own database on startup. The
`shared/common` jar ships `V1__common_outbox_and_processed_event.sql` (`outbox_event`,
`processed_event`); service migrations start at `V2`. Market Service
and Settlement Service each run a Flyway migration that creates their own `scheduled_tasks`
table (the DDL from section 10) in their respective databases.

---

## 14. Repository layout

```text
bet-market/
├── docker-compose.yml
├── kubernetes_manifest.yaml
├── README.md
├── architecture.md
├── docs/
│   ├── diagrams/
│   ├── event-catalog.md
│   └── api/
├── infra/
│   ├── postgres/init/01-databases.sql
│   ├── kafka/create-topics.sh
│   ├── prometheus/prometheus.yml
│   ├── prometheus/alerts.yml
│   └── grafana/
│       ├── provisioning/
│       └── dashboards/*.json
├── frontend/
│   ├── Dockerfile
│   ├── vite.config.ts
│   └── src/
│       ├── api/            REST clients, generated types
│       ├── ws/             STOMP client
│       ├── features/       markets, bets, wallet, leaderboard
│       ├── components/
│       └── stores/
├── shared/
│   ├── events/             versioned Kotlin event data classes (JSON)
│   └── common/             outbox, idempotency, correlation, error model
└── services/
    ├── api-gateway/
    ├── user-service/
    ├── wallet-service/
    ├── market-service/
    ├── bet-service/
    ├── settlement-service/
    ├── projection-service/
    ├── query-service/
    └── notification-service/
```

Each service folder follows the same shape:

```text
<service>/
├── Dockerfile
├── build.gradle.kts
└── src/main/
    ├── kotlin/com/betting/<service>/
    │   ├── Application.kt
    │   ├── api/            controllers, DTOs, mappers
    │   ├── domain/         aggregates, value objects, domain events
    │   ├── infra/          persistence, kafka, redis, clients
    │   ├── scheduling/     db-scheduler task definitions (market, settlement)
    │   └── config/
    └── resources/
        ├── application.yml
        ├── application-docker.yml
        └── db/migration/   Flyway V1__*.sql
```

---

## 15. Design notes and trade-offs

| Decision | Why | Cost |
|---|---|---|
| Microservices over a modular monolith | The point of the exercise is distributed-systems practice | ~10× the boilerplate, slower cold start, harder debugging |
| CQRS with separate stores | Queries and writes have genuinely different shapes; read latency matters for a live betting UI | Eventual consistency; the SPA must handle optimistic state |
| Sync reservation, async commit | User needs immediate "bet accepted/rejected"; the debit itself is not latency-critical | Two-phase token movement adds a compensation path |
| One PostgreSQL container, five DBs | Keeps `docker-compose up` fast while preserving logical isolation | Not a true per-service data boundary; discipline required |
| Kafka in KRaft mode | No ZooKeeper container, fewer moving parts | Some older tooling assumes ZooKeeper |
| **db-scheduler over ShedLock** | **ShedLock is only a lock; db-scheduler is a true persistent scheduler with exactly-one-execution guarantees, missed-execution recovery, and built-in retry** | **Extra table per service; slightly more configuration** |
| Choreographed saga (no orchestrator) | Fewer services, events are self-describing | Global flow is harder to see; needs good event catalog docs |
| Single Kafka broker, RF=1 | Local only | No fault-tolerance demonstration; do not reuse this config |
| Redis for cache only | One dependency covers cache, rate limiting, leaderboard, WS sessions; wallet locking stays in Postgres (`FOR UPDATE`) | Redis outage degrades reads and rate limiting, never money |
| JWT validated at gateway | Downstream services stay simple; `X-User-Id` header carries identity | Services trust the network; in production use mTLS + re-validation |

### Known hard problems worth solving in this project

1. **Freeze-time race.** A bet arrives at `freezeAt - 1ms` but the freeze task executes at
   `freezeAt + 200ms`. Policy: Bet Service rejects when `now >= freezeAt` (own clock, snapshot
   `freezeAt`), regardless of status; Settlement refunds any bet with `placedAt >= freezeAt`.
2. **Rounding in payouts.** `floor` per user loses tokens. Either keep the remainder as house
   revenue with a ledger entry, or distribute it to the largest stakers deterministically.
3. **Duplicate `bet.placed`.** Kafka redelivery must not double-debit. This is where the
   idempotent-consumer pattern earns its keep.
4. **Projection rebuild.** Wipe `read-db`, reset consumer offsets to zero, replay everything.
   This should work without touching PostgreSQL — a good test of your event design.
5. **Zero-winner settlement.** Nobody picked the winning side: refund everyone.
6. **db-scheduler task idempotency.** If a freeze task runs twice (e.g. after a crash and
   revive), the second run must detect the market is already `FROZEN` and exit cleanly.
