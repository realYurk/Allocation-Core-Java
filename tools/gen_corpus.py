#!/usr/bin/env python3
"""Generates the conformance corpus.

Each scenario is a self-contained directory: a rule document plus its datasets. The corpus is frozen
against the reference implementation exactly once; from then on it is the acceptance gate.

Scenarios are written to be *readable*. A scenario that fails should fail for one obvious reason, so
that when it breaks two years from now the person looking at it does not need to reconstruct intent.
"""
import json
import os
import shutil
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                    "allocation-conformance", "src", "test", "resources", "corpus")

DIMS5 = ["period", "plant", "sku", "account", "currency"]
DIMS4 = ["period", "plant", "sku", "currency"]


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(text)


def csv(headers, rows):
    out = [",".join(headers)]
    for row in rows:
        cells = []
        for value in row:
            text = str(value)
            if any(ch in text for ch in [",", '"', "\n", "\r"]) or text[:1].isspace():
                text = '"' + text.replace('"', '""') + '"'
            cells.append(text)
        out.append(",".join(cells))
    return "\n".join(out) + "\n"


def rule(dims, stages, precision=2, name="", immutable=None, hierarchies=None,
         amounts=None, drivers=None, rounding="LARGEST_REMAINDER_IDENTITY"):
    return {
        "schema": "allocation-ir/2.0",
        "rounding_policy": rounding,
        "name": name or "corpus scenario",
        "precision": precision,
        "dimensions": dims,
        "immutable_dimensions": immutable if immutable is not None else [dims[0]],
        "hierarchies": hierarchies or {},
        "datasets": {
            "amounts": amounts or {"amounts": "amounts.csv"},
            "drivers": drivers or {"drivers": "drivers.csv"},
        },
        "stages": stages,
    }


def stage(sid, grouping, target, allocations=None, inputs=None, conservation=None,
          operation=None, source_filters=None, enabled=None):
    body = {"id": sid}
    if operation:
        body["operation"] = operation
    body["inputs"] = inputs or [{"from": "amounts", "share": "1"}]
    body["source"] = {"grouping": grouping, "filters": source_filters or {}}
    body["target"] = target
    body["conservation_keys"] = conservation or []
    if allocations is None:
        body["allocations"] = []
    else:
        body["allocations"] = allocations
    if enabled is not None:
        body["enabled"] = enabled
    return body


def alloc(aid, share="1", dataset="drivers", field="weight", policy=None, reason=None, filters=None):
    body = {"id": aid, "share": share, "driver_dataset": dataset, "weight_field": field}
    if filters:
        body["driver_filters"] = filters
    if policy:
        body["negative_driver_policy"] = policy
    if reason:
        body["signed_driver_reason"] = reason
    return body


def group(dims, overrides=None):
    out = {}
    for dim in dims:
        out[dim] = (overrides or {}).get(dim, "AS_IS")
    return out


def tgt(dims, overrides=None):
    out = {}
    for dim in dims:
        out[dim] = (overrides or {}).get(dim, {"mode": "SOURCE"})
    return out


def scenario(name, spec, files, expect="PASS", note=""):
    path = os.path.join(ROOT, name)
    if os.path.isdir(path):
        shutil.rmtree(path)
    os.makedirs(path)
    write(os.path.join(path, "rule.json"), json.dumps(spec, ensure_ascii=False, indent=2) + "\n")
    for filename, content in files.items():
        write(os.path.join(path, filename), content)
    write(os.path.join(path, "expect.json"),
          json.dumps({"status": expect, "note": note}, ensure_ascii=False, indent=2) + "\n")


# ------------------------------------------------------------------------------------------------
# Group 1: rounding geometry
# ------------------------------------------------------------------------------------------------

