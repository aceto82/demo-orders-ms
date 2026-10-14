# Spec Delta

## Purpose

Defines how the microservices register themselves with the Eureka discovery server and
how that registration makes gateway load-balanced routes resolvable.

## ADDED Requirements

### Requirement: Services register using the configured registry credentials

Every service that registers itself with the discovery server SHALL use the registry
endpoint configured for it, including the credentials the registry requires, so
authentication-protected registry endpoints receive the credentials on registration and
heartbeat calls.

#### Scenario: Registration is accepted

- **WHEN** a service starts with the registry credentials configured and the discovery
  server is reachable
- **THEN** the discovery server accepts the registration and lists the service instance as
  available

#### Scenario: Credentials are applied to registry calls

- **WHEN** the discovery server requires authentication for its registry endpoints
- **THEN** the registering service's registration and heartbeat calls are authenticated
  with the configured credentials instead of being rejected

#### Scenario: Registration failures are diagnosable

- **WHEN** a service cannot register because the registry rejected its credentials
- **THEN** the service's log records the failed registration and the rejection status, so
  the cause is visible from the service log

### Requirement: Registered instances resolve through the gateway

Services registered with the discovery server SHALL be resolvable by discovery-based
gateway routes, so a request routed to a service by name reaches a registered instance.

#### Scenario: Gateway resolves a registered service

- **WHEN** a service has registered successfully and a request arrives on a gateway route
  that targets it by name
- **THEN** the gateway forwards the request to that service instance

#### Scenario: All client services register

- **WHEN** the whole stack is running and each client service has been started
- **THEN** every client service appears as an available instance in the registry
