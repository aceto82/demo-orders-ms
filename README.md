# demo-orders-serv

> 🇪🇸 [Español](#español) · 🇬🇧 [English](#english)

---

## Español

### ¿Qué es?

`demo-orders-serv` es un backend de ejemplo, construido con **microservicios**, que
simula la parte de atrás de una tienda en línea. En lugar de ser un único programa,
está dividido en varios servicios pequeños que se hablan entre sí: uno recibe los
pedidos, otro controla el stock, otro administra el catálogo de productos, otro avisa
cuando se crea una orden, y dos más se encargan de centralizar la configuración, la
seguridad y la observabilidad de todo el conjunto.

Es un proyecto de aprendizaje: sirve para ver, en la práctica, cómo se construye y se
ejecuta una arquitectura de microservicios con Spring.

### ¿Qué se puede hacer con él?

- **Crear una orden** y ver el listado de órdenes.
- **Consultar el stock** de un producto y **reservarlo** de forma atómica: si no hay
  stock, la orden no se guarda.
- **Administrar el catálogo de productos** (alta y listado), protegido por roles
  (`ADMIN` para crear, `USER` para ver).
- **Notificar** internamente cada orden creada mediante eventos.
- **Ver métricas y trazas** de todo el sistema (Prometheus, Grafana y Zipkin).

### Cómo funciona (a grandes rasgos)

El cliente solo conoce **una dirección**: el *gateway* (puerta de entrada). Detrás,
los servicios se encuentran entre ellos y se reparten el trabajo.

```
                 Cliente (navegador / API)
                          |
                          v
                 +------------------+
                 |   api-gateway    |  :8080  (única entrada, login OAuth2)
                 +------------------+
                    |      |      |
                    v      v      v
               orders  inventory  products
                 |         |
                 v         v
              MySQL    PostgreSQL
                 |
                 v
              Kafka  ────►  notification-service

   Todos se registran en discovery-server (Eureka, :8761)
   Observabilidad: Prometheus + Grafana + Zipkin
```

El recorrido de una orden:

1. El cliente envía `POST /api/order` (autenticado con Keycloak).
2. `orders-service` primero **reserva el stock** en `inventory-service`
   (`POST /api/inventory/reserve`), con una operación atómica.
3. Si hay stock, guarda la orden en MySQL; si no, **no se guarda nada**.
4. Publica un evento `OrderEvent` en Kafka (tema `orders-topic`).
5. `notification-service` consume el evento y lo registra.
6. Mientras tanto, `discovery-server` mantiene el registro de qué servicio está
   disponible y en qué dirección.

### Stack tecnológico

| Área | Tecnología |
|------|------------|
| Lenguaje | Java 21 |
| Framework | Spring Boot 4.1.1, Spring Cloud 2025.1.3 |
| Enrutamiento y descubrimiento | Spring Cloud Gateway, Netflix Eureka (discovery-server) |
| Seguridad | Spring Security + OAuth2 (Keycloak 26.8.0) |
| Persistencia | Spring Data JPA · MySQL 9.7.2 (orders) · PostgreSQL 18.0 (inventory, products, keycloak) |
| Mensajería | Apache Kafka 7.9.10 (+ Zookeeper) |
| Resiliencia | Resilience4j (circuit breaker) |
| Observabilidad | Micrometer + Prometheus v3.15.0 + Grafana 13.0.2 + Zipkin 3.6.1 |
| Infraestructura local | Docker Compose |
| Build | Maven (wrapper incluido en cada módulo) |
| Utilidades | Lombok |

### Los servicios

| Servicio | Qué hace | Puerto |
|----------|----------|--------|
| `discovery-server` | Registro y descubrimiento de servicios (Eureka) | `8761` (fijo) |
| `api-gateway` | Única puerta de entrada; login y enrutamiento hacia los demás | `8080` (fijo) |
| `orders-services` | Crea y lista órdenes; publica eventos (MySQL) | aleatorio |
| `inventory-service` | Consulta, disponibilidad y reserva atómica de stock (PostgreSQL) | aleatorio |
| `products-service` | Catálogo de productos, protegido por roles (PostgreSQL) | aleatorio |
| `notification-service` | Consume los eventos de órdenes (Kafka) | aleatorio |

> Los servicios de negocio usan **puerto aleatorio** y se registran en Eureka; por eso
> no se accede a ellos directamente, sino siempre a través del *gateway*.

### Requisitos

- **Docker** y **Docker Compose** (para las bases de datos, Keycloak, Kafka, etc.).
- **JDK 21**.
- **Maven 3.9+**, o bien el *wrapper* (`./mvnw`) que ya trae cada módulo.

### Ejecutarlo localmente

**1. Clonar el repositorio**

```bash
git clone git@github.com:aceto82/demo-orders-ms.git
cd demo-orders-ms
```

**2. Crear el archivo `.env`**

Los secretos **no** están en el repositorio: se leen de variables de entorno. Copiá la
plantilla y completá los valores:

```bash
cp .env.example .env
```

Puntos importantes del `.env`:

- `DB_USERNAME` / `DB_PASSWORD`: usuario y clave con los que los servicios se conectan
  a MySQL y PostgreSQL. Deben **coincidir** con `POSTGRES_USER` / `POSTGRES_PASSWORD`
  (Postgres) y `MYSQL_USER` / `MYSQL_PASSWORD` (MySQL): en local conviene usar el mismo
  usuario y la misma clave para todas las bases.
- `MYSQL_ROOT_PASSWORD`: clave del usuario `root` de MySQL.
- `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`: administrador de Keycloak.
- `KEYCLOAK_CLIENT_SECRET`: se completa en el paso 4 (lo genera Keycloak).
- `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD`: administrador de Grafana.
- `EUREKA_USERNAME` / `EUREKA_PASSWORD`: credenciales de Eureka; en local los valores por
  defecto (`eureka` / `password`) alcanzan.

> `.env` está en `.gitignore`: **nunca** lo subas al repositorio.

**3. Levantar la infraestructura**

```bash
docker compose up -d
```

Esto levanta MySQL (3306), PostgreSQL (5431 inventory, 5432 products, 5433 keycloak),
Keycloak (8181), Kafka (9092) + Zookeeper, Zipkin (9411), Prometheus (9090) y
Grafana (3000).

**4. Configurar Keycloak (una sola vez)**

El repositorio **no** incluye el *realm* de Keycloak, así que hay que crearlo:

1. Abrí <http://localhost:8181> e iniciá sesión con `KEYCLOAK_ADMIN` /
   `KEYCLOAK_ADMIN_PASSWORD`.
2. Creá un *realm* llamado `demo-orders-serv-realm`.
3. Dentro del realm, creá un *client* llamado `demo_orders_serv_client`:
   - *Client authentication*: **activado** (confidencial) → así Keycloak genera un
     *client secret*.
   - *Valid redirect URIs*: `http://localhost:8080/login/oauth2/code/keycloak`.
   - *Standard flow*: habilitado.
4. Copiá el *client secret* generado en `.env` como `KEYCLOAK_CLIENT_SECRET`.
5. Creá los roles de realm `ADMIN` y `USER`.
6. Creá un usuario, asignale una contraseña y otorgale al menos el rol `USER`
   (agregá `ADMIN` si vas a crear productos).

**5. Levantar los servicios**

En terminales separadas, y **primero** el de descubrimiento:

```bash
mvn -pl discovery-server spring-boot:run
mvn -pl orders-services    spring-boot:run
mvn -pl inventory-service  spring-boot:run
mvn -pl products-service   spring-boot:run
mvn -pl notification-service spring-boot:run
mvn -pl api-gateway        spring-boot:run
```

> El `pom.xml` raíz **no** tiene wrapper. Podés usar `mvn` desde la raíz (como arriba) o
> entrar al módulo y usar su wrapper: `cd orders-services && ./mvnw spring-boot:run`.

**6. Acceder**

Abrí <http://localhost:8080> en el navegador. El *gateway* te va a pedir iniciar
sesión con Keycloak.

### Probarlo

Endpoints principales (todos a través del gateway, `http://localhost:8080`):

| Método | Ruta | Rol requerido |
|--------|------|---------------|
| `GET` | `/api/order` | `USER` |
| `POST` | `/api/order` | `USER` |
| `GET` | `/api/inventory/{sku}` | `USER` |
| `POST` | `/api/inventory/in-stock` | `USER` |
| `POST` | `/api/inventory/reserve` | `USER` |
| `GET` | `/api/product` | `USER` |
| `POST` | `/api/product` | `ADMIN` |

Ejemplo simple, sin autenticación (el *health* es público):

```bash
curl http://localhost:8080/actuator/orders/health
```

Para probar los endpoints autenticados desde la terminal necesitás un *token* de
Keycloak. La forma más fácil de interactuar es por el navegador (login en el gateway);
si querés automatizarlo, obtené un token con el flujo *password* del client
(recordá habilitar *Direct access grants* en el client) y usalo como `Bearer`:

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8181/realms/demo-orders-serv-realm/protocol/openid-connect/token \
  -d grant_type=password -d client_id=demo_orders_serv_client \
  -d client_secret="$KEYCLOAK_CLIENT_SECRET" \
  -d username=<usuario> -d password=<clave> | jq -r .access_token)

curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/product
```

### Tests

Los tests usan **H2** en memoria, así que no necesitan Docker ni infraestructura:

```bash
# Todos los módulos
mvn test

# Un módulo
mvn -pl orders-services -am test

# Una clase
mvn -pl orders-services test -Dtest=OrderServiceTests
```

### Observabilidad

| Herramienta | URL | Para qué |
|-------------|-----|----------|
| Grafana | <http://localhost:3000> | Tableros de métricas |
| Prometheus | <http://localhost:9090> | Recolección de métricas |
| Zipkin | <http://localhost:9411> | Trazas de las peticiones |

Métricas por servicio (expuestas vía gateway):
`http://localhost:8080/actuator/{servicio}/prometheus` — por ejemplo
`/actuator/orders/prometheus`.

### Notas y cosas a tener en cuenta

- **Secretos fuera del repositorio:** todos los valores sensibles se leen de variables
  de entorno (`.env`). No commitees `.env`.
- **El realm de Keycloak no viene incluido:** hay que crearlo a mano (paso 4). Exportarlo
  para que sea reproducible es una mejora pendiente.
- **`api-gateway` es la única entrada pública:** el resto de los servicios usan puertos
  aleatorios y se registran en Eureka.
- **Reserva de stock atómica:** no agregues reintentos alrededor de la creación de
  órdenes; podrían consumir stock dos veces.

