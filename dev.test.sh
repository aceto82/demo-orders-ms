#!/usr/bin/env bash
#
# Smoke tests for dev.sh — no Docker or Maven needed.
# Run with: ./dev.test.sh
set -uo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$SELF_DIR/dev.sh"

TMP_DIR="$(mktemp -d)"
export DEV_RUN_DIR="$TMP_DIR/run"
export DEV_LOG_DIR="$TMP_DIR/logs"
export DEV_INFRA_WAIT=0
export DEV_SERVICE_WAIT=0
export DEV_POLL_INTERVAL=1
mkdir -p "$DEV_RUN_DIR" "$DEV_LOG_DIR"

GOOD_ENV="$TMP_DIR/good.env"
BAD_ENV="$TMP_DIR/bad.env"
MISSING_ENV="$TMP_DIR/does-not-exist.env"
export DEV_ENV_FILE="$GOOD_ENV"

cat >"$GOOD_ENV" <<'EOF'
POSTGRES_USER=test-postgres-user
POSTGRES_PASSWORD=test-postgres-pass
MYSQL_USER=test-mysql-user
MYSQL_PASSWORD=test-mysql-pass
MYSQL_ROOT_PASSWORD=test-root-pass
DB_USERNAME=test-db-user
DB_PASSWORD=test-db-pass
KEYCLOAK_CLIENT_SECRET=test-client-secret
EUREKA_USERNAME=eureka
EUREKA_PASSWORD=test-eureka-pass
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=test-keycloak-pass
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=test-grafana-pass
EOF

# Same file without DB_PASSWORD, to exercise the preflight failure.
grep -v '^DB_PASSWORD=' "$GOOD_ENV" >"$BAD_ENV"

# Fake mvn/docker/curl so the orchestration can be exercised without real infra.
FAKE_BIN="$TMP_DIR/bin"
FAKE_LOG="$TMP_DIR/calls.log"
FAKE_STATE="$TMP_DIR/state"
mkdir -p "$FAKE_BIN" "$FAKE_STATE"
export FAKE_STATE

cat >"$FAKE_BIN/docker" <<EOF
#!/usr/bin/env bash
printf 'docker %s\n' "\$*" >>"$FAKE_LOG"
if [[ "\${1:-}" == "inspect" ]]; then
  name="\${@: -1}"
  if [[ -f "$FAKE_STATE/\$name" ]]; then cat "$FAKE_STATE/\$name"; else printf 'healthy\n'; fi
fi
exit 0
EOF

cat >"$FAKE_BIN/mvn" <<EOF
#!/usr/bin/env bash
printf 'mvn %s\n' "\$*" >>"$FAKE_LOG"
printf '  env DB_PASSWORD=%s DB_USERNAME=%s EUREKA_PASSWORD=%s KEYCLOAK_CLIENT_SECRET=%s\n' \
  "\${DB_PASSWORD:-}" "\${DB_USERNAME:-}" "\${EUREKA_PASSWORD:-}" "\${KEYCLOAK_CLIENT_SECRET:-}" >>"$FAKE_LOG"
# Stay alive so dev.sh can treat the "service" as a running process.
exec sleep 300
EOF

cat >"$FAKE_BIN/curl" <<EOF
#!/usr/bin/env bash
printf 'curl %s\n' "\$*" >>"$FAKE_LOG"
url="\${@: -1}"
if [[ -n "\${FAKE_HTTP_DOWN:-}" && "\$url" == *"\${FAKE_HTTP_DOWN}"* ]]; then
  printf '%s' "\${FAKE_HTTP_DOWN_CODE:-503}"
  exit 0
fi
printf '%s' "\${FAKE_HTTP_DEFAULT:-200}"
exit 0
EOF

chmod +x "$FAKE_BIN/docker" "$FAKE_BIN/mvn" "$FAKE_BIN/curl"
export PATH="$FAKE_BIN:$PATH"

LIVE_PIDS=()

