# ADR 0004: Durable deployment jobs, workers, retries and idempotency

Status: accepted · Date: 2026-10-25

## Context
Until Milestone 3 a timer inside the API process advanced deployments. There was no notion of a job,
no retries, nothing to recover work after a crash, and double-clicking "Deploy" created two deployments.
Real builds (M6) need all of that, and so do the system-design requirements.

## Decisions
1. **Queue = a table in the same Oracle database** (`deployment_jobs`), not a separate broker.
   - The job row is inserted in the **same transaction** as the deployment, so a committed request can't lose its
     job. A separate broker (OCI Queue, RabbitMQ, Kafka) would need a transactional outbox to get that guarantee.
   - *Trade-off:* polling adds DB load, and the queue scales with the database. At DeployHub's scale (tens of jobs)
     that's fine. A broker becomes worth it at thousands of jobs per minute; the claim/finish code would then move
     behind the same worker interface.
2. **Claiming is a compare-and-set UPDATE** ("set RUNNING where still PENDING-and-due, or RUNNING with an expired
   lease"); the row count says who won. It works identically on Oracle and H2. `SELECT … FOR UPDATE SKIP LOCKED` is
   faster on Oracle but not portable to the H2 test database.
3. **Leases + heartbeats** (30 s, renewed before every step) detect dead workers. Every later write by a worker
   (heartbeat, finish, reschedule) is conditional on still holding the lease, so a worker that was presumed dead
   can't overwrite its successor (fencing).
4. **At-least-once execution, idempotent steps.** A crash can repeat a step. Each step continues from the
   deployment's current status, and the deployment's `@Version` stops two workers applying the same transition.
   We don't claim exactly-once.
5. **Retries:** exponential backoff with equal jitter (2 s, 4 s, 8 s… capped at 1 min), at most 3 attempts. Permanent
   errors are not retried. Exhausted jobs become **DEAD** and are kept for inspection, as a dead-letter state in the
   same table. An attempt lost to a crash counts toward the limit, so a job that keeps killing workers also ends up DEAD.
6. **Idempotency:** a separate `idempotency_keys` table with primary key `(user_id, key)` and a SHA-256 of the
   request. Same key and same request returns the original deployment (200); same key and a different request gets
   422. The record implements `Persistable` (always INSERT): with Spring Data's default `merge`, a concurrent
   duplicate could overwrite the winner's row instead of hitting the primary key. A concurrency test caught this.
7. **Backpressure:** each process runs at most `max-concurrent-jobs` (2) jobs and claims only when a slot is free,
   so the backlog stays in the database. Admission control allows at most 5 in-progress deployments per user (429).
8. **Status history** is written by one `DeploymentTransitionService`, in the same transaction as the status change.

## Consequences / limitations
- The per-user admission check isn't locked; simultaneous bursts can briefly exceed 5.
- Idempotency keys never expire yet.
- Queue metrics (depth, wait time, throughput) were added in M5, see ADR 0005.
- Execution is still simulated; M6 replaces the step bodies with isolated Docker builds.
