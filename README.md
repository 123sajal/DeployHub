# DeployHub

A learning-grade deployment platform inspired by Render, Railway and Vercel, built milestone by milestone
to demonstrate backend engineering and system design.

> **Current state: Milestone 6 (real builds) complete.** Deployments clone a **public** repository (read-only),
> build it with **Docker** and run it in a resource-limited container on this machine (`http://localhost:<port>`).
> Nothing is deployed to the internet yet (that is Milestone 7). Sign up at http://localhost:4200/signup; every user sees only their own data.

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

If your backend runs on another port, tell the proxy: `API_PORT=8081 npm start`.

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
cd ~/Desktop/DeployHub/deployhub-backend && ./mvnw test                # 312 tests on H2 (no Docker needed; uses the simulator)
cd ~/Desktop/DeployHub/deployhub-backend && ./mvnw test -Poracle-it     # 140 tests on real Oracle Free (needs Docker running)
cd ~/Desktop/DeployHub/deployhub-backend && ./mvnw test -Pdocker-it     # 7 tests: real git, build/run, address proxy, MySQL isolation
cd ~/Desktop/DeployHub/deployhub-frontend && npx ng test --watch=false   # 141 Vitest tests
cd ~/Desktop/DeployHub/deployhub-frontend && npx ng build
```

H2 tests alone don't prove Oracle compatibility. The `oracle-it` suite runs the API, lifecycle and restart tests
on real Oracle Database Free with Hibernate schema validation. Autonomous Database itself is verified by hand (see the guide).

### Load test: 100 users at once (k6)
```bash
./load-tests/start-test-backend.sh          # separate backend on :8081, in-memory DB, simulated deploys
k6 run load-tests/hundred-users.js          # in a second Terminal; -e USERS=200 -e HOLD=5m to change it
```
Each of 100 virtual users signs up, creates a project, deploys it, then keeps using the dashboard, projects,
deployments and logs for 2 minutes, with security checks (no login = 401, another user's project = 404).
Result on 2026-10-26 (this Mac): 71,991 requests, 0 errors, all checks passed; reads p95 1.7 ms, writes p95
119 ms, all 100 deployments LIVE within 4.4 s. It never touches your real DeployHub on port 8080.

### Load test: users deploy at once and each gets a live link (k6, real builds)
```bash
k6 run load-tests/live-links.js                  # 10 users, against your running DeployHub on :8080
k6 run -e USERS=100 load-tests/live-links.js     # 100 users (raise Docker Desktop memory first)
```
Each user signs up (or logs in on later runs), creates a project, deploys it for real, waits for LIVE, opens the
live link (expects HTTP 200), then deletes the project. Passes only if every user got a working link. Add
`-e KEEP=1` to leave the apps running. Options and limits are at the top of the file. With paid deploys
on, start the backend with `-Dspring-boot.run.arguments=--deployhub.billing.enabled=false` for this test (it
stops with that message otherwise).

## What is implemented, simulated, or planned

| Area | Status |
|------|--------|
| Landing page, responsive dashboard, projects, deployments, settings UI; light warm-orange theme by default with a dark mode switch (remembered per device) | ✅ Implemented (theme 2026-10-28) |
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
| Paid deploys: first 2 deployed projects free, other projects pay per deploy with Razorpay Checkout (402 → pay → deploy) | ✅ Implemented (test mode); see "Paid deploys" |
| Real deployments: read-only `git fetch` of a public repo, `docker build`, `docker run` with limits, HTTP health check, then LIVE | ✅ Milestone 6 (`deployhub.execution.mode=REAL`, the default) |
| Generated Dockerfiles for Java/Spring Boot, Node.js, static sites, Python; or the repository's own Dockerfile | ✅ Milestone 6 |
| Container isolation: memory/CPU/process limits, all capabilities dropped, no-new-privileges, unprivileged user, published on 127.0.0.1 only | ✅ Milestone 6 |
| Exact commit recorded per deployment; images reused for the same commit + settings (instant rollback); old images pruned | ✅ Milestone 6 |
| Cancel stops a running build within ~2 s; long builds keep their lease via a background heartbeat | ✅ Milestone 6 |
| Simulator (no Docker): `deployhub.execution.mode=SIMULATED`. Build-command keywords: `fail` = permanent error, `flaky` = fails twice then succeeds, `unstable` = always fails → DEAD | ✅ Kept for tests and Docker-less runs |
| Live logs over Server-Sent Events (`/logs/stream`), resuming via `Last-Event-ID`; falls back to cursor polling | ✅ Milestone 5 |
| Rollback: `POST /api/deployments/{id}/rollback` queues a new deployment of an earlier (replaced) one | ✅ Milestone 5; exact commit since M6 |
| Metrics: queue depth, queue wait, throughput, retries, durations at `/actuator/prometheus`; last-hour panel on the dashboard | ✅ Milestone 5 |
| Liveness / readiness probes (`/actuator/health/liveness`, `/readiness` incl. database + worker), graceful shutdown | ✅ Milestone 5 |
| `oracle` profile (env/.env config, no fallback, schema validation, small pool) | ✅ Milestone 2 |
| Tests on real Oracle (Testcontainers) and data-survives-restart tests (H2 + Oracle) | ✅ Milestone 2 |
| Running against Oracle Autonomous Database in the cloud | ⏳ Needs your OCI account; steps in docs/oracle-setup.md |
| Sign-up, login, logout, `/api/auth/me` (Spring Security, server-side sessions, BCrypt) | ✅ Milestone 3 |
| CSRF protection, session-fixation defence, HttpOnly/SameSite cookies | ✅ Milestone 3 |
| Login throttling (per email+IP and per IP) and sign-up limit per IP | ✅ Milestone 3 |
| Audit log (`audit_events`: sign-ups, logins, failures, logouts, project/deployment actions) | ✅ Milestone 3 |
| AI assistant: sign-up as a conversation (email proven with a code, typo suggestions), "Ask the assistant" chat, pop-up explanations of failed deployments, missing settings, failed payments and refused imports | ✅ 2026-10-28 (ADR 0015). 🟡 Needs `GEMINI_API_KEY` (free tier, default) or `ANTHROPIC_API_KEY`; without one, built-in answers |
| Student discount: verify with a student ID photo (read by AI, never stored; not on Gemini's free tier) or a college email code; Rs 5 off each paid deploy | ✅ 2026-10-28 |
| Profile editing, password change | ⏳ Later |
| Environment variables per project: encrypted (AES-256-GCM), write-only, masked in logs, passed as `-e NAME` | ✅ Milestone 6 |
| Fixed address per project (`http://localhost:19xxx`) via a small nginx proxy; zero-downtime switch on redeploy | ✅ Milestone 6 |
| Routes: send a path (e.g. `/api`) of a project's address to another project's live app (no CORS needed) | ✅ Milestone 6 |
| One-click MySQL database per project on a shared server; connection details set as environment variables | ✅ Milestone 6. ⚠️ No backups yet |
| Public link per project (opt-in): an https link anyone can open, via a free Cloudflare quick tunnel; "Open app" uses it | ✅ Milestone 6. 🟡 Link changes when the tunnel restarts; works while this Mac is on |
| Forgot password: a 6-digit code by email (Gmail SMTP), then a new password | ✅ 2026-10-26. 🟡 Without Gmail settings the code is written to the backend log |
| Private repositories, build on push (webhooks) | ⏳ Later (only with your explicit go-ahead: they change things on GitHub) |
| OCI deployment, HTTPS | ⏳ Milestone 7 |

