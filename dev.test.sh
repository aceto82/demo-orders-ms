#!/usr/bin/env bash
#
# Smoke tests for dev.sh — no Docker or Maven needed.
# Run with: ./dev.test.sh
set -uo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$SELF_DIR/dev.sh"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
export DEV_RUN_DIR="$TMP_DIR/run"
export DEV_LOG_DIR="$TMP_DIR/logs"
export DEV_DISCOVERY_WAIT=0
mkdir -p "$DEV_RUN_DIR" "$DEV_LOG_DIR"

# Fake mvn/docker so the orchestration can be exercised without real infra.
FAKE_BIN="$TMP_DIR/bin"
FAKE_LOG="$TMP_DIR/calls.log"
mkdir -p "$FAKE_BIN"
cat >"$FAKE_BIN/docker" <<EOF
#!/usr/bin/env bash
echo "docker \$*" >>"$FAKE_LOG"
exit 0
EOF
cat >"$FAKE_BIN/mvn" <<EOF
#!/usr/bin/env bash
echo "mvn \$*" >>"$FAKE_LOG"
exit 0
EOF
chmod +x "$FAKE_BIN/docker" "$FAKE_BIN/mvn"
export PATH="$FAKE_BIN:$PATH"

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

start "no arguments prints usage and exits 2"
run
expect_status 2
expect_contains "Usage:"

start "help exits 0 and prints usage"
run help
expect_status 0
expect_contains "Usage:"

start "unknown command exits 2"
run frobnicate
expect_status 2
expect_contains "Unknown command"

start "services start with an unknown service exits 2"
run services start nope-service
expect_status 2
expect_contains "Unknown service"

start "services status lists every service as stopped"
run services status
expect_status 0
for svc in discovery-server orders-services inventory-service products-service notification-service api-gateway; do
  expect_contains "$svc"
done
expect_contains "stopped"

start "infra with an unknown action exits 2"
run infra nope
expect_status 2
expect_contains "Unknown infra action"

start "logs with an unknown service exits 2"
run logs nope-service
expect_status 2
expect_contains "Unknown service"

start "up starts the docker stack and all services"
: >"$FAKE_LOG"
run up
expect_status 0
expect_log_contains "docker compose up -d"
expect_log_contains "mvn -q -pl discovery-server spring-boot:run"
expect_log_contains "mvn -q -pl api-gateway spring-boot:run"
count="$(grep -c 'spring-boot:run' "$FAKE_LOG" || true)"
if [[ "$count" -eq 6 ]]; then ok "started exactly 6 services"; else no "started exactly 6 services" "got $count"; fi

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
