# ADR 0012: Work out the environment variables an app needs, and fill them in

Status: accepted · Date: 2026-10-27

## Context
Deploying an app needed knowledge most users don't have: which variables it reads, what a "secret key" is, how to
make one (`openssl rand -base64 32`), which database names it expects. The Employee Management backend and a demo
copy of DeployHub both showed it. The user asked to solve this for their own users.

## Decisions
1. **Read the requirements from the repository** (`GET /api/projects/{id}/env/requirements`), in this order, first
   one wins per name: `deployhub.json` (the author's description), `.env.example` and similar files (App folder,
   else the main folder; comments above a line become its help text), then the code: `process.env.X` (required
   unless followed by `||`/`??`), `${X}` in Spring `application*.properties|yml` (required unless `${X:default}`),
   Python `os.environ["X"]` (required) and `os.getenv`/`environ.get` (optional). node_modules, build output and
   tests are skipped; at most 300 code files.
2. **One fetch for everything:** the repository is read with git as in ADR 0011, but with `--filter=blob:limit=256k`,
   so all small files arrive in one round trip. The repository part is cached for 5 minutes per project; what is set
   is read fresh every time.
3. **Kinds decide how DeployHub helps:** SECRET (the app's own: `*_SECRET`, `*SECRET_KEY`, `*_ENCRYPTION_KEY`, ...)
   gets a generated 32-byte random value (base64); SETTING gets its example value (placeholders such as
   `your_...`, `<...>`, `changeme` are never used); DATABASE points to Add MySQL database; DATABASE_ALIAS
   (`DB_USERNAME`, `MYSQL_*`) copies the database's value; PLATFORM (`PORT`) is set by DeployHub; EXTERNAL (Stripe,
   SMTP, AWS, `*_API_KEY`, `*_CLIENT_SECRET`, ...) is never generated: only the user can get it; LOCAL_ONLY (example
   points at localhost) warns; UNKNOWN asks the user.
4. **Fill in automatically** (`POST .../env/requirements/fill {names}`): the server decides every value (the browser
   can't choose them), stores them encrypted through the normal variable service (validation, audit), and never
   overwrites a variable that already has a value. Generated secrets are never shown.
5. **Deploy checks first** (frontend): missing required variables open a dialog: "Fill in & deploy" when DeployHub can
   fill them all, otherwise it names what the user must add (and the database) with "Deploy anyway". It doesn't
   block: the list can be wrong for unusual apps.
6. **Crash hint:** when the app stops before its health check, its last 60 output lines are scanned for a variable
   it complains about (UPPER_SNAKE name on a line with "undefined/not set/missing/required/KeyError", or Spring's
   "Could not resolve placeholder 'X'") that the project doesn't set; the failure message names it.
7. **`deployhub.json`** (optional): `env.<NAME>` with `value`, `generate: "secret"`, `required`, `description`;
   `database: "mysql"`. When present it also decides whether a database is needed. Added
   `deployhub-backend/deployhub.json` for a demo copy of DeployHub (H2 `local` profile, `SIMULATED` mode, generated
   `DEPLOYHUB_SECRETS_KEY`, Oracle variables optional).

## Consequences / limitations
- Variables built dynamically (`process.env[name]`) or read by libraries aren't found; `.env.example` or
  `deployhub.json` cover those.
- App-specific knowledge (e.g. which Spring profile to use) can only come from `deployhub.json` or the README.
