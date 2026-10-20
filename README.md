# Bet Market

A pari-mutuel betting exchange built as event-driven microservices. Users bet tokens (not money)
on TRUE/FALSE markets. When a market resolves, the winners share the pool minus a house edge.

- Backend: 9 Spring Boot 4 services plus a load generator (Kotlin, JDK 21), Kafka, PostgreSQL, MongoDB, Redis.
- Frontend: React 19 SPA with live updates over STOMP/WebSocket.
- Observability: Prometheus, Grafana, JSON logs.
- Design: [architecture.md](architecture.md). Plan and status: [ROADMAP.md](ROADMAP.md).

## Starting options

| Option | Command | Open | Use it for |
|---|---|---|---|
| 1. Full stack (Docker Compose) | `docker compose up -d --build --wait` | http://localhost | Normal use, demos, the smoke test |
| 2. Full stack + load | option 1, then `docker compose --profile load up -d load-generator` | http://localhost:3000 | Load and dashboards |
| 3. Kubernetes (k3d) | `scripts/k8s-up.sh` | http://localhost:8000 | Checking the Kubernetes manifest |
| 4. Frontend dev server | option 1, then `cd frontend && npm run dev` | http://localhost:5173 | Changing the SPA with hot reload |

Options 1 and 3 can run at the same time. They use different ports and separate data.

### Prerequisites

| Tool | Needed for |
|---|---|
| Docker (Desktop) with Compose v2 | all options |
| Node.js 24 LTS or 26 | option 4, frontend tests, smoke step 21 (Node ≥ 22 there) |
| JDK 21 | building and testing the backend outside Docker (`./gradlew`) |
| k3d 5.9+ and kubectl | option 3 |

Docker builds the services itself, so options 1–3 do not need a local JDK.
If another Postgres holds port 5432, start with `PG_PORT=55432 docker compose up -d --build --wait`.
Node 23 does not work: Vitest 5 and jsdom 30 reject it (see [the caveat](docs/caveats/frontend-toolchain.md)).

### Option 1: full stack with Docker Compose

```bash
docker compose up -d --build --wait
```

The first build takes several minutes. `--wait` returns when every container is healthy.

| URL | What |
|---|---|
| http://localhost | The SPA (nginx). It proxies `/api` and `/ws` to the gateway. |
| http://localhost:8080 | API gateway (REST and `/ws`) |
| http://localhost:3000 | Grafana: opens on "Platform overview"; "Betting funnel" is under Dashboards (no login) |
| http://localhost:9090 | Prometheus: targets, queries, alerts |

Infrastructure ports on the host: Postgres 5432 (`PG_PORT`), Kafka 9092, Redis 6379, MongoDB 27017.
Debezium (Kafka Connect) sends the outbox events to Kafka. Check it with
`docker compose exec connect curl -s localhost:8083/connectors?expand=status`.
Upgrading from a stack without Debezium? Run `docker compose down -v` once: Postgres creates the replication slots only on an empty volume.

Log in as the seeded admin: `admin@bet-market.local` / `admin-password` (local dev values).
Or register a new user on the Log in page. Every new user gets 1000 tokens.

- Any logged-in user can create markets, with at most 3 active at a time.
- Only admins freeze, resolve or cancel markets.
- The upper-right switch toggles light and dark mode.

Stop and clean up:

```bash
docker compose down        # keep the data volumes
docker compose down -v     # also delete the data
```

### Option 2: add load (10,000 simulated users)

```bash
docker compose --profile load up -d load-generator
```

- Bots sign up through the gateway, then bet (70%), browse (25%) or cancel a bet (5%).
- They pause 10–60 s between actions. Bot starts are spread over 10 minutes.
- A bot that runs out of tokens retires, and a new user takes its slot.
- Bots never create markets. Open some markets first, or the bots only browse.
- Watch it in Grafana: Betting funnel, panels "Load generator: actions/sec" and "Load generator: bots".

Change the load with environment variables:

```bash
LOAD_USERS=2000 LOAD_MIN_DELAY=5s LOAD_MAX_DELAY=20s LOAD_RAMP_UP=20m \
  docker compose --profile load up -d load-generator
```

