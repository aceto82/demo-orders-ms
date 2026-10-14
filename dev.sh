#!/usr/bin/env bash
#
# dev.sh — local development orchestrator for demo-orders-serv.
#
# Manages the Docker Compose infra and the Spring Boot microservices
# (started in the background, with logs written to logs/<service>.log),
# and runs the Maven test suite.
#
# Credentials come from the repository .env file: it is loaded here and exported
# to every process the script starts, so it wins over values exported by the
# invoking shell. The commands that start something refuse to run when a
# required variable is missing.
#
# Usage: ./dev.sh <command> [args]
# Run `./dev.sh help` for details.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_DIR="${DEV_RUN_DIR:-$ROOT_DIR/.run}"
LOG_DIR="${DEV_LOG_DIR:-$ROOT_DIR/logs}"
ENV_FILE="${DEV_ENV_FILE:-$ROOT_DIR/.env}"

# Services in start order: discovery first, gateway last.
SERVICES=(discovery-server orders-services inventory-service products-service notification-service api-gateway)
BUSINESS_SERVICES=(orders-services inventory-service products-service notification-service)
GATEWAY=api-gateway
DISCOVERY=discovery-server

# Bounded waits (seconds) and polling cadence; overridable for faster or slow machines.
INFRA_WAIT="${DEV_INFRA_WAIT:-120}"
SERVICE_WAIT="${DEV_SERVICE_WAIT:-90}"
POLL_INTERVAL="${DEV_POLL_INTERVAL:-2}"
HTTP_TIMEOUT="${DEV_HTTP_TIMEOUT:-5}"

# Stateful containers a service start depends on. dev.sh waits for each of these
# to report healthy before starting the services that use them.
INFRA_CONTAINERS=(db-orders db-inventory db-products db-keycloak keycloak kafka)

# Every variable each command group needs from .env.
REQUIRED_INFRA=(POSTGRES_USER POSTGRES_PASSWORD MYSQL_USER MYSQL_PASSWORD MYSQL_ROOT_PASSWORD KEYCLOAK_ADMIN_PASSWORD GRAFANA_ADMIN_PASSWORD)
REQUIRED_SERVICES=(DB_USERNAME DB_PASSWORD KEYCLOAK_CLIENT_SECRET EUREKA_USERNAME EUREKA_PASSWORD)

# Which stateful container each service depends on.
declare -A SERVICE_CONTAINER_DEPS=(
  [orders-services]=db-orders
  [inventory-service]=db-inventory
  [products-service]=db-products
  [notification-service]=kafka
  [api-gateway]=keycloak
)

# Actuator base path per service; health lives at <base>/health.
declare -A SERVICE_ACTUATOR_BASE=(
  [discovery-server]=/actuator/discovery
  [api-gateway]=/actuator
  [orders-services]=/actuator/orders
  [inventory-service]=/actuator/inventory
  [products-service]=/actuator/products
  [notification-service]=/actuator/notifications
)

# Services with a fixed port; the rest bind an ephemeral port (server.port: 0)
# that is read from the service log.
declare -A SERVICE_FIXED_PORT=(
  [discovery-server]=8761
  [api-gateway]=8080
)

# Per-start outcome, keyed by service name.
declare -A SVC_STATUS=()
declare -A SVC_REASON=()
LAST_REASON=""

usage() {
  cat <<EOF
Usage: ./dev.sh <command> [args]

Credentials come from .env: this script loads it and exports it to the stack and
to every service it starts, so it takes precedence over variables exported by
your shell.

Commands:
  up                                     Start the Docker stack and all services
  infra start|stop|restart|status        Manage the Docker Compose stack
  infra reset                            Destructive: discard database data and
                                         recreate the stack empty
  services start|stop|restart|status [s] Manage microservices (all if none given)
  logs <service>                         Follow a microservice log
  test [module...]                       Run Maven tests (all modules if none given)
  status                                 Show infra + microservices status
  help                                   Show this help

Infrastructure lifecycle:
  infra restart   keeps stored data (databases and the Keycloak realm)
  infra reset     DISCARDS stored data (docker compose down -v) and brings the
                  stack back with empty databases; re-run the one-time Keycloak
                  realm setup afterwards

Services:
  ${SERVICES[*]}

Examples:
  ./dev.sh infra start
  ./dev.sh services start                 # discovery, business services, gateway
  ./dev.sh services start orders-services # just one
  ./dev.sh services stop api-gateway
  ./dev.sh services status
  ./dev.sh logs orders-services
  ./dev.sh test orders-services
EOF
}

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 2
}

