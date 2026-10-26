# ADR 0003: Authentication and tenant isolation

Status: accepted · Date: 2026-10-25 · Supersedes the `dev-auth` profile from ADR 0002

## Context
Milestone 3 adds real accounts. Every project, deployment and log line must be visible only to its owner,
and the solution has to work on Oracle and on H2 without needing Oracle Cloud.

## Decisions
1. **Server-side sessions (Spring Security) instead of JWTs.**
   - *Why:* the SPA and API share an origin (dev proxy now, reverse proxy in M7), so a cookie session is the
     simplest secure option. The token never touches JavaScript (`HttpOnly`), and logout and disable
     take effect immediately on the server.
   - *Trade-off:* sessions live in backend memory, so a restart logs users out, and horizontal scaling needs
     Spring Session JDBC (a planned M7 change, with no API change). JWTs would avoid that, but bring
     revocation, storage (XSS) and refresh-token complexity we don't need yet.
2. **CSRF uses the SPA cookie pattern:** `XSRF-TOKEN` (readable cookie) plus an `X-XSRF-TOKEN` header, which
   Angular sends automatically, configured via `csrf.spa()`. The token and session id both rotate on login (session fixation).
   Logout also issues a fresh token. Without that, "sign up → log out → log in" in one tab failed with 403
   (bug found in manual testing). As a safety net, the frontend fetches `GET /api/auth/csrf` and retries a write
   once if it gets a 403.
3. **Passwords:** `DelegatingPasswordEncoder` (BCrypt today, `{id}`-prefixed so the algorithm can be upgraded).
   10-64 characters, ≤ 72 UTF-8 bytes (BCrypt silently truncates beyond that), not equal to the email.
4. **Account enumeration:** login gives one message and similar timing for unknown, wrong-password,
   disabled and legacy accounts (Spring's `DaoAuthenticationProvider` runs a dummy hash check). Sign-up must say
   "already registered" until email verification exists; it is rate-limited per IP.
5. **Throttling backed by the audit table**, keyed by (email, IP) and by IP. Not by email alone, so an attacker
   can't lock the real user out. The limits survive restarts and are shared across instances.
6. **Ownership is enforced only on the server.** `ICurrentUserService` reads the user id from the session's
   principal; ids from the client are never trusted. Every repository query is scoped by owner, and foreign ids
   return the same 404 as missing ones. Angular route guards are UX only.
7. **The background worker** has no user context: it only advances deployments by id, and supersedes only
   deployments of the same project, so it can't cross owners. A test covers two owners with identically named projects.
8. **Database outages return 503** (including connections lost mid-request, detected by root cause). There is
   never a 401, and never a fallback.
9. **Schema changes are additive** (`V2__authentication.sql`). The old passwordless dev user is marked
   `LEGACY_NO_PASSWORD` and cannot log in; its data is kept, not deleted.

## Consequences
- Every API call except register, login and health needs a session plus a CSRF header on writes.
- Tests sign up real users through the API and use the real double-submit CSRF flow, not test-only shortcuts.
- Remaining limitations are listed in the README.
