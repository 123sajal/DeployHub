# ADR 0002: Oracle persistence and testing strategy

Status: accepted · Date: 2026-10-25

## Context
Milestone 2 needs Oracle as the real database. The user has no Oracle Cloud account yet, and H2 in
Oracle mode had already produced two dialect surprises (`escape ''`, and `NUMBER(19)` reported as `NUMERIC`).
So H2 alone cannot be trusted as evidence of Oracle compatibility.

## Decisions
1. **An `oracle` profile with no fallback.** Settings come from environment variables or a git-ignored `.env` file
   (`spring.config.import=optional:file:.env[.properties]`). If Oracle is unreachable, startup fails.
   `NoSilentFallbackTest` enforces this.
2. **Hibernate `ddl-auto=validate` on Oracle.** Flyway owns the schema. Hibernate only checks that entities
   match it, and refuses to start if they drift.
3. **Real Oracle in tests via Testcontainers** (`gvenzl/oracle-free`). The existing API and lifecycle suites are
   re-run against Oracle by subclassing them (`*OracleTest`, tag `oracle`), so there is one source of test cases
   and two databases. They are excluded from the default `./mvnw test` because they need Docker and about 30 s of
   start-up; run them with `-Poracle-it`.
4. **Local Oracle via Docker Compose** for day-to-day development. No cloud account is needed, and it uses the
   same credentials file as the app.
5. **(Superseded by ADR 0003.)** **The development identity is split into a `dev-auth` profile.** `local` and `test` include it automatically,
   while `oracle` requires asking for it explicitly. The dev user is now created at startup on any database,
   which replaces the H2-only seed for tests.
6. **Least-privilege database user** (`CREATE SESSION`, `CREATE TABLE`, `CREATE SEQUENCE` plus a quota); ADMIN is never used.
   A future hardening step is to split this into a schema-owner user for migrations and a runtime user with
   DML-only grants.
7. **Small connection pool (max 5)** because of Always Free's session limit.

## Consequences
- Oracle compatibility is now tested, but on Oracle Free, not Autonomous Database itself.
- The Oracle test image is large (about 6 GB on disk).
- ~~Running on Oracle still needs the `dev-auth` stand-in identity until Milestone 3.~~ Superseded by ADR 0003: `dev-auth` was removed.