# --- .env and preflight ----------------------------------------------------

# Load .env and export every variable it defines, overriding the current
# environment (the file is the single source of credentials).
load_env() {
  [[ -f "$ENV_FILE" ]] || return 0
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
}

# Fail, without starting anything, when any of the named variables is unset.
require_env() {
  local -a missing=()
  local name
  for name in "$@"; do
    [[ -n "${!name:-}" ]] || missing+=("$name")
  done
  [[ ${#missing[@]} -eq 0 ]] && return 0
  if [[ ! -f "$ENV_FILE" ]]; then
    die "no $ENV_FILE found; copy .env.example to .env and fill it in (missing: ${missing[*]})"
  fi
  die "missing required variables in $ENV_FILE: ${missing[*]}"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
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

docker_compose() { (cd "$ROOT_DIR" && docker compose "$@"); }

container_health() {
  docker inspect -f '{{.State.Health.Status}}' "$1" 2>/dev/null || true
}

# Wait until every named container reports healthy, or INFRA_WAIT expires.
infra_gate() {
  local -a containers=("$@")
  [[ ${#containers[@]} -eq 0 ]] && return 0
  local waited=0 c st
  local -a pending=()
  while :; do
    pending=()
    for c in "${containers[@]}"; do
      st="$(container_health "$c")"
      [[ "$st" == "healthy" ]] || pending+=("$c")
    done
    [[ ${#pending[@]} -eq 0 ]] && return 0
    if [[ "$waited" -ge "$INFRA_WAIT" ]]; then
      for c in "${pending[@]}"; do
        st="$(container_health "$c")"
        printf "container '%s' is not healthy (state: %s)\n" "$c" "${st:-missing}" >&2
      done
      return 1
    fi
    sleep "$POLL_INTERVAL"
    waited=$((waited + POLL_INTERVAL))
  done
}

# Names of the containers the given services depend on.
required_containers_for() {
  local -A seen=()
  local svc c
  for svc in "$@"; do
    c="${SERVICE_CONTAINER_DEPS[$svc]:-}"
    [[ -n "$c" ]] && seen["$c"]=1
  done
  printf '%s\n' "${!seen[@]}"
}

http_status() {
  curl -s -o /dev/null -w '%{http_code}' --max-time "$HTTP_TIMEOUT" "$1" 2>/dev/null || true
}

# The HTTP port a service listens on: fixed for discovery/gateway, otherwise the
# port reported by the service's own startup log line.
port_from_log() {
  local log="$LOG_DIR/$1.log"
  [[ -f "$log" ]] || return 1
  grep -oE '(Tomcat|Netty) started on port [0-9]+' "$log" 2>/dev/null | tail -n 1 | grep -oE '[0-9]+$'
}

service_port() {
  local svc="$1" port
  port="${SERVICE_FIXED_PORT[$svc]:-}"
  if [[ -n "$port" ]]; then
    printf '%s' "$port"
    return 0
  fi
  port="$(port_from_log "$svc" || true)"
  [[ -n "$port" ]] || return 1
  printf '%s' "$port"
}

service_health_url() {
  local svc="$1" port
  port="$(service_port "$svc" || true)"
  [[ -n "$port" ]] || return 1
  printf 'http://127.0.0.1:%s%s/health' "$port" "${SERVICE_ACTUATOR_BASE[$svc]}"
}

health_probe() {
  local url
  url="$(service_health_url "$1" || true)"
  [[ -n "$url" ]] || return 1
  [[ "$(http_status "$url")" == "200" ]]
}

# --- infra -----------------------------------------------------------------

cmd_infra() {
  local action="${1:-}"
  [[ $# -gt 0 ]] && shift || true
  require_cmd docker
  case "$action" in
    start)
      require_env "${REQUIRED_INFRA[@]}"
      docker_compose up -d
      ;;
    stop)
      docker_compose down
      ;;
    restart)
      require_env "${REQUIRED_INFRA[@]}"
      docker_compose down
      docker_compose up -d
      ;;
    reset)
      require_env "${REQUIRED_INFRA[@]}"
      printf 'Resetting the stack: removing named volumes (databases and Keycloak realm).\n'
      docker_compose down -v
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

# Poll a service's actuator health endpoint until it answers 200 or SERVICE_WAIT
# expires. Sets LAST_REASON on failure.
wait_service_healthy() {
  local svc="$1" waited=0 url
  printf 'Waiting for %s to be healthy ' "$svc"
  while :; do
    if ! is_running "$svc"; then
      printf '\n'
      LAST_REASON="process exited; see $LOG_DIR/$svc.log"
      return 1
    fi
    url="$(service_health_url "$svc" || true)"
    if [[ -n "$url" ]] && [[ "$(http_status "$url")" == "200" ]]; then
      printf 'healthy\n'
      return 0
    fi
    if [[ "$waited" -ge "$SERVICE_WAIT" ]]; then
      printf '\n'
      if [[ -z "$url" ]]; then
        LAST_REASON="no HTTP port found in $LOG_DIR/$svc.log within ${SERVICE_WAIT}s"
      else
        LAST_REASON="health endpoint $url did not report UP within ${SERVICE_WAIT}s"
      fi
      return 1
    fi
    sleep "$POLL_INTERVAL"
    waited=$((waited + POLL_INTERVAL))
  done
}

print_start_summary() {
  local failed=0 s status
  printf '== Service summary ==\n'
  for s in "$@"; do
    status="${SVC_STATUS[$s]:-unknown}"
    case "$status" in
      healthy) printf '  %-20s healthy\n' "$s" ;;
      failed)
        printf '  %-20s FAILED - %s (log: %s)\n' "$s" "${SVC_REASON[$s]:-no details}" "$LOG_DIR/$s.log"
        failed=1
        ;;
      *) printf '  %-20s %s\n' "$s" "$status" ;;
    esac
  done
  [[ "$failed" -eq 0 ]]
}

cmd_services_start() {
  local -a targets=("$@")
  [[ ${#targets[@]} -eq 0 ]] && targets=("${SERVICES[@]}")
  local s
  for s in "${targets[@]}"; do
    is_known_service "$s" || die "Unknown service: $s"
  done

  require_env "${REQUIRED_SERVICES[@]}"
  require_cmd curl

  SVC_STATUS=()
  SVC_REASON=()

  local -a need=()
  local line
  while IFS= read -r line; do
    [[ -n "$line" ]] && need+=("$line")
  done < <(required_containers_for "${targets[@]}")

  if ! infra_gate "${need[@]}"; then
    printf 'Infrastructure is not ready; no service will be started.\n' >&2
    for s in "${targets[@]}"; do
      SVC_STATUS["$s"]=failed
      SVC_REASON["$s"]="required infrastructure is not healthy"
    done
    print_start_summary "${targets[@]}" || true
    return 1
  fi

  # Discovery first: the other services register with it.
  for s in "${targets[@]}"; do
    [[ "$s" == "$DISCOVERY" ]] || continue
    start_service "$s"
    if wait_service_healthy "$s"; then
      SVC_STATUS["$s"]=healthy
    else
      SVC_STATUS["$s"]=failed
      SVC_REASON["$s"]="$LAST_REASON"
    fi
  done

  for s in "${targets[@]}"; do
    [[ "$s" == "$DISCOVERY" ]] && continue
    start_service "$s"
    if wait_service_healthy "$s"; then
      SVC_STATUS["$s"]=healthy
    else
      SVC_STATUS["$s"]=failed
      SVC_REASON["$s"]="$LAST_REASON"
    fi
  done

  printf '\n'
  print_start_summary "${targets[@]}" || return 1
  return 0
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

# healthy  -> process alive and health endpoint answers
# starting -> process alive but health endpoint not ready yet
# failed   -> a pid file exists but the process is gone
# stopped  -> never started
service_state() {
  local svc="$1"
  if is_running "$svc"; then
    if health_probe "$svc"; then printf 'healthy'; else printf 'starting'; fi
  elif [[ -f "$(pid_file "$svc")" ]]; then
    printf 'failed'
  else
    printf 'stopped'
  fi
}

cmd_services_status() {
  local targets=("$@")
  [[ ${#targets[@]} -eq 0 ]] && targets=("${SERVICES[@]}")
  require_cmd curl
  local s state
  for s in "${targets[@]}"; do
    is_known_service "$s" || die "Unknown service: $s"
    state="$(service_state "$s")"
    if [[ "$state" == "failed" ]]; then
      printf '%-20s %s (log: %s)\n' "$s" "$state" "$LOG_DIR/$s.log"
    else
      printf '%-20s %s\n' "$s" "$state"
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
  require_env "${REQUIRED_INFRA[@]}" "${REQUIRED_SERVICES[@]}"
  require_cmd docker
  require_cmd curl
  docker_compose up -d
  cmd_services_start
}

# --- dispatch --------------------------------------------------------------

main() {
  load_env
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
