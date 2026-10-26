# ADR 0006: Real builds in Docker

Status: accepted · Date: 2026-10-26

## Context
Until Milestone 5 every deployment was simulated. Milestone 6 makes them real: fetch a repository, build it,
run it, and only call it LIVE once it answers. Constraints from the user: **public repositories only**, and
**nothing outside this Mac may be changed** (no pushes, webhooks or settings on GitHub/GitLab, no cloud resources).
Docker Desktop on this Mac has ~4 GB of memory, shared with the Oracle container.

## Decisions
1. **Executors behind one interface.** The worker keeps the state machine, leases and retries; a
   `DeploymentExecutor` does BUILDING and STARTING. `DockerDeploymentExecutor` is the real one; the old simulator
   became `SimulatedDeploymentExecutor`. Each deployment stores the mode it was created with
   (`deployhub.execution.mode`, REAL by default; the tests use SIMULATED and need no Docker).
2. **Long work outside transactions.** A build can take minutes and the pool has 5 connections. Each step reads an
   immutable `DeploymentSpec` in a short transaction, runs outside any transaction, then records the result in a
   short transaction that re-checks the deployment (a user may have cancelled; `@Version` catches races). A guard
   thread renews the lease every ~2 s during the work and cancels it when the user cancels or the lease is lost;
   cancelling kills the running process.
3. **Source: shallow, read-only fetch on the host** (`git fetch --depth 1 <url> <ref>` into a temp folder, then the
   exact commit SHA is recorded). Hardening: no system/global git config (so no macOS keychain helper), no askpass
   (an IDE's helper could otherwise hand over the user's GitHub token), no terminal prompt, only the https protocol,
   no hooks, symlinks checked out as plain files, `--end-of-options` before user-supplied refs. A private
   repository therefore fails instead of quietly using this machine's login.
   *Alternative rejected:* letting BuildKit clone (`docker build <git-url>`) avoids the host checkout, but it can't
   report the resolved commit reliably and makes the generated-Dockerfile case awkward.
4. **Build: `docker build` with a generated Dockerfile** per app type (one stage: copy, `RUN <build command>`,
   `CMD ["sh","-c",<start command>]` JSON-encoded), or the repository's own Dockerfile. The project's commands run
   only inside Docker's build sandbox and the container, never on the host. Line breaks in commands are rejected
   (they would start new Dockerfile instructions).
5. **Images are tagged by commit and settings** (`deployhub/p<project>:<sha12>-<hash>`). A repeated step or a
   rollback to a commit built before reuses the image (instant rollback). The images of the last 3 successful
   deployments per project are kept; older ones are removed after each go-live.
6. **Run: one container per deployment** (`deployhub-d<id>`, removed first, so retries are idempotent) with
   `--memory 512m --cpus 1 --pids-limit 256 --cap-drop ALL --security-opt no-new-privileges`, an unprivileged user
   for generated images (own-Dockerfile images keep their user but only get CHOWN/SETUID/SETGID/NET_BIND_SERVICE),
   `--restart unless-stopped`, and a port in 20000-20999 published on **127.0.0.1 only**. A taken port is retried
   with the next one.
7. **LIVE means "answers HTTP".** The health check polls `GET <path>` until any status below 500. Because of
   `--restart unless-stopped`, a crashed app looks "running" again a moment later, so a crash is detected from the
   restart count, not the running flag (the real-Docker test caught this). Failures copy the app's last output into
   the deployment log and remove the container.
8. **Go-live stays serialised per project** (row lock from M5). After it commits, the replaced deployment's
   container is stopped. Deleting a project stops its live container and removes its images after the delete commits.
9. **Error classification:** unreachable Docker or network problems while pulling are transient (retried);
   a failing build command, a missing branch, a private/nonexistent repository, a crashing app or a failed health
   check are permanent (retrying would fail the same way).

## Consequences / limitations
- Each deployment gets its own port, so the app's address changes per deployment; M7 adds a stable public address.
- A LIVE app isn't monitored after its health check (Docker restarts it if it crashes; DeployHub still says LIVE).
- Builds have a time limit but no memory limit (BuildKit doesn't support one).
- The host's git does the checkout. It never runs repository code, but git checkout bugs are a (small) attack
  surface; on a Linux server (M7) the fetch could move into a throwaway container.
- Private repositories, build-on-push webhooks and environment variables/secrets are out of scope; each needs the
  user's explicit go-ahead because it stores secrets or changes things outside this machine.
