#!/usr/bin/env python3
"""Generates a synthetic workload for benchmarking: one amount pool per plant, N targets each."""
import os
import sys

def main():
    if len(sys.argv) != 4:
        print("usage: gen_bench.py <outputdir> <pools> <targets>")
        return 2
    out, pools, targets = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
    os.makedirs(out, exist_ok=True)

    with open(os.path.join(out, "amounts.csv"), "w", encoding="utf-8", newline="") as handle:
        handle.write("period,plant,sku,account,currency,amount\n")
        for p in range(pools):
            period = "2026-%02d" % (p % 12 + 1)
            amount = "%d.%02d" % (100000 + p * 37, p % 100)
            handle.write("%s,P%04d,Unassigned,Cost-A,CUR,%s\n" % (period, p, amount))

    names = ",".join('"S%d"' % i for i in range(targets))
    with open(os.path.join(out, "drivers.csv"), "w", encoding="utf-8", newline="") as handle:
        handle.write("period,plant,sku,weight\n")
        for p in range(pools):
            period = "2026-%02d" % (p % 12 + 1)
            for s in range(targets):
                handle.write("%s,P%04d,S%d,%d\n" % (period, p, s, 1 + (s * 7 + p) % 1000))

    with open(os.path.join(out, "rule.json"), "w", encoding="utf-8", newline="") as handle:
        handle.write("""{
  "schema": "allocation-ir/2.0",
  "rounding_policy": "LARGEST_REMAINDER_IDENTITY",
  "name": "benchmark %d x %d",
  "precision": 2,
  "dimensions": ["period", "plant", "sku", "account", "currency"],
  "immutable_dimensions": ["period", "currency"],
  "hierarchies": {},
  "datasets": { "amounts": { "expenses": "amounts.csv" }, "drivers": { "sales": "drivers.csv" } },
  "stages": [
    {
      "id": "st1",
      "inputs": [{ "from": "expenses", "share": "1" }],
      "source": { "grouping": { "period": "AS_IS", "plant": "AS_IS", "sku": "TOTAL", "account": "AS_IS", "currency": "AS_IS" }, "filters": {} },
      "target": {
        "period": { "mode": "SOURCE" },
        "plant": { "mode": "SOURCE" },
        "sku": { "mode": "INCLUDE", "include": [%s] },
        "account": { "mode": "SOURCE" },
        "currency": { "mode": "SOURCE" }
      },
      "conservation_keys": ["period", "plant", "account", "currency"],
      "allocations": [
        { "id": "units", "share": "1", "driver_dataset": "sales", "weight_field": "weight", "negative_driver_policy": "REJECT" }
      ]
    }
  ]
}
""" % (pools, targets, names))

    total_rows = pools * targets
    print("wrote %s: %d pools x %d targets = %d driver rows" % (out, pools, targets, total_rows))
    return 0


if __name__ == "__main__":
    sys.exit(main())
