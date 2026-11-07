package io.allocation.kernel.exec;

import io.allocation.kernel.json.JsonValue;
import io.allocation.kernel.money.Decimal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable outcome of a run.
 *
 * <p>The summary is the receipt. It records what was read (content hashes), what was produced (row
 * counts and totals) and whether the books balanced — enough for an auditor to reproduce the run from
 * the archived inputs without trusting the person who ran it.
 */
public record RunSummary(
        Map<String, String> dataSha256,
        String schema,
        String engine,
        String name,
        String ruleSha256,
        String startedUtc,
        List<StageSummary> stages,
        long inputAmount,
        long outputAmount,
        int precision,
        long poolChecks,
        long outputRows,
        long terminalRows,
        long ledgerRows,
        String status,
        List<RunSink.Warning> warnings) {

    /** Per-stage totals. */
    public record StageSummary(String stage, long input, long output, int pools, long outputRows) {
    }

    public RunSummary {
        dataSha256 = Map.copyOf(dataSha256);
        stages = List.copyOf(stages);
        warnings = List.copyOf(warnings);
    }

    public boolean passed() {
        return "PASS".equals(status);
    }

    /** Serialises to the reference summary layout, field order included. */
    public String toJson() {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        Map<String, JsonValue> hashes = new LinkedHashMap<>();
        List<String> labels = new ArrayList<>(dataSha256.keySet());
        labels.sort(io.allocation.kernel.coord.Utf8Order::compare);
        for (String label : labels) {
            hashes.put(label, new JsonValue.Str(dataSha256.get(label)));
        }
        fields.put("data_sha256", new JsonValue.Obj(hashes));
        fields.put("schema", new JsonValue.Str(schema));
        fields.put("engine", new JsonValue.Str(engine));
        fields.put("name", new JsonValue.Str(name));
        fields.put("rule_sha256", new JsonValue.Str(ruleSha256));
        fields.put("started_utc", new JsonValue.Str(startedUtc));
        List<JsonValue> stageValues = new ArrayList<>(stages.size());
        for (StageSummary stage : stages) {
            Map<String, JsonValue> s = new LinkedHashMap<>();
            s.put("stage", new JsonValue.Str(stage.stage()));
            s.put("input", new JsonValue.Str(Decimal.format(stage.input(), precision)));
            s.put("output", new JsonValue.Str(Decimal.format(stage.output(), precision)));
            s.put("pools", new JsonValue.Num(Integer.toString(stage.pools())));
            s.put("output_rows", new JsonValue.Num(Long.toString(stage.outputRows())));
            stageValues.add(new JsonValue.Obj(s));
        }
        fields.put("stages", new JsonValue.Arr(stageValues));
        fields.put("input_amount", new JsonValue.Str(Decimal.format(inputAmount, precision)));
        fields.put("output_amount", new JsonValue.Str(Decimal.format(outputAmount, precision)));
        fields.put("pool_checks", new JsonValue.Num(Long.toString(poolChecks)));
        fields.put("output_rows", new JsonValue.Num(Long.toString(outputRows)));
        fields.put("terminal_rows", new JsonValue.Num(Long.toString(terminalRows)));
        fields.put("ledger_rows", new JsonValue.Num(Long.toString(ledgerRows)));
        fields.put("status", new JsonValue.Str(status));
        return io.allocation.kernel.json.Json.writeIndented(new JsonValue.Obj(fields), "  ") + "\n";
    }
}