## API (as of Milestone 5)

| Method | Path | Notes |
|--------|------|-------|
| POST | `/api/auth/register` | Public. `{displayName, email, password}` → 201 + session. 409 if the email exists |
| POST | `/api/auth/login` | Public. `{email, password}` → 200 + new session. 401 generic message, 429 when throttled, 503 if DB down |
| POST | `/api/auth/forgot-password` | Public. `{email}` → **202** always (never tells whether the account exists); emails a 6-digit code valid 10 min. 429 after 5 per email or 20 per IP per hour |
| POST | `/api/auth/reset-password` | Public. `{email, code, newPassword}` → 204. 400 with `errors.code` if the code is wrong, used, expired or out of its 5 guesses |
| POST | `/api/auth/logout` | Ends the session → 204, and hands out a fresh CSRF token so the user can log in again without reloading |
| GET | `/api/auth/csrf` | Public. 204 with a fresh `XSRF-TOKEN` cookie (used by the frontend if a write was rejected for a missing token) |
| GET | `/api/auth/me` | The logged-in user (with `occupation`, `emailVerified`, `studentVerifiedUntil`, `studentVerifiedBy`), or 401 |
| PATCH | `/api/auth/me` | `{"occupation": "STUDENT" or "PROFESSIONAL"}` |
| GET | `/api/assistant/status` | Public. `{aiAvailable, idCardCheck}`: key set and budget left; student photos can be checked |
| POST | `/api/assistant/signup/check` | Public. `{field: NAME or EMAIL, value}` → `{ok, value, message, suggestion}` (no AI, no cost) |
| POST | `/api/assistant/signup/email-code` | Public. `{email}` → 202 and a 6-digit code by email; 409 if the email has an account; 429 after 5 per email / 20 per IP per hour |
| POST | `/api/assistant/signup/email-code/verify` | Public. `{email, code}` → `{proof, expiresAt}`; send `emailProof` (and optional `occupation`) with `/api/auth/register` |
| POST | `/api/assistant/chat` | Public. `{page, messages: [{role: USER or ASSISTANT, text}]}` (≤ 12, last = USER) → `{text, source: AI or BUILT_IN, notice}` |
| POST | `/api/assistant/explain` | `{topic: DEPLOYMENT_FAILED (deploymentId) or MISSING_SETTINGS / PAYMENT_FAILED / IMPORT_REFUSED (errorText, projectId)}` → same reply; a deployment's explanation is saved (`saved: true` the next time) |
| POST | `/api/students/verify/id-card` | multipart `file` (JPG/PNG ≤ 5 MB) + `consent=true` → `{verified, validUntil, method, message}`; 400 `errors.file` with what to fix; 503 without the AI |
| POST | `/api/students/verify/college-email/code`, `/college-email` | `{email}` (must end in .ac.in/.edu/.edu.in) then `{email, code}` → verified for a year |
| GET | `/api/health` | Public. DB connectivity, active profile, execution mode |
| GET | `/api/projects?search=&appType=&page=&size=` | size ≤ 100 |
| POST/GET/PUT/DELETE | `/api/projects[/{id}]` | DELETE is 409 while a deployment is in progress |
| POST | `/api/projects/{id}/deployments` | **202 Accepted** with a `Location` header; optional body `{"commitRef": "..."}`. Optional `Idempotency-Key` header: a repeat returns the same deployment (200, `Idempotent-Replayed: true`); 429 when 5 are already active; **402** (with `price`, `currency`) when the project isn't free and you have no paid deploy credit |
| GET | `/api/deployments?projectId=&status=&page=&size=` | |
| GET | `/api/deployments/{id}` | |
| POST | `/api/deployments/{id}/cancel` | 409 if already final |
| GET | `/api/projects/{id}/env` | Variable names only (values are never returned) |
| PUT / DELETE | `/api/projects/{id}/env/{NAME}` | `{"value": "..."}`; encrypted before storing; applies at the next deployment. `PORT` is reserved |
| GET / POST / DELETE | `/api/projects/{id}/database` | One-click MySQL database: 204 if none; POST creates it and returns the password once (503 if Docker is down); DELETE drops it with all data |
| GET / POST / DELETE | `/api/projects/{id}/public-link` | Public internet link: POST turns it on (project must be live; 409 otherwise), DELETE turns it off |
| POST | `/api/projects/{id}/database/import` | multipart `file` (.sql, ≤ 50 MB) and `replace` (true = delete the current data first) → `{tablesBefore, tablesAfter, message}`; 409 when the database has tables and `replace` is false; 400 `errors.file` with MySQL's message |
| GET | `/api/projects/{id}/database/export` | the database as a `.sql` download |
| POST | `/api/projects/{id}/database/reset-password` | New password (returned once); DB_PASSWORD and DATABASE_URL updated |
| GET / PUT | `/api/projects/{id}/routes` | `{"routes":[{"pathPrefix":"/api","targetProjectId":12}]}` replaces all routes; the address updates at once |
| DELETE | `/api/deployments/{id}` | 204. Only FAILED, CANCELLED or SUPERSEDED (with its logs, history and job); 409 for the LIVE one or one in progress |
| POST | `/api/deployments/{id}/rollback` | **202**: a new deployment that redeploys `{id}`, which must be SUPERSEDED (was live, since replaced); 409 otherwise. Same `Idempotency-Key` and 429 rules as a deploy |
| GET | `/api/deployments/{id}/history` | Every status change (who/when/why/attempt) plus the queue job's attempts and next retry |
| GET | `/api/deployments/{id}/logs?after=<cursor>&limit=` | Returns `nextCursor`, `hasMore` and `deploymentStatus` |
| GET | `/api/deployments/{id}/logs/stream?after=<cursor>` | `text/event-stream`. Events: `log` (id = cursor), `status`, `end`. Honours `Last-Event-ID`. 429 beyond 5 streams per user |
| DELETE | `/api/projects/{id}/repository-token` | Forgets the access token of a private repository (`repositoryToken` on POST/PUT `/api/projects` sets it, write-only; the response only says `privateRepository: true`) |
| POST | `/api/projects/detect` | `{repositoryUrl, branch, rootDirectory}` → suggested `appType`, `buildCommand`, `startCommand`, `appFolders`, `notes` (nothing is saved). 429 after 20 per minute |
| GET | `/api/projects/{id}/env/requirements` | The variables the app needs (from its repository), each with `kind`, `status` (SET/PROVIDED/MISSING), `required`, `help`; plus `autoFillable`, `databaseSuggested` |
| POST | `/api/projects/{id}/env/requirements/fill` | `{names:[...]}`: fills those missing variables where DeployHub can (generated secrets, example values, database values); never overwrites a set value |
| GET | `/api/billing` | Your paid-deploy credits, the price (paise), free projects per account and how many are left (`freeProjects`, `freeProjectsLeft`), whether payments are set up |
| POST | `/api/billing/orders` | **201**: a Razorpay order for one deploy (`orderId`, public `keyId`, `amount`). 503 without Razorpay keys, 429 after 20 per hour |
| POST | `/api/billing/orders/{orderId}/verify` | `{paymentId, signature}` from Checkout → 200 with the new credit count. 400 if the signature or Razorpay's record doesn't match; repeats are harmless |
| GET | `/api/dashboard/summary` | Counts and recent deployments |
| GET | `/api/dashboard/metrics` | Last hour: your queue wait (avg/max), finished/succeeded/failed, success rate; platform-wide queue depth |
| GET | `/actuator/health/liveness`, `/actuator/health/readiness` | Public, `UP`/`DOWN` only. Readiness checks the database and this process's worker |
| GET | `/actuator/prometheus` | Prometheus metrics; only from `deployhub.metrics.allowed-ips` (default: this machine) |

