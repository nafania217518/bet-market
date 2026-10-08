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

## M2 — Gateway + auth (1 day) — ✅ done
- Spring Cloud Gateway: routing table from §8.1, JWT via JWKS, injects `X-User-Id`/`X-User-Roles`,
  correlation id, CORS, Redis rate limiter (built-in `RequestRateLimiter`, no custom code).
- Seed an ADMIN user via Flyway for market creation.

Done when: smoke script goes only through `:8080`.

**Outcome (2026-10-08):** smoke runs only through `:8080`; user/wallet ports are closed on the host.
Gateway: 13 tests, 98% coverage. Live check: 40 requests pass (burst), the rest get 429.
Decisions and deferrals:
- Spring Cloud 2025.1.3 (Gateway 5.0.3); it supports Boot 4.1. Route prefix: `spring.cloud.gateway.server.webflux`.
- Routes exist only for running services (auth, wallet). Each later milestone adds its rows of §8.1.
- Security rules are already final: public `GET` reads, `ROLE_ADMIN` for market writes, the rest needs a JWT.
- The gateway always strips client `X-User-Id`/`X-User-Roles` and sets them from the JWT.
- Admin seed: repeatable Flyway `R__seed_admin.sql` (pgcrypto bcrypt), credentials from
  `ADMIN_EMAIL`/`ADMIN_PASSWORD` (dev defaults). The admin has no wallet.
- Deferred: JWT deny-list (needs logout), circuit breakers/retries (§8.1) — add with M4 when a slow
  downstream exists to test against.

## M3 — Markets + db-scheduler (1–2 days) — ✅ done
- Market Service: aggregate + state machine, admin CRUD, `scheduled_tasks` Flyway migration,
  `freeze-market` / `end-market` one-time tasks (idempotent, write via outbox), reschedule on PATCH of times.
- Integration test: 2 app instances on one DB, task fires exactly once; crash-revive runs as no-op.

Done when: create market with `freezeAt = now+30s` → `market.frozen` appears in Kafka.

**Outcome (2026-10-08):** smoke creates a market with `freezeAt = now+30s`; `market.frozen` reaches
Kafka 0.4 s after `freezeAt`. Market Service: 19 tests, 92% coverage. Full build: 76 tests.
Decisions and deferrals:
- `db-scheduler-spring-boot-4-starter` 16.12.0 (the old starter is Boot 3 only), `lock-and-fetch`.
- Task instance id = market id; no task data. Schedule calls join the create transaction (tested).
- Tests: 2 scheduler instances on one DB run each of 20 freeze tasks exactly once; a revived dead
  execution runs as a no-op.
- All six `market.*` events carry the full state (`MarketV1` with `version`). Consumers upsert and
  drop older versions.
- Writes lock the market row; no JPA `@Version`. `winningSide` is text `TRUE|FALSE`.
- Gateway routes `POST/PATCH/DELETE /api/markets/**` to Market Service. `GET /api/markets/**` waits
  for the Query Service (M6). Market Service also checks the `ADMIN` role itself.
- Deferred: correlation id for scheduler-run events (empty now; M8), db-scheduler health indicator (M8).

## M4 — Bet saga (2–3 days) — the core of the project — ✅ done
- Bet Service: local market snapshot from `market.*`, `Idempotency-Key`, sync reserve via `RestClient`,
  bet + outbox in one tx, compensation (`release` + `bet.failed`), cancel while OPEN.
- Wallet Service: reservation endpoints, commit on `bet.placed`, release on `bet.cancelled`/`bet.failed`,
  ledger append-only, non-negative balance check, DLT config (3 retries, backoff) in `shared/common`.
- Tests: duplicate `bet.placed` delivery → single debit; concurrent bets on one wallet → no overdraft;
  persist failure after reserve → tokens released.

**Outcome (2026-10-08):** smoke places a bet through `:8080`; the wallet debits it after `bet.placed`;
an `Idempotency-Key` replay returns the same bet; a cancelled bet is refunded; a bet after freeze gets 409.
Full build: 115 tests, every module ≥ 92% coverage (bet 93%, wallet 97%). One correlation id flows
gateway → Bet → wallet HTTP call → `bet.placed` → `wallet.debited`.
Required tests: duplicate `bet.placed` → one debit; 20 parallel reserves on 1000 → exactly 10 pass;
persist failure after reserve → release + `bet.failed`; concurrent requests with one key → one bet.
Decisions and deferrals:
- Reservation id = bet id, so Bet Service can always release by bet id (also after a timeout).
- No `PENDING` bet status: the bet is stored after the reserve succeeds, so it is `ACCEPTED`.
- `Idempotency-Key` is unique per user, not global.
- Wallet has no commit endpoint (commit only on `bet.placed`). `bet.cancelled` after a commit refunds.
- DLT: `DefaultErrorHandler` in `shared/common`, 3 retries with exponential backoff, then `<topic>.DLT`.
- Gateway: one circuit breaker per route, 5s timeout (504), open breaker 503, retries for GET only.
- `RestClient.Builder` needs `spring-boot-starter-restclient` on Boot 4.1 (caveat recorded).
- Deferred: reservation sweeper for the "release and bet-db both down" gap; `wallet.debit_failed`
  (the DLT covers it; alert in M8); `GET /api/wallets/me/ledger`.

