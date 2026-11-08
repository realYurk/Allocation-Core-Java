#!/usr/bin/env python3
"""Generates two large CSV files for the reconciliation benchmark.

The shape is deliberately boring: N distinct coordinates, both sides identical except
for a few cents scattered through the file. That way the only thing the benchmark
measures is the cost of finding the differences, not the cost of parsing exotic input.

    python tools/gen_reconcile_bench.py /tmp/bench 1000000

Writes actual.csv and reference.csv, each with a header. Every 10000th row differs by
one cent in the reference, so a correct comparison must report exactly N/10000
differences and nothing else.
"""

import os
import sys


def main(argv):
    if len(argv) != 3:
        print(__doc__)
        return 2
    out_dir = argv[1]
    rows = int(argv[2])
    os.makedirs(out_dir, exist_ok=True)

    actual_path = os.path.join(out_dir, "actual.csv")
    reference_path = os.path.join(out_dir, "reference.csv")
    expected = 0

    with open(actual_path, "w", encoding="utf-8", newline="\n") as actual, \
            open(reference_path, "w", encoding="utf-8", newline="\n") as reference:
        actual.write("plant,account,amount\n")
        reference.write("plant,account,amount\n")
        for i in range(rows):
            plant = "P%04d" % (i // 1000)
            account = "A%04d" % (i % 1000)
            cents = (i * 7) % 100000
            amount = "%d.%02d" % (cents // 100, cents % 100)
            actual.write("%s,%s,%s\n" % (plant, account, amount))
            if i % 10000 == 0:
                # One cent more in the reference: a difference the comparison must find.
                bumped = cents + 1
                reference.write("%s,%s,%d.%02d\n" % (plant, account, bumped // 100, bumped % 100))
                expected += 1
            else:
                reference.write("%s,%s,%s\n" % (plant, account, amount))

    print("rows=%d expected_differences=%d" % (rows, expected))
    print("actual=%s (%d bytes)" % (actual_path, os.path.getsize(actual_path)))
    print("reference=%s (%d bytes)" % (reference_path, os.path.getsize(reference_path)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
