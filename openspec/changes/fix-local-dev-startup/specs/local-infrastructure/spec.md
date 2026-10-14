# Spec Delta

## Purpose

Defines the docker compose stack that provides the stateful dependencies of the local
development environment - MySQL, PostgreSQL, Keycloak, Kafka, Zipkin and the monitoring
services - including how their data survives restarts, how a clean slate is requested,
and how container readiness is reported.

## ADDED Requirements

### Requirement: Stateful data survives container recreation

The local stack SHALL store the data of every stateful service (the three application
databases and the Keycloak database) in storage that is reattached when the containers
are recreated, so stopping and starting the stack does not discard data.

#### Scenario: Data is preserved through a stop and start

- **WHEN** a developer writes data into a database service and then stops and restarts the
  stack with the non-destructive lifecycle commands
- **THEN** the previously written data is still present after the restart

#### Scenario: Keycloak configuration is preserved

- **WHEN** a developer has configured Keycloak and restarts the stack without requesting a
  reset
- **THEN** the configured realm, clients and roles are still available

### Requirement: Discarding state requires an explicit reset

The local stack SHALL discard database contents only when the developer requests an
explicit destructive reset, and the non-destructive lifecycle commands SHALL never remove
stored data.

#### Scenario: Reset recreates empty databases

- **WHEN** the developer requests an explicit reset of the stack
- **THEN** the stack comes back up with freshly initialised, empty databases and no data
  from before the reset

#### Scenario: Restart does not remove stored data

- **WHEN** the developer restarts or stops the stack without requesting a reset
- **THEN** no database storage is removed

### Requirement: Stateful services report readiness

Every stateful service in the stack (the databases, Keycloak and Kafka) SHALL declare a
container health check that only reports healthy once the service accepts requests over
its own protocol, so callers can wait for readiness instead of guessing a delay.

#### Scenario: Health reflects startup progress

- **WHEN** a stateful container has just been created and its service is not yet accepting
  requests
- **THEN** its health state does not report healthy

#### Scenario: Health reports readiness

- **WHEN** a stateful container is accepting requests over its protocol
- **THEN** its health state reports healthy

### Requirement: Container health is visible from the stack's own tooling

The stack SHALL expose the health state of its containers through the container status
output the developer already uses, without requiring additional diagnostic tooling.

#### Scenario: Inspecting status shows health

- **WHEN** the developer lists the stack's container status while some services are still
  starting
- **THEN** the output shows the health state of each stateful container