All endpoints except register, login, forgot/reset password and health need a logged-in session. Every POST/PUT/DELETE must send
the `X-XSRF-TOKEN` header matching the `XSRF-TOKEN` cookie (Angular does this automatically).

## Paid deploys (Razorpay)
> Switched off from 2026-10-27 to 2026-10-28 (`deployhub.billing.enabled=false`); **on again since 2026-10-28**.
> Deploys made while it was off didn't use up free projects.

- **Your first 2 projects are free** (`deployhub.billing.free-projects`), e.g. a backend and a frontend. The
  first two projects you deploy become free projects; deploying them again (after code changes) stays free.
  Deleting one doesn't give it back. Projects that existed before billing stay free.
- **Every other project costs ₹49 per deploy** (`deployhub.billing.price-per-deploy`, in paise). Deploying one
  without a credit returns 402, and DeployHub opens the payment page `/app/projects/payment?project=<id>`:
  project, price, paid deploys left and **Pay ₹49 & deploy**, which opens Razorpay Checkout (UPI QR code, cards,
  netbanking on Razorpay's own page) and deploys as soon as the payment is confirmed.
- **One payment = one deploy credit**, usable on any of your paid projects. Rollbacks are free. A deploy that
  fails or is cancelled gives its credit back.
- **The server confirms every payment** before crediting it: Checkout's HMAC-SHA256 signature (made with the key
  secret, which never leaves the backend) and Razorpay's own record of the payment (right order, right amount,
  money captured). If the browser closes after paying, the payment is picked up the next time the page loads.
