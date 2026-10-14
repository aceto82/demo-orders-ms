#!/usr/bin/env bash
#
# dev.sh — local development orchestrator for demo-orders-serv.
#
# Manages the Docker Compose infra and the Spring Boot microservices
# (started in the background, with logs written to logs/<service>.log),
# and runs the Maven test suite.
#
# Usage: ./dev.sh <command> [args]
# Run `./dev.sh help` for details.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_DIR="${DEV_RUN_DIR:-$ROOT_DIR/.run}"
LOG_DIR="${DEV_LOG_DIR:-$ROOT_DIR/logs}"

# Services in start order: discovery first, gateway last.
SERVICES=(discovery-server orders-services inventory-service products-service notification-service api-gateway)
BUSINESS_SERVICES=(orders-services inventory-service products-service notification-service)
GATEWAY=api-gateway
DISCOVERY=discovery-server
DISCOVERY_PORT=8761
DISCOVERY_WAIT="${DEV_DISCOVERY_WAIT:-60}"

usage() {
  cat <<EOF
Usage: ./dev.sh <command> [args]

Commands:
  up                                     Start the Docker stack and all services
  infra start|stop|restart|status        Manage the Docker Compose stack
  services start|stop|restart|status [s] Manage microservices (all if none given)
  logs <service>                         Follow a microservice log
  test [module...]                       Run Maven tests (all modules if none given)
  status                                 Show infra + microservices status
  help                                   Show this help

Services:
  ${SERVICES[*]}

Examples:
  ./dev.sh infra start
  ./dev.sh services start                 # discovery, business services, gateway
  ./dev.sh services start orders-services # just one
  ./dev.sh services stop api-gateway
  ./dev.sh logs orders-services
  ./dev.sh test orders-services
EOF
}

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 2
}

# --- helpers ---------------------------------------------------------------

is_known_service() {
  local s
  for s in "${SERVICES[@]}"; do
    [[ "$s" == "$1" ]] && return 0
  done
  return 1
}

pid_file() { printf '%s/%s.pid' "$RUN_DIR" "$1"; }

is_running() {
  local f pid
  f="$(pid_file "$1")"
  [[ -f "$f" ]] || return 1
  pid="$(cat "$f" 2>/dev/null || true)"
  [[ -n "$pid" ]] || return 1
  kill -0 "$pid" 2>/dev/null
}

mvn_available() { command -v mvn >/dev/null 2>&1; }

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1; }

wait_for_port() {
  local port="$1" timeout="${2:-60}" waited=0
  printf 'Waiting for port %s ' "$port"
  while ! port_open "$port"; do
    if [[ "$waited" -ge "$timeout" ]]; then
      printf '\n'
      printf 'Warning: port %s did not open within %ss (continuing)\n' "$port" "$timeout" >&2
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  printf 'ready\n'
}

docker_compose() { (cd "$ROOT_DIR" && docker compose "$@"); }

# --- infra -----------------------------------------------------------------

cmd_infra() {
  local action="${1:-}"
  [[ $# -gt 0 ]] && shift || true
  case "$action" in
    start) docker_compose up -d ;;
    stop) docker_compose down ;;
    restart)
      docker_compose down
      docker_compose up -d
      ;;
    status) docker_compose ps ;;
    *) die "Unknown infra action: ${action:-<none>}" ;;
  esac
}

# --- services --------------------------------------------------------------

start_service() {
  local svc="$1"
  is_known_service "$svc" || die "Unknown service: $svc"

  if is_running "$svc"; then
    printf '%s already running (pid %s)\n' "$svc" "$(cat "$(pid_file "$svc")")"
    return 0
  fi

  mkdir -p "$RUN_DIR" "$LOG_DIR"
  local log="$LOG_DIR/$svc.log"
  printf 'Starting %s ... (log: %s)\n' "$svc" "$log"

  if mvn_available; then
    (cd "$ROOT_DIR" && exec nohup mvn -q -pl "$svc" spring-boot:run) \
      </dev/null >>"$log" 2>&1 &
  else
    (cd "$ROOT_DIR/$svc" && exec nohup ./mvnw -q spring-boot:run) \
      </dev/null >>"$log" 2>&1 &
  fi
  echo $! >"$(pid_file "$svc")"
}

stop_service() {
  local svc="$1"
  is_known_service "$svc" || die "Unknown service: $svc"

  if ! is_running "$svc"; then
    printf '%s not running\n' "$svc"
    rm -f "$(pid_file "$svc")"
    return 0
  fi

  local pid
  pid="$(cat "$(pid_file "$svc")")"
  printf 'Stopping %s (pid %s) ...\n' "$svc" "$pid"
  kill_tree "$pid"

  local i
  for i in {1..20}; do
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.5
  done
  kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
  rm -f "$(pid_file "$svc")"
}

