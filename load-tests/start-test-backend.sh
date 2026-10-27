#!/bin/sh
# Starts a separate DeployHub backend just for load tests, on http://localhost:8081:
#   - in-memory database (gone when it stops; your real Oracle data is never touched)
#   - SIMULATED deployments (no Docker builds, so 100 users can't overload this Mac)
#   - sign-up limit raised (normally 10 per network per hour; the test signs up 100 users from one computer)
# Stop it with Ctrl+C.
cd "$(dirname "$0")/../deployhub-backend" || exit 1
exec ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments="\
--server.port=8081 \
--spring.datasource.url=jdbc:h2:mem:loadtest;MODE=Oracle;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1 \
--spring.h2.console.enabled=false \
--deployhub.execution.mode=SIMULATED \
--deployhub.worker.step-delay=300ms \
--deployhub.worker.max-concurrent-jobs=4 \
--deployhub.security.max-registrations-per-ip=1000 \
--deployhub.gateway.public-link-refresh=1h"