def rounding_scenarios():
    base_amounts = csv(
        ["period", "plant", "sku", "account", "currency", "amount"],
        [["2026-01", "P0", "Unassigned", "Cost-A", "CUR", "100.00"]])
    drivers3 = csv(["period", "plant", "sku", "weight"],
                   [["2026-01", "P0", "S0", 1], ["2026-01", "P0", "S1", 1], ["2026-01", "P0", "S2", 1]])
    spec = rule(DIMS5, [stage("st1", group(DIMS5, {"sku": "TOTAL"}),
                              tgt(DIMS5, {"sku": {"mode": "INCLUDE", "include": ["S0", "S1", "S2"]}}),
                              [alloc("r1", field="weight")],
                              conservation=["period", "plant", "account", "currency"])])
    scenario("rounding-three-equal", spec,
             {"amounts.csv": base_amounts, "drivers.csv": drivers3},
             note="1.00 split three ways: residual goes to the first identity")

    amount = csv(["period", "plant", "sku", "account", "currency", "amount"],
                 [["2026-01", "P0", "Unassigned", "Cost-A", "CUR", "0.01"]])
    scenario("rounding-one-cent", spec,
             {"amounts.csv": amount, "drivers.csv": drivers3},
             note="a single cent cannot be split; exactly one target receives it")

    negative = csv(["period", "plant", "sku", "account", "currency", "amount"],
                   [["2026-01", "P0", "Unassigned", "Cost-A", "CUR", "-100.00"]])
    scenario("rounding-negative-total", spec,
             {"amounts.csv": negative, "drivers.csv": drivers3},
             note="negative totals must mirror positive ones, not round the other way")

    drivers7 = csv(["period", "plant", "sku", "weight"],
                   [["2026-01", "P0", "S%d" % i, i + 1] for i in range(7)])
    spec7 = rule(DIMS5, [stage("st1", group(DIMS5, {"sku": "TOTAL"}),
                               tgt(DIMS5, {"sku": {"mode": "INCLUDE",
                                                   "include": ["S%d" % i for i in range(7)]}}),
                               [alloc("r1", field="weight")],
                               conservation=["period", "plant", "account", "currency"])])
    scenario("rounding-weight-ramp", spec7,
             {"amounts.csv": base_amounts, "drivers.csv": drivers7},
             note="seven targets with weights 1..7: residual ranks by remainder then identity")

    amount_odd = csv(["period", "plant", "sku", "account", "currency", "amount"],
                     [["2026-01", "P0", "Unassigned", "Cost-A", "CUR", "1000.01"]])
    scenario("rounding-odd-amount", spec7,
             {"amounts.csv": amount_odd, "drivers.csv": drivers7},
             note="residual larger than one: the first N ranks each receive one unit")


# ------------------------------------------------------------------------------------------------
# Group 2: precision
# ------------------------------------------------------------------------------------------------

def precision_scenarios():
    for precision, amount in [(0, "101"), (2, "100.01"), (4, "100.0001"), (6, "100.000001")]:
        spec = rule(["period", "sku"], [
            stage("st1", {"period": "AS_IS", "sku": "TOTAL"},
                  {"period": {"mode": "SOURCE"},
                   "sku": {"mode": "INCLUDE", "include": ["S0", "S1", "S2"]}},
                  [alloc("r1", field="weight")],
                  conservation=["period"])],
            precision=precision, immutable=["period"])
        scenario("precision-%d" % precision, spec, {
            "amounts.csv": csv(["period", "sku", "amount"], [["2026-01", "Unassigned", amount]]),
            "drivers.csv": csv(["period", "sku", "weight"],
                               [["2026-01", "S0", 1], ["2026-01", "S1", 1], ["2026-01", "S2", 1]]),
        }, note="precision %d must round-trip without drift" % precision)


# ------------------------------------------------------------------------------------------------
# Group 3: stages and routing
# ------------------------------------------------------------------------------------------------