- **Setup:** put your Razorpay test keys in `deployhub-backend/.env` (`RAZORPAY_KEY_ID=rzp_test_…`,
  `RAZORPAY_KEY_SECRET=…`) and restart the backend. In test mode, choose **Netbanking**, any bank, then **Success**
  on Razorpay's test bank page, or use a card from razorpay.com/docs/payments/payments/test-card-details. (On a
  computer Razorpay shows UPI only as a QR code; typing a UPI ID is being phased out in India.) Switching to live money
  later is only a key change, after Razorpay approves your KYC.
- **Not done:** refunds, invoices/GST, and Razorpay webhooks (they need a public URL; the page-load check covers a
  missed confirmation instead). See ADR 0010.

## Authentication in one minute
- **Server-side sessions.** After login the browser holds only an HttpOnly `JSESSIONID` cookie. Nothing
  sensitive is in `localStorage`, and logout destroys the session on the server immediately.
- **Passwords:** 10-64 characters, stored as BCrypt hashes (`{bcrypt}$2a$…`). Never logged or returned.
- **Emails** are trimmed and lower-cased, so `Bob@X.com` and `bob@x.com` are the same account (enforced by a DB constraint too).
- **Brute force:** 5 failed logins per email+IP, or 30 per IP, within 15 minutes → HTTP 429. Counted in the database, so limits survive restarts.
  A password reset clears that account's lockout.