## M5 — Settlement (1–2 days) — ✅ done
- Settlement Service: own bet table from events, on `market.resolved` compute pari-mutuel payouts
  (formula §7), remainder → house ledger entry, zero-winner → refund all, `market.cancelled` → refund.
  Unique `marketId` for idempotency; db-scheduler retry task.
- Wallet credits on `payout.calculated`.
- Unit test for payout math (rounding, zero-winner, single bettor, house edge). This is the money path — test it hardest.

Done when: full lifecycle in smoke script: register 2 users → bet both sides → resolve → balances correct.

**Outcome (2026-10-08):** smoke runs the full lifecycle: A bets 100 on TRUE, B bets 300 on FALSE,
the market freezes and ends on schedule, the admin resolves TRUE, A ends at 1280 and B at 700
(pool 400, 5% edge, house 20). Full build: 144 tests, every module ≥ 92% coverage (settlement 97%).
Payout math: 14 unit tests, including 1000 random markets that must always balance.
Decisions and deferrals:
- No losers (incl. a single bettor) → refund all, like no winners. Otherwise winners lose money.
- Payouts per user (stakes added before rounding), integer math, edge in basis points.
- A settlement row (PK `marketId`) + db-scheduler task `settle-market` with exponential backoff.
- Wallet credits one payout list per transaction, wallets locked in `userId` order.
- Deferred: `settlement.started` and `GET /internal/settlements/{id}` (no consumer yet); bets that
  arrive after settlement are not paid (admins resolve long after `endAt`; `ponytail:` comment).

