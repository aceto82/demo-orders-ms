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
mkdir -p "$DEV_RUN_DIR" "$DEV_LOG_DIR"

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

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
