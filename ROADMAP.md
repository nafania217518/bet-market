# Roadmap: bet-market

## Context
Repo is greenfield: only `architecture.md` exists (no code, no commits). The doc describes 9 Kotlin/Spring
services + React SPA + Kafka/Postgres/Mongo/Redis + Prometheus/Grafana, with `docker-compose up` and a
valid `kubernetes_manifest.yaml` as the definition of done.

Strategy: **walking skeleton first, then vertical slices.** Each milestone ends with something that runs
under `docker-compose up` and a smoke check that proves it. No service is built "fully" before the
end-to-end path exists — that's how 9-service pet projects die at service #3.


---

## M0 — Fix the design before writing code (½ day) — ✅ done
Contradictions/gaps in `architecture.md` that will bite during implementation:

1. **Who picks the winner?** `end-market` task is scheduled at creation with `winningSide = null`, then
   does `winningSide!!` → NPE (§11.4). Lifecycle needs a split: `endAt` → `ENDED` (auto, no winner),
   admin `POST /end {winningSide}` → `RESOLVED` → settlement. Update the state diagram in §1.
2. **Scheduler tasks bypass the outbox** (§11.4 sends to Kafka directly; §11.1 says every change goes via
   outbox). Tasks should update the market + insert outbox row in one tx.
3. **Settlement calls Bet Service over HTTP** (`/internal/bets/by-market`) — violates rule 4 in §5. It
   already consumes `bet.placed`/`bet.cancelled`; keep its own bet table and drop the sync call.
4. **Bet Service reads the projection's Redis cache** for market status — couples the write side to the
   read side. Bet Service already consumes `market.*`; keep a local `market_snapshot` table instead.
