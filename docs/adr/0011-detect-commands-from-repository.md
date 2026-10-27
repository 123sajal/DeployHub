# ADR 0011: Detect the type and commands from the repository

Status: accepted · Date: 2026-10-27

## Context
The suggested commands per application type are generic, so real projects failed: the Employee Management backend
got `npm run build` (it has no build script) and no `db:setup` (its tables were never created), and its Angular
frontend was served from `dist` instead of `dist/ems-frontend/browser`. The user asked how their own users would
ever fix that. They shouldn't have to: DeployHub can read the repository.

## Decisions
1. **`POST /api/projects/detect {repositoryUrl, branch, rootDirectory}`** returns the suggested type, build and start
   commands, app folders (when the chosen folder has no app) and plain-language notes. Nothing is saved; the form
   fills the fields and the user can still change them. Same validation as projects (URL allowlist, branch and folder
   patterns); 20 checks per user per minute.
2. **Read with git, not the hosts' raw-file URLs.** First version used `raw.githubusercontent.com`; on this Mac Java
   could not connect to that address at all (curl could; GitHub, GitLab, Razorpay could), so it failed with 503.
   Git works the same for GitHub, GitLab and Bitbucket, needs no token and has no rate limit: `fetch --depth 1
   --filter=blob:none` gets only the branch's file list, then `cat-file` reads the few files needed (0.5-1 s each).
   Typical detection: 1-3 s. Temporary folder in the build workspace, deleted afterwards.
3. **One set of git safety rules** (`GitSafety`): https only, no credential helpers/askpass/prompts, no system or global
   config, no hooks, no symlinks, `--end-of-options` before the branch. Deploys and detection share it. Files over
   512 KB are ignored; paths are checked against the fetched file list.
4. **Rules** (ProjectDetectionService): package.json dependencies decide Angular (exact output folder from
   angular.json, Angular 17+ `/browser`), Vite/CRA/Vue (static), Next and Node backends (a server framework wins over
   Vite); `build` runs only if the script exists; `db:setup`/`setup:db`/`db:migrate`/`migrate`/`prisma:migrate` run
   before start; lock files pick npm ci / yarn / pnpm / npm install. Maven/Gradle (warn without wrapper), Django
   (settings module from manage.py), FastAPI, Flask (warn when gunicorn/uvicorn is missing), Dockerfile.
5. **Frontend:** on New, detection runs by itself when the URL, branch or App folder box is left (once per
   combination); "Detect from repository" re-runs it on New and Edit and always applies. Automatic detection keeps
   commands the user wrote. "Use folder: backend / frontend" buttons set the App folder and detect again.

## Consequences / limitations
- Detection suggests, it doesn't guarantee: unusual projects (custom scripts, monorepo tools) may still need edits;
  build-error hints (e.g. missing npm script) cover the common failures that remain.
- Private repositories can't be read (same as deploys).
- The rate limit is in memory (per backend process).