def stage_scenarios():
    amounts = csv(["period", "plant", "sku", "account", "currency", "amount"], [
        ["2026-01", "P0", "Unassigned", "Cost-A", "CUR", "1000.00"],
        ["2026-01", "P1", "Unassigned", "Cost-A", "CUR", "250.00"],
        ["2026-02", "P0", "Unassigned", "Cost-A", "CUR", "75.50"],
    ])
    drivers = csv(["period", "plant", "sku", "weight"], [
        ["2026-01", "P0", "S0", 30], ["2026-01", "P0", "S1", 70],
        ["2026-01", "P1", "S0", 1], ["2026-01", "P1", "S1", 3],
        ["2026-02", "P0", "S0", 5], ["2026-02", "P0", "S1", 5],
    ])
    second = csv(["period", "plant", "sku", "account", "weight"], [
        ["2026-01", "P0", "S0", "Cost-A", 1], ["2026-01", "P0", "S0", "Cost-B", 1],
        ["2026-01", "P0", "S1", "Cost-A", 1], ["2026-01", "P0", "S1", "Cost-B", 1],
        ["2026-01", "P1", "S0", "Cost-A", 1], ["2026-01", "P1", "S0", "Cost-B", 1],
        ["2026-01", "P1", "S1", "Cost-A", 1], ["2026-01", "P1", "S1", "Cost-B", 1],
        ["2026-02", "P0", "S0", "Cost-A", 1], ["2026-02", "P0", "S0", "Cost-B", 1],
        ["2026-02", "P0", "S1", "Cost-A", 1], ["2026-02", "P0", "S1", "Cost-B", 1],
    ])

    two_stage = rule(DIMS5, [
        stage("by-sku", group(DIMS5, {"sku": "TOTAL"}),
              tgt(DIMS5, {"sku": {"mode": "INCLUDE", "include": ["S0", "S1"]}}),
              [alloc("r1", field="weight")], conservation=["period", "plant", "account", "currency"]),
        stage("by-account", group(DIMS5, {"account": "TOTAL"}),
              tgt(DIMS5, {"account": {"mode": "INCLUDE", "include": ["Cost-A", "Cost-B"]}}),
              [alloc("r2", dataset="accounts", field="weight")],
              inputs=[{"from": "by-sku", "share": "1"}],
              conservation=["period", "plant", "sku", "currency"]),
    ], drivers={"drivers": "drivers.csv", "accounts": "accounts.csv"})
    scenario("stage-two-level", two_stage, {
        "amounts.csv": amounts, "drivers.csv": drivers, "accounts.csv": second,
    }, note="two stages: pool membership and conservation across the hop")

    three_stage = rule(DIMS5, [
        stage("s1", group(DIMS5, {"sku": "TOTAL"}),
              tgt(DIMS5, {"sku": {"mode": "INCLUDE", "include": ["S0", "S1"]}}),
              [alloc("r1", field="weight")], conservation=["period", "plant", "account", "currency"]),
        stage("s2", group(DIMS5, {"account": "TOTAL"}),
              tgt(DIMS5, {"account": {"mode": "INCLUDE", "include": ["Cost-A", "Cost-B"]}}),
              [alloc("r2", dataset="accounts", field="weight")],
              inputs=[{"from": "s1", "share": "1"}],
              conservation=["period", "plant", "sku", "currency"]),
        stage("s3", group(DIMS5, {"sku": "TOTAL"}),
              tgt(DIMS5, {"sku": {"mode": "INCLUDE", "include": ["S0", "S1"]}}),
              [alloc("r3", field="weight")],
              inputs=[{"from": "s2", "share": "1"}],
              conservation=["period", "plant", "account", "currency"]),
    ], drivers={"drivers": "drivers.csv", "accounts": "accounts.csv"})
    scenario("stage-three-level", three_stage, {
        "amounts.csv": amounts, "drivers.csv": drivers, "accounts.csv": second,
    }, note="three stages: terminal_stage, lineage and totals")

    split = rule(DIMS5, [
        stage("share-a", group(DIMS5, {"sku": "TOTAL"}),
              tgt(DIMS5, {"sku": {"mode": "INCLUDE", "include": ["S0", "S1"]}}),
              [alloc("r1", field="weight")],
              inputs=[{"from": "amounts", "share": "0.3"}],
              conservation=["period", "plant", "account", "currency"]),
        stage("share-b", group(DIMS5, {"sku": "TOTAL"}),
              tgt(DIMS5, {"sku": {"mode": "INCLUDE", "include": ["S0", "S1"]}}),
              [alloc("r2", field="weight")],
              inputs=[{"from": "amounts", "share": "0.7"}],
              conservation=["period", "plant", "account", "currency"]),
    ])
    scenario("stage-split-source", split, {
        "amounts.csv": amounts,
        "drivers.csv": drivers,
    }, note="one source feeds two stages 30/70; both stages must stay conserved")

    merge_spec = rule(DIMS5, [
        stage("fold", group(DIMS5, {"sku": "TOTAL"}),
              tgt(DIMS5, {"sku": {"mode": "INCLUDE", "include": ["S0", "S1"]}}),
              [alloc("r1", field="weight")], inputs=[{"from": "amounts", "share": "1"},
                                                     {"from": "other", "share": "1"}],
              conservation=["period", "plant", "account", "currency"]),
        stage("coalesce", group(DIMS5, {"sku": "TOTAL"}),
              tgt(DIMS5, {"sku": {"mode": "INCLUDE", "include": ["S0", "S1"]}}),
              [], inputs=[{"from": "fold", "share": "1"}], operation="MERGE"),
    ], amounts={"amounts": "amounts.csv", "other": "other.csv"})
    scenario("stage-merge", merge_spec, {
        "amounts.csv": amounts,
        "other.csv": csv(["period", "plant", "sku", "account", "currency", "amount"],
                         [["2026-01", "P0", "Unassigned", "Cost-A", "CUR", "10.00"]]),
        "drivers.csv": drivers,
    }, note="MERGE coalesces duplicate coordinates and records lineage")

    pass_spec = rule(DIMS5, [
        stage("skip", group(DIMS5, {"sku": "TOTAL"}),
              tgt(DIMS5, {"sku": {"mode": "INCLUDE", "include": ["S0", "S1"]}}),
              [alloc("r1", field="weight")], inputs=[{"from": "amounts", "share": "1"},
                                                     {"from": "other", "share": "1"}],
              conservation=["period", "plant", "account", "currency"]),
        stage("pass", group(DIMS5),
              tgt(DIMS5),
              [], inputs=[{"from": "skip", "share": "1"}], operation="PASS"),
    ], amounts={"amounts": "amounts.csv", "other": "other.csv"})
    scenario("stage-pass", pass_spec, {
        "amounts.csv": amounts,
        "other.csv": csv(["period", "plant", "sku", "account", "currency", "amount"],
                         [["2026-01", "P0", "Unassigned", "Cost-A", "CUR", "10.00"]]),
        "drivers.csv": drivers,
    }, note="PASS forwards rows untouched")

    disabled_spec = rule(DIMS5, [
        stage("off", group(DIMS5, {"sku": "TOTAL"}),
              tgt(DIMS5, {"sku": {"mode": "INCLUDE", "include": ["S0", "S1"]}}),
              [alloc("r1", field="weight")], inputs=[{"from": "amounts", "share": "1"},
                                                     {"from": "other", "share": "1"}],
              conservation=["period", "plant", "account", "currency"], enabled=False),
    ], amounts={"amounts": "amounts.csv", "other": "other.csv"})
    scenario("stage-disabled", disabled_spec, {
        "amounts.csv": amounts,
        "other.csv": csv(["period", "plant", "sku", "account", "currency", "amount"],
                         [["2026-01", "P0", "Unassigned", "Cost-A", "CUR", "10.00"]]),
        "drivers.csv": drivers,
    }, note="a disabled stage behaves as PASS but still records membership")


