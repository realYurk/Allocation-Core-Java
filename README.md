# Allocation Core (Java 21)

**A deterministic, auditable cost-allocation engine.** Exact integer arithmetic, largest-remainder
settlement that is independent of input order, and an evidence chain that can answer "where did this
penny go" for every row.

This is a from-scratch Java 21 implementation of the allocation semantics defined in
[`docs/SEMANTICS.md`](docs/SEMANTICS.md). It replaces a Go reference implementation that proved the
algorithm correct on 1,000,000 business coordinates; that implementation is used exactly once, to
freeze a golden corpus, and then leaves the project.

---

## Why this exists

Allocation looks like division. It is not.

On 1,000,000 rows totalling 4.5 trillion units, naive per-row rounding drifts by **+329.22** with
`double` and **+995.30** with integer rounding. That is not a precision artefact, it is an
unreconcilable set of books. The problem was never division — it is *who receives the remainder*, and
that answer has to be conserved, deterministic, and independent of file order.

| | naive `double` | naive integer | this engine |
|---|---|---|---|
| drift over 1M rows | +329.22 | +995.30 | **0** |

## Design constraints (non-negotiable)

| Constraint | Consequence |
|---|---|
| Money is a scaled `long`, never `double`/`BigDecimal` in the kernel | no representation drift, no GC churn |
| Every arithmetic op is proven non-overflowing | a wrapped amount never reaches a report |
| Division only via `Apportion` (integer quotient + explicit remainder settlement) | conservation is structural |
| Tie-break on business identity, never row order | reshuffling a CSV cannot move a cent |
| Parallelism only at pool level, output collected in key order | identical bytes for any worker count |
| `allocation-kernel` has **zero** third-party runtime dependencies | usable from a unit test, a batch job, or Spring |
| Fail-closed: any violation aborts the run, publishes nothing | "partially correct books" cannot exist |

Full ruleset: [`docs/CONSTITUTION.md`](docs/CONSTITUTION.md).

## Modules

| Module | Purpose | Dependencies |
|---|---|---|
| `allocation-kernel` | parser, planner, executor, evidence, reconciliation, service layer, CLI | **JDK only** |
| `allocation-conformance` | golden corpus + differential harness (the acceptance gate) | kernel, JUnit |
| `allocation-spring` | Spring Boot 3.2 starter + REST binding (`/allocation/**`) | kernel, spring-boot-web |
| `allocation-bench` | JMH baselines for the hot path; never on a host's classpath | kernel, JMH |
| `allocation-example` | a runnable host app: two-pass overhead allocation, zero config, demo at startup | spring, boot-web |

## Requirements

- JDK 21 (records, sealed types, pattern matching, virtual threads)
- Maven 3.9+

## Build

```bash
mvn verify
```

That is the whole gate: 243 kernel tests, 129 golden-corpus tests, 51 starter tests and 4 example tests,
including three that boot a real application on a random port and call the REST contract over HTTP. Update a
number in `docs/` only after this passes.

Want to see it work before reading anything?

```bash
mvn -pl allocation-example spring-boot:run
```

```bash
mvn -q -pl allocation-bench -am package   # benchmarks
java -jar allocation-bench/target/allocation-bench-*.jar -f 1 -wi 3 -i 5
```

## Status

| Area | State |
|---|---|
| Money, largest-remainder settlement, tie-break by identity | done — byte-identical to the frozen Go corpus |
| Rule model, planner, multi-stage executor (MERGE, filters, hierarchies) | done |
| Evidence, idempotent runs, JDBC persistence (MySQL 8 / PostgreSQL 15 / SQLite) | done |
| Reconciliation (in-memory and sharded-on-disk) | done — 10M × 2 rows in 128MB |
| Scale: 10M amount rows in a 64MB heap | done (`docs/PERF.md`) |
| Service layer: preview / submit / status / result / explain / reconcile / cancel | done, with live progress |
| Spring starter + REST contract (`docs/API.md` §2) | done, verified over a real socket |
| Runnable example (`allocation-example`) | done — zero config, two stages, reconciles against a frozen file |
| Pluggable authorisation, run lifecycle events | done (`AllocationAuthorizer`, `AllocationListener`, composed if several) |
| Metrics for actuator/Prometheus | done — 10 run-level meters from the listener, no query per scrape (`docs/API.md` §5.1) |
| JMH baseline + regression comparison | done (`docs/PERF.md` §7) |
| Checkpoint / resume after interruption | **not done** — an interrupted run is marked `FAILED` (`ALLOC_012`), never resumed |
| Ten-million *target* driver tables | measured: 10,000,000 driver rows need ~1.5GB of heap (17.2s); below 1.5GB the run does not finish |

Milestone-by-milestone history and the reasoning behind each decision: [`docs/PLAN.md`](docs/PLAN.md),
[`docs/DECISIONS.md`](docs/DECISIONS.md).

## Licence

TBD. Until a licence is added, all rights are reserved; do not redistribute.