- **Forgot password:** "Forgot password?" on the login page → a 6-digit code by email → new password. Only a BCrypt
  hash of the code is stored (table `password_reset_codes`); it works once, for 10 minutes, with 5 guesses; a new
  code replaces the old one. The answer is the same for unknown emails, and the email is sent in the background,
  so neither the reply nor its timing reveals who has an account. See ADR 0009.

### Environment variables
| Variable | Default | Purpose |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | none | Oracle connection (`oracle` profile only), via `deployhub-backend/.env` |
| `MAIL_USERNAME` | empty | Gmail address that sends reset codes. Empty = no email; the code is written to the backend log (development only) |
| `MAIL_PASSWORD` | empty | A Google **App password** for that address (Google Account → Security → 2-Step Verification → App passwords), not the normal Gmail password |
| `MAIL_FROM`, `MAIL_HOST`, `MAIL_PORT` | address, `smtp.gmail.com`, `587` | Optional: another sender name/address or SMTP server |
| `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` | empty | Razorpay API keys for paid deploys (Dashboard → Account & Settings → API Keys). `rzp_test_…` = test mode, no real money. Empty = the free project still works; paid deploys are refused with a message |
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

### Private repositories (access token)
Tick **This repository is private** on the project form and paste a read-only access token; the form links to
where each host makes one:
- **GitHub:** a fine-grained token (Settings → Developer settings → Fine-grained tokens) for *only this repository*
  with *Contents: Read-only*.
