# Spec Delta

## Purpose

Defines the lifecycle contract of `dev.sh`, the script that starts the local stack and
the microservices: it decides which credentials the services run with, in what order
things start, what counts as a successful start, and how failures are reported.

## ADDED Requirements

### Requirement: `.env` is the single source of credentials

`dev.sh` SHALL read the repository `.env` file and pass those values to the stack and to
every service it starts, so the containers and the services always use the same
credentials regardless of what the invoking shell happens to export.

#### Scenario: Stale shell values do not win

- **WHEN** the invoking shell has a database password exported that differs from `.env`
  and the developer starts the services
- **THEN** the services run with the value from `.env`

#### Scenario: Services receive database credentials

- **WHEN** the developer starts a database-backed service with `dev.sh` and `.env` defines
  the database credentials
- **THEN** the service starts without a missing-credential configuration error

### Requirement: Missing required variables are reported before anything starts

Before starting infrastructure or services, `dev.sh` SHALL verify that every variable the
stack needs is defined, and SHALL stop with a non-zero exit code and a message naming the
missing variables if any are absent.

#### Scenario: A missing variable stops the command early

- **WHEN** `.env` is missing a variable the stack requires
- **THEN** `dev.sh` exits non-zero, names the missing variable, and starts neither the
  containers nor any service

### Requirement: Dependencies are ready before dependents start

`dev.sh` SHALL wait for the stateful containers to report healthy and for the discovery
server to be reachable before starting services that depend on them, so a service is
never started against an unavailable dependency.

#### Scenario: The gateway starts after Keycloak is ready

- **WHEN** the developer starts the whole stack with a single command
- **THEN** the gateway process is not started until Keycloak is accepting requests

#### Scenario: Database-backed services start after their database

- **WHEN** a database-backed service is started while its database container is still
  initialising
- **THEN** the service start is deferred until that database reports healthy

### Requirement: Started services are verified to be healthy

After starting a service, `dev.sh` SHALL poll the service's health endpoint until it
reports healthy or a bounded timeout expires, and SHALL record the outcome per service.

#### Scenario: A booting service is awaited

- **WHEN** a service starts successfully but needs some seconds to become healthy
- **THEN** `dev.sh` waits for the health endpoint and reports the service as healthy

#### Scenario: An unhealthy service is detected

- **WHEN** a service process is running but its health endpoint never reports healthy
  within the timeout
- **THEN** `dev.sh` reports that service as failed

### Requirement: A failed start is signalled

`dev.sh` SHALL exit non-zero when any requested service fails to become healthy, and SHALL
name the failing services together with the path of the log file that explains why.

#### Scenario: Failures are surfaced with their logs

- **WHEN** one of the services fails to become healthy during a start
- **THEN** the command exits non-zero, names that service and points to its log file

#### Scenario: Success is reported

- **WHEN** every requested service becomes healthy
- **THEN** the command exits zero and reports the services as healthy

### Requirement: Service status reflects the real state

`dev.sh`'s status command SHALL distinguish a healthy service, a service that is still
starting, a service whose process died and a service that was never started.

#### Scenario: A crashed service is not reported as running

- **WHEN** a service process has exited and its entry remains on disk
- **THEN** the status command reports that service as stopped or failed rather than
  running

#### Scenario: A starting service is distinguishable from a healthy one

- **WHEN** a service process is alive but its health endpoint does not yet report healthy
- **THEN** the status command reports it as starting rather than healthy

### Requirement: Restart and reset are separate, documented operations

`dev.sh` SHALL provide a non-destructive restart of the infrastructure that preserves data
and a separate explicit reset that discards it, and SHALL describe the difference in its
help output.

#### Scenario: Help explains the destructive command

- **WHEN** the developer asks `dev.sh` for help
- **THEN** the help output documents which infrastructure command discards data

#### Scenario: Restart keeps data

- **WHEN** the developer restarts the infrastructure with the non-destructive command
- **THEN** database contents are kept
