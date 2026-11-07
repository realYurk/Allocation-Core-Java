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
| `allocation-kernel` | parser, planner, executor, evidence, reconciliation | **JDK only** |
| `allocation-conformance` | golden corpus + differential harness (the acceptance gate) | kernel, JUnit |

## Requirements

- JDK 21 (records, sealed types, pattern matching, virtual threads)
- Maven 3.9+

## Build

```bash
mvn verify
```

## Status

Under construction, milestone by milestone. See [`docs/PLAN.md`](docs/PLAN.md).

| Milestone | Scope | State |
|---|---|---|
| M1 | money domain, exact split, golden corpus | in progress |
| M2 | rule model, planner, single-stage executor | planned |
| M3 | evidence, reconciliation, Spring starter | planned |
| M4 | streaming / checkpointed execution | planned |

## Licence

TBD. Until a licence is added, all rights are reserved; do not redistribute.
