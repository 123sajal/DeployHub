# ADR 0008: One-click databases

Status: accepted · Date: 2026-10-26

## Context
Apps such as the user's Employee Management API need a database. Asking every user to run MySQL themselves and
copy credentials into environment variables doesn't scale. Hosting platforms offer "add database" instead.

## Decisions
1. **One shared MySQL server, one database + user per project.** DeployHub runs `deployhub-mysql` (mysql:8.4,
   created on first use, data in the `deployhub-mysql-data` volume, 512 MB limit, small buffer pool). Each project
   gets database and user `p<projectId>_<name>` with `ALL` privileges on its own database only, so apps can't
   read each other's data (proven against real MySQL in the docker-it suite). One server instead of one per
   project keeps memory use flat (~150-250 MB) on a 4 GB Docker.
2. **Credentials as environment variables.** Creating the database sets `DB_HOST=deployhub-mysql`, `DB_PORT`,
   `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `DATABASE_URL` (encrypted, ADR 0007). Apps reach the server by name on the
   `deployhub-net` network; this machine reaches it on `127.0.0.1:3307` (e.g. to create tables).
3. **Passwords:** 24 random alphanumeric characters, shown once (on create and on reset), stored only encrypted.
   The server's admin password is generated on first use and stored encrypted in `database_servers`.
4. **Nothing user-typed reaches SQL or a command line.** Identifiers are generated and checked against
   `[a-z0-9_]{1,32}`, passwords are alphanumeric, and SQL plus passwords travel to `docker exec` as environment
   variables. Admin commands use TCP, because during MySQL's first start a temporary server answers on the socket.
5. **Idempotent server operations** (`CREATE ... IF NOT EXISTS`, `DROP ... IF EXISTS`), done outside Oracle
   transactions (the first start can take a minute); a failed request can simply be repeated.
6. **Lifecycle:** removing the database or deleting the project drops the database and its user.

## Consequences / limitations
- **No backups yet** (deferred by the user until M7). Deleting the `deployhub-mysql-data` volume loses every
  app's data.
- MySQL only; PostgreSQL would be a second server of the same kind.
- Tables are the app's job (e.g. its own setup script, run from this machine via 127.0.0.1:3307 or in the
  start command).
- All apps share one server's CPU and memory; a heavy app can slow the others down.