| Variable | Default | Meaning |
|---|---|---|
| `LOAD_USERS` | 10000 | Number of bots |
| `LOAD_MIN_DELAY`, `LOAD_MAX_DELAY` | 10s, 60s | Random pause between two actions of one bot |
| `LOAD_RAMP_UP` | 10m | Time over which the bots start |

The gateway allows about 20 anonymous requests per second per IP. Sign-ups above that rate get
429 and retry, so a longer `LOAD_RAMP_UP` gives a smoother start.

Stop it with `docker compose stop load-generator`. The bots' users and bets stay in the database.

### Option 3: Kubernetes on a local k3d cluster

```bash
scripts/k8s-up.sh
```

The script runs these steps:
1. It creates the k3d cluster `bet-market`, with the Traefik ingress on host port 8000.
2. It builds the images with Docker Compose and imports them into the cluster.
3. It applies [kubernetes_manifest.yaml](kubernetes_manifest.yaml) and waits for every pod.
4. It runs the smoke test through the ingress.

The first deploy shows a few pod restarts. Services start before Postgres and Kafka are ready.

- Open http://localhost:8000.
- HPAs scale api-gateway, wallet, bet and query (1–3 replicas) and market (2–4) on CPU. Watch them: `kubectl -n bet-market get hpa`.
- Prometheus and Grafana run only in Docker Compose.
- Load generator: `kubectl -n bet-market scale deployment/load-generator --replicas=1`.

Settings: `CLUSTER` (default `bet-market`) and `PORT` (default `8000`), for example `PORT=8001 scripts/k8s-up.sh`.

Delete the cluster:

```bash
k3d cluster delete bet-market
```

Validate the manifest without a cluster:

```bash
scripts/k8s-check.sh
```

### Option 4: frontend dev server

Start option 1 first. The Vite dev server proxies `/api` and `/ws` to the gateway on `localhost:8080`.

```bash
cd frontend
npm ci
npm run dev
```

Open http://localhost:5173. Changes reload at once.

## Tests and checks

| Command | What it checks |
|---|---|
| `./gradlew build` | Backend unit and Testcontainers integration tests, ≥ 90% coverage per module (needs Docker and JDK 21) |
| `cd frontend && npm test` | SPA unit tests with coverage (Vitest) |
| `scripts/smoke.sh` | End-to-end run against the running Docker Compose stack (26 steps) |
| `scripts/rebuild-read-model.sh` | Drops the read model, replays Kafka, and proves the result is identical |
| `scripts/k8s-check.sh` | kubeconform validation of the manifest, a byte check of its embedded scripts, and the HPA rules |

Notes on `scripts/smoke.sh`:
- Stop the load generator first. Bots bet on the smoke market and change its payout. The script refuses to run while they are active.
- It needs `curl`, `python3` and Node ≥ 22.
- For Kubernetes, `scripts/k8s-up.sh` runs it with `K8S_NAMESPACE=bet-market`. It then skips the Docker-only monitoring steps.

## Configuration

Set these in the `environment` block of a service in [docker-compose.yml](docker-compose.yml), or in
the Kubernetes manifest.

| Variable | Service | Default | Meaning |
|---|---|---|---|
| `STARTING_TOKENS` | wallet-service | 1000 | Tokens for every new user |
| `HOUSE_EDGE_BPS` | settlement-service, query-service | 500 | House edge in basis points (500 = 5%) |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | user-service | `admin@bet-market.local`, `admin-password` | Seeded admin account |
| `RATE_LIMIT_REPLENISH`, `RATE_LIMIT_BURST` | api-gateway | 20, 40 | Requests per second and burst, per user or per IP |
| `DOWNSTREAM_TIMEOUT` | api-gateway | 5s | Timeout before the gateway answers 504 |
| `SCHEDULER_POLLING_INTERVAL` | market-service, settlement-service | 10s | db-scheduler polling interval |

## Repository layout

```text
services/        9 Spring Boot services + the load generator
shared/          events (versioned event classes) and common (outbox, idempotency, correlation)
frontend/        React SPA, nginx config, Dockerfile
infra/           Postgres init, Kafka topics, Prometheus rules, Grafana dashboards
scripts/         smoke test, read-model rebuild, k3d deploy, manifest checks
docs/caveats/    things that differ from older docs (Spring Boot 4, Kubernetes, Node)
```
