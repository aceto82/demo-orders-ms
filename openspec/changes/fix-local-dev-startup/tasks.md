# Tasks

## 1. Persistent local infrastructure

- [ ] 1.1 Add named volumes (`orders-db-data`, `inventory-db-data`, `products-db-data`,
      `keycloak-db-data`) with a top-level `volumes:` block and attach them to the four
      database services at the mount points the images already use (`/var/lib/mysql`,
      `/var/lib/postgresql`). Verify: start `db-orders`, insert a probe row, run
      `docker compose down && docker compose up -d db-orders`, and confirm the row is
      still there, and that `docker inspect` shows the named volume instead of an
      anonymous one.
- [ ] 1.2 Add healthchecks to `db-orders` (`mysqladmin ping` with the root credentials),
      `db-inventory` and `db-products` (`psql -c 'select 1'` against the application
      database with the application credentials), `db-keycloak` (`pg_isready`) and `kafka`
      (`kafka-broker-api-versions`). Verify: after `docker compose up -d`,
      `docker inspect -f '{{.State.Health.Status}}'` reports `healthy` for each, and the
      credential-checking probes fail when run with a wrong password (proves the probe
      catches a `.env`/volume mismatch instead of only an open port).
- [ ] 1.3 Enable Keycloak health on its management port (`KC_HEALTH_ENABLED`) and add a
      `/health/ready` healthcheck that works in the micro-based image (shell TCP probe, no
      HTTP client). Verify: `docker inspect -f '{{.State.Health.Status}}' keycloak` reaches
      `healthy` and reaches it only after the Keycloak HTTP port serves requests.

## 2. Eureka registration

- [ ] 2.1 Rename the registry key from `default-zone` to `defaultZone` in
      `orders-services`, `inventory-service`, `products-service`, `notification-service`
      and `api-gateway` `application.yaml`. Verify: `grep -rn "default-zone"` returns
      nothing and `grep -rn "defaultZone"` matches all five files.
- [ ] 2.2 Verify registration end to end against a running registry: start
      `discovery-server` and one client service (for example `notification-service`) with
      `mvn -pl … spring-boot:run`, then confirm the service log shows
      `registration status: 204` and `curl -u eureka:password
      http://localhost:8761/eureka/apps` lists the instance as `UP`.

## 3. `dev.sh`: credentials from `.env`

- [ ] 3.1 Load the repository `.env` in `dev.sh` and export it to every process the script
      starts, failing with an actionable message when the file is missing. Verify with a
      new `dev.test.sh` case that a missing `.env` exits non-zero without calling `docker`
      or `mvn`.
- [ ] 3.2 Add a preflight that validates the required variables for the commands that need
      them (`up`, `infra start|restart|reset`, `services start|restart`) and leaves
      `help`, `status`, `logs` and `test` usable without a full `.env`. Verify with
      `dev.test.sh` cases: a missing key exits non-zero and names the key, and nothing is
      started; `./dev.sh help` succeeds with an incomplete `.env`.
- [ ] 3.3 Make `.env` authoritative over the invoking shell. Verify with a `dev.test.sh`
      case that exports a conflicting value for a required key before invoking `dev.sh`
      and asserts (via the fake `mvn`/`docker` recording the environment they were called
      with) that the service processes received the `.env` value.
- [ ] 3.4 Confirm in a real run that a database-backed service start no longer depends on
      the shell: with no `DB_*` variables exported, `./dev.sh services start
      orders-services` reaches its health endpoint (see task 4.2) and
      `logs/orders-services.log` contains no credential or placeholder failure.
- [ ] 3.5 Update the README (`dev.sh` sections, ES and EN) to state that `.env` is loaded
      by `dev.sh` and takes precedence over exported variables, and add `curl` to the
      prerequisites. Verify: the documented commands match the implementation's usage
      output.

## 4. `dev.sh`: readiness, health and failure reporting

- [ ] 4.1 Gate service startup on infrastructure health: poll
      `docker inspect -f '{{.State.Health.Status}}'` for the stateful containers until
      healthy or `DEV_INFRA_WAIT` (default 120s) expires, and abort the start with a clear
      message if a container never becomes healthy. Verify with a `dev.test.sh` case that
      no service is started while a fake container reports `starting`, and that the
      gateway is only started after Keycloak reports `healthy`.
- [ ] 4.2 Health-check each started service against its actuator health endpoint
      (discovery on `:8761/actuator/discovery/health`, gateway on
      `:8080/actuator/health`, the rest on the port read from `logs/<service>.log` plus
      `/actuator/<service>/health`), polling until `UP` or `DEV_SERVICE_WAIT` (default
      90s). Verify with `dev.test.sh` cases using a fake `curl`: a service that answers
      `200` is reported healthy, and a port that never appears in the log is reported
      failed rather than hanging.
- [ ] 4.3 Keep starting the remaining services when one fails, then print a per-service
      summary naming each failure with its `logs/<service>.log` path and exit `1`. Verify
      with a `dev.test.sh` case asserting the exit code and that the failing service and
      its log path appear in the output, and that the other services were still started.
- [ ] 4.4 Report real service state in `services status`: `healthy`, `starting`, `failed`
      (process gone) and `stopped` (never started), combining the PID file with a
      bounded health probe. Verify with `dev.test.sh` cases: a stale PID file reports
      `stopped`/`failed`, a live process without a healthy endpoint reports `starting`,
      and `up`'s summary and `services status` agree after a real start.

## 5. `dev.sh`: infrastructure lifecycle and documentation

- [ ] 5.1 Keep `infra restart` non-destructive (`down` + `up -d`) and add an explicit
      `infra reset` (`down -v` + `up -d`). Verify in a real run: write a row, run
      `./dev.sh infra restart`, confirm the row is still present; run
      `./dev.sh infra reset`, confirm the database comes back empty.
- [ ] 5.2 Document the lifecycle in `dev.sh help` and in the README (ES and EN): which
      command preserves data, which one discards it, and the note that the one-time
      Keycloak realm setup must be repeated after a reset (and once after this change,
      because the databases move to new named volumes). Verify: `./dev.sh help` mentions
      the destructive command, and the README command lists match the script's usage
      output.
- [ ] 5.3 Update `.env.example` if the required-key validation or the volume change alters
      what a developer must fill in, keeping its comments consistent with the README.
      Verify: the values listed in `.env.example` satisfy the preflight added in task 3.2
      (copy it to a temporary `.env` and run the preflight).

## 6. End-to-end verification

- [ ] 6.1 From a clean state (`./dev.sh infra reset`), run `./dev.sh up` and verify: every
      one of the six services is reported healthy, each client service appears as `UP` in
      `curl -u eureka:password http://localhost:8761/eureka/apps`, and
      `curl http://localhost:8080/actuator/orders/health` (routed through the gateway by
      service name) returns `UP` - which also proves `lb://` resolution works.
- [ ] 6.2 Verify the failure path is honest: stop one dependency (for example
      `docker compose stop keycloak`), run `./dev.sh services restart api-gateway`, and
      confirm the command exits non-zero and names the gateway with its log path instead
      of reporting a successful start.
- [ ] 6.3 Run the whole suite: `./dev.test.sh` green, and `mvn test` (root, all modules)
      green with the `defaultZone` change in place.

## Workflow follow-up

- Archive the change once the implementation and verification tasks above are complete.
- After archiving, verify the resulting specs under `openspec/specs/` reflect the three
  new capabilities.