- **GitLab:** a personal access token with the scope `read_repository`.
- **Bitbucket:** the repository's *Repository settings → Access tokens*, *Repositories: Read*.

DeployHub tries the token against the repository before saving it (a wrong or too-narrow token is reported on the
form), stores it encrypted (AES-256-GCM with `DEPLOYHUB_SECRETS_KEY`, like environment variables), never returns or
logs it, and uses it only to read that repository: deploys, Detect from repository and "What this app needs". git gets
it as an `Authorization` header in its environment (`GIT_CONFIG_*`), sent only to that repository's host, never on the
command line. Changing the repository URL forgets the token; **Remove token** on the Edit page does too. See ADR 0013.

### Detect from repository (the right commands, automatically)
On a new project, as soon as the repository URL is filled in, DeployHub reads the repository's files and fills in
the application type and commands that fit **that** project; **Detect from repository** re-runs it (New and Edit).
It explains what it found, e.g. "Runs db:setup before starting, so the database tables exist". When the main folder
has no app it lists the folders that do (`backend`, `frontend`), and one click picks one.

| It looks at | And decides |
|---|---|
| `package.json` dependencies and scripts | Angular / Vite / React / Next / Node backend; `npm run build` only if a `build` script exists; database scripts (`db:setup`, `migrate`, ...) run before start |
| lock files | `(npm ci \|\| npm install)` with package-lock.json (DeployHub also rewrites a plain `npm ci` in any build command this way) (falls back by itself when the lock file was made by another npm version), the same for yarn/pnpm, or `npm install` |
| `angular.json` | the exact output folder, e.g. `dist/ems-frontend/browser` |
| `pom.xml` / `build.gradle` + wrappers | Maven/Gradle commands; warns when the wrapper is missing |
| `requirements.txt`, `manage.py`, `main.py`/`app.py` | Django / FastAPI / Flask start commands; warns about missing gunicorn/uvicorn |
| `Dockerfile` | the Dockerfile type, or a note when another type was found too |

It reads the repository with git (the same hardened, read-only fetch as deploys: only the file list plus the few
files it needs), so it works for GitHub, GitLab and Bitbucket without tokens or rate limits. Limited to 20 checks
per user per minute. Users can still change every command before saving. See ADR 0011.

### What this app needs (environment variables, filled in for you)
The project page reads the repository and lists the environment variables the app uses, so users don't have to
know them: from `deployhub.json` (if the author wrote one), `.env.example` (in the App folder or the repository's
main folder) and the code (`process.env.X`, `${X}` in Spring config, `os.environ["X"]`).

| Kind | Example | What DeployHub does |
|---|---|---|
| Secret of the app | `JWT_SECRET`, `SESSION_SECRET`, `DEPLOYHUB_SECRETS_KEY` | **Fill in automatically** generates a strong random value (nobody needs to see it) |
| Setting with an example | `JWT_EXPIRES_IN=24h`, `NODE_ENV` | fills in the example value (`production` for NODE_ENV) |
| Database | `DB_HOST`, `DATABASE_URL` | points to **Add MySQL database**, which sets them |
| Database, other name | `DB_USERNAME`, `MYSQL_PASSWORD` | copies the database's value once it exists |
| Set by DeployHub | `PORT` | nothing to do |
| Key from another service | `STRIPE_SECRET_KEY`, `SMTP_PASSWORD` | explains that only the user can get it |
| Laptop-only example | `CORS_ORIGIN=http://localhost:4200` | warns; usually leave it out when the frontend uses an `/api` route |