# ------------------------------------------------------------------------------------------------
# Group 4: filters, hierarchies, target modes
# ------------------------------------------------------------------------------------------------

def filter_scenarios():
    hierarchy = {"sku": {"sellable": ["S0", "S1"], "sampled": ["S1", "S2"]}}
    amounts = csv(["period", "plant", "sku", "account", "currency", "amount"], [
        ["2026-01", "P0", "Unassigned", "Cost-A", "CUR", "100.00"],
        ["2026-01", "P0", "Unassigned", "Cost-B", "CUR", "50.00"],
    ])
    drivers = csv(["period", "plant", "sku", "weight"], [
        ["2026-01", "P0", "S0", 1], ["2026-01", "P0", "S1", 1],
        ["2026-01", "P0", "S2", 1], ["2026-01", "P0", "S3", 1],
    ])

    include_spec = rule(DIMS5, [
        stage("st1", group(DIMS5, {"sku": "TOTAL"}),
              tgt(DIMS5, {"sku": {"mode": "INCLUDE", "include": ["sellable"]}}),
              [alloc("r1", field="weight")],
              conservation=["period", "plant", "account", "currency"])], hierarchies=hierarchy)
    scenario("filter-hierarchy-include", include_spec,
             {"amounts.csv": amounts, "drivers.csv": drivers},
             note="hierarchy 'sellable' expands to S0,S1; S2 and S3 are excluded")

    exclude_spec = rule(DIMS5, [
        stage("st1", group(DIMS5, {"sku": "TOTAL"}),
              tgt(DIMS5, {"sku": {"mode": "INCLUDE_EXCLUDE", "include": ["S0", "S1", "S2", "S3"],
                                  "exclude": ["sampled"]}}),
              [alloc("r1", field="weight")],
              conservation=["period", "plant", "account", "currency"])], hierarchies=hierarchy)
    scenario("filter-hierarchy-exclude", exclude_spec,
             {"amounts.csv": amounts, "drivers.csv": drivers},
             note="'sampled' expands to S1,S2 and removes them from the include list")

    source_filter = rule(DIMS5, [
        stage("st1", group(DIMS5, {"sku": "TOTAL"}),
              tgt(DIMS5, {"sku": {"mode": "INCLUDE", "include": ["S0", "S1"]}}),
              [alloc("r1", field="weight")],
              source_filters={"account": {"include": ["Cost-A"]}},
              conservation=["period", "plant", "account", "currency"])])
    scenario("filter-source-pass-through", source_filter,
             {"amounts.csv": amounts, "drivers.csv": drivers},
             note="Cost-B does not match the source filter and passes through unchanged")

    element_spec = rule(DIMS5, [
        stage("st1", group(DIMS5, {"sku": "TOTAL", "account": "TOTAL"}),
              tgt(DIMS5, {"sku": {"mode": "INCLUDE", "include": ["S0", "S1"]},
                          "account": {"mode": "ELEMENT", "value": "Pooled"}}),
              [alloc("r1", field="weight")],
              conservation=["period", "plant", "currency"])])
    scenario("target-element-mode", element_spec,
             {"amounts.csv": amounts, "drivers.csv": drivers},
             note="ELEMENT collapses the account dimension to a constant")

    driver_filter = rule(DIMS5, [
        stage("st1", group(DIMS5, {"sku": "TOTAL"}),
              tgt(DIMS5, {"sku": {"mode": "INCLUDE", "include": ["S0", "S1", "S2", "S3"]}}),
              [alloc("r1", field="weight",
                     filters={"sku": {"include": ["S0", "S1"]}})],
              conservation=["period", "plant", "account", "currency"])])
    scenario("filter-driver-subset", driver_filter,
             {"amounts.csv": amounts, "drivers.csv": drivers},
             note="driver_filters restrict rows before candidates are built")

    two_rules = rule(DIMS5, [
        stage("st1", group(DIMS5, {"sku": "TOTAL"}),
              tgt(DIMS5, {"sku": {"mode": "INCLUDE", "include": ["S0", "S1", "S2", "S3"]}}),
              [alloc("head", share="0.4", field="weight"),
               alloc("tail", share="0.6", field="weight")],
              conservation=["period", "plant", "account", "currency"])])
    scenario("multi-rule-shares", two_rules,
             {"amounts.csv": amounts, "drivers.csv": drivers},
             note="two rules share the pool 40/60 and each splits with the same weights")


