# DeployHub

A learning-grade deployment platform inspired by Render, Railway and Vercel, built milestone by milestone
to demonstrate backend engineering and system design.

> **Current state: Milestone 5 (live log streaming, metrics, health probes, rollback) complete.** Deployments are
> **simulated**: no repository is cloned and no code is built or executed yet. Sign up at http://localhost:4200/signup; every user sees only their own data.

## Repository layout

```
DeployHub/
├── deployhub-backend/    Spring Boot 4.1 · Java 21 · Maven · Flyway · H2 (local) or Oracle
├── deployhub-frontend/   Angular 21 (standalone, zoneless, signals) · PrimeNG 21 · SCSS
├── docs/adr/             Architecture decision records
└── docs/oracle-setup.md  Local Oracle (Docker) and Oracle Autonomous Database setup
```

## Prerequisites (verified versions)

| Tool    | Version used | Notes |
|---------|--------------|-------|
| JDK     | 21 (Temurin/Oracle) | `./mvnw` uses `JAVA_HOME` / `java` on PATH. Homebrew `mvn` runs on JDK 25, so use `./mvnw`. |
| Node.js | 24.11.1 | Angular 21 supports Node ≥ 24.0. Angular 22 would need Node ≥ 24.15. |
| npm     | 11.6.2 | |

## Run it locally

Terminal 1, backend (port 8080):

