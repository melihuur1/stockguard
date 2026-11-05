# StockGuard

**Inventory management and a concurrent reservation engine that cannot oversell.**

[![CI](https://github.com/melihuur1/stockguard/actions/workflows/ci.yml/badge.svg)](https://github.com/melihuur1/stockguard/actions/workflows/ci.yml)

The feature set is simple: products, stock and reservations. The engineering problem is not. If one unit is left and
100 customers reserve it at the same moment, exactly one must succeed, on any number of application instances, even
if an instance crashes halfway through.

```text
Initial stock:           100
Concurrent requests:     1000
Successful reservations: 100
Rejected reservations:   900
Unexpected failures:     0
Rows in database:        100
Stock after the race:    total 100, available 0, reserved 100
Oversold units:          0
Negative stock counters: 0
Requests per instance:   app-1: 500, app-2: 500
Duration:                2337 ms
Data consistency:        PRESERVED
```

*Real output of `scripts/simulate.sh` against two containers. Nothing is hard-coded: every value is counted from HTTP
responses or read back from PostgreSQL.*

## What it guarantees

| Guarantee | How | Proven by |
|---|---|---|
| Stock is never oversold or negative | One conditional `UPDATE ... WHERE available >= q`, backed by `CHECK` constraints | `ReservationConcurrencyIT`, `MultiInstanceIT` |
| A duplicate request creates nothing new | `UNIQUE (user_id, idempotency_key)` with `INSERT ... ON CONFLICT DO NOTHING` | `IdempotencyIT` |
| A reservation is settled exactly once | Compare-and-set on status, backed by a trigger on final states | `ReservationLifecycleIT`, `ReservationExpiryIT` |
| Expired stock returns safely, with any number of sweepers | `FOR UPDATE SKIP LOCKED` and ordered locking | `ReservationExpiryIT`, `MultiInstanceIT` |
| A failure leaves no partial effects | One transaction per operation, including ledger and audit | `TransactionRollbackIT` |
| A crash loses nothing that was acknowledged | PostgreSQL durability, and retrying with the same key is safe | `RestartDurabilityIT` |
| Holds across instances | No JVM locks or shared memory; all coordination is in PostgreSQL | `MultiInstanceIT` (separate JVM processes) |

The design decisions and measured results are in [docs/concurrency.md](docs/concurrency.md).

## Tech stack

Java 21, Spring Boot 4.1, Spring Data JPA / Hibernate, PostgreSQL 17, Flyway, Spring Security (JWT), JUnit 5, Mockito,
Testcontainers, Docker Compose, GitHub Actions, OpenAPI / Swagger.

No Redis, no message broker and no microservices: PostgreSQL's transactions are the whole coordination mechanism, and
every extra moving part would be another way to lose consistency.

## Quick start

You need Docker. Ports 8095, 8096 and 5442 are used on localhost by default; change them in `.env` if they are taken.

```bash
git clone https://github.com/melihuur1/stockguard.git
cd stockguard

cp .env.example .env
# Fill in POSTGRES_PASSWORD, STOCKGUARD_JWT_SECRET and STOCKGUARD_ADMIN_PASSWORD.
# Compose refuses to start while they are blank. "openssl rand -base64 48" generates good values.

docker compose up --build --detach --wait
```

| What | Where |
|---|---|
| Demo screen: run a race and see the result | <http://localhost:8095/demo/index.html> |
| Swagger UI: try every API operation | <http://localhost:8095/swagger-ui.html> |
| First instance / second instance | <http://localhost:8095> / <http://localhost:8096> |

Sign in with `admin` and the password you put in `.env`.

### Run a race from the command line

```bash
export STOCKGUARD_ADMIN_PASSWORD=...   # the value from .env
scripts/simulate.sh 1 100              # 1 unit, 100 concurrent requests
scripts/simulate.sh 10 100 2           # 10 units, 100 requests for 2 units each
```

The script exits with a non-zero status if the data did not stay consistent. CI uses it as an end-to-end check.

## Try the API

These examples need `curl` and `jq`. Swagger UI offers the same operations without either.

```bash
# Log in
TOKEN=$(curl -s localhost:8095/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"YOUR_PASSWORD"}' | jq -r .accessToken)
AUTH="Authorization: Bearer $TOKEN"; JSON='Content-Type: application/json'

# Create a product and receive stock
PRODUCT=$(curl -s localhost:8095/api/v1/products -H "$AUTH" -H "$JSON" \
  -d '{"sku":"LAPTOP-1","name":"Laptop"}' | jq -r .id)
curl -s localhost:8095/api/v1/products/$PRODUCT/stock -H "$AUTH" -H "$JSON" -d '{"quantity":5}'

# Reserve two units. Sending this again, even to the other instance, returns the same reservation with 200.
RESERVATION=$(curl -s localhost:8095/api/v1/reservations -H "$AUTH" -H "$JSON" \
  -H 'Idempotency-Key: order-1001' \
  -d "{\"productId\":\"$PRODUCT\",\"quantity\":2}" | jq -r .id)

# Confirm it: total 3, available 3, reserved 0
curl -s -X POST localhost:8095/api/v1/reservations/$RESERVATION/confirm -H "$AUTH"
curl -s localhost:8095/api/v1/products/$PRODUCT/stock -H "$AUTH"
```

### Endpoints

| Method and path | Purpose | Access |
|---|---|---|
| `POST /api/v1/auth/register`, `/login` | Create an account, obtain a token | Public |
| `GET /api/v1/products`, `/products/{id}` | List and read products with stock quantities | User |
| `POST /api/v1/products`, `PUT /products/{id}` | Create and update products | Admin |
| `GET /api/v1/products/{id}/stock` | Total, available and reserved quantities | User |
| `POST /api/v1/products/{id}/stock` | Receive stock | Admin |
| `GET /api/v1/products/{id}/stock/movements` | Stock ledger | Admin |
| `POST /api/v1/reservations` | Reserve, requires an `Idempotency-Key` header | User |
| `GET /api/v1/reservations`, `/reservations/{id}` | Read reservations (your own; admins see all) | User |
| `POST /api/v1/reservations/{id}/confirm`, `/cancel` | Settle a reservation | Owner or admin |
| `GET /api/v1/audit-events` | Audit trail | Admin |
| `GET /api/v1/admin/consistency` | Recompute all invariants from the raw tables | Admin |
| `POST /api/v1/simulations/reservations` | Run a concurrency race (if enabled) | Admin |

## Tests

Tests prove behaviour against the real thing rather than a stand-in. Integration tests run on real PostgreSQL through
Testcontainers and send real HTTP requests. Tests of multi-instance behaviour and crashes launch the packaged jar as
separate JVM processes and kill them mid-traffic. Docker must be running.

```bash
./mvnw verify                                   # everything
./mvnw test                                     # unit tests only
./mvnw verify -Dit.test=ReservationConcurrencyIT # one integration test class
```

On Windows, use `mvnw.cmd` and make sure `JAVA_HOME` points to a JDK 21. A run writes what it measured to
`target/concurrency-report/report.md`, which CI publishes on each build.

| # | Scenario | Test | Outcome verified |
|---|---|---|---|
| 1 | Stock 1, 100 different users at once | `ReservationConcurrencyIT` | Exactly 1 succeeds, 99 get `409` |
| 2 | Stock 10, 100 different users at once | `ReservationConcurrencyIT` | Exactly 10 succeed |
| 3 | One idempotency key, concurrent repeats | `IdempotencyIT` | 1 reservation, 1 `201`, the rest replay it with `200` |
| 4 | Cancel | `ReservationLifecycleIT` | Units become available and can be sold again |
| 5 | Confirm | `ReservationLifecycleIT` | Total and reserved drop once, available is untouched |
| 6 | Confirm racing expiry at the deadline | `ReservationExpiryIT` | One winner per round, stock matches the winner |
| 7 | Failure after the stock update | `TransactionRollbackIT` | Everything undone, the key stays usable |
| 8 | Two instances in separate JVMs | `MultiInstanceIT` | All of the above, requests split 50/50 |
| 9 | Unauthorised access and invalid input | `SecurityAndValidationIT` | `401`, `403`, `404`, `400` with no internals exposed |
| 10 | Restart and crash | `RestartDurabilityIT` | `kill -9` of the application and of PostgreSQL loses nothing committed |

Also covered: real deadlocks and lock timeouts (`DeadlockAndContentionIT`), optimistic locking on products
(`ProductCatalogIT`), the verifier detecting deliberately corrupted data (`ConsistencyVerifierIT`), and a measured
comparison of locking strategies with controls showing that the harness catches overselling (`StrategyComparisonIT`).

To check that the tests can actually fail, each safeguard was removed from the code in turn and the tests were run.
All 16 removals were caught; the table is in [docs/concurrency.md](docs/concurrency.md#do-the-tests-actually-catch-these-bugs).

## Project layout

```text
src/main/java/com/stockguard/
  reservation/   lifecycle, idempotency, expiry: the core of the system
  inventory/     stock counters, ledger, consistency verifier
  catalog/       products, optimistic locking
  audit/         append-only audit trail
  user/          accounts and login
  security/      JWT, authorisation rules
  simulation/    demo-only concurrency race
  common/        configuration, error contract
src/main/resources/db/migration/   schema, constraints and triggers
src/test/java/com/stockguard/      integration tests (*IT) and support code
docs/                              architecture and concurrency
scripts/simulate.sh                command line race
```

## Documentation

- [Architecture](docs/architecture.md): structure, data model, transaction flows, security, known limitations
- [Concurrency](docs/concurrency.md): the problem, the strategies compared, the decision and the measured results

## Configuration

Everything is set through environment variables; see [.env.example](.env.example).

| Variable | Default | Purpose |
|---|---|---|
| `STOCKGUARD_JWT_SECRET` | *(required)* | Token signing secret, at least 32 characters, the same on every instance |
| `STOCKGUARD_DB_URL`, `_USERNAME`, `_PASSWORD` | local defaults | PostgreSQL connection |
| `STOCKGUARD_ADMIN_USERNAME`, `_PASSWORD` | *(none)* | First administrator; none is created if blank |
| `STOCKGUARD_SIMULATION_ENABLED` | `false` | Enables the simulation endpoint, which creates demo data |
| `STOCKGUARD_EXPIRY_SWEEP_INTERVAL` | `5s` | How often each instance looks for expired reservations |
