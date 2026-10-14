# AGENTS.md

Spring Boot 4 / Spring Cloud microservices demo ("demo-orders-serv"). Maven
multi-module aggregator: Java 21, Spring Boot 4.1.1, Spring Cloud 2025.1.3.

## Modules
Each module is its own Maven project inheriting the root `pom.xml`:
- `discovery-server` — Eureka server, fixed port 8761
- `api-gateway` — Spring Cloud Gateway (WebFlux), fixed port 8080; the only public entrypoint
- `orders-services` — MySQL; places orders and publishes `orders-topic`
- `inventory-service` — PostgreSQL; atomic stock reservation
- `products-service` — PostgreSQL; role-protected
- `notification-service` — Kafka consumer of `orders-topic`

## Build & test
The root `pom.xml` is an aggregator with **no Maven wrapper**. Each module has its
own `mvnw`, so either use system `mvn` from the repo root or `cd <module> && ./mvnw`.
- Whole module: `mvn -pl orders-services -am test`
- One test class: `mvn -pl orders-services test -Dtest=OrderServiceTests`
- One method: add `#placeOrderDoesNotCopyClientSuppliedIds`
- Run a service: `mvn -pl orders-services spring-boot:run`

Tests use H2 via per-module `src/test/resources/application.yaml` (production yaml
points at MySQL/Postgres); the root pom adds H2 test-scoped everywhere.
`*ApplicationTests` are `@SpringBootTest`; most domain tests are plain JUnit/Mockito
or standalone MockMvc. `OrderEventKafkaContractTests` reads
`src/main/resources/application.yaml` relative to the module dir — run it through
Maven, don't relocate or casually rewrite it.

## Don't "fix" these
- Boot 4 starter names are intentional: `spring-boot-starter-webmvc`,
  `-webmvc-test`, `-data-jpa-test`, `-kafka`, `-zipkin`. Not Boot 3 names.
- Kafka producer `value-serializer` must stay `StringSerializer` with raw JSON
  payloads (`JsonUtils.toJson`), never double-encoded. `OrderEvent`, `OrderStatus`
  and `JsonUtils` are duplicated in `orders-services` and `notification-service`;
  keep both copies in sync (a contract test guards the wire format).

## Request flow
- Clients hit only the gateway on `:8080` (`/api/order/**`, `/api/inventory/**`,
  `/api/product/**`). Services bind `server.port: 0` and register with Eureka, so
  they can't be curl'ed directly.
- Gateway uses `oauth2Login` + `TokenRelay`; services validate the Keycloak JWT and
  map `realm_access.roles` to `ROLE_*` (`KeyCloakRealmRoleConverter`). Realm
  `demo-orders-serv-realm` on `:8181`; the client secret comes from
  `KEYCLOAK_CLIENT_SECRET` (see `.env.example`). Services also pin the expected
  issuer via `app.security.issuer-uri`.
- `POST /api/order` -> orders-service calls
  `POST lb://inventory-service/api/inventory/reserve` (atomic conditional
  decrement) -> saves order -> publishes `OrderEvent` to `orders-topic` ->
  notification-service logs it. If inventory rejects, nothing is persisted.
- `reserve` is atomic and single-shot: don't add retries around `placeOrder`, it
  would risk double-consuming stock. A Resilience4j circuit breaker wraps the
  controller.
- Actuator base paths are non-default per service (`/actuator/orders`,
  `/actuator/inventory`, `/actuator/products`, `/actuator/discovery`,
  `/actuator/notifications`); the gateway routes `/actuator/{service}/**` to them
  and Prometheus scrapes there. Only `health` + `prometheus` are exposed; health
  details require `ADMIN`; the discovery server requires HTTP Basic for everything
  except `/actuator/discovery/health` and `/actuator/discovery/prometheus`. The
  circuit-breaker endpoints are no longer exposed.
- Role checks use `hasRole('ADMIN')` / `hasRole('USER')` (no `ROLE_` prefix — in
  Spring Security 7 `hasRole` strips a leading `ROLE_`). POST roles are also enforced
  at the HTTP layer so an unauthorized caller gets 403 before body validation.

## Local infra
`docker compose up -d` starts Postgres (inventory 5431, products 5432, keycloak
5433), MySQL (orders 3306), Keycloak 8181, Kafka 9092 + Zookeeper, Zipkin 9411,
and Prometheus + Grafana (host network). DB and admin credentials are read from env
vars (`.env`, see `.env.example`); the earlier hard-coded literals were removed from
the tracked yamls (they remain in pre-hardening git history until the deferred Task 2
purge). `files/grafana/` runtime data is gitignored (only
`.gitkeep` tracked) — don't commit Grafana DB/plugins. Tracked monitoring config:
`files/prometheus.yml`, `files/grafana-prometheus-dashboard.json`.

## Conventions
- Conventional Commits scoped by area: `feat(orders): …`, `fix(observability): …`,
  `test(orders): …`, `feat(infra): …`.
- Single `main` branch, no CI — verify locally with Maven (plus docker compose for
  integration).
- Lombok is used throughout; annotation processing is configured per module in the
  `maven-compiler-plugin`.
