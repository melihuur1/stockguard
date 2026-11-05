# Concurrency

This document explains the concurrency problems StockGuard faces, how each one is solved and why, and what was
measured. Every number here comes from a real test run; the environment is described in [Results](#results).

## The problem

The obvious way to reserve stock is wrong:

```java
int available = repository.getAvailable(productId);   // 1. read
if (available >= quantity) {                          // 2. decide
    repository.setAvailable(productId, available - quantity);   // 3. write
}
```

Two requests can interleave like this:

| Time | Request A | Request B | Stored value |
|---|---|---|---|
| t1 | reads 1 | | 1 |
| t2 | | reads 1 | 1 |
| t3 | writes 0, success | | 0 |
| t4 | | writes 0, success | 0 |

One unit has been sold twice, and the database looks perfectly healthy: the value is 0, not negative, so a constraint
would not even notice. The decision in step 2 was based on data that was already out of date when step 3 ran.

A Java lock does not solve this. `synchronized` protects one JVM, so two instances simply hold two different locks.
`StrategyComparisonIT.synchronizedDoesNotProtectAcrossInstances` demonstrates exactly that.

## Strategies compared

`StrategyComparisonIT` runs one workload against each strategy on a scratch table, through a 20 connection pool like
the application's. These alternatives exist only in test code. Production has a single code path.

| Strategy | Statement | Correct? | Under contention |
|---|---|---|---|
| **Atomic conditional UPDATE** | `UPDATE stock SET available = available - 1 WHERE id = ? AND available >= 1` | Yes | Requests wait briefly, then proceed or get a clean "no stock" |
| Pessimistic lock | `SELECT ... FOR UPDATE`, decide in Java, then `UPDATE` | Yes | Same waiting, but the lock is held across extra round trips |
| Optimistic lock | `UPDATE ... WHERE version = ?`, retry on 0 rows | Never oversells, but may undersell | A failure means "someone else wrote first", not "no stock", so it needs retries |
| Naive read-then-write (control) | `SELECT`, then `UPDATE` with the computed value | **No** | Oversells |

### Decision: atomic conditional UPDATE for counters

- **Correct by construction.** Checking and changing are one statement, so there is no gap between them to race in.
- **Shortest possible lock.** The decision is made inside PostgreSQL, so no lock is held during a round trip to Java
  merely to compute `available - q`.
- **No retry logic.** Waiting is handled by PostgreSQL. Zero rows always means the same thing: not enough stock.
- **Independent of instance count.** All coordination happens on the database row.

The reason it is safe deserves spelling out, because at first glance it looks like it should fail: under READ
COMMITTED, the second `UPDATE` starts from a snapshot in which the stock was still 1.

| Time | Transaction A | Transaction B |
|---|---|---|
| t1 | `UPDATE ... WHERE available >= 1`: matches, locks the row | |
| t2 | | Same statement. Its snapshot says `available = 1`, so the row matches; it blocks on A's lock |
| t3 | `COMMIT` (`available = 0`) | |
| t4 | | Wakes up, and PostgreSQL **re-evaluates the `WHERE` clause against the newly committed row** (the EvalPlanQual mechanism). `0 >= 1` is false: 0 rows updated |

The same re-evaluation is what makes `available = available - 1` immune to lost updates: the new value is computed
from the latest committed row, not from what the statement saw when it started.

### Why not SERIALIZABLE?

It would also prevent overselling, but by aborting losers with error `40001`, which every caller must then catch and
retry. Since each decision here is already one atomic statement, READ COMMITTED gives the same safety, and a request
that simply waits a moment is cheaper than one that is aborted and starts over.

### The right tool for each job

The conditional update is not the answer to everything. Each kind of contention gets the mechanism that fits it:

| Contention | Mechanism | Why this one |
|---|---|---|
| Stock counters, very hot | Conditional `UPDATE` | Above |
| Settling a reservation | Compare-and-set: `UPDATE ... WHERE status = 'PENDING'` | Exactly one can match. Losers learn the winner's status |
| Duplicate requests | Unique index with `ON CONFLICT DO NOTHING` | The database serialises on the key itself |
| Parallel expiry sweepers | `FOR UPDATE SKIP LOCKED` | Sweepers should share the work instead of queueing |
| Editing a product, rare | Optimistic `@Version` | A conflict needs a person to look at the new values, not a queue |

## Idempotency

A client that gets no response cannot know whether its request took effect, so its only safe option is to retry. The
`Idempotency-Key` header makes that retry safe.

`reservations` has `UNIQUE (user_id, idempotency_key)`, and creation begins with:

```sql
INSERT INTO reservations (...) VALUES (...) ON CONFLICT (user_id, idempotency_key) DO NOTHING
```

| Situation | What happens |
|---|---|
| First request | The row is inserted, stock is taken, everything commits: `201` |
| Duplicate, after the first has committed | 0 rows inserted; the original is read back: `200` with `Idempotency-Replayed: true` |
| Duplicate, **while the first is still running** | PostgreSQL blocks the `INSERT` on the unique index until the first finishes. If it committed, this is the case above. If it rolled back, this insert proceeds as the new first request |
| Same key, different content | The stored SHA-256 fingerprint differs: `422 IDEMPOTENCY_KEY_REUSED` |
| Same key, different user | A separate reservation. A key means nothing outside its owner's scope, and one user cannot reach another's reservation by guessing |
| First attempt failed (no stock, error) | Rolled back, so the key is unused and a retry is a fresh attempt |

Decisions worth noting:

- **The key and the reservation are one row, committed in one transaction.** There is no "in progress" state that could
  get stuck if a server dies, because the database's own lock plays that role and dies with the connection.
- **Failures are not remembered.** A retry after `409 INSUFFICIENT_STOCK` may succeed once stock arrives. That is what
  a client retrying later wants, and a failed attempt had no effect that could be duplicated.
- **The fingerprint comes from parsed values,** so whitespace or JSON key order cannot make equal requests look different.
- **Exactly-once delivery does not exist over a network.** What this provides is at-least-once delivery plus
  idempotent processing: the effect happens once. The crash test below shows it working when it matters most.

## Reservation lifecycle races

Confirm, cancel and expiry all compete to settle the same reservation, and all go through one compare-and-set:

```sql
-- Confirm and cancel
UPDATE reservations SET status = :target, settled_at = now()
 WHERE id = :id AND status = 'PENDING' AND expires_at > now()

-- Expiry
... WHERE status = 'PENDING' AND expires_at <= now() ... FOR UPDATE SKIP LOCKED
```

The stock movement happens only in the transaction that won this statement, in the same commit. So a reservation
cannot be settled twice, and stock cannot move without the status changing or the other way round. As a further
layer, a trigger rejects any update to a reservation that is no longer `PENDING`.

**At the deadline,** confirm and expiry are mutually exclusive by construction. `expires_at > now()` and
`expires_at <= now()` cannot both hold, all instances use the database clock, and if the two meet on one row, the row
lock and the status check decide. A sweeper that finds the row locked skips it and picks it up on a later sweep, if it
is still pending by then.

## Deadlocks and lock waits

**Prevention.** Every transaction takes its locks in the same order: reservation row, then stock row. The sweeper
touches several stock rows in one transaction, so it sorts them by product id first. Without that sort, two sweepers
could lock two products in opposite orders.

**Detection and recovery.** Application order cannot rule out every deadlock, since an operator's manual transaction
could still cause one. When PostgreSQL detects a cycle it aborts one transaction with `40P01`. An aborted transaction
cannot continue, so the retry must begin a new one. That is why `ReservationService` (retry, no transaction) and
`ReservationTransactions` (transaction, no retry) are separate beans. The retry is bounded at three attempts with
jittered backoff, and only covers lock failures: business rejections are final.

**Bounded waits.** `lock_timeout` is 3 s, so a stuck row cannot hang requests forever. After the retries the client
gets `503` with `Retry-After`. Since the transaction was rolled back, retrying with the same key is safe.
`lock_timeout` must exceed PostgreSQL's `deadlock_timeout` (1 s), or a deadlock would be misreported as a timeout.

## Crashes

A process crash needs no special handling, because nothing depends on the process:

- **In-flight transaction:** PostgreSQL sees the connection die and rolls back. No half-finished reservation can exist.
- **Row locks:** released with that rollback, so other instances continue immediately.
- **Committed but unanswered:** the row is durable and the client does not know. It retries with the same key and gets
  a replay. No unit is taken twice.
- **Expiry during downtime:** the deadline lives in the database, so the next sweep after a restart catches up.
- **PostgreSQL crash:** the write-ahead log restores everything committed. The API answers `503` during the outage, and
  the connection pool recovers without an application restart.

## Verifying consistency

`ConsistencyService` recomputes the invariants from the raw tables, independently of the code that wrote them:

| Rule | Checks |
|---|---|
| `NEGATIVE_STOCK` | No counter below zero |
| `TOTAL_IS_NOT_AVAILABLE_PLUS_RESERVED` | `total = available + reserved` |
| `RESERVED_DOES_NOT_MATCH_PENDING_RESERVATIONS` | `reserved` equals the sum of all `PENDING` reservations |
| `OVERSOLD` | Units held or sold never exceed units ever received |
| `COUNTERS_DO_NOT_MATCH_LEDGER` | Each counter equals the sum of its ledger deltas |
| `LEDGER_CHAIN_BROKEN` | Each ledger line's after-values follow from the previous line |
| `RESERVATION_NOT_SETTLED_EXACTLY_ONCE` | One reservation line, and exactly one settlement line matching the status |

The ledger chain check is strong: ledger lines are inserted while the stock row lock is held, so per product their ids
follow the commit order. Any lost or reordered update would break the chain.

It runs at REPEATABLE READ, so all rules see one snapshot and a report stays correct while writes continue.

## Results

Measured on 2026-11-05 with three consecutive clean `./mvnw clean verify` runs: **148 tests (20 unit, 128 integration),
0 failures in all three runs**, about 3 minutes each. Ranges below cover those three runs.

Environment: Windows 11, 16 logical CPUs, Java 21.0.2, PostgreSQL 17 in Docker Desktop with `fsync` on, 20 connections
per instance. These are correctness results from a developer machine, not a performance benchmark. The correctness
figures should hold anywhere; the timings will not. Every build produces its own measurements in
`target/concurrency-report/report.md`, and CI publishes them.

### Strategy comparison

Stock 50, 200 concurrent requests, each wanting one unit:

| Strategy | Units sold | Transactions | Time |
|---|---|---|---|
| **Atomic UPDATE** | **50** | 200 | 57 to 73 ms |
| `SELECT FOR UPDATE` | 50 | 200 | 287 to 381 ms |
| Optimistic, no retry | 14 to 16 | 200 | 166 to 222 ms |
| Optimistic, up to 3 attempts | 33 to 40 | 538 to 545 | 411 to 541 ms |
| Optimistic, up to 50 attempts | 50 | 986 to 1054 | 680 to 835 ms |

Stock 1, 100 concurrent requests, to show what the correct strategies protect against:

| Strategy | Units sold | Time |
|---|---|---|
| **Atomic UPDATE** | **1** | 38 to 46 ms |
| `SELECT FOR UPDATE` | 1 | 122 to 155 ms |
| Naive read-then-write (control) | **13 to 16, oversold** | 16 to 21 ms |

How to read this:

- **Optimistic locking does not oversell, but it is wrong for this job.** Without enough retries it turned customers
  away with most of the stock still on the shelf. With enough retries it sold everything, at about five times the
  transactions.
- **Trust the ordering of these timings, not the ratios.** The experiment measures the bare locking pattern. In the
  application, the transaction also writes a ledger line and an audit event, so the real difference between atomic
  update and `SELECT FOR UPDATE` is smaller.
- **The naive control is the fastest and wrong.** It also proves that the harness can see overselling when it happens.

### Required scenarios

| # | Scenario | Measured | Test |
|---|---|---|---|
| 1 | Stock 1, 100 different users | 1 success, 99 `409`, stock (total 1, available 0, reserved 1), peak 100 requests in flight, 93 to 205 ms | `ReservationConcurrencyIT` |
| 2 | Stock 10, 100 different users | 10 successes, 90 `409`, stock (10, 0, 10), peak 100 in flight | `ReservationConcurrencyIT` |
| 3 | One key, 50 concurrent repeats | 1 `201`, 49 `200` replays, 1 row, stock (5, 4, 1) | `IdempotencyIT` |
| 4 | Cancel | Units available again, and sold again in the same test | `ReservationLifecycleIT` |
| 5 | Confirm, and 20 concurrent confirms | 1 `200`, 19 `409`, stock reduced once | `ReservationLifecycleIT` |
| 6 | Confirm racing expiry, 40 rounds | Confirm won 12 to 16, expiry won 24 to 28, no double settlement | `ReservationExpiryIT` |
| 7 | Failure after the stock update | `500`; stock, reservation and audit event all undone; same key then gets `201` | `TransactionRollbackIT` |
| 8 | Two JVM processes | Same as 1 and 2 with requests split 50/50; 60 duplicates across both gave 1 `201` and 59 replays; 150 expiries by the real schedulers, each released once | `MultiInstanceIT` |
| 9 | Unauthorised and invalid requests | 44 tests of `401`, `403`, `404` and `400`, including forged, expired and `alg=none` tokens | `SecurityAndValidationIT` |
| 10 | Restart and crash | See below | `RestartDurabilityIT` |

### Crash during traffic

400 requests compete for 200 units across two JVMs, and one JVM is killed with `kill -9` once it has answered about 30
requests. Clients that got no response then retry on the survivor with the same key.

| Measured | Result |
|---|---|
| Requests answered by the doomed instance before the kill | 31 to 32 |
| Requests cut off by the kill | 168 to 169 |
| Committed but never answered, later recovered as a replay | 0 to 1 per run |
| Final reservations | Exactly 200 of 200 units, each sold once |
| Ledger after the crash and after the retries | Consistent |

The third row is the one idempotency exists for. It does not happen on every run because the window between commit
and response is tiny, and when it does, the retry returns the existing reservation rather than selling another unit.

Other restart results:

- `kill -9` of the application, then restart: all data intact, pending reservations can still be settled, and an old
  idempotency key still deduplicates.
- A reservation whose deadline passes while nothing is running is released by the first sweep after restart.
- `kill -9` of PostgreSQL itself, with `fsync` on: committed data survives WAL recovery, the API answers `503` during
  the outage, and the same application process recovers without a restart.

### Deadlocks and contention

| Scenario | Result |
|---|---|
| Forced deadlock against a confirm | PostgreSQL aborted the API transaction with `40P01`, the server retried, the client saw `200`, and `pg_stat_database.deadlocks` confirmed a real deadlock |
| Stock row locked beyond `lock_timeout` | `503` with `Retry-After` after about 9.2 s (3 attempts), nothing written, then `201` with the same key |
| 8 parallel sweepers, 300 reservations, 5 products | Each reservation released once, no deadlock |

### End to end in Docker Compose

`scripts/simulate.sh` against two real containers, with requests split evenly between them:

| Stock | Requests | Successful | Rejected | Consistent |
|---|---|---|---|---|
| 1 | 100 | 1 | 99 | Yes |
| 10 | 100 | 10 | 90 | Yes |
| 100 | 1000 | 100 | 900 | Yes |
| 1000 | 1000 | 1000 | 0 | Yes |

The last row gives a rough idea of hot-row throughput: 1000 reservations of one product took 3.3 to 3.8 s once warmed
up (6.2 s on a cold start), about 270 to 300 commits per second, each writing a reservation, a ledger line and an audit
event. This is a single measurement on a laptop, useful for scale only.

## Do the tests actually catch these bugs?

A green test proves nothing if it cannot go red. So each safeguard was removed from the code, one at a time, and the
relevant tests were run. Unmodified code was confirmed green first, in the same script.

| Safeguard removed | Result | Tests that failed |
|---|---|---|
| `AND available_quantity >= :quantity` | Caught | 4, including `oneUnitHundredUsers` |
| `AND status = 'PENDING'` in settlement | Caught | 4, including `concurrentConfirmsSettleOnce` |
| `AND expires_at > now()` in settlement | Caught | `deadlineIsEnforcedBeforeTheSweepRuns` |
| `ON CONFLICT ... DO NOTHING` | Caught | 5, including `concurrentDuplicatesCreateOneReservation` |
| Request fingerprint comparison | Caught | `differentPayloadIsRejected`, `concurrentMixedPayloads` |
| `SKIP LOCKED` | Caught | `sweepSkipsLockedRows` |
| Sorting the sweeper's stock locks | Caught, 3 of 3 attempts | `parallelSweepersReleaseEachReservationOnce` (real deadlocks), `releasesInProductOrder` |
| `@Retryable` on confirm | Caught | `deadlockVictimIsRetried` |
| `@Transactional` on create | Caught | 4, including `oneUnitHundredUsers` |
| Ownership check on settlement | Caught | `otherUsersReservationsAreInvisible` |
| Admin rule on stock receipts | Caught | `usersCannotCallAdminEndpoints` |
| `@Version` on products | Caught | 6, including `concurrentEditsAreDetected`. Broad because the `NOT NULL` column then breaks product creation too |

The first two rows also show the layers working. With the `WHERE` guard gone, stock still did not go negative:
`ck_stock_available_non_negative` rejected the writes, so clients got `500` instead of `409`, which is why the tests
failed. With the status check gone, the trigger on settled reservations rejected the second settlement.

`ConsistencyVerifierIT` does the same for the verifier by corrupting data in ways that get past the constraints:
counters changed without a ledger entry, a reservation settled twice, a gap in the ledger chain.

## Reproducing

```bash
./mvnw clean verify                  # all tests; needs Docker and JDK 21
cat target/concurrency-report/report.md

docker compose up --build --detach --wait
STOCKGUARD_ADMIN_PASSWORD=... scripts/simulate.sh 1 100
```
