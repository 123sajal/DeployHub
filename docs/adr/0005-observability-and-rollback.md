# ADR 0005: Live log streaming, metrics, health probes and rollback

Status: accepted · Date: 2026-10-26

## Context
Until Milestone 4 the browser polled for new log lines every 1.5 s, nothing measured the queue, the only health
check was `/api/health`, and a bad release could only be fixed by deploying again. Milestone 7 puts DeployHub
behind a load balancer, which needs to know when an instance can take traffic.

## Decisions
1. **Live logs use Server-Sent Events**, not WebSockets. Logs only flow server → browser, SSE is plain HTTP
   (cookies, CSRF-free GET, proxies), and browsers reconnect by themselves. Each `log` event's `id` is the log row id,
   so after a dropped connection the browser sends `Last-Event-ID` and the stream continues with no gaps or
   duplicates, the same cursor the polling API already used. The client falls back to polling when SSE is
   unavailable or refused (e.g. too many open streams).
2. **Each stream reads the database on a 500 ms timer** instead of being pushed to from memory. Workers can run in
   separate processes, so an in-memory event bus would miss their lines; the database is the one place that sees
   every line. Each poll is one indexed query (`deployment_id, id`) on a pooled connection held only for that
   query. Limits (5 streams per user, 100 per process; 429 beyond) protect Oracle's 5-connection pool.
   *Trade-off:* cost grows with open streams. Thousands of viewers would need a push channel (Oracle AQ,
   Redis pub/sub) feeding the same events.
   The stream reads the deployment's status *before* its new lines; because a deployment's last lines commit with
   (or before) its final status, "final status and no new lines" really means the log is complete.
3. **Metrics via Micrometer, exported for Prometheus** at `/actuator/prometheus`:
   queue depth by state (a gauge read from the database every 10 s, so every process reports the same
   platform-wide value and a database outage can't break a scrape), queue wait (claim time − due time), finished
   jobs by outcome (throughput = their rate), retries, and request-to-final-state durations. Only networks in
   `deployhub.metrics.allowed-ips` (default: localhost) may read it, since metrics reveal load and internals.
4. **The dashboard shows per-user metrics** computed from the deployments table (last hour: average/max wait until a
   worker picked a deployment up, finished/succeeded/failed, success rate). Only queue depth is platform-wide: it
   explains waiting and exposes no other user's data. Tenant isolation from M3 is kept.
5. **Liveness ≠ readiness.** Liveness (`livenessState` only) says the process should be restarted if DOWN, so it
   excludes the database: restarting can't fix an outage and would only add restart storms. Readiness adds the
   database and a `worker` check (DOWN when this process's worker hasn't read the queue for 10 polls, min. 30 s).
   Probe responses show only `UP`/`DOWN`. On shutdown, readiness goes DOWN, open log streams are closed (browsers
   reconnect elsewhere), then requests get up to 20 s to finish (`server.shutdown=graceful`).
6. **A rollback is a new deployment**, not a status change: `POST /deployments/{id}/rollback` queues a deployment of
   the same project and commit, with `rollback_of_id` pointing to the source. It reuses the queue, idempotency keys,
   admission control, history and audit. Only SUPERSEDED deployments qualify (they were live and are known good);
   LIVE, FAILED, CANCELLED and in-progress ones get 409. Being the newest deployment, a rollback wins the M4 rule
   that only the newest deployment of a project may go LIVE.

## Consequences / limitations
- A deployment made without a `commitRef` recorded only a branch, so rolling back to it redeploys that branch's
  current head. Real builds (M6) will record the resolved commit SHA and make rollbacks exact.
- A rollback re-runs the project's current build/start commands; there are no stored build artifacts yet.
- Micrometer counters and timers are per process and reset on restart; Prometheus aggregates them. There is no
  Prometheus/Grafana deployment yet (M7).
- Log ids come from an identity column. If two transactions wrote lines for the same deployment at once, a line
  could commit after a higher id was already streamed and be skipped. Today one worker writes a deployment's lines
  and a concurrent cancel is serialised against it by the deployment's `@Version`, so this does not occur.
