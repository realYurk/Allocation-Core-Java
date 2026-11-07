#!/usr/bin/env bash
# Freezes reference output for every corpus scenario.
#
# This runs the Go reference implementation ONCE per scenario and stores its evidence files under
# `<scenario>/.golden/`. After this script has run, the Go implementation is no longer needed: the
# frozen files are the oracle.
#
# Usage: tools/freeze_golden.sh <path-to-reference-binary> [--force]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORPUS="$ROOT/allocation-conformance/src/test/resources/corpus"
REFERENCE="${1:-}"
FORCE="${2:-}"

if [ -z "$REFERENCE" ] || [ ! -x "$REFERENCE" ]; then
  echo "usage: $0 <path-to-reference-binary> [--force]" >&2
  echo "the reference binary is only needed to (re)freeze the corpus" >&2
  exit 2
fi

failures=0
for dir in "$CORPUS"/*/; do
  name="$(basename "$dir")"
  golden="$dir/.golden"
  expect="$(python -c "import json,sys;print(json.load(open(sys.argv[1]))['status'])" "$dir/expect.json")"

  if [ -d "$golden" ] && [ "$FORCE" != "--force" ]; then
    echo "skip  $name (already frozen)"
    continue
  fi
  rm -rf "$golden"

  set +e
  output="$("$REFERENCE" run "$dir/rule.json" "$golden" --workers 1 2>&1)"
  status_code=$?
  set -e

  actual="FAIL"
  if [ $status_code -eq 0 ]; then actual="PASS"; fi

  if [ "$actual" != "$expect" ]; then
    echo "MISMATCH $name: expected $expect, reference said $actual"
    echo "$output" | head -5
    failures=$((failures + 1))
    rm -rf "$golden"
    continue
  fi

  if [ "$actual" = "FAIL" ]; then
    rm -rf "$golden"
    echo "ok    $name (both reject)"
  else
    if [ ! -f "$golden/results.csv" ]; then
      echo "MISSING $name: reference produced no results"
      failures=$((failures + 1))
      continue
    fi
    # The summary contains timestamps and absolute paths; only the CSVs are frozen.
    rm -f "$golden/run_summary.json"
    echo "ok    $name (frozen)"
  fi
done

echo
if [ $failures -ne 0 ]; then
  echo "$failures scenario(s) disagreed with the expected status"
  exit 1
fi
echo "corpus frozen"
