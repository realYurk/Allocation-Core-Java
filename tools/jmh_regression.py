#!/usr/bin/env python3
"""Compare a JMH run against the checked-in baseline, and fail when something got slower.

Why a script rather than a JUnit test: a throughput assertion that runs inside `mvn verify` fails on a
loaded CI machine for reasons that have nothing to do with the code, and after the second false alarm
everybody adds `-DskipTests`. So the comparison lives outside the ordinary gate and is run when a change
touches the hot path — a deliberate act with a number attached, not a red build nobody believes.

Usage:
    mvn -q -pl allocation-bench -am package
    java -jar allocation-bench/target/allocation-bench-*.jar -f 1 -wi 3 -i 5 \
         -rf json -rff /tmp/jmh.json
    python tools/jmh_regression.py allocation-bench/jmh-baseline.json /tmp/jmh.json

Exit code 0 means every benchmark is within tolerance; 1 means at least one regressed.
"""

import json
import sys

# 20% is wide on purpose. Run-to-run noise on a laptop is a few percent; a real regression in this
# codebase has always been structural (an extra pass, an allocation in a loop), which shows up as tens of
# percent. A threshold tight enough to catch 5% would fire on noise, and noise is how gates die.
DEFAULT_TOLERANCE = 0.20


def load(path):
    with open(path, encoding="utf-8") as handle:
        return {
            (entry["benchmark"], tuple(sorted(entry.get("params", {}).items()))): entry["primaryMetric"]["score"]
            for entry in json.load(handle)
        }


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    tolerance = float(argv[2]) if len(argv) > 2 else DEFAULT_TOLERANCE
    baseline = load(argv[0])
    current = load(argv[1])

    regressed = 0
    missing = 0
    print(f"{'benchmark':<50} {'params':<18} {'baseline':>10} {'now':>10} {'delta':>8}")
    for key, before in sorted(baseline.items()):
        name, params = key
        after = current.get(key)
        label = ",".join(f"{k}={v}" for k, v in params)
        if after is None:
            missing += 1
            print(f"{name:<50} {label:<18} {before:>10.1f} {'MISSING':>10}")
            continue
        delta = (after - before) / before
        flag = ""
        if delta < -tolerance:
            flag = "  <-- regression"
            regressed += 1
        print(f"{name:<50} {label:<18} {before:>10.1f} {after:>10.1f} {delta:>+7.1%}{flag}")

    extra = len(current) - len(baseline) + missing
    if extra:
        print(f"\n{extra} benchmark(s) present on only one side; update the baseline if they are intended.")
    if regressed:
        print(f"\n{regressed} benchmark(s) slower than -{tolerance:.0%}.")
        return 1
    print(f"\nNo benchmark slower than -{tolerance:.0%}.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