**Deploy** checks first: when required variables are missing it offers **Fill in & deploy** (or says what only the
user can add, with **Deploy anyway**). And when an app still stops at start, the failure message names the variable
it complained about (e.g. "JWT_SECRET is not defined", Spring's "Could not resolve placeholder 'DB_URL'").

**`deployhub.json`** (optional, in the App folder) lets an app's author describe exactly what it needs:
```json
{ "env": { "JWT_SECRET": { "generate": "secret" }, "JWT_EXPIRES_IN": { "value": "24h" },
           "SMTP_PASSWORD": { "required": false, "description": "Only for emails" } },
  "database": "mysql" }
```
`deployhub-backend/deployhub.json` in this repository does that for running a demo copy of DeployHub on DeployHub
(H2 database, simulated deploys, generated encryption key). See ADR 0012.

### Suggested commands per application type
Picking an **Application type** on the project form (New or Edit) fills in that type's usual commands; switching
type switches them. Commands you wrote yourself are kept, with a **Use the suggested … commands** button.

| Type | Build | Start |
|---|---|---|
| Node.js | `npm install` | `npm start` |
| Static site | `npm install && npm run build` | serves the first of `dist/*/browser` (Angular), `dist` (Vite/Vue), `build` (React), `out` (Next export) |
| Java / Spring Boot | `./mvnw -B -DskipTests package` | `java -jar target/*.jar` |
| Python | `pip install --no-cache-dir -r requirements.txt` | `gunicorn -b 0.0.0.0:$PORT app:app` |
| Dockerfile | (none) | (the Dockerfile's CMD) |

Apps with an extra step still need it added, e.g. Employee Management's backend: `npm run db:setup && npm start`.

### App folder (repositories with backend/ and frontend/ folders)
When your app's files are in a folder of the repository, type that folder in **App folder** on the project form
(e.g. `backend` or `frontend`). DeployHub copies the whole repository, then runs the build and start commands
inside that folder, so plain commands work: `npm ci` and `npm start`, no `cd backend &&`. A missing folder fails the
deployment straight away with a clear message. For Dockerfile projects, the folder's own `Dockerfile` is built with
that folder as its context. Empty = the repository's main folder (as before).

### AI assistant and student discount

- **Provider:** `deployhub.assistant.provider=gemini` (default, Google, free tier) or `claude` (Anthropic, paid).
- **Gemini (free):** create a key at aistudio.google.com (Get API key), put `GEMINI_API_KEY=...` in
  `deployhub-backend/.env`, restart the backend. Models: `gemini-3.5-flash-lite` (chat, short explanations) and
  `gemini-3.8-flash` (failed deployments). On the free tier calls cost nothing, only the per-user limits apply
  (Google's own free limits too: when they are reached Gemini answers 429 and DeployHub gives built-in answers), and
  Google may use what it receives to improve its products, so **student ID photos are not sent**: students verify
  with a college email. If you add billing to the Google project, set `deployhub.assistant.gemini.free-tier=false`
  (photo check on, cost counted against `daily-budget-inr`). For testing only, `deployhub.assistant.gemini.id-cards-on-free-tier=true`
  (e.g. in your local `.env`) sends photos on the free tier too; use your own or a sample card.
- **Claude:** `provider=claude` and `ANTHROPIC_API_KEY` (console.anthropic.com; a Claude subscription can't be used).
  Models `claude-haiku-5-5` / `claude-opus-5-5`; the Rs 20/day cap applies.
- Without a key the assistant gives built-in answers; everything else works.
- Secrets (env var values, tokens, `NAME=secret` lines) are masked before anything goes to the AI; passwords never do.
  Chats aren't stored. A student ID photo (paid tiers only) is checked once and never stored.
- `/signup` is the assistant; the plain form is at `/signup/form`. Students: Settings > Student discount.
- Details, risks and costs: docs/ai-assistant.pdf and docs/adr/0015-ai-assistant.md.

### Import and export a project's database
On the project's **Database** card, under *Your data*:
- **Import data:** choose a `.sql` export of a database on your computer (`mysqldump -u root -p mydb > mydb.sql`, or
  Export in MySQL Workbench / phpMyAdmin), up to 50 MB. DeployHub first leaves out the lines that only work on the
  computer the file came from (`CREATE DATABASE` / `USE`, `DEFINER=...`, server settings), then runs it as the
  project's own database user, so the file can't touch any other database and its views/triggers keep working.
  A database that already has tables is only overwritten with **Delete the current data first** ticked.
  MySQL's own error is shown if the file doesn't run.
- **Export data:** downloads the whole database as a `.sql` file (`p12_shop-2026-10-28.sql`): a backup, or to
  move it elsewhere.

One import or export per project at a time; both are recorded in the audit log. See ADR 0014.

### Deploying an app that has a frontend, a backend and a database
1. **Database:** on the backend project's page, click **Add MySQL database**. DeployHub creates it on its shared
   MySQL server and sets `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` and `DATABASE_URL`. The password
   is shown once; create your tables from your Mac on `127.0.0.1:3307` (or in the app's start command).
2. **Backend project:** add any other environment variables (e.g. `JWT_SECRET`), then deploy. If its code is in a subfolder, set **App folder** to `backend`; then build `npm ci`, start `npm start`.
3. **Frontend project** (type Static site): add the route `/api` → the backend project, and make the frontend call
   `/api/...` (a relative URL). Deploy and open the frontend's address: `/api` requests reach the backend, no CORS.
4. Every project keeps its address (`http://localhost:19xxx`) across redeploys and rollbacks.
5. **Share it:** on the frontend project, **Make public** gives an https link anyone can open. Keep the backend
   private: it is reached through the frontend's `/api` route.

`DEPLOYHUB_SECRETS_KEY` in `deployhub-backend/.env` encrypts the variables. Never change or lose it.

### Real builds: what to know
- **Docker Desktop must be running.** If it isn't, deployments retry and then fail with "Docker is not running".
- **Private repositories need the project's own access token.** git runs with every credential source switched off
  (no keychain, no IDE askpass), so it never uses your GitHub login; a private repository without a token fails with
  "Repository not found or not public" (see Private repositories).
- **Your app must listen on `0.0.0.0` and the port in `$PORT`.** Set the project's port if it isn't the type's
  default (Java 8080, Node/static 3000, Python 8000, Dockerfile 8080). The health check path must answer below HTTP 500.
- **Where it runs:** each LIVE deployment gets its own port between 20000 and 20999 on `127.0.0.1`; the project page
  has an "Open app" link. Replaced deployments' containers are stopped. Containers restart with Docker Desktop.
- **Resources:** 512 MB memory, 1 CPU and 256 processes per app (`deployhub.execution.container-*`). Docker Desktop on
  this Mac has about 4 GB in total, shared with Oracle, so keep only a few apps live at once.
- **Clean up by hand** (if ever needed): `docker ps --filter label=deployhub.project` lists DeployHub's containers,
  `docker image ls 'deployhub/*'` its images.

### Known limitations
- A password reset doesn't end sessions that are already logged in on other devices; they end at logout or after
  30 minutes without activity.
- Each open log stream checks the database for new lines twice a second (workers may run in other processes, so
  the database is the only place that sees every line). That is cheap at this scale; limits (5 per user, 100 per
  process) protect the connection pool. Thousands of viewers would need a push channel (e.g. Oracle AQ or Redis).
- A rollback reuses the stored image when the commit and settings are unchanged; otherwise it rebuilds that exact
  commit with the project's *current* commands. Only the images of the last 3 successful deployments are kept.
- A LIVE app is not watched after its health check: if it crashes later, Docker restarts it, but DeployHub still
  shows LIVE.
- Files an app writes (e.g. uploads) are lost when it is redeployed; keep data in a database.
- Environment variables are available when the app runs, not during its build.
- Builds have a time limit (15 min) but no memory limit of their own (Docker's build engine doesn't support one).
- Workers of the same deployment share a container name; if a worker is presumed dead but still running, the
  takeover can remove its container. Leases make this rare; the deployment then retries.
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
[0005 observability and rollback](docs/adr/0005-observability-and-rollback.md),
[0006 real Docker builds](docs/adr/0006-real-docker-builds.md),
[0007 environment variables and project addresses](docs/adr/0007-env-vars-and-project-addresses.md),
[0008 one-click databases](docs/adr/0008-one-click-databases.md).