# ------------------------------------------------------------------------------------------------
# Group 5: data pathologies
# ------------------------------------------------------------------------------------------------

def pathology_scenarios():
    base = group(DIMS5, {"sku": "TOTAL"})
    target = tgt(DIMS5, {"sku": {"mode": "INCLUDE", "include": ["S0", "S1", "S2"]}})

    duplicate = rule(DIMS5, [stage("st1", base, target, [alloc("r1", field="weight")],
                                  conservation=["period", "plant", "account", "currency"])])
    scenario("data-duplicate-driver-rows", duplicate, {
        "amounts.csv": csv(["period", "plant", "sku", "account", "currency", "amount"],
                           [["2026-01", "P0", "Unassigned", "Cost-A", "CUR", "300.00"]]),
        "drivers.csv": csv(["period", "plant", "sku", "weight"],
                           [["2026-01", "P0", "S0", 1], ["2026-01", "P0", "S0", 2],
                            ["2026-01", "P0", "S1", 1], ["2026-01", "P0", "S2", 1]]),
    }, note="duplicate driver rows are summed (S0 gets weight 3 of 5)")

    missing = rule(DIMS5, [stage("st1", base, target, [alloc("r1", field="weight")],
                                conservation=["period", "plant", "account", "currency"])])
    scenario("data-missing-declared-target", missing, {
        "amounts.csv": csv(["period", "plant", "sku", "account", "currency", "amount"],
                           [["2026-01", "P0", "Unassigned", "Cost-A", "CUR", "100.00"]]),
        "drivers.csv": csv(["period", "plant", "sku", "weight"],
                           [["2026-01", "P0", "S0", 1], ["2026-01", "P0", "S1", 1]]),
    }, expect="PASS",
        note="S2 is declared but has no weight: it silently disappears, and Java raises a warning")

    zero_rows = rule(DIMS5, [stage("st1", base, target, [alloc("r1", field="weight")],
                                   conservation=["period", "plant", "account", "currency"])])
    scenario("data-zero-amount", zero_rows, {
        "amounts.csv": csv(["period", "plant", "sku", "account", "currency", "amount"],
                           [["2026-01", "P0", "Unassigned", "Cost-A", "CUR", "0.00"],
                            ["2026-01", "P0", "Unassigned", "Cost-A", "CUR", "100.00"]]),
        "drivers.csv": csv(["period", "plant", "sku", "weight"],
                           [["2026-01", "P0", "S0", 1], ["2026-01", "P0", "S1", 1],
                            ["2026-01", "P0", "S2", 1]]),
    }, note="a zero amount contributes nothing and produces no output row")

    signed = rule(DIMS5, [stage("st1", base, target,
                                [alloc("r1", field="weight",
                                       policy="ALLOW_SIGNED",
                                       reason="reversal entry approved by controller")],
                                conservation=["period", "plant", "account", "currency"])],
                  immutable=["period", "currency"])
    scenario("data-signed-drivers", signed, {
        "amounts.csv": csv(["period", "plant", "sku", "account", "currency", "amount"],
                           [["2026-01", "P0", "Unassigned", "Cost-A", "CUR", "100.00"]]),
        "drivers.csv": csv(["period", "plant", "sku", "weight"],
                           [["2026-01", "P0", "S0", 3], ["2026-01", "P0", "S1", -1],
                            ["2026-01", "P0", "S2", 0]]),
    }, note="negative weights need an explicit policy and a business reason")

    unicode = rule(["期间", "工厂", "产品", "科目", "币种"], [
        stage("分摊", {"期间": "AS_IS", "工厂": "AS_IS", "产品": "TOTAL",
                       "科目": "AS_IS", "币种": "AS_IS"},
              {"期间": {"mode": "SOURCE"}, "工厂": {"mode": "SOURCE"},
               "产品": {"mode": "INCLUDE", "include": ["甲", "乙", "丙"]},
               "科目": {"mode": "SOURCE"}, "币种": {"mode": "SOURCE"}},
              [alloc("规则", field="权重")],
              conservation=["期间", "工厂", "科目", "币种"])], immutable=["期间", "币种"])
    scenario("text-unicode-members", unicode, {
        "amounts.csv": csv(["期间", "工厂", "产品", "科目", "币种", "amount"],
                           [["2026-01", "上海", "Unassigned", "人工", "人民币", "100.00"]]),
        "drivers.csv": csv(["期间", "工厂", "产品", "权重"],
                           [["2026-01", "上海", "甲", 1], ["2026-01", "上海", "乙", 1],
                            ["2026-01", "上海", "丙", 1]]),
    }, note="non-ASCII dimension names, members and rule identifiers")

    awkward = rule(["period", "plant", "sku", "account", "currency"], [
        stage("st1", group(DIMS5, {"sku": "TOTAL"}),
              tgt(DIMS5, {"sku": {"mode": "INCLUDE",
                                  "include": ['a,b', 'q"q', "line\nbreak"]}}),
              [alloc("r1", field="weight")],
              conservation=["period", "plant", "account", "currency"])])
    scenario("text-quoting", awkward, {
        "amounts.csv": csv(["period", "plant", "sku", "account", "currency", "amount"],
                           [["2026-01", "P0", "Unassigned", "Cost-A", "CUR", "100.00"]]),
        "drivers.csv": csv(["period", "plant", "sku", "weight"],
                           [["2026-01", "P0", 'a,b', 1], ["2026-01", "P0", 'q"q', 1],
                            ["2026-01", "P0", "line\nbreak", 1]]),
    }, note="members containing comma, quote and newline must round-trip through CSV")

