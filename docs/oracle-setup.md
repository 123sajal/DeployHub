# Oracle setup

DeployHub runs against Oracle in two ways. Both use the same `oracle` profile and the same Flyway migrations.

| | A. Local Oracle in Docker | B. Oracle Autonomous Database (cloud) |
|---|---|---|
| Account needed | None | Oracle Cloud account (Always Free tier) |
| Cost | ₹0 | ₹0 if you stay on Always Free resources |
| Version | Oracle Database 23ai/26ai Free | 19c or 23ai (pick 23ai) |
| Use it for | Daily development, Oracle-compatibility tests | Real cloud persistence; needed for Milestone 7 |

All commands are run from `~/Desktop/DeployHub/deployhub-backend`.

---

## Step 0: create your `.env` (both options)

```bash
cd ~/Desktop/DeployHub/deployhub-backend
cp .env.example .env
open -e .env          # set DB_PASSWORD (12+ chars, upper, lower, digit)
```

`.env` is git-ignored. Never commit it, paste it into chats, or put it in screenshots.

## Option A: local Oracle in Docker

Requirements: Docker Desktop running, about 7 GB of disk for the image, and about 2 GB of RAM for the container.

```bash
# 1. Start Oracle. The first start pulls the image; later starts take about 20 s.
docker compose -f docker-compose.oracle.yml up -d
docker inspect -f '{{.State.Health.Status}}' deployhub-oracle    # wait until it prints "healthy"

# 2. Run DeployHub against it, then sign up at http://localhost:4200/signup
./mvnw spring-boot:run -Dspring-boot.run.profiles=oracle

# 3. Check the connection
curl -s localhost:8080/api/health     # expect "databaseProduct":"Oracle"
```

Data lives in the Docker volume `deployhub-oracle-data`, so it survives `docker compose ... down` and Mac restarts.
`docker compose -f docker-compose.oracle.yml down -v` deletes the data.

## Option B: Oracle Autonomous Database (Always Free)

Only do this once you have an Oracle Cloud account. Signing up asks for a card to verify identity;
Always Free resources are not charged. **Do not click "Upgrade to Paid".**

1. **Create the database.** In the OCI Console, go to *Oracle Database → Autonomous Database → Create*.
   - Workload: *Transaction Processing*. Switch on **Always Free** (the console shows the badge).
   - Version: **23ai**. Name: `deployhubdb`.
   - Set a strong ADMIN password and store it in a password manager. The app never uses ADMIN.
   - Network: *Secure access from everywhere* is fine for development (connections still need mTLS and a password).
2. **Create a least-privilege user for the app.** Open the database, then *Database actions → SQL*, signed in as ADMIN:
   ```sql
   CREATE USER deployhub_app IDENTIFIED BY "<strong password>"
       DEFAULT TABLESPACE DATA QUOTA 500M ON DATA;
   GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO deployhub_app;
   ```
   No DBA, `RESOURCE` or `DWROLE` roles. Those three privileges are all Flyway and the app need.
3. **Download the wallet.** On the database page, click *Database connection → Download wallet*, then set a wallet password.
   Unzip it **outside the repository**:
   ```bash
   mkdir -p ~/.oracle/wallets/deployhub
   unzip ~/Downloads/Wallet_deployhubdb.zip -d ~/.oracle/wallets/deployhub
   chmod 700 ~/.oracle/wallets/deployhub
   grep -o '^[a-z0-9_]*_tp' ~/.oracle/wallets/deployhub/tnsnames.ora    # e.g. deployhubdb_tp
   ```
4. **Point `.env` at it** (Option B lines in `.env.example`):
   ```
   DB_URL=jdbc:oracle:thin:@deployhubdb_tp?TNS_ADMIN=/Users/<you>/.oracle/wallets/deployhub
   DB_USERNAME=DEPLOYHUB_APP
   DB_PASSWORD=<the password from step 2>
   ```
5. **Run and verify:**
   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=oracle
   curl -s localhost:8080/api/health    # "database":"UP","databaseProduct":"Oracle"
   ```
   Restart the app and check that your projects are still there.

### Always Free limits to keep in mind
- About 20 concurrent sessions in total, so the app's connection pool is capped at 5 (`application-oracle.properties`).
- 20 GB of storage. The database is **stopped after 7 days of no activity**; start it again from the console.
- An instance that stays stopped for a long time can be reclaimed. Check Oracle's current Always Free terms.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `Could not resolve placeholder 'DB_URL'` | No `.env` in `deployhub-backend/`, or you ran the app from another folder |
| `ORA-01017: invalid credential` | Wrong `DB_USERNAME`/`DB_PASSWORD` |
| `ORA-12506` / `IO Error ... TNS_ADMIN` | Wrong wallet path, or wrong service name in `DB_URL` |
| Container exits with `SP2-0667 Message file sp1<lang>.msb not found` | Corrupted image download. Run `docker rmi gvenzl/oracle-free:23-slim-faststart` and pull again. |

## What the tests prove (and don't)
- `./mvnw test`: 112 tests on H2 (fast, no Docker).
- `./mvnw test -Poracle-it`: 72 tests on **real Oracle 23ai/26ai Free in Docker**, with Hibernate schema validation,
  including sign-up/login, tenant isolation and a network-outage-and-recovery test.
  This is strong evidence for Autonomous Database 23ai, but it isn't the same as running on Autonomous Database itself:
  ADB adds its own wallet/mTLS setup, session limits and auto-stop behaviour. Option B step 5 is the real check.
