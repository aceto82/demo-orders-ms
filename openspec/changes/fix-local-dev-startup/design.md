# Design

## Context

See `proposal.md` - Why for the four diagnosed defects. The constraints that shape the
approach:

- `dev.sh` must stay a dependency-light bash script: today it needs only `docker compose`
  and `mvn` (or per-module `./mvnw`). Anything new it depends on must be justified and
  must be fakeable so `dev.test.sh` keeps exercising the orchestration without Docker or
  Maven.
- The services bind `server.port: 0` and register with Eureka, so their HTTP ports are
  ephemeral and cannot be used for readiness checks from outside. The gateway (`8080`) and
  the discovery server (`8761`) are the only fixed ports.
- Every service exposes an actuator health endpoint under a non-default base path
  (`/actuator/orders`, `/actuator/inventory`, `/actuator/products`,
  `/actuator/notifications`, `/actuator/discovery`); the gateway keeps the default
  `/actuator`. Health is reachable without authentication; `discovery-server` permits
  `/actuator/discovery/health` publicly.
- The compose services all declare `container_name`, so container state can be inspected
  with `docker inspect <name>` without parsing JSON.
- The containers' data currently lives in anonymous volumes (observed mount point
  `/var/lib/postgresql` for the PostgreSQL services, `/var/lib/mysql` for MySQL), which is
  what makes `down` + `up` destructive.
- `.env` is consumed by compose only. `application.yaml` has no default for
  `DB_PASSWORD`, and the gateway resolves the Keycloak issuer eagerly at startup, so a
  missing `.env` value is fatal rather than degraded.

## Goals / Non-Goals

**Goals:**

- One command (`./dev.sh up`) brings up a stack in which every service is reachable and
  registered, or fails loudly.
- Credentials have exactly one source for both containers and services.
- Starting and restarting the stack never silently destroys developer state; discarding
  state is an explicit, documented action.
- Failures are attributable: the developer is told which service failed and where its log
  is, without reading `logs/` blindly.

**Non-Goals:**

- Reproducible Keycloak realm provisioning (the realm stays a documented one-time manual
  step; this change only makes it survive restarts).
- Fixed service ports, running packaged jars instead of `spring-boot:run`, adding CI, or
  changing the runtime topology (Eureka, `lb://` routing, the circuit breaker).
- Migrating data out of the existing anonymous volumes.

## Decisions

### D1: Fix Eureka registration by correcting the configuration key, not the transport

Rename `eureka.client.service-url.default-zone` to `defaultZone` in the five client
services. Spring Boot binds map keys literally (verified with the Boot binder:
`default-zone` stays `default-zone`), while Eureka reads `defaultZone`, so the configured
URL - including the `eureka:…@` userinfo - was never used and the client silently fell
back to the unauthenticated localhost URL. End-to-end A/B against the running registry:
committed key -> `401` / `attempting to refresh its cache` failures; `defaultZone` ->
`registration status: 204` and the instance listed as `UP`.

Alternatives considered: dropping authentication from `/eureka/**` (weakens the registry
and contradicts the existing security setup); replacing the URL userinfo with an
explicit `EurekaClientConfigBean`/`RestClient` credentials bean (more code for the same
result, and unnecessarily couples the fix to the transport implementation).

### D2: `dev.sh` sources `.env` and exports it, taking precedence over the shell

Load the repository `.env` at the top of the script with `set -a` semantics and validate
required keys before any side effect. Making `.env` authoritative (rather than
"fill in what is missing") is the point: compose and the services must agree, and a stale
exported value is what produced the observed `Access denied` / `password authentication
failed` errors while `.env` itself was correct.

Alternatives considered: `--env-file` style pass-through to Maven (the services are
started as Maven child processes, so env is the natural channel); a Spring
`spring.config.import: optional:file:.env` in each service (11 files touched and it moves
a local-dev concern into production config); letting the shell win (reproduces the bug).

Validation is scoped to the commands that need it (`up`, `infra start|restart|reset`,
`services start|restart`); `help`, `status`, `logs` and `test` stay usable without a
complete `.env`.

### D3: Wait on container health states, not on ports or sleeps

Poll `docker inspect -f '{{.State.Health.Status}}' <container_name>` for the stateful
services until each reports `healthy`, with a bounded timeout (`DEV_INFRA_WAIT`, default
120s). This reuses the healthchecks added to compose and needs no JSON parsing, because
the compose file already pins container names. Container health is preferable to probing
ports because a database that has opened its port but is still initialising is exactly
the state that broke the previous "start everything immediately" behaviour.

Alternatives considered: `docker compose up -d --wait` (clean, but adds a Compose version
requirement for the same result and does not give per-service messaging); probing
databases with `nc`/`/dev/tcp` (only proves the port is open, not that credentials work);
fixed sleeps (the current `wait_for_port` pattern, unreliable).

### D4: Verify each service against its own actuator health endpoint

After starting a service, poll its health endpoint until it reports `UP` or
`DEV_SERVICE_WAIT` (default 90s) expires. Endpoint resolution:

- `discovery-server` -> `http://127.0.0.1:8761/actuator/discovery/health`
- `api-gateway` -> `http://127.0.0.1:8080/actuator/health`
- the remaining services -> the port is read from the service's own log
  (`logs/<service>.log`), matching the bound-port line emitted by Tomcat/Netty, then
  `http://127.0.0.1:<port>/actuator/<service>/health`