# ------------------------------------------------------------------------------------------------
# Group 6: expected failures
# ------------------------------------------------------------------------------------------------

def failure_scenarios():
    base = group(DIMS5, {"sku": "TOTAL"})
    target = tgt(DIMS5, {"sku": {"mode": "INCLUDE", "include": ["S0", "S1"]}})
    amount = csv(["period", "plant", "sku", "account", "currency", "amount"],
                 [["2026-01", "P0", "Unassigned", "Cost-A", "CUR", "100.00"]])

    scenario("fail-no-eligible-drivers",
             rule(DIMS5, [stage("st1", base, target, [alloc("r1", field="weight")],
                                conservation=["period", "plant", "account", "currency"])]),
             {"amounts.csv": amount,
              "drivers.csv": csv(["period", "plant", "sku", "weight"], [["2026-01", "P0", "ZZ", 1]])},
             expect="FAIL", note="the pool has no candidate at all: fail-closed")

    scenario("fail-negative-driver",
             rule(DIMS5, [stage("st1", base, target, [alloc("r1", field="weight")],
                                conservation=["period", "plant", "account", "currency"])]),
             {"amounts.csv": amount,
              "drivers.csv": csv(["period", "plant", "sku", "weight"],
                                 [["2026-01", "P0", "S0", 1], ["2026-01", "P0", "S1", -1]])},
             expect="FAIL", note="a negative weight without ALLOW_SIGNED is rejected")

    scenario("fail-hierarchy-cycle",
             rule(DIMS5, [stage("st1", base,
                                tgt(DIMS5, {"sku": {"mode": "INCLUDE", "include": ["a"]}}),
                                [alloc("r1", field="weight")],
                                conservation=["period", "plant", "account", "currency"])],
                  hierarchies={"sku": {"a": ["b"], "b": ["a"]}}),
             {"amounts.csv": amount,
              "drivers.csv": csv(["period", "plant", "sku", "weight"], [["2026-01", "P0", "a", 1]])},
             expect="FAIL", note="a cyclic hierarchy must fail instead of looping forever")

    scenario("fail-share-total",
             rule(DIMS5, [stage("st1", base, target,
                                [alloc("h", share="0.4", field="weight"),
                                 alloc("t", share="0.4", field="weight")],
                                conservation=["period", "plant", "account", "currency"])]),
             {"amounts.csv": amount,
              "drivers.csv": csv(["period", "plant", "sku", "weight"], [["2026-01", "P0", "S0", 1]])},
             expect="FAIL", note="allocation shares must sum to exactly 1")

    scenario("fail-scale-exceeds-precision",
             rule(DIMS5, [stage("st1", base, target, [alloc("r1", field="weight")],
                                conservation=["period", "plant", "account", "currency"])], precision=2),
             {"amounts.csv": csv(["period", "plant", "sku", "account", "currency", "amount"],
                                 [["2026-01", "P0", "Unassigned", "Cost-A", "CUR", "100.005"]]),
              "drivers.csv": csv(["period", "plant", "sku", "weight"],
                                 [["2026-01", "P0", "S0", 1], ["2026-01", "P0", "S1", 1]])},
             expect="FAIL", note="three decimals at precision 2 is rejected, never rounded")

    scenario("fail-amount-overflow",
             rule(DIMS5, [stage("st1", base, target, [alloc("r1", field="weight")],
                                conservation=["period", "plant", "account", "currency"])]),
             {"amounts.csv": csv(["period", "plant", "sku", "account", "currency", "amount"],
                                 [["2026-01", "P0", "Unassigned", "Cost-A", "CUR",
                                   "999999999999999999999.00"]]),
              "drivers.csv": csv(["period", "plant", "sku", "weight"],
                                 [["2026-01", "P0", "S0", 1], ["2026-01", "P0", "S1", 1]])},
             expect="FAIL", note="an amount outside int64 is rejected, not truncated")

    scenario("fail-duplicate-column",
             rule(DIMS5, [stage("st1", base, target, [alloc("r1", field="weight")],
                                conservation=["period", "plant", "account", "currency"])]),
             {"amounts.csv": csv(["period", "plant", "sku", "account", "currency", "amount", "amount"],
                                 [["2026-01", "P0", "Unassigned", "Cost-A", "CUR", "100.00", "200.00"]]),
              "drivers.csv": csv(["period", "plant", "sku", "weight"],
                                 [["2026-01", "P0", "S0", 1], ["2026-01", "P0", "S1", 1]])},
             expect="FAIL", note="a duplicated column name makes the amount column ambiguous")


def main():
    if os.path.isdir(ROOT):
        shutil.rmtree(ROOT)
    os.makedirs(ROOT)
    rounding_scenarios()
    precision_scenarios()
    stage_scenarios()
    filter_scenarios()
    pathology_scenarios()
    failure_scenarios()
    names = sorted(os.listdir(ROOT))
    print("generated %d scenarios" % len(names))
    for name in names:
        print("  " + name)


if __name__ == "__main__":
    sys.exit(main())
