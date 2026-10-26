# ADR 0007: Environment variables, fixed project addresses and routing

Status: accepted · Date: 2026-10-26

## Context
The user's first real app (an Express API + an Angular frontend + MySQL) needed three things DeployHub lacked:
settings such as database passwords passed to the app, an address that doesn't change with every deployment,
and a way for the frontend to reach the backend without hard-coding a changing URL or configuring CORS.

## Decisions
1. **Environment variables are write-only and encrypted.** Stored per project as AES-256-GCM ciphertext
   (`v1:` + base64(IV + ciphertext)); the key comes from `DEPLOYHUB_SECRETS_KEY` (in the git-ignored `.env`), never
   from the database. The API returns names only. GCM authenticates, so a tampered value or a wrong key fails
   loudly instead of producing garbage. `PORT`/`SERVER_PORT` are reserved (DeployHub sets them).
2. **Values never appear on a command line or in logs.** The container gets `-e NAME` and docker reads the value
   from its own process environment; the deployment log masks every value (4+ characters) in build and app output.
   Variables apply at the next deployment (runtime only, not during the build); a rollback uses current values.
3. **Each project gets a fixed address** `http://localhost:<19000-19999>`, allocated on its first real go-live
   (unique index settles races). A small nginx container per project ("edge", 64 MB, all capabilities dropped
   except what nginx needs to switch user) holds the port and forwards to the live app by container name over a
   private Docker network (`deployhub-net`). On every go-live its config is rewritten and reloaded *before* the
   replaced container is stopped, so the address never changes and the switch has no downtime.
4. **Routes instead of hard-coded URLs.** A project can send path prefixes to another project's live app
   (`/api` → backend). The frontend calls `/api/...` on its own address: same origin, so no CORS and no backend URL
   in its code. Upstreams are resolved per request (`resolver` + variable), so a reload never fails because an app
   is momentarily gone; a target without a live app answers 503 with a reason. When a target redeploys, every
   project routing to it is refreshed; when it is deleted, its routes are removed and those projects refreshed.
   *Alternative considered:* a fixed host port per project on the app container itself. Rejected: the new and old
   containers can't hold the same port at once (downtime on every deploy), and it doesn't solve routing or CORS.
5. **The app folder is writable** by the unprivileged user the app runs as (`COPY --chown`), as on other platforms
   (e.g. `uploads/`); writes are lost when the container is replaced.
6. **Restarts only after the health check** (`--restart no`, then `docker update --restart unless-stopped`), so
   a crash during startup shows its real exit code.

## Consequences / limitations
- Addresses are on 127.0.0.1 (this Mac only). M7 puts a public proxy with HTTPS and real hostnames in front.
- Files an app writes are not persistent; databases belong in a separate service (e.g. MySQL in Docker, reached
  from the app via `host.docker.internal`).
- Losing or changing `DEPLOYHUB_SECRETS_KEY` makes stored values unreadable (deployments then fail with a clear
  message); there is no key rotation yet.
- Environment variables are not available during the build (no build-time secrets).
