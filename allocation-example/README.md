# allocation-example

A small host application that uses the engine the way a real backend would: two passes of cost
allocation over a rule file and two CSVs, served over HTTP, with no configuration beyond two directory
paths.

```bash
mvn -pl allocation-example spring-boot:run
```

```
=== allocation example: two-pass manufacturing overhead ===
submitted 01M6WYF94QSV22R8PKARTD9CMR (replay=false)
status=PUBLISHED  input=410000.00  output=410000.00  balanced=true  rows=36

first 6 result lines:
  {period=2026-01, plant=P1, dept=ASSEMBLY, account=ENERGY}      5419.35
  ...

why {period=2026-01, plant=P1, dept=ASSEMBLY, account=MATERIAL} got its number:
  stage=to-account rule=by-account
    pool=["2026-01","P1","ASSEMBLY","__POOL__"] of 27096.77
    weight=5.000000/10.000000 rank=0 residual=true
    -> 13548.39

reconcile vs data/reference/results.csv: status=MATCH matched=36 differed=0 missing=0 extra=0
```

## What the rule does

Two plants book overhead for two months (410,000.00 in total). The rule splits it twice:

| Stage | Grouping | Target | Driver |
|---|---|---|---|
| `to-dept` | plant × account, dept collapsed | 3 departments | headcount (`dept-headcount.csv`) |
| `to-account` | plant × dept, account collapsed | 3 accounts | account weight (`account-weight.csv`) |

The weights are deliberately not round numbers (7/11/13, 5/3/2), so the split leaves remainders that have
to go somewhere. That is what makes the result worth looking at: 120,000.00 split 7:11:13 across three
departments gives 27,096.77 / 42,580.65 / 50,322.58, which sums to exactly 120,000.00 — the residual
units are assigned by a documented, identity-based tie-break, not by rounding each share and hoping.

```
data/
├── rules/
│   ├── cost-allocation.json     the rule; the file name is the rule code
│   ├── amounts.csv              the amounts to allocate
│   ├── dept-headcount.csv       driver for the first pass
│   └── account-weight.csv       driver for the second pass
└── reference/results.csv        the same 36 numbers, frozen
```

Datasets resolve relative to the rule file, which is why the CSVs sit beside it.

## The HTTP surface

```bash
# what happened
curl -s localhost:8080/allocation/runs/<runId>

# a page of results, money as decimal strings
curl -s 'localhost:8080/allocation/runs/<runId>/result?page=1&size=10'

# why one coordinate got its number, stage by stage
curl -s -X POST localhost:8080/allocation/runs/<runId>/explain \
     -H 'Content-Type: application/json' \
     -d '{"coord":{"period":"2026-01","plant":"P1","dept":"ASSEMBLY","account":"MATERIAL"}}'

# run it yourself, with an idempotency key
curl -s -X POST localhost:8080/allocation/runs -H 'Content-Type: application/json' -d '{
  "ruleCode": "cost-allocation",
  "source": {"type": "csv", "path": "data/rules"},
  "idempotencyKey": "my-key"
}'
```

The full contract, including the error-code table, is [`docs/API.md`](../docs/API.md). Money crosses the
wire as a decimal string and never as a floating-point number.

## Why this module exists

It is three claims made executable.

1. **A host does not scan the starter's package.** This application's base package is
   `io.allocation.example`; the starter lives in `io.allocation.spring`. Its component scan therefore finds
   none of the engine's beans, so everything here arrived through `AutoConfiguration.imports`. Drop the
   dependency into a backend whose base package is `com.ruoyi` and this is the situation you are in. If the
   starter ever depended on being scanned, this module would stop starting.
2. **No configuration is required.** `application.yml` sets two directories and nothing else: workers,
   queue size, memory budget, page limits and every other property have defaults that are correct for a
   single process (`docs/API.md` §5).
3. **The answer is reproducible.** `data/reference/results.csv` is this rule's output as first produced,
   and the demo reconciles against it at startup. If a change to the engine moves one of those 36 amounts by
   one cent, the demo says `status=DIFF` instead of printing new numbers — the same call a migrating team
   makes against their legacy system ([`docs/API.md`](../docs/API.md) §7 step 10), pointed at a file that
   is in the repository.

Turn the startup demo off with `example.demo=false`; point the reference elsewhere with
`example.reference=path/to/file.csv`.

## Tests

```bash
mvn -pl allocation-example test
```

Three tests, on a real port: the demo run publishes with the sample totals, the frozen reference still
matches, and resubmitting the same idempotency key returns the same run instead of computing it twice.