## M6 — Read side (2 days) — ✅ done
- Projection Service: `market_view`, `user_bet_view`, leaderboard; offset/eventId dedupe; Redis writes.
- Query Service: endpoints from §8.8, Redis cache-aside.
- Rebuild script: drop `read-db`, reset consumer group offsets, replay → same result (§15 #4).
  Requires topic retention long enough — set bet/market topics to infinite retention or compacted
  snapshots, otherwise replay is impossible after 90d. Note this in the doc.

**Outcome (2026-10-08):** smoke reads the market, my bets (A WON, B LOST) and the leaderboard
(A +280, B −300) through `:8080`. `scripts/rebuild-read-model.sh` drops `read_db` and the Redis
keys, replays from offset 0, and the result is identical to the live-built model (26 docs/keys).
Full build: 179 tests, every module ≥ 92% coverage (projection 98%, query 99%).
Decisions and deferrals:
- `shared/common` is now conditional (outbox needs a DataSource, Kafka parts need Kafka, web parts
  need a servlet app), so read-side services reuse it without Postgres.
- Idempotency without Mongo transactions: version-guarded upserts, recomputed pools, Lua-guarded
  `ZINCRBY` per eventId. No offset collection.
- Bug found by the live rebuild and fixed test-first: `settlement.completed` replayed before
  `bet.placed` left bets `ACCEPTED`. The settlement result now lives on `market_view`, and a
  shuffled-order test guards the whole projection.
- `bet_view` (one doc per bet) instead of `user_bet_view` with an array; odds computed at read time.
- Replayed topics keep events forever (`retention.ms=-1`); the doc says why.
- Deferred: `/internal/projections/status`, `roi` on the leaderboard, payout amount per bet.

## M7 — Frontend + live updates (3–4 days) ✅ done
- Notification Service: STOMP `/ws`, JWT on CONNECT, Kafka → `/topic/public.markets`, `/user/queue/*`.
  Single replica first; Redis session registry only when testing 2 replicas.
- SPA (Vite, React, TanStack Query, Zustand, Tailwind): login, market list/detail, place/cancel bet with
  optimistic update, wallet + ledger, my bets, leaderboard, admin market form.
- nginx container serving the build, proxied to the gateway.

**Outcome (2026-10-08):** smoke steps 19–21 pass. nginx on `:80` serves the SPA and proxies
`/api` and `/ws`. A bet pushes `bet.placed` to `/topic/public.markets` and `wallet.reserved` to
the bettor's `/user/queue/wallet` through nginx, the gateway and Notification Service.
A browser check covered register, a bet with a live pool and balance update, and the wallet ledger.
Full build: 192 JVM tests, every module ≥ 90% coverage (notification 95%). SPA: 62 Vitest tests,
99.6% statements.
Decisions and deferrals:
- Notification Service: plain WebSocket (no SockJS), single replica, simple broker, no Redis.
  Anonymous sessions get public topics; `SUBSCRIBE` is limited to `/topic/public.*` and `/user/queue/*`.
- New `GET /api/wallets/me/ledger` (it was deferred in M1).
- SPA: React 19, Vite 8, TypeScript 7, Vitest 5. No router library: six hash routes in a small hook.
  It needs Node 24 or 26 (see the caveat).
- The SPA refetches 1 s after a push, because the projection can lag behind it.
- Known limit: behind nginx, all anonymous users share one gateway rate-limit bucket (key = the nginx IP).
  Trust `X-Forwarded-For` from nginx only, if that matters.

## M8 — Observability (1–2 days) ✅ done
- Actuator/Prometheus on every service (mostly config in a shared `application.yml` fragment).
- Custom metrics from §12 (bet, wallet, market, settlement, db-scheduler).
- Prometheus + alerts, Grafana provisioning, exporters (kafka/redis/postgres).
- Dashboards: start with Platform overview + Betting funnel; add others as needed.
- JSON logs via logstash encoder.

**Outcome (2026-10-09):** smoke steps 22–26 pass.
- Prometheus scrapes 12 targets (9 services, 3 exporters).
- The alert rules pass 12 promtool unit tests.
- The custom metrics show this run's bets, reservations, freeze task, settlement and payouts.
  `wallet_balance_mismatch` is 0.
- Grafana serves both dashboards, and every panel query returns data.
- The services log JSON.
- Full build: 221 JVM tests, every module ≥ 92% coverage.
Decisions and deferrals:
- No `logstash-logback-encoder`: Boot's built-in structured logging writes the same Logstash JSON.
- db-scheduler metrics and health come from the starter (`dbscheduler_task_*`, `dbScheduler`).
  No custom scheduler metrics.
- Kafka lag comes from kafka-exporter (`kafka_consumergroup_lag`), not from a custom gauge.
- Scheduler-run events now carry a correlation id (deferred from M3).
- Skipped:
  - `wallet_balance_tokens{userId}`, because a per-user label grows without limit.
  - A scheduled-task backlog gauge, Alertmanager, and dashboards 3–6.
- Config is per service: two `management` lines in each `application.yml`, not a shared fragment.
  The gateway does not use `shared:common`.

## M9 — Kubernetes manifest (1–2 days) ✅ done
- Single `kubernetes_manifest.yaml`: Namespace, ConfigMap/Secret, StatefulSets for postgres/kafka/mongo/redis,
  Deployments + Services for the 9 services and frontend, Ingress, readiness/liveness via actuator probes,
  kafka-init + postgres-init as Jobs.
- Run Market Service with 2 replicas to actually exercise db-scheduler's exactly-once claim.
- Validate: `kubeconform` in CI + deploy to kind/k3d and run `scripts/smoke.sh` against the ingress.

**Outcome (2026-10-09):** `scripts/k8s-up.sh` deploys to k3d 5.9.0 (k3s v1.36.4). The smoke test
passes steps 1–21 through the Traefik ingress; steps 22–26 run only under compose.
- Market Service runs 2 replicas. Across both pods, `dbscheduler_task_completions_total` shows one
  freeze and one end, so each task ran once. Smoke step 12 now checks that `market.frozen` is
  published exactly once.
- kubeconform: 35 objects valid (`-strict`). CI runs `scripts/k8s-check.sh`, which also checks that
  the embedded init scripts match `infra/` byte for byte.
- The compose smoke still passes all 26 steps.
Decisions and deferrals:
- Traefik (built into k3s) instead of ingress-nginx, which was retired in March 2026.
- Postgres init runs from `/docker-entrypoint-initdb.d`, not a separate Job. kafka-init is a Job.
- One Ingress rule (`/` → frontend). nginx already proxies `/api` and `/ws` to the gateway.
- No observability stack in Kubernetes. Secrets are dev values in the manifest.
- user-service stays at 1 replica until its JWT key comes from a Secret.

## Extra (2026-10-09): user-created markets and a new look
- Any logged-in user may create markets, with at most 3 active (OPEN, FROZEN or ENDED) at a time.
  A 4th gets 409 `MARKET_LIMIT_REACHED`. Admins have no limit. A per-user advisory lock stops
  parallel creates from passing the limit. Only admins freeze, resolve, update or cancel.
- SPA restyle that follows the system light/dark setting. It has market cards with a TRUE/FALSE
  pool bar, TRUE/FALSE toggle buttons with quick amounts, a leaderboard podium and glass panels.
  The "New market" page (`#/markets/new`) is open to every logged-in user.
- Smoke step 8 now checks: a user creates 3 markets, the 4th is rejected, and a user cannot freeze.

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
