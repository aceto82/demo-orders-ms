# Proposal

## Why

`./dev.sh up` is documented as the one-command way to bring up the local stack, but
today it leaves the environment broken: services either fail to start or start without
being able to talk to Eureka or the databases. Diagnosis of a real run (logs under
`logs/`, plus A/B and container experiments) found four independent defects:

- **Eureka registration never succeeds.** All five Eureka clients
  (`orders-services`, `inventory-service`, `products-service`, `notification-service`,
  `api-gateway`) set `eureka.client.service-url.default-zone`. Spring Boot binds that
  YAML key literally as `default-zone`, but Eureka looks up `defaultZone`, so the
  configured URL (with the `eureka:…@` credentials) is ignored and the client falls back
  to the unauthenticated `http://localhost:8761/eureka/`. The discovery server answers
  `401`, registration fails with `Cannot execute request on any known server`, and every
  `lb://` gateway route has no instance to resolve. Verified: switching the key to
  `defaultZone` yields `registration status: 204` and the instance shows `UP` in Eureka.
- **`dev.sh` never loads `.env`.** Reading `.env` only happens inside `docker compose`,
  so the Spring processes depend on whatever happens to be exported in the invoking
  shell. `application.yaml` requires `DB_PASSWORD` (no default) and the gateway needs a
  resolvable Keycloak issuer plus `KEYCLOAK_CLIENT_SECRET`; with a stale or missing
  shell environment services fail at startup or talk to the database with the wrong
  password. The README already promises dev.sh "uses the `.env` from step 2".
- **No readiness gating.** `up` runs `docker compose up -d` and immediately starts all
  services, and the compose stack defines no healthchecks. The gateway resolves the
  Keycloak issuer eagerly and dies with `Unable to resolve Configuration with the
  provided Issuer …` if Keycloak is not ready yet; database-backed services have the same
  race against MySQL/PostgreSQL.
- **`infra restart` destroys all data.** The four database services declare no volumes,
  so their data lives in anonymous volumes that are abandoned when the container is
  recreated. Verified: a row written to `db-orders` is gone after `dev.sh infra restart`
  (`down` + `up -d`). The Keycloak realm is stored the same way and is created manually,
  so every infra restart silently wipes it and breaks JWT validation for the whole stack.

Additionally, `services status` only inspects the Maven PID, so a service that crashed
during boot or is still starting is reported as running or stopped with no explanation,
and `wait_for_port` treats a timeout as success.

## What Changes

- Fix Eureka client registration by using the `defaultZone` key (camelCase) in the five
  service `application.yaml` files, so the credentials in the service URL are actually
  sent and services register with `discovery-server`.
- Make `dev.sh` load the repository's `.env` and export it to the service processes, so
  compose and the Spring apps share one source of truth for credentials. **BREAKING**:
  the `.env` file takes precedence over variables already exported in the shell; required
  keys are validated up front with a clear error instead of surfacing as a Spring stack
  trace later.
- Add readiness gating: `dev.sh` waits for the stateful dependencies (MySQL, both
  PostgreSQL instances, Keycloak, Kafka) before starting services, and waits for the
  discovery server before starting Eureka clients.
- Add health verification: after starting, `dev.sh` polls each service's actuator health
  endpoint (through the gateway where applicable) with a bounded timeout, reports which
  services are healthy, and fails `up` with a non-zero exit code plus a pointer to the
  failing log instead of silently leaving dead processes behind.
- Report honest status: `services status` reports `healthy` / `starting` / `failed` /
  `stopped` based on PID **and** health, and a failed start is distinguishable from a
  service that is still booting.
- Give infrastructure lifecycle explicit semantics: `infra restart` preserves database
  data, and a new explicit destructive command (e.g. `infra reset`) recreates the
  database volumes when a clean slate is wanted. Add named volumes to the compose file so
  this is deterministic.
- Add healthchecks to the stateful compose services so readiness is a property of the
  stack, not something `dev.sh` has to guess.
- Update `dev.test.sh` smoke tests and the README (`dev.sh` command list, the "uses
  `.env`" claim, and the Keycloak realm step now that data survives restarts).

## Capabilities

### New Capabilities

- `local-infrastructure`: the docker compose local stack - persistent named storage for
  the stateful services, health/readiness reporting per container, and the distinction
  between a restart (data preserved) and a destructive reset (data recreated).
- `local-dev-orchestration`: the `dev.sh` lifecycle contract - consuming `.env` as the
  single source of credentials, ordering and waiting for dependency readiness, verifying
  service health after start, truthful status reporting, and failure signalling.
- `service-discovery`: Eureka client behaviour - registering with `discovery-server`
  using the credentials supplied for the registry, so gateway `lb://` routes resolve.

### Modified Capabilities

None - `openspec/specs/` is empty; all three capabilities are new.

## Impact

- `docker-compose.yaml`: named volumes for `db-orders`, `db-inventory`, `db-products`,
  `db-keycloak`, and healthchecks for those services plus `keycloak` and `kafka`. Local
  database contents are preserved across `down`/`up`; existing anonymous volumes are
  orphaned once and are not migrated.
- `dev.sh`: new `.env` handling, dependency readiness waits, health polling, status
  rework, and the new infra reset command. `dev.test.sh` must be updated in the same
  change (its `up` test asserts the current fire-and-forget behaviour).
- `application.yaml` for `orders-services`, `inventory-service`, `products-service`,
  `notification-service`, `api-gateway`: the `defaultZone` key fix. No API, port, or
  dependency changes.
- README (`dev.sh` sections, ES and EN) and `.env.example` if key validation adds
  requirements.
- Out of scope: importing a reproducible Keycloak realm (the realm stays a documented
  one-time manual setup, now able to survive restarts), changing `server.port: 0`,
  replacing Maven `spring-boot:run` with packaged jars, and adding CI.