For the business services this also retroactively proves the capability the proposal
cares about: their health endpoint is served only by a running instance, and a database
failure makes health report `DOWN`. Polling by log-derived port, rather than through the
gateway, keeps notification-service (which has no gateway route) symmetric with the rest
and avoids adding a route purely for diagnostics.

Alternatives considered: probing through gateway routes (works today for
orders/inventory/products/discovery, needs a new route for notification, and reports a
healthy gateway rather than a healthy service); resolving the port from the Eureka
registry (elegant and doubles as a registration check, but it reports "not registered"
when the process is healthy and only the registry is broken - two failure modes that
should stay distinguishable).

### D5: HTTP probing uses `curl`, and the fakes in `dev.test.sh` grow with it

Health polling uses `curl -s -o /dev/null -w '%{http_code}' --max-time N`. `curl` is
checked in the preflight step for the commands that read health, so a missing client
produces an actionable message rather than a confusing failure. `curl` is chosen over a
hand-rolled `/dev/tcp` HTTP client because `dev.test.sh` already stubs external commands
(`mvn`, `docker`) on `PATH`; stubbing `curl` gives deterministic coverage of the
readiness, health, status and failure paths without opening sockets.

Alternatives considered: pure-bash `/dev/tcp` probing (no new dependency, but the tests
would need real listening sockets to cover the interesting paths); `jq`/`python` for
compose JSON (rejected with D3).

### D6: Compose gains named volumes and healthchecks

- Add named volumes `orders-db-data`, `inventory-db-data`, `products-db-data`,
  `keycloak-db-data` and attach them at the mount points the images already use
  (`/var/lib/mysql`, `/var/lib/postgresql`). Named volumes survive `down`/`up` and are
  removed only by `down -v`, which makes "restart preserves, reset discards" a property
  of the stack rather than a promise in a script.
- Healthchecks: `db-orders` uses `mysqladmin status` with the root credentials. `ping` is
  not used because it exits `0` even when the server answers `Access denied`, so it cannot
  catch a `.env`/volume mismatch; `status` authenticates. The PostgreSQL services use
  `psql -c 'select 1'` against the application database, connected through the container
  address rather than `127.0.0.1`, because the image's `pg_hba.conf` `trust`s loopback and
  a loopback probe would therefore ignore the password. Together these verify credentials
  as well as availability (a mismatch between `.env` and an existing
  volume therefore surfaces as `unhealthy` instead of a Spring stack trace);
  `db-keycloak` uses `pg_isready`; `keycloak` enables `KC_HEALTH_ENABLED` and probes
  `/health/ready` on its management port with a shell TCP check (the image is a
  micro-based image without a general-purpose HTTP client); `kafka` uses
  `kafka-broker-api-versions`. `zookeeper`, `zipkin`, `prometheus` and `grafana` are not
  dependencies of any service start and get no healthcheck.

### D7: Explicit infrastructure lifecycle

`infra restart` stays `down` + `up -d`, which now preserves data because of D6. A new
`infra reset` runs `docker compose down -v` followed by `up -d`, and the help output
states that reset discards database contents and therefore also the Keycloak realm.

### D8: Start everything, then report

`services start` (and `up`) keep starting the remaining services when one fails, so a
single run produces a full picture, then print a per-service summary and exit `1` if any
service never became healthy. Each failure line includes the log path
(`logs/<service>.log`). This changes the exit contract of `up`, which is why
`dev.test.sh`'s existing "up starts the docker stack and all services" expectation must
be updated in the same change.

## Risks / Trade-offs

- [`.env` now wins over exported variables] -> Documented in the README and in
  `dev.sh help`; an explicit message is not needed because the file is the documented
  source of truth.
- [Adding named volumes re-initialises the databases and orphans the existing anonymous
  volumes] -> Expected and intentional (the old data was already being discarded by every
  restart); the Keycloak realm must be re-created once after this change. Called out in
  the README and in the change's task list.
- [Postgres healthchecks validate credentials, so a stale volume makes the container
  report unhealthy and blocks `up`] -> Intentional fail-fast; the failure message points
  at `./dev.sh infra reset`.
- [Log-derived ports couple health polling to the services' log format] -> Match both the
  Tomcat and Netty bound-port lines; when no port is found within the timeout, report the
  service as failed with its log path rather than hanging. If it proves brittle, the
  fallback is D4's alternative (registry-resolved port).
- [Health polling lengthens `up`] -> Bounded by `DEV_INFRA_WAIT` / `DEV_SERVICE_WAIT`, both
  overridable, and polling stops as soon as each unit is healthy.
- [`defaultZone` change alters how all five clients authenticate to the registry] ->
  Verified against the live registry during diagnosis (204 + `UP`); the registry
  credentials keep coming from `EUREKA_USERNAME` / `EUREKA_PASSWORD`, which D2 now
  supplies to both the server and the clients from one source.
- [Keycloak management port for healthchecks] -> Only used for in-container probing; no
  new host port is published.

## Migration Plan

1. Land the `defaultZone` fix, the compose volumes/healthchecks, and the `dev.sh` change
   together; they are mutually dependent (healthchecks are what `dev.sh` waits on).
2. First start after the change: databases come up empty in their new named volumes, so
   re-apply the Keycloak realm setup from the README once. No application data exists to
   migrate.
3. Rollback is `git revert` plus `docker compose down`; leftover named volumes are inert
   and can be removed with `docker compose down -v`.

## Open Questions

None - the remaining unknowns (exact timeout defaults, polling interval) are tunables
recorded as tasks, not decisions that change the specs or the approach.