---

## English

### What is it?

`demo-orders-serv` is a sample **microservices** backend that models the back end of an
online store. Rather than a single program, it is split into several small services that
talk to each other: one takes orders, another manages stock, another handles the product
catalog, another announces when an order is placed, and two more centralize discovery,
security and observability for the whole system.

It is a learning project: a hands-on way to see how a Spring microservices architecture
is built and run.

### What can you do with it?

- **Create an order** and list orders.
- **Check a product's stock** and **reserve** it atomically: if there is no stock, the
  order is not saved.
- **Manage the product catalog** (create and list), protected by roles (`ADMIN` to
  create, `USER` to view).
- **Get notified** internally whenever an order is created, via events.
- **See metrics and traces** for the whole system (Prometheus, Grafana and Zipkin).

### How it works (high level)

The client only knows **one address**: the gateway. Behind it, the services discover
each other and share the work.

```
                 Client (browser / API)
                          |
                          v
                 +------------------+
                 |   api-gateway    |  :8080  (single entry point, OAuth2 login)
                 +------------------+
                    |      |      |
                    v      v      v
               orders  inventory  products
                 |         |
                 v         v
              MySQL    PostgreSQL
                 |
                 v
              Kafka  ────►  notification-service

   All register with discovery-server (Eureka, :8761)
   Observability: Prometheus + Grafana + Zipkin
```