```bash
cd ~/Desktop/DeployHub/deployhub-backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Terminal 2, frontend (port 4200; proxies `/api` to 8080):

```bash
cd ~/Desktop/DeployHub/deployhub-frontend
npm install        # first time only
npm start
```

Open http://localhost:4200. API docs: http://localhost:8080/swagger-ui/index.html.
H2 console (local only): http://localhost:8080/h2-console (JDBC URL `jdbc:h2:file:./data/deployhub`, user `sa`).

**A profile is required.** Started without one, the backend refuses to boot (`'url' must start with "jdbc"`).
That is deliberate: it never silently falls back to an in-memory database.

Reset local data: stop the backend and `rm -rf deployhub-backend/data`.

### Daily setup: Oracle in Docker (recommended)
```bash
# once per Mac start: Docker Desktop must be running
cd ~/Desktop/DeployHub/deployhub-backend
docker compose -f docker-compose.oracle.yml up -d        # start Oracle (data kept in a Docker volume)
./mvnw spring-boot:run -Dspring-boot.run.profiles=oracle  # backend on Oracle; reads .env
```
Then start the frontend (`npm start`) as above. Accounts, projects and deployments are stored in Oracle and survive
restarts of the app, of Oracle, and of your Mac. Never run `docker compose ... down -v`: `-v` deletes the data.

### Run on Oracle instead of H2
No cloud account needed: [docs/oracle-setup.md](docs/oracle-setup.md) shows how to run Oracle 23ai Free in Docker
(`docker compose -f docker-compose.oracle.yml up -d`), then start the backend with
`-Dspring-boot.run.profiles=oracle`. The same guide covers Oracle Autonomous Database (Always Free).

## Tests and builds

```bash
cd ~/Desktop/DeployHub/deployhub-backend && ./mvnw test                # 113 tests on H2 (no Docker needed)
cd ~/Desktop/DeployHub/deployhub-backend && ./mvnw test -Poracle-it     # 73 tests on real Oracle Free (needs Docker running)
cd ~/Desktop/DeployHub/deployhub-frontend && npx ng test --watch=false   # 77 Vitest tests
cd ~/Desktop/DeployHub/deployhub-frontend && npx ng build
```

H2 tests alone don't prove Oracle compatibility. The `oracle-it` suite runs the API, lifecycle and restart tests
on real Oracle Database Free with Hibernate schema validation. Autonomous Database itself is verified by hand (see the guide).

## What is implemented, simulated, or planned

| Area | Status |
|------|--------|
| Landing page, responsive dark dashboard, projects, deployments, settings UI | ✅ Implemented |
| Project CRUD, search, app-type filter, pagination, validation | ✅ Implemented |
| Repository URL allowlist (SSRF guard: https only, github/gitlab/bitbucket, no credentials/ports) | ✅ Implemented |
| Deployment state machine `QUEUED → BUILDING → STARTING → LIVE → SUPERSEDED`, `FAILED`/`CANCELLED` terminal | ✅ Implemented and tested |
| Optimistic locking (`@Version`), so a stale worker cannot overwrite a cancel | ✅ Implemented and tested |
| Cancel eligible deployments (409 otherwise) | ✅ Implemented |
| Persistent logs, cursor (keyset) pagination, secret masking before storage | ✅ Implemented |
| RFC 9457 problem-detail errors, OpenAPI docs, `/api/health` (503 when DB down) | ✅ Implemented |
| Per-user data isolation (every query scoped to the logged-in user; others' ids return 404) | ✅ Milestone 3, tested with two real accounts on H2 and Oracle |
| Durable job queue in Oracle (`deployment_jobs`), created in the same transaction as the deployment | ✅ Milestone 4 |
| Independent workers: atomic claiming, 30 s leases + heartbeats, crash recovery, at-least-once execution | ✅ Milestone 4 |
| Retries with exponential backoff + jitter, max 3 attempts, DEAD jobs ("dead letter") kept for inspection | ✅ Milestone 4 |
| `Idempotency-Key` on deployment creation (DB-enforced; same key + different request → 422) | ✅ Milestone 4 |
| Backpressure: per-worker concurrency limit, max 5 in-progress deployments per user (429) | ✅ Milestone 4 |
| Deployment status history (who/when/why/attempt) in API and UI | ✅ Milestone 4 |
| Deployment execution | 🟡 **Simulated** steps (3 s each). Build-command keywords: `fail` = permanent error, `flaky` = fails twice then succeeds, `unstable` = always fails → DEAD. |
| Health check before LIVE | 🟡 Simulated log line only |
| Live logs over Server-Sent Events (`/logs/stream`), resuming via `Last-Event-ID`; falls back to cursor polling | ✅ Milestone 5 |
| Rollback: `POST /api/deployments/{id}/rollback` queues a new deployment of an earlier (replaced) one | ✅ Milestone 5. 🟡 Without a `commitRef` the code version isn't pinned yet (real commit SHAs arrive in M6) |
| Metrics: queue depth, queue wait, throughput, retries, durations at `/actuator/prometheus`; last-hour panel on the dashboard | ✅ Milestone 5 |
| Liveness / readiness probes (`/actuator/health/liveness`, `/readiness` incl. database + worker), graceful shutdown | ✅ Milestone 5 |
| `oracle` profile (env/.env config, no fallback, schema validation, small pool) | ✅ Milestone 2 |
| Tests on real Oracle (Testcontainers) and data-survives-restart tests (H2 + Oracle) | ✅ Milestone 2 |
| Running against Oracle Autonomous Database in the cloud | ⏳ Needs your OCI account; steps in docs/oracle-setup.md |
| Sign-up, login, logout, `/api/auth/me` (Spring Security, server-side sessions, BCrypt) | ✅ Milestone 3 |
| CSRF protection, session-fixation defence, HttpOnly/SameSite cookies | ✅ Milestone 3 |
| Login throttling (per email+IP and per IP) and sign-up limit per IP | ✅ Milestone 3 |
| Audit log (`audit_events`: sign-ups, logins, failures, logouts, project/deployment actions) | ✅ Milestone 3 |
| Profile editing, password change/reset, email verification | ⏳ Later |
| GitHub integration, isolated Docker builds | ⏳ Milestone 6 |
| OCI deployment, HTTPS | ⏳ Milestone 7 |

## API (as of Milestone 5)

| Method | Path | Notes |
|--------|------|-------|
| POST | `/api/auth/register` | Public. `{displayName, email, password}` → 201 + session. 409 if the email exists |
| POST | `/api/auth/login` | Public. `{email, password}` → 200 + new session. 401 generic message, 429 when throttled, 503 if DB down |
| POST | `/api/auth/logout` | Ends the session → 204, and hands out a fresh CSRF token so the user can log in again without reloading |
| GET | `/api/auth/csrf` | Public. 204 with a fresh `XSRF-TOKEN` cookie (used by the frontend if a write was rejected for a missing token) |
| GET | `/api/auth/me` | The logged-in user, or 401 |
| GET | `/api/health` | Public. DB connectivity, active profile, execution mode |
| GET | `/api/projects?search=&appType=&page=&size=` | size ≤ 100 |
| POST/GET/PUT/DELETE | `/api/projects[/{id}]` | DELETE is 409 while a deployment is in progress |
| POST | `/api/projects/{id}/deployments` | **202 Accepted** with a `Location` header; optional body `{"commitRef": "..."}`. Optional `Idempotency-Key` header: a repeat returns the same deployment (200, `Idempotent-Replayed: true`); 429 when 5 are already active |
| GET | `/api/deployments?projectId=&status=&page=&size=` | |
| GET | `/api/deployments/{id}` | |
| POST | `/api/deployments/{id}/cancel` | 409 if already final |
| POST | `/api/deployments/{id}/rollback` | **202**: a new deployment that redeploys `{id}`, which must be SUPERSEDED (was live, since replaced); 409 otherwise. Same `Idempotency-Key` and 429 rules as a deploy |
| GET | `/api/deployments/{id}/history` | Every status change (who/when/why/attempt) plus the queue job's attempts and next retry |
| GET | `/api/deployments/{id}/logs?after=<cursor>&limit=` | Returns `nextCursor`, `hasMore` and `deploymentStatus` |
| GET | `/api/deployments/{id}/logs/stream?after=<cursor>` | `text/event-stream`. Events: `log` (id = cursor), `status`, `end`. Honours `Last-Event-ID`. 429 beyond 5 streams per user |
| GET | `/api/dashboard/summary` | Counts and recent deployments |
| GET | `/api/dashboard/metrics` | Last hour: your queue wait (avg/max), finished/succeeded/failed, success rate; platform-wide queue depth |
| GET | `/actuator/health/liveness`, `/actuator/health/readiness` | Public, `UP`/`DOWN` only. Readiness checks the database and this process's worker |
| GET | `/actuator/prometheus` | Prometheus metrics; only from `deployhub.metrics.allowed-ips` (default: this machine) |

All endpoints except register, login and health need a logged-in session. Every POST/PUT/DELETE must send
the `X-XSRF-TOKEN` header matching the `XSRF-TOKEN` cookie (Angular does this automatically).

## Authentication in one minute
- **Server-side sessions.** After login the browser holds only an HttpOnly `JSESSIONID` cookie. Nothing
  sensitive is in `localStorage`, and logout destroys the session on the server immediately.
- **Passwords:** 10-64 characters, stored as BCrypt hashes (`{bcrypt}$2a$…`). Never logged or returned.
- **Emails** are trimmed and lower-cased, so `Bob@X.com` and `bob@x.com` are the same account (enforced by a DB constraint too).
- **Brute force:** 5 failed logins per email+IP, or 30 per IP, within 15 minutes → HTTP 429. Counted in the database, so limits survive restarts.

### Environment variables
| Variable | Default | Purpose |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | none | Oracle connection (`oracle` profile only), via `deployhub-backend/.env` |
| `SESSION_COOKIE_SECURE` | `false` | Set to `true` whenever DeployHub is served over HTTPS (always in the cloud) |

### Running workers
Every backend process runs a worker by default (2 concurrent jobs). To add capacity, start extra
worker-only processes against the same database; they coordinate through the queue table:
```bash
cd ~/Desktop/DeployHub/deployhub-backend
java -jar target/deployhub-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=oracle \
     --spring.main.web-application-type=none --deployhub.worker.id=worker-2
