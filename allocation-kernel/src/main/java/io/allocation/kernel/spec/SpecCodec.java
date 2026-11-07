package io.allocation.kernel.spec;

import io.allocation.kernel.json.Json;
import io.allocation.kernel.json.JsonException;
import io.allocation.kernel.json.JsonFields;
import io.allocation.kernel.json.JsonValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes {@code allocation-ir/2.0} documents.
 *
 * <p>Parsing is deliberately unforgiving. A rule file is not configuration in the casual sense; it is
 * the thing an auditor will read to understand why a number is what it is. Typos that "look harmless"
 * — a misspelled key, a mode written in lower case, a precision of 7 — change money. They must therefore
 * be reported at the line that caused them, not at the end of a month.
 */
public final class SpecCodec {

    private SpecCodec() {
    }

    /** Parses a rule document from JSON text. */
    public static AllocationSpec parse(String json) {
        JsonValue root = Json.parse(json);
        JsonFields f = JsonFields.of(root, "");
        String schema = f.requireString("schema");
        String roundingPolicy = f.optString("rounding_policy");
        String name = f.optString("name", "");
        int precision = f.optInt("precision", 0);
        List<String> dimensions = required(f.optStringList("dimensions"), "dimensions");
        List<String> immutable = f.optStringList("immutable_dimensions");
        Map<String, Map<String, List<String>>> hierarchies = readHierarchies(f);
        JsonValue.Obj datasets = f.optObject("datasets");
        Map<String, String> amounts = Map.of();
        Map<String, String> drivers = Map.of();
        if (datasets != null) {
            JsonFields d = JsonFields.of(datasets, "datasets");
            Map<String, String> readAmounts = d.optStringMap("amounts");
            Map<String, String> readDrivers = d.optStringMap("drivers");
            d.done();
            amounts = readAmounts == null ? Map.of() : readAmounts;
            drivers = readDrivers == null ? Map.of() : readDrivers;
        }
        List<JsonValue.Obj> rawStages = f.optObjectList("stages");
        if (rawStages == null) {
            throw new JsonException("stages: required array is missing");
        }
        List<StageSpec> stages = new ArrayList<>(rawStages.size());
        for (int i = 0; i < rawStages.size(); i++) {
            stages.add(readStage(rawStages.get(i), "stages[" + i + "]"));
        }
        f.done();

        return new AllocationSpec(schema, roundingPolicy, name, precision, dimensions, immutable,
                hierarchies, amounts, drivers, stages);
    }

    private static List<String> required(List<String> value, String field) {
        if (value == null) {
            throw new JsonException(field + ": required array is missing");
        }
        return value;
    }

    private static Map<String, Map<String, List<String>>> readHierarchies(JsonFields f) {
        JsonValue.Obj raw = f.optObject("hierarchies");
        if (raw == null) {
            return Map.of();
        }
        Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
        String base = "hierarchies";
        for (Map.Entry<String, JsonValue> dimEntry : raw.fields().entrySet()) {
            if (!(dimEntry.getValue() instanceof JsonValue.Obj members)) {
                throw new JsonException(base + "." + dimEntry.getKey() + ": expected object");
            }
            Map<String, List<String>> levels = new LinkedHashMap<>();
            for (Map.Entry<String, JsonValue> memberEntry : members.fields().entrySet()) {
                if (!(memberEntry.getValue() instanceof JsonValue.Arr children)) {
                    throw new JsonException(base + "." + dimEntry.getKey() + "." + memberEntry.getKey()
                            + ": expected array of members");
                }
                List<String> list = new ArrayList<>(children.size());
                for (int i = 0; i < children.size(); i++) {
                    JsonValue child = children.at(i);
                    if (!(child instanceof JsonValue.Str s)) {
                        throw new JsonException(base + "." + dimEntry.getKey() + "." + memberEntry.getKey()
                                + "[" + i + "]: expected string");
                    }
                    list.add(s.value());
                }
                levels.put(memberEntry.getKey(), list);
            }
            out.put(dimEntry.getKey(), levels);
        }
        return out;
    }