The life of an order:

1. The client sends `POST /api/order` (authenticated with Keycloak).
2. `orders-service` first **reserves stock** in `inventory-service`
   (`POST /api/inventory/reserve`), in a single atomic operation.
3. If there is stock, it saves the order in MySQL; if not, **nothing is saved**.
4. It publishes an `OrderEvent` to Kafka (topic `orders-topic`).
5. `notification-service` consumes the event and logs it.
6. Meanwhile, `discovery-server` keeps track of which service is available and where.

### Tech stack

| Area | Technology |
|------|------------|
| Language | Java 21 |
| Framework | Spring Boot 4.1.1, Spring Cloud 2025.1.3 |
| Routing & discovery | Spring Cloud Gateway, Netflix Eureka (discovery-server) |
| Security | Spring Security + OAuth2 (Keycloak 26.8.0) |
| Persistence | Spring Data JPA · MySQL 9.7.2 (orders) · PostgreSQL 18.0 (inventory, products, keycloak) |
| Messaging | Apache Kafka 7.9.10 (+ Zookeeper) |
| Resilience | Resilience4j (circuit breaker) |
| Observability | Micrometer + Prometheus v3.15.0 + Grafana 13.0.2 + Zipkin 3.6.1 |
| Local infrastructure | Docker Compose |
| Build | Maven (wrapper included in each module) |
| Utilities | Lombok |

### The services

| Service | What it does | Port |
|---------|--------------|------|
| `discovery-server` | Service registry and discovery (Eureka) | `8761` (fixed) |
| `api-gateway` | Single entry point; login and routing to the rest | `8080` (fixed) |
| `orders-services` | Creates and lists orders; publishes events (MySQL) | random |
| `inventory-service` | Stock lookup, availability and atomic reservation (PostgreSQL) | random |
| `products-service` | Product catalog, protected by roles (PostgreSQL) | random |
| `notification-service` | Consumes order events (Kafka) | random |

> Business services use a **random port** and register with Eureka, so you never reach
> them directly — always through the gateway.

### Requirements

- **Docker** and **Docker Compose** (for the databases, Keycloak, Kafka, etc.).
- **JDK 21**.
- **Maven 3.9+**, or the wrapper (`./mvnw`) bundled with each module.

### Run it locally

**1. Clone the repository**

```bash
git clone git@github.com:aceto82/demo-orders-ms.git
cd demo-orders-ms
```

**2. Create the `.env` file**

Secrets are **not** in the repository: they are read from environment variables. Copy
the template and fill in the values:

```bash
cp .env.example .env
```

Key points in `.env`:

- `DB_USERNAME` / `DB_PASSWORD`: the user and password the services use to connect to
  MySQL and PostgreSQL. They must **match** `POSTGRES_USER` / `POSTGRES_PASSWORD`
  (Postgres) and `MYSQL_USER` / `MYSQL_PASSWORD` (MySQL): locally, use the same user and
  password for every database.
- `MYSQL_ROOT_PASSWORD`: the MySQL `root` password.
- `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`: the Keycloak administrator.
- `KEYCLOAK_CLIENT_SECRET`: filled in step 4 (generated by Keycloak).
- `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD`: the Grafana administrator.
- `EUREKA_USERNAME` / `EUREKA_PASSWORD`: Eureka credentials; the defaults
  (`eureka` / `password`) are fine locally.

> `.env` is in `.gitignore`: **never** commit it.

**3. Start the infrastructure**

```bash
docker compose up -d
```

This starts MySQL (3306), PostgreSQL (5431 inventory, 5432 products, 5433 keycloak),
Keycloak (8181), Kafka (9092) + Zookeeper, Zipkin (9411), Prometheus (9090) and
Grafana (3000).