cleanup() {
  local f pid
  for f in "$DEV_RUN_DIR"/*.pid; do
    [[ -e "$f" ]] || continue
    pid="$(cat "$f" 2>/dev/null || true)"
    [[ -n "$pid" ]] && kill -9 "$pid" 2>/dev/null
  done
  for pid in "${LIVE_PIDS[@]:-}"; do
    [[ -n "$pid" ]] && kill -9 "$pid" 2>/dev/null
  done
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

PASS=0
FAIL=0
OUTPUT=""
STATUS=0

ok() { PASS=$((PASS + 1)); printf '  ok   %s\n' "$1"; }
no() {
  FAIL=$((FAIL + 1))
  printf '  FAIL %s\n' "$1"
  [[ -n "${2:-}" ]] && printf '       %s\n' "$2"
}
start() { printf '\n%s\n' "$1"; }

run() {
  OUTPUT="$(bash "$SCRIPT" "$@" 2>&1)"
  STATUS=$?
}
expect_status() {
  if [[ "$STATUS" -eq "$1" ]]; then ok "exit code = $1"; else no "exit code = $1" "got $STATUS"; fi
}
expect_contains() {
  if [[ "$OUTPUT" == *"$1"* ]]; then ok "output contains: $1"; else no "output contains: $1" "actual: $OUTPUT"; fi
}
expect_log_contains() {
  if grep -qF "$1" "$FAKE_LOG"; then ok "calls include: $1"; else no "calls include: $1" "log: $(cat "$FAKE_LOG")"; fi
}
expect_log_absent() {
  if grep -qF "$1" "$FAKE_LOG"; then no "calls exclude: $1" "log: $(cat "$FAKE_LOG")"; else ok "calls exclude: $1"; fi
}

# Clears run/log/state so each test starts from a known situation.
reset_state() {
  local f pid
  for f in "$DEV_RUN_DIR"/*.pid; do
    [[ -e "$f" ]] || continue
    pid="$(cat "$f" 2>/dev/null || true)"
    [[ -n "$pid" ]] && kill -9 "$pid" 2>/dev/null
  done
  rm -rf "$DEV_RUN_DIR" "$DEV_LOG_DIR" "$FAKE_STATE"
  mkdir -p "$DEV_RUN_DIR" "$DEV_LOG_DIR" "$FAKE_STATE"
  : >"$FAKE_LOG"
}

# Writes the bound-port log line dev.sh reads for services with an ephemeral port.
fake_service_log() {
  printf 'Tomcat started on port %s (http) with context path /\n' "$2" >"$DEV_LOG_DIR/$1.log"
}

# ---------------------------------------------------------------------------

start "no arguments prints usage and exits 2"
run
expect_status 2
expect_contains "Usage:"

start "help exits 0 and prints usage"
run help
expect_status 0
expect_contains "Usage:"
expect_contains "infra reset"

start "help documents the destructive reset"
if [[ "$OUTPUT" == *"DISCARDS"* ]]; then ok "help marks reset as destructive"; else no "help marks reset as destructive" "actual: $OUTPUT"; fi

start "unknown command exits 2"
run frobnicate
expect_status 2
expect_contains "Unknown command"

start "services start with an unknown service exits 2"
run services start nope-service
expect_status 2
expect_contains "Unknown service"

start "infra with an unknown action exits 2"
run infra nope
expect_status 2
expect_contains "Unknown infra action"

start "logs with an unknown service exits 2"
run logs nope-service
expect_status 2
expect_contains "Unknown service"

start "services status lists every service as stopped"
reset_state
run services status
expect_status 0
for svc in discovery-server orders-services inventory-service products-service notification-service api-gateway; do
  expect_contains "$svc"
done
expect_contains "stopped"

# --- .env handling ---------------------------------------------------------

start "a missing .env stops the command without calling docker or mvn"
reset_state
export DEV_ENV_FILE="$MISSING_ENV"
run up
expect_status 2
expect_contains "copy .env.example to .env"
expect_log_absent "docker"
expect_log_absent "mvn"
export DEV_ENV_FILE="$GOOD_ENV"

start "a missing required variable is named and nothing starts"
reset_state
export DEV_ENV_FILE="$BAD_ENV"
run services start orders-services
expect_status 2
expect_contains "DB_PASSWORD"
expect_log_absent "docker"
expect_log_absent "mvn"
export DEV_ENV_FILE="$GOOD_ENV"

start "help works with an incomplete .env"
export DEV_ENV_FILE="$BAD_ENV"
run help
expect_status 0
expect_contains "Usage:"
export DEV_ENV_FILE="$GOOD_ENV"

start ".env.example satisfies the preflight"
reset_state
cp "$SELF_DIR/.env.example" "$TMP_DIR/example.env"
export DEV_ENV_FILE="$TMP_DIR/example.env"
for svc in orders-services inventory-service products-service notification-service; do
  case "$svc" in
    orders-services) port=42001 ;;
    inventory-service) port=42002 ;;
    products-service) port=42003 ;;
    notification-service) port=42004 ;;
  esac
  fake_service_log "$svc" "$port"
done
run up
expect_status 0
if [[ "$OUTPUT" != *"missing required variables"* ]]; then ok "no missing variables reported"; else no "no missing variables reported" "actual: $OUTPUT"; fi
export DEV_ENV_FILE="$GOOD_ENV"

start ".env wins over a conflicting value exported by the shell"
reset_state
fake_service_log orders-services 50001
export DB_PASSWORD="from-shell"
run services start orders-services
expect_status 0
expect_log_contains "env DB_PASSWORD=test-db-pass"
expect_log_absent "env DB_PASSWORD=from-shell"
unset DB_PASSWORD
export DEV_ENV_FILE="$GOOD_ENV"

# --- infrastructure readiness gate ----------------------------------------

start "no service is started while a dependency container is still starting"
reset_state
printf 'starting\n' >"$FAKE_STATE/keycloak"
run up
expect_status 1
expect_contains "keycloak"
expect_contains "not healthy"
expect_log_absent "spring-boot:run"

start "the gateway is started only once Keycloak is healthy"
reset_state
printf 'starting\n' >"$FAKE_STATE/keycloak"
run services start api-gateway
expect_status 1
expect_contains "api-gateway"
expect_contains "api-gateway.log"
expect_log_absent "spring-boot:run"
printf 'healthy\n' >"$FAKE_STATE/keycloak"
run services start api-gateway
expect_status 0
expect_log_contains "mvn -q -pl api-gateway spring-boot:run"

# --- per-service health ----------------------------------------------------

start "a service answering 200 on its health endpoint is healthy"
reset_state
fake_service_log inventory-service 54321
run services start inventory-service
expect_status 0
expect_contains "healthy"
expect_contains "inventory-service"

start "a service with no port in its log is reported failed, not hung"
reset_state
run services start products-service
expect_status 1
expect_contains "products-service"
expect_contains "products-service.log"

start "a failing service does not stop the others and the exit code is 1"
reset_state
fake_service_log orders-services 40001
fake_service_log inventory-service 40002
fake_service_log products-service 40003
export FAKE_HTTP_DOWN=inventory
export FAKE_HTTP_DOWN_CODE=503
run services start orders-services inventory-service products-service
expect_status 1
expect_contains "inventory-service"
expect_contains "inventory-service.log"
count="$(grep -c 'spring-boot:run' "$FAKE_LOG" || true)"
if [[ "$count" -eq 3 ]]; then ok "started all three services"; else no "started all three services" "got $count"; fi
unset FAKE_HTTP_DOWN FAKE_HTTP_DOWN_CODE

# --- status ----------------------------------------------------------------

start "status reports a stale pid file as failed"
reset_state
echo 999999 >"$DEV_RUN_DIR/orders-services.pid"
run services status orders-services
expect_status 0
expect_contains "failed"

start "status reports a live process without a healthy endpoint as starting"
reset_state
sleep 300 &
LIVE_PIDS+=("$!")
echo "$!" >"$DEV_RUN_DIR/inventory-service.pid"
export FAKE_HTTP_DEFAULT=503
run services status inventory-service
expect_status 0
expect_contains "starting"

start "status reports a never-started service as stopped"
reset_state
run services status products-service
expect_status 0
expect_contains "stopped"
export FAKE_HTTP_DEFAULT=200

start "up starts the docker stack and all services"
reset_state
for svc in orders-services inventory-service products-service notification-service; do
  case "$svc" in
    orders-services) port=41001 ;;
    inventory-service) port=41002 ;;
    products-service) port=41003 ;;
    notification-service) port=41004 ;;
  esac
  fake_service_log "$svc" "$port"
done
run up
expect_status 0
expect_log_contains "docker compose up -d"
expect_log_contains "mvn -q -pl discovery-server spring-boot:run"
expect_log_contains "mvn -q -pl api-gateway spring-boot:run"
count="$(grep -c 'spring-boot:run' "$FAKE_LOG" || true)"
if [[ "$count" -eq 6 ]]; then ok "started exactly 6 services"; else no "started exactly 6 services" "got $count"; fi

start "the up summary and services status agree"
run services status
expect_status 0
healthy="$(printf '%s\n' "$OUTPUT" | grep -c 'healthy' || true)"
if [[ "$healthy" -eq 6 ]]; then ok "status reports all six as healthy"; else no "status reports all six as healthy" "got $healthy: $OUTPUT"; fi

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
