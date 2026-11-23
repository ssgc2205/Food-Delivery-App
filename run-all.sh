#!/usr/bin/env bash
#
# Runs every service locally from its built jar, against:
#   - your local Postgres (localhost:5432)
#   - Kafka started via docker-compose.infra.yml (localhost:9092)
#
# Build the jars first (once):   see TESTING.md, "Build the jars"
# Then:                          ./run-all.sh
# Stop everything:               ./stop-all.sh
#
set -euo pipefail
cd "$(dirname "$0")"

# --- connection settings (override by exporting before running) ---
export EUREKA_HOST="${EUREKA_HOST:-localhost}"
export KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
export POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
export POSTGRES_PORT="${POSTGRES_PORT:-5432}"
export POSTGRES_USER="${POSTGRES_USER:-postgres}"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-postgres}"
# Each service already defaults to its own database name (authdb, userdb, orderdb, ...).

# The JWT signing key. auth-service signs tokens with it; every other service verifies
# against it, so they must all see the SAME value — which is why it is exported once here
# rather than configured per service. HS256 needs at least 32 characters.
export JWT_SECRET="${JWT_SECRET:-dev-only-jwt-secret-change-me-before-deploying-anywhere}"
# Seeds an admin account on first start so there is someone who can grant roles.
export BOOTSTRAP_ADMIN_USERNAME="${BOOTSTRAP_ADMIN_USERNAME:-admin}"
export BOOTSTRAP_ADMIN_PASSWORD="${BOOTSTRAP_ADMIN_PASSWORD:-admin12345}"

mkdir -p logs
: > logs/pids

start() {
  local module="$1"
  local jar
  jar=$(ls "${module}"/target/*.jar 2>/dev/null | head -n1 || true)
  if [[ -z "${jar}" ]]; then
    echo "ERROR: no jar for ${module}. Build first (see TESTING.md)." >&2
    exit 1
  fi
  echo "starting ${module} ..."
  java -jar "${jar}" > "logs/${module}.log" 2>&1 &
  echo "$!" >> logs/pids
}

# Discovery must be up before the others register.
start discovery-server
echo "waiting 15s for Eureka to come up ..."
sleep 15

for m in api-gateway auth-service user-service restaurant-service order-service \
         payment-service delivery-service notification-service; do
  start "${m}"
done

echo
echo "All services launching. Logs are in ./logs/*.log"
echo "  Eureka:    http://localhost:8761"
echo "  Gateway:   http://localhost:8080"
echo "  Kafka UI:  http://localhost:8090"
echo
echo "Every endpoint now needs a token. Log in as the seeded admin with:"
echo "  curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \\"
echo "    -d '{\"username\":\"${BOOTSTRAP_ADMIN_USERNAME}\",\"password\":\"${BOOTSTRAP_ADMIN_PASSWORD}\"}'"
echo "Give them ~20-30s to register, then follow TESTING.md."
