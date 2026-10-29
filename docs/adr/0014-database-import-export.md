# ADR 0014: Import and export a project's database

Status: accepted · Date: 2026-10-28

## Context
One-click databases (ADR 0008) always started empty. The user asked whether their users could bring the data of a
database on their own computer. Connecting to DeployHub's MySQL directly isn't an option for them (it listens on
127.0.0.1 of the server only, on purpose), so DeployHub needs to do the transfer.

## Decisions
1. **Import:** `POST /api/projects/{id}/database/import` (multipart `file`, `replace`). Only `.sql` files, at most
   50 MB (multipart limit and again while reading). Export: `GET .../database/export` streams a mysqldump
   (`--no-tablespaces --single-transaction --skip-lock-tables --triggers`) as `<database>-<date>.sql`.
2. **As the project's own MySQL user** (password from the project's `DB_PASSWORD`): it can only use its own database,
   so a file can't read or change another one (tested against real MySQL: `DROP TABLE` on another database is
   "denied"). It also becomes the owner of the views and triggers the file creates; with a temporary user (the first
   version) they broke as soon as that user was removed. A `DB_PASSWORD` that no longer matches gives a clear 409
   ("use Reset password").
3. **Cleaning (SqlDumpCleaner):** exports from a laptop contain lines a project user can't run: `CREATE DATABASE` /
   `USE` (the data goes into the project's database whatever it was called), `DEFINER=...` (admin rights), and
   `SET @@GLOBAL/SESSION.SQL_LOG_BIN`/GTID lines. They are replaced by `-- removed by DeployHub: ...` comments; the
   response says how many. Line by line, so large files don't fill the memory.
4. **No silent overwrite:** a database that already has tables is refused (409) unless `replace=true`, which drops and
   recreates it as admin (the project user keeps its rights) before the import. MySQL errors come back as
   `errors.file` (400) with MySQL's message.
5. **Transfer path:** the file is copied into the MySQL container (`docker cp`) under a random name and run with
   `docker exec mysql ... < file`, password via the environment; removed afterwards. One import/export per project at
   a time; audit events DATABASE_IMPORTED / DATABASE_EXPORTED.
6. **Frontend:** Database card, "Your data": file picker, "Delete the current data first" with a warning, Import data,
   and an Export data download link, with how to export from a laptop (mysqldump / Workbench / phpMyAdmin).

## Consequences / limitations
- MySQL only (the only managed engine); `.sql` only (no compressed files yet).
- Large imports take time (10-minute limit) and block that project's other transfers meanwhile.
- Stored routines that need admin rights (e.g. `SUPER`) still fail and are reported with MySQL's message.