5. **Non-goals says "Docker Compose only"** but goals require a k8s manifest. Remove the non-goal.
6. **Freeze-time race policy** (§15 #1): decide now — reject if `now >= freezeAt` in Bet Service using
   its own clock + snapshot `freezeAt`; settlement refunds any bet with `placedAt >= freezeAt`.
7. Small ones: `wallet.debit_failed` missing from topic table; bet response says `ACCEPTED` but initial
   status is `PENDING`; `lb:weekly:{marketId}` key is meaningless; `ZINCRBY leaderboard:7d` never
   expires old data (use daily buckets + `ZUNIONSTORE`, or compute 7d/30d from Mongo snapshots only).

**Simplifications to consider (cut now, add later if wanted):**
- **Schema Registry** → plain JSON envelope + versioned Kotlin data classes in `shared/events`. Add
  registry in M9 if you want to practice schema evolution.
- **Redis wallet lock + `SELECT FOR UPDATE`** → keep only `FOR UPDATE`; the Redis lock adds nothing.
- **Caffeine + Redis + Mongo** in Query Service → Redis + Mongo. Caffeine only if a load test says so.
- **Projection → Redis pub/sub → Notification** *and* Notification consuming all Kafka events is two
  fan-out paths. Pick Kafka-only.

Done when: `architecture.md` updated with decisions.

## M1 — Walking skeleton (1–2 days) — ✅ done
- Gradle Kotlin DSL multi-module: `shared/common`, `shared/events`, `services/user-service`, `services/wallet-service`.
- `shared/common`: outbox entity + poller (`FOR UPDATE SKIP LOCKED`), `processed_event` idempotent
  consumer helper, correlation-id filter/Kafka header propagation, error model. Written **once**, reused by every service.
- `docker-compose.yml` infra: postgres (+`infra/postgres/init/01-databases.sql`), kafka KRaft, `kafka-init`
  (`infra/kafka/create-topics.sh`), redis, mongo. Healthchecks + `depends_on: service_healthy`.
- User Service: register/login, BCrypt, RSA-signed JWT, JWKS endpoint, emits `user.registered` via outbox.
- Wallet Service: consumes `user.registered`, creates wallet + `CREDIT` ledger entry, `GET /wallets/me`.
- One Dockerfile pattern (multi-stage Gradle → JRE 21) shared by all services.
- GitHub Actions: `./gradlew build` with Testcontainers.

Done when: `docker-compose up` → `curl register` → `curl /wallets/me` shows starting balance.
Leave behind `scripts/smoke.sh` and grow it every milestone.

**Outcome (2026-10-08):** smoke passes; 38 tests; coverage events 100%, common 98%, user 93%, wallet 91%.
Decisions and deferrals:
- Spring Boot 4.1.1 / Kotlin 2.3.21, not 3.x (end-of-life). See `docs/caveats/spring-boot-4.md`.
- `shared/common` uses `JdbcClient`, not JPA entities, so no entity-scan setup. It ships Flyway `V1`.
- JWT via Nimbus (`spring-security-oauth2-jose`), not JJWT. RSA key is generated at startup →
  load it from a Secret before >1 replica (M9).
- Deferred to later milestones: refresh/logout, `GET/PATCH /api/users/me`, `/internal/users/{id}`.
- `GET /api/wallets/me` reads `X-User-Id` (no auth until the M2 gateway).
- Wallet semantics: `available = balance - reserved`; reserve does not change `balance`.

## M2 — Gateway + auth (1 day)
- Spring Cloud Gateway: routing table from §8.1, JWT via JWKS, injects `X-User-Id`/`X-User-Roles`,
  correlation id, CORS, Redis rate limiter (built-in `RequestRateLimiter`, no custom code).
- Seed an ADMIN user via Flyway for market creation.

Done when: smoke script goes only through `:8080`.

## M3 — Markets + db-scheduler (1–2 days)
- Market Service: aggregate + state machine, admin CRUD, `scheduled_tasks` Flyway migration,
  `freeze-market` / `end-market` one-time tasks (idempotent, write via outbox), reschedule on PATCH of times.
- Integration test: 2 app instances on one DB, task fires exactly once; crash-revive runs as no-op.

Done when: create market with `freezeAt = now+30s` → `market.frozen` appears in Kafka.

## M4 — Bet saga (2–3 days) — the core of the project
- Bet Service: local market snapshot from `market.*`, `Idempotency-Key`, sync reserve via `RestClient`,
  bet + outbox in one tx, compensation (`release` + `bet.failed`), cancel while OPEN.
- Wallet Service: reservation endpoints, commit on `bet.placed`, release on `bet.cancelled`/`bet.failed`,
  ledger append-only, non-negative balance check, DLT config (3 retries, backoff) in `shared/common`.
- Tests: duplicate `bet.placed` delivery → single debit; concurrent bets on one wallet → no overdraft;
  persist failure after reserve → tokens released.

## M5 — Settlement (1–2 days)
- Settlement Service: own bet table from events, on `market.resolved` compute pari-mutuel payouts
  (formula §7), remainder → house ledger entry, zero-winner → refund all, `market.cancelled` → refund.
  Unique `marketId` for idempotency; db-scheduler retry task.
- Wallet credits on `payout.calculated`.
- Unit test for payout math (rounding, zero-winner, single bettor, house edge). This is the money path — test it hardest.

Done when: full lifecycle in smoke script: register 2 users → bet both sides → resolve → balances correct.

## M6 — Read side (2 days)
- Projection Service: `market_view`, `user_bet_view`, leaderboard; offset/eventId dedupe; Redis writes.
- Query Service: endpoints from §8.8, Redis cache-aside.
- Rebuild script: drop `read-db`, reset consumer group offsets, replay → same result (§15 #4).
  Requires topic retention long enough — set bet/market topics to infinite retention or compacted
  snapshots, otherwise replay is impossible after 90d. Note this in the doc.

## M7 — Frontend + live updates (3–4 days)
- Notification Service: STOMP `/ws`, JWT on CONNECT, Kafka → `/topic/public.markets`, `/user/queue/*`.
  Single replica first; Redis session registry only when testing 2 replicas.
- SPA (Vite, React, TanStack Query, Zustand, Tailwind): login, market list/detail, place/cancel bet with
  optimistic update, wallet + ledger, my bets, leaderboard, admin market form.
- nginx container serving the build, proxied to the gateway.

## M8 — Observability (1–2 days)
- Actuator/Prometheus on every service (mostly config in a shared `application.yml` fragment).
- Custom metrics from §12 (bet, wallet, market, settlement, db-scheduler).
- Prometheus + alerts, Grafana provisioning, exporters (kafka/redis/postgres).
- Dashboards: start with Platform overview + Betting funnel; add others as needed.
- JSON logs via logstash encoder.

## M9 — Kubernetes manifest (1–2 days)
- Single `kubernetes_manifest.yaml`: Namespace, ConfigMap/Secret, StatefulSets for postgres/kafka/mongo/redis,
  Deployments + Services for the 9 services and frontend, Ingress, readiness/liveness via actuator probes,
  kafka-init + postgres-init as Jobs.
- Run Market Service with 2 replicas to actually exercise db-scheduler's exactly-once claim.
- Validate: `kubeconform` in CI + deploy to kind/k3d and run `scripts/smoke.sh` against the ingress.

## M10 — Stretch
Tracing (OTLP + Tempo), Schema Registry + compatibility checks, Debezium outbox, load test (k6) with
the freeze-time race, chaos (kill wallet mid-saga), wallet-integrity reconciliation job.

---

## Verification (per milestone)
- `./gradlew build` green (unit + Testcontainers integration tests).
- `docker-compose up -d && scripts/smoke.sh` exits 0 — the script grows each milestone and is the
  real definition of done.
- From M9: same smoke script against kind cluster.

## Rough total
~3–4 weeks of focused evenings-and-weekends work; M4–M5 are the high-value core, M7–M9 are the polish.
