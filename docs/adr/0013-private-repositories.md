# ADR 0013: Private repositories with access tokens

Status: accepted · Date: 2026-10-27

## Context
DeployHub could only build public repositories (ADR 0006 kept private ones out because they need stored secrets and
the user's go-ahead). The user asked for private repositories and chose access tokens over "Connect GitHub" (OAuth,
GitHub only, needs a registered OAuth app) and deploy keys (SSH, unfamiliar to beginners).

## Decisions
1. **One read-only token per project** (`projects.repo_token_encrypted`, `repo_token_updated_at`, V13), given as the
   write-only `repositoryToken` on create/update; empty keeps the saved one; `DELETE /api/projects/{id}/repository-token`
   removes it. Responses only carry `privateRepository` and the date.
2. **Checked before saving:** `git ls-remote --heads` with the token, outside any database transaction. A token that
   can't read the repository/branch is a field error on the form ("The access token doesn't work for this repository:
   it may be wrong or expired, or it doesn't have read access").
3. **Encrypted at rest** with SecretCipher (AES-256-GCM, DEPLOYHUB_SECRETS_KEY), like environment variables; no key = 409
   with instructions. Audit events REPO_TOKEN_SET / REPO_TOKEN_REMOVED never contain it.
4. **Never on a command line:** git gets the token through environment configuration (`GIT_CONFIG_COUNT/KEY/VALUE`):
   `http.https://<host>/.extraheader = Authorization: Basic base64(<user>:<token>)`, scoped to the repository's host;
   user `x-access-token` (GitHub), `oauth2` (GitLab), `x-token-auth` (Bitbucket). All other GitSafety rules stay
   (no credential helpers, prompts, system/global config, hooks). DeploymentSpec, request DTOs and logs never print it;
   log masking also covers `github_pat_…`, `glpat-…` and `Authorization: Basic …`.
5. **Used for every read of that repository:** deploys (the worker decrypts it into the build step), Detect from
   repository (a token typed into the form, or the saved one when editing, only for the same repository URL and owner),
   and "What this app needs" (its cache key includes the token date).
6. **A token belongs to one repository:** changing the project's repository URL without a new token drops the saved
   one, so it is never sent to a different repository.
7. **Frontend:** "This repository is private" + token box with how-to links for GitHub (fine-grained, this repository
   only, Contents: Read-only), GitLab (read_repository) and Bitbucket (Repositories: Read); the box opens by itself when
   detection can't read the repository; the Edit page says a token is saved and offers Remove token.

## Consequences / limitations
- Tokens expire; the deploy then fails with "The access token doesn't work ..." and the user pastes a new one.
- One token per project (not per account): two projects of the same private repository each need it.
- Real private-repository fetches were tested by hand only against GitHub's rejection of a made-up token; the token
  path through git is covered by unit tests (environment, scoping, no command-line exposure).
