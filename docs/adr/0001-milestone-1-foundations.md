# ADR 0001: Milestone 1 foundations

Status: accepted · Date: 2026-10-25

## Context
Milestone 1 needs a working local platform (UI, API, persistence, simulated deployments) that later
milestones can extend without rewrites, on a ₹0 budget, before Oracle credentials or real workers exist.

## Decisions

1. **Spring Boot 4.1.2 / Java 21, Angular 21 LTS + PrimeNG 21.** These were the current stable lines on the
   decision date. Angular 22 was skipped because it requires Node ≥ 24.15, and the dev machine has 24.11.
   springdoc 3.2.0 is built against Boot 4.1.2.
2. **Project layout follows the team's AKC template** (`controllers`, `services/Interface|Implementations`,
   `models/{entities,DTO,enums}`, `mappers`, …), plus `deployment/` (domain rules) and `worker/`.
3. **Real persistence from day one (JPA + Flyway), H2 file DB in an explicit `local` profile.** Migrations are
   written in Oracle syntax (`NUMBER`, `VARCHAR2`, identity columns, `TIMESTAMP WITH TIME ZONE`), and H2 runs
   in `MODE=Oracle`. The base config has no datasource, so a missing profile fails fast. Hibernate's
   schema validation is off for H2 only, because H2 reports `NUMBER(19)` as `NUMERIC`.
4. **The state machine is a single table of allowed transitions** (`DeploymentStateMachine`). The entity can
   only change status through `transitionTo`, so illegal moves can't be written from anywhere.
5. **Optimistic locking (`@Version`) instead of row locks.** Conflicts (a worker step racing a user cancel) are
   rare, and a failed version check is cheap and explicit. `DeploymentLifecycleIntegrationTest` proves that a
   stale write is rejected. Milestone 4 adds leases for cross-process job claiming.
6. **Keyset (cursor) pagination for logs** (`id > :after ORDER BY id` on index `(deployment_id, id)`).
   Its cost doesn't grow with log size, unlike OFFSET, and the same cursor becomes the SSE `Last-Event-ID` in M5.
7. **Simulated worker is in-process and clearly labelled.** Every deployment carries
   `executionMode = SIMULATED`, which the UI shows. It is not durable or distributed, by design.
8. **Ownership via `ICurrentUserService`.** M1 uses `DevCurrentUserService` (profiles `local`/`test` only).
   All queries already filter by owner, so M3 only swaps the implementation.
9. **Defence in depth for untrusted input:** an allowlist for repository URLs (SSRF), and a log sanitizer that
   masks secret-looking values before they are persisted.

## Consequences / known limitations
- No authentication: anyone who can reach port 8080 acts as the dev user. Local use only. (Resolved in ADR 0003.)
- The simulated worker polls the DB every 2 s; with several backend instances, each would run it. Optimistic
  locking keeps state correct, but work is duplicated. Milestone 4 replaces it.
- The dashboard reads counts and recent activity in two queries, so under concurrent changes they can briefly
  disagree.
- H2 tests do not prove Oracle compatibility.