    private static StageSpec readStage(JsonValue.Obj raw, String path) {
        JsonFields f = JsonFields.of(raw, path);
        StageOperation operation = readOperation(f.optString("operation"), f.child("operation"));
        String id = f.requireString("id");
        List<JsonValue.Obj> rawInputs = f.optObjectList("inputs");
        List<InputEdge> inputs = new ArrayList<>();
        if (rawInputs != null) {
            for (int i = 0; i < rawInputs.size(); i++) {
                JsonFields in = JsonFields.of(rawInputs.get(i), f.child("inputs[" + i + "]"));
                String from = in.requireString("from");
                String share = in.optString("share");
                in.done();
                inputs.add(new InputEdge(from, share));
            }
        }
        SourceSpec source = readSource(f.optObject("source"), f.child("source"));
        Map<String, TargetSpec> target = readTargets(f.optObjectMap("target"), f.child("target"));
        List<String> conservationKeys = f.optStringList("conservation_keys");
        List<JsonValue.Obj> rawAllocations = f.optObjectList("allocations");
        List<AllocationRule> allocations = new ArrayList<>();
        if (rawAllocations != null) {
            for (int i = 0; i < rawAllocations.size(); i++) {
                allocations.add(readAllocation(rawAllocations.get(i),
                        f.child("allocations[" + i + "]")));
            }
        }
        Boolean enabled = f.optBoolean("enabled");
        f.done();
        return new StageSpec(operation, id, inputs, source, target,
                conservationKeys == null ? List.of() : conservationKeys, allocations, enabled);
    }

    private static StageOperation readOperation(String raw, String path) {
        if (raw == null || raw.isEmpty()) {
            return StageOperation.ALLOCATE;
        }
        try {
            return StageOperation.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new JsonException(path + ": unknown operation " + raw
                    + " (expected ALLOCATE, MERGE or PASS)");
        }
    }

    private static SourceSpec readSource(JsonValue.Obj raw, String path) {
        if (raw == null) {
            return new SourceSpec(Map.of(), Map.of());
        }
        JsonFields f = JsonFields.of(raw, path);
        Map<String, String> rawGrouping = f.optStringMap("grouping");
        Map<String, Grouping> grouping = new LinkedHashMap<>();
        if (rawGrouping != null) {
            for (Map.Entry<String, String> entry : rawGrouping.entrySet()) {
                try {
                    grouping.put(entry.getKey(), Grouping.valueOf(entry.getValue()));
                } catch (IllegalArgumentException e) {
                    throw new JsonException(path + ".grouping." + entry.getKey() + ": unknown grouping "
                            + entry.getValue() + " (expected AS_IS or TOTAL)");
                }
            }
        }
        Map<String, FilterSpec> filters = readFilters(f.optObjectMap("filters"), path + ".filters");
        f.done();
        return new SourceSpec(grouping, filters);
    }