# Kill a process and its descendants (Maven forks the Spring Boot JVM).
kill_tree() {
  local pid="$1" child
  for child in $(pgrep -P "$pid" 2>/dev/null || true); do
    kill_tree "$child"
  done
  kill "$pid" 2>/dev/null || true
}

cmd_services_start() {
  if [[ $# -eq 0 ]]; then
    start_service "$DISCOVERY"
    wait_for_port "$DISCOVERY_PORT" "$DISCOVERY_WAIT"
    local s
    for s in "${BUSINESS_SERVICES[@]}"; do
      start_service "$s"
    done
    start_service "$GATEWAY"
    return 0
  fi

  local s
  for s in "$@"; do
    start_service "$s"
    [[ "$s" == "$DISCOVERY" ]] && wait_for_port "$DISCOVERY_PORT" "$DISCOVERY_WAIT"
  done
}

cmd_services_stop() {
  local targets=("$@")
  if [[ ${#targets[@]} -eq 0 ]]; then
    # Reverse order: gateway, business services, discovery.
    targets=("$GATEWAY" "${BUSINESS_SERVICES[@]}" "$DISCOVERY")
  fi
  local s
  for s in "${targets[@]}"; do
    stop_service "$s"
  done
}

cmd_services_status() {
  local targets=("$@")
  [[ ${#targets[@]} -eq 0 ]] && targets=("${SERVICES[@]}")
  local s
  for s in "${targets[@]}"; do
    is_known_service "$s" || die "Unknown service: $s"
    if is_running "$s"; then
      printf '%-20s running (pid %s)\n' "$s" "$(cat "$(pid_file "$s")")"
    else
      printf '%-20s stopped\n' "$s"
    fi
  done
}

cmd_services() {
  local action="${1:-}"
  [[ $# -gt 0 ]] && shift || true
  case "$action" in
    start) cmd_services_start "$@" ;;
    stop) cmd_services_stop "$@" ;;
    restart)
      cmd_services_stop "$@"
      cmd_services_start "$@"
      ;;
    status) cmd_services_status "$@" ;;
    *) die "Unknown services action: ${action:-<none>}" ;;
  esac
}

# --- logs ------------------------------------------------------------------

cmd_logs() {
  local svc="${1:-}"
  [[ -n "$svc" ]] || die "logs requires a service name"
  is_known_service "$svc" || die "Unknown service: $svc"
  local log="$LOG_DIR/$svc.log"
  [[ -f "$log" ]] || die "No log for $svc yet ($log)"
  tail -n 50 -f "$log"
}

# --- tests -----------------------------------------------------------------

cmd_test() {
  local modules=("$@")

  if [[ ${#modules[@]} -eq 0 ]]; then
    printf 'Running all tests ...\n'
    if mvn_available; then
      (cd "$ROOT_DIR" && mvn test)
    else
      local m
      for m in "${SERVICES[@]}"; do
        printf '\n-> %s\n' "$m"
        (cd "$ROOT_DIR/$m" && ./mvnw test)
      done
    fi
    return 0
  fi

  local m
  for m in "${modules[@]}"; do
    [[ -d "$ROOT_DIR/$m" ]] || die "Unknown module: $m"
  done

  printf 'Running tests for: %s ...\n' "${modules[*]}"
  if mvn_available; then
    local csv
    csv="$(IFS=,; echo "${modules[*]}")"
    (cd "$ROOT_DIR" && mvn -pl "$csv" test)
  else
    for m in "${modules[@]}"; do
      (cd "$ROOT_DIR/$m" && ./mvnw test)
    done
  fi
}

# --- status ----------------------------------------------------------------

cmd_status() {
  printf '== Docker Compose ==\n'
  docker_compose ps || true
  printf '\n== Microservices ==\n'
  cmd_services_status
}

# --- shortcuts -------------------------------------------------------------

cmd_up() {
  cmd_infra start
  cmd_services start
}

# --- dispatch --------------------------------------------------------------

main() {
  local cmd="${1:-}"
  [[ $# -gt 0 ]] && shift || true

  case "$cmd" in
    "") usage >&2; exit 2 ;;
    help | -h | --help) usage ;;
    up) cmd_up ;;
    infra) cmd_infra "$@" ;;
    services | svc) cmd_services "$@" ;;
    logs) cmd_logs "$@" ;;
    test) cmd_test "$@" ;;
    status) cmd_status "$@" ;;
    *)
      printf 'Unknown command: %s\n\n' "$cmd" >&2
      usage >&2
      exit 2
      ;;
  esac
}

main "$@"