```
Worker settings are the `deployhub.worker.*` properties in `application.properties`.

### Watching metrics locally
```bash
curl -s localhost:8080/actuator/prometheus | grep deployhub_
```
`deployhub_queue_jobs{state=waiting|retry_scheduled|running|dead}` (platform-wide, read from the database every 10 s),
`deployhub_queue_wait_seconds` (time until a worker claimed a job), `deployhub_jobs_finished_total{outcome}` (its rate
is the throughput), `deployhub_jobs_retries_total`, `deployhub_deployments_duration_seconds{status}`. Counters and timers
are per process and start from zero on restart; Prometheus adds them up across processes.

### Known limitations
- Each open log stream checks the database for new lines twice a second (workers may run in other processes, so
  the database is the only place that sees every line). That is cheap at this scale; limits (5 per user, 100 per
  process) protect the connection pool. Thousands of viewers would need a push channel (e.g. Oracle AQ or Redis).
- A rollback re-runs the project's *current* build and start commands; there are no stored build artifacts yet.
- Only the newest deployment of a project can go LIVE. If an older one finishes after a newer one is already
  live (e.g. it needed retries), it is marked CANCELLED ("Skipped: newer deployment #N is already live").
- Job execution is **at-least-once**: after a crash a step may run again (steps are written to be safe to repeat).
- The per-user limit of 5 is checked without a lock, so a burst of simultaneous requests can briefly exceed it.
- Idempotency keys are kept forever (no expiry job yet).
- Sessions live in the backend's memory: restarting the backend logs everyone out, and running several
  backend instances would need shared sessions (Spring Session JDBC), planned for M7.
- A disabled account keeps an already-open session until it expires (30 min idle).
- Sign-up reveals whether an email is registered (409). There is no email verification yet, so this can't be
  hidden; the per-IP sign-up limit makes bulk probing slow.
- Client IP for rate limits is the direct TCP peer. Behind a reverse proxy (M7) forwarded headers must be configured.
- Data created in Milestones 1-2 belongs to the old passwordless `dev@deployhub.local` user, which can't log in.
  To start clean locally: stop the backend and `rm -rf deployhub-backend/data`.

Decision records: [0001 foundations](docs/adr/0001-milestone-1-foundations.md),
[0002 Oracle persistence](docs/adr/0002-oracle-persistence.md),
[0003 authentication](docs/adr/0003-authentication-and-tenant-isolation.md),
[0004 durable deployment jobs](docs/adr/0004-durable-deployment-jobs.md),
[0005 observability and rollback](docs/adr/0005-observability-and-rollback.md).