**4. Configure Keycloak (once)**

The repository does **not** include the Keycloak realm, so create it:

1. Open <http://localhost:8181> and sign in with `KEYCLOAK_ADMIN` /
   `KEYCLOAK_ADMIN_PASSWORD`.
2. Create a realm named `demo-orders-serv-realm`.
3. Inside the realm, create a client named `demo_orders_serv_client`:
   - *Client authentication*: **on** (confidential) → Keycloak then generates a client
     secret.
   - *Valid redirect URIs*: `http://localhost:8080/login/oauth2/code/keycloak`.
   - *Standard flow*: enabled.
4. Copy the generated client secret into `.env` as `KEYCLOAK_CLIENT_SECRET`.
5. Create the realm roles `ADMIN` and `USER`.
6. Create a user, set a password, and grant at least the `USER` role (add `ADMIN` if you
   will create products).

**5. Start the services**

In separate terminals, and the discovery server **first**:

```bash
mvn -pl discovery-server spring-boot:run
mvn -pl orders-services    spring-boot:run
mvn -pl inventory-service  spring-boot:run
mvn -pl products-service   spring-boot:run
mvn -pl notification-service spring-boot:run
mvn -pl api-gateway        spring-boot:run
```

> The root `pom.xml` has **no** wrapper. You can use `mvn` from the root (as above) or
> enter the module and use its wrapper: `cd orders-services && ./mvnw spring-boot:run`.

**6. Open it**

Go to <http://localhost:8080> in your browser. The gateway will ask you to log in with
Keycloak.

### Try it

Main endpoints (all through the gateway, `http://localhost:8080`):

| Method | Path | Required role |
|--------|------|---------------|
| `GET` | `/api/order` | `USER` |
| `POST` | `/api/order` | `USER` |
| `GET` | `/api/inventory/{sku}` | `USER` |
| `POST` | `/api/inventory/in-stock` | `USER` |
| `POST` | `/api/inventory/reserve` | `USER` |
| `GET` | `/api/product` | `USER` |
| `POST` | `/api/product` | `ADMIN` |

A simple unauthenticated example (health is public):

```bash
curl http://localhost:8080/actuator/orders/health
```

To call the authenticated endpoints from a terminal you need a Keycloak token. The
easiest way to interact is through the browser (log in at the gateway); to script it,
get a token with the client's password flow (enable *Direct access grants* on the
client) and send it as `Bearer`:

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8181/realms/demo-orders-serv-realm/protocol/openid-connect/token \
  -d grant_type=password -d client_id=demo_orders_serv_client \
  -d client_secret="$KEYCLOAK_CLIENT_SECRET" \
  -d username=<user> -d password=<password> | jq -r .access_token)

curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/product
```

### Tests

Tests use in-memory **H2**, so they need no Docker or infrastructure:

```bash
# Every module
mvn test

# One module
mvn -pl orders-services -am test

# One class
mvn -pl orders-services test -Dtest=OrderServiceTests
```

### Observability

| Tool | URL | Purpose |
|------|-----|---------|
| Grafana | <http://localhost:3000> | Metrics dashboards |
| Prometheus | <http://localhost:9090> | Metrics scraping |
| Zipkin | <http://localhost:9411> | Request traces |

Per-service metrics (exposed through the gateway):
`http://localhost:8080/actuator/{service}/prometheus` — for example
`/actuator/orders/prometheus`.

### Notes and gotchas

- **Secrets stay out of the repository:** every sensitive value is read from environment
  variables (`.env`). Never commit `.env`.
- **The Keycloak realm is not bundled:** you must create it by hand (step 4). Exporting
  it so it is reproducible is a pending improvement.
- **`api-gateway` is the only public entry point:** the other services use random ports
  and register with Eureka.
- **Atomic stock reservation:** do not add retries around order creation; that could
  consume stock twice.