    private static Map<String, FilterSpec> readFilters(Map<String, JsonValue.Obj> raw, String path) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, FilterSpec> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue.Obj> entry : raw.entrySet()) {
            JsonFields f = JsonFields.of(entry.getValue(), path + "." + entry.getKey());
            List<String> include = f.optStringList("include");
            List<String> exclude = f.optStringList("exclude");
            f.done();
            out.put(entry.getKey(), new FilterSpec(include, exclude));
        }
        return out;
    }

    private static Map<String, TargetSpec> readTargets(Map<String, JsonValue.Obj> raw, String path) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, TargetSpec> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue.Obj> entry : raw.entrySet()) {
            String here = path + "." + entry.getKey();
            JsonFields f = JsonFields.of(entry.getValue(), here);
            String modeText = f.optString("mode");
            TargetMode mode;
            if (modeText == null) {
                throw new JsonException(here + ".mode: required");
            }
            try {
                mode = TargetMode.valueOf(modeText);
            } catch (IllegalArgumentException e) {
                throw new JsonException(here + ".mode: unknown mode " + modeText
                        + " (expected SOURCE, ELEMENT, INCLUDE or INCLUDE_EXCLUDE)");
            }
            List<String> include = f.optStringList("include");
            List<String> exclude = f.optStringList("exclude");
            String value = f.optString("value");
            f.done();
            out.put(entry.getKey(), new TargetSpec(mode, include, exclude, value));
        }
        return out;
    }

    private static AllocationRule readAllocation(JsonValue.Obj raw, String path) {
        JsonFields f = JsonFields.of(raw, path);
        String id = f.requireString("id");
        Boolean enabled = f.optBoolean("enabled");
        String share = f.optString("share");
        String dataset = f.optString("driver_dataset");
        String weightField = f.optString("weight_field");
        Map<String, FilterSpec> filters = readFilters(f.optObjectMap("driver_filters"),
                path + ".driver_filters");
        String policyText = f.optString("negative_driver_policy");
        NegativeDriverPolicy policy = null;
        if (policyText != null && !policyText.isEmpty()) {
            try {
                policy = NegativeDriverPolicy.valueOf(policyText);
            } catch (IllegalArgumentException e) {
                throw new JsonException(path + ".negative_driver_policy: unknown policy " + policyText
                        + " (expected REJECT or ALLOW_SIGNED)");
            }
        }
        String reason = f.optString("signed_driver_reason");
        f.done();
        return new AllocationRule(id, enabled, share, dataset, weightField, filters, policy, reason);
    }

    // ---------------------------------------------------------------------------------------------
    // Serialisation
    // ---------------------------------------------------------------------------------------------

    /** Serialises a spec back to canonical JSON. Field order matches the reference document layout. */
    public static String write(AllocationSpec spec) {
        JsonValue.Obj root = toJson(spec);
        return Json.writeIndented(root, "  ") + "\n";
    }

    /** SHA-256 of the given text encoded as UTF-8, lower-case hex. */
    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /** SHA-256 of raw bytes, lower-case hex. */
    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static JsonValue.Obj toJson(AllocationSpec spec) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("schema", new JsonValue.Str(spec.schema()));
        if (spec.roundingPolicy() != null && !spec.roundingPolicy().isEmpty()) {
            fields.put("rounding_policy", new JsonValue.Str(spec.roundingPolicy()));
        }
        fields.put("name", new JsonValue.Str(spec.name()));
        fields.put("precision", new JsonValue.Num(Integer.toString(spec.precision())));
        fields.put("dimensions", stringArray(spec.dimensions()));
        fields.put("immutable_dimensions", stringArray(spec.immutableDimensions()));
        fields.put("hierarchies", hierarchiesToJson(spec.hierarchies()));
        Map<String, JsonValue> datasets = new LinkedHashMap<>();
        datasets.put("amounts", stringMapToJson(spec.amountDatasets()));
        datasets.put("drivers", stringMapToJson(spec.driverDatasets()));
        fields.put("datasets", new JsonValue.Obj(datasets));
        List<JsonValue> stages = new ArrayList<>(spec.stages().size());
        for (StageSpec stage : spec.stages()) {
            stages.add(stageToJson(stage));
        }
        fields.put("stages", new JsonValue.Arr(stages));
        return new JsonValue.Obj(fields);
    }

    private static JsonValue stageToJson(StageSpec stage) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("operation", new JsonValue.Str(stage.operationOrDefault().name()));
        fields.put("id", new JsonValue.Str(stage.id()));
        List<JsonValue> inputs = new ArrayList<>();
        for (InputEdge edge : stage.inputs()) {
            Map<String, JsonValue> in = new LinkedHashMap<>();
            in.put("from", new JsonValue.Str(edge.from()));
            in.put("share", new JsonValue.Str(edge.effectiveShare()));
            inputs.add(new JsonValue.Obj(in));
        }
        fields.put("inputs", new JsonValue.Arr(inputs));

        Map<String, JsonValue> grouping = new LinkedHashMap<>();
        stage.source().grouping().forEach((k, v) -> grouping.put(k, new JsonValue.Str(v.name())));
        Map<String, JsonValue> filters = new LinkedHashMap<>();
        stage.source().filters().forEach((k, v) -> filters.put(k, filterToJson(v)));
        Map<String, JsonValue> source = new LinkedHashMap<>();
        source.put("grouping", new JsonValue.Obj(grouping));
        source.put("filters", new JsonValue.Obj(filters));
        fields.put("source", new JsonValue.Obj(source));

        Map<String, JsonValue> target = new LinkedHashMap<>();
        stage.target().forEach((k, v) -> target.put(k, targetToJson(v)));
        fields.put("target", new JsonValue.Obj(target));
        fields.put("conservation_keys", stringArray(stage.conservationKeys()));

        List<JsonValue> allocations = new ArrayList<>();
        for (AllocationRule rule : stage.allocations()) {
            Map<String, JsonValue> a = new LinkedHashMap<>();
            a.put("id", new JsonValue.Str(rule.id()));
            if (rule.enabled() != null) {
                a.put("enabled", new JsonValue.Bool(rule.enabled()));
            }
            a.put("share", new JsonValue.Str(rule.effectiveShare()));
            a.put("driver_dataset", new JsonValue.Str(rule.driverDataset() == null ? "" : rule.driverDataset()));
            a.put("weight_field", new JsonValue.Str(rule.weightField() == null ? "" : rule.weightField()));
            Map<String, JsonValue> driverFilters = new LinkedHashMap<>();
            rule.driverFilters().forEach((k, v) -> driverFilters.put(k, filterToJson(v)));
            a.put("driver_filters", new JsonValue.Obj(driverFilters));
            a.put("negative_driver_policy", new JsonValue.Str(rule.policy().name()));
            if (rule.signedDriverReason() != null) {
                a.put("signed_driver_reason", new JsonValue.Str(rule.signedDriverReason()));
            }
            allocations.add(new JsonValue.Obj(a));
        }
        fields.put("allocations", new JsonValue.Arr(allocations));
        if (stage.enabled() != null) {
            fields.put("enabled", new JsonValue.Bool(stage.enabled()));
        }
        return new JsonValue.Obj(fields);
    }

    private static JsonValue filterToJson(FilterSpec filter) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("include", stringArray(filter.include()));
        fields.put("exclude", stringArray(filter.exclude()));
        return new JsonValue.Obj(fields);
    }

    private static JsonValue targetToJson(TargetSpec target) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("mode", new JsonValue.Str(target.mode().name()));
        if (!target.include().isEmpty()) {
            fields.put("include", stringArray(target.include()));
        }
        if (!target.exclude().isEmpty()) {
            fields.put("exclude", stringArray(target.exclude()));
        }
        if (target.value() != null) {
            fields.put("value", new JsonValue.Str(target.value()));
        }
        return new JsonValue.Obj(fields);
    }

    private static JsonValue hierarchiesToJson(Map<String, Map<String, List<String>>> hierarchies) {
        Map<String, JsonValue> out = new LinkedHashMap<>();
        hierarchies.forEach((dim, members) -> {
            Map<String, JsonValue> inner = new LinkedHashMap<>();
            members.forEach((member, children) -> inner.put(member, stringArray(children)));
            out.put(dim, new JsonValue.Obj(inner));
        });
        return new JsonValue.Obj(out);
    }

    private static JsonValue stringMapToJson(Map<String, String> map) {
        Map<String, JsonValue> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(k, new JsonValue.Str(v)));
        return new JsonValue.Obj(out);
    }

    static JsonValue stringArray(List<String> values) {
        List<JsonValue> items = new ArrayList<>(values.size());
        for (String value : values) {
            items.add(new JsonValue.Str(value));
        }
        return new JsonValue.Arr(items);
    }
}
