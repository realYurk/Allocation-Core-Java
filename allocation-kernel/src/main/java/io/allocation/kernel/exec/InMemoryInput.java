package io.allocation.kernel.exec;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.coord.Coord;
import io.allocation.kernel.money.Decimal;
import io.allocation.kernel.spec.AllocationSpec;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Datasets supplied from memory.
 *
 * <p>This is the path a web request takes: rows have already been decoded by the caller, so there is no
 * file, no header validation and no ordinal-based identifier. Everything downstream is identical to the
 * file path, which is the point — a preview and a batch run cannot diverge because they share the
 * executor.
 */
public final class InMemoryInput implements AllocationInput {

    private final Map<String, List<Row>> amounts;
    private final Map<String, List<DriverRow>> drivers;

    private InMemoryInput(Map<String, List<Row>> amounts, Map<String, List<DriverRow>> drivers) {
        this.amounts = amounts;
        this.drivers = drivers;
    }

    /** Creates a builder bound to a spec, so amounts are parsed with the spec's precision. */
    public static Builder builder(AllocationSpec spec) {
        return new Builder(spec);
    }

    @Override
    public List<Row> readAmounts(String dataset) {
        List<Row> rows = amounts.get(dataset);
        if (rows == null) {
            throw new AllocationException(ErrorCode.RULE_INVALID, "unknown amount dataset " + dataset);
        }
        return rows;
    }

    @Override
    public List<DriverRow> readDrivers(String dataset, String weightField) {
        List<DriverRow> rows = drivers.get(key(dataset, weightField));
        if (rows == null) {
            throw new AllocationException(ErrorCode.RULE_INVALID,
                    "unknown driver dataset " + dataset + " for weight " + weightField);
        }
        return rows;
    }

    @Override
    public String fingerprint(String dataset, String weightField) {
        return null;
    }

    private static String key(String dataset, String weightField) {
        return dataset + '\u0000' + weightField;
    }

    /** Fluent construction helper. */
    public static final class Builder {

        private final AllocationSpec spec;
        private final Map<String, List<Row>> amounts = new LinkedHashMap<>();
        private final Map<String, List<DriverRow>> drivers = new HashMap<>();
        private int autoId;

        private Builder(AllocationSpec spec) {
            this.spec = spec;
        }

        /** Registers an amount dataset. Rows without an identifier receive a stable ordinal one. */
        public Builder amounts(String dataset, List<Map<String, String>> rows) {
            List<Row> built = new java.util.ArrayList<>(rows.size());
            for (Map<String, String> row : rows) {
                built.add(row(row, null));
            }
            amounts.put(dataset, built);
            return this;
        }

        /** Registers an amount dataset from already-built rows. */
        public Builder amounts(String dataset, java.util.function.Supplier<List<Row>> rows) {
            amounts.put(dataset, rows.get());
            return this;
        }

        /** Registers a driver dataset. */
        public Builder drivers(String dataset, String weightField, List<Map<String, String>> rows) {
            List<DriverRow> built = new java.util.ArrayList<>(rows.size());
            for (Map<String, String> row : rows) {
                built.add(driverRow(row, weightField));
            }
            drivers.put(key(dataset, weightField), built);
            return this;
        }

        /** Builds one amount row from a coordinate map plus an amount literal. */
        public Row row(Map<String, String> coord, String amountText) {
            String[] members = new String[spec.dimensions().size()];
            for (int i = 0; i < members.length; i++) {
                members[i] = coord.get(spec.dimensions().get(i));
            }
            String text = amountText != null ? amountText : coord.get("amount");
            if (text == null) {
                throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE, "row is missing an amount");
            }
            long amount = Decimal.parseScaled(text, spec.precision());
            autoId++;
            String id = coord.get("row_id");
            return new Row(new Coord(members), amount, new String[]{id == null ? Integer.toString(autoId) : id});
        }

        /** Builds one driver row from a coordinate map plus a weight literal. */
        public DriverRow driverRow(Map<String, String> coord, String weightField) {
            String[] members = new String[spec.dimensions().size()];
            for (int i = 0; i < members.length; i++) {
                members[i] = coord.get(spec.dimensions().get(i));
            }
            String text = coord.get(weightField);
            if (text == null) {
                throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                        "driver row is missing weight field " + weightField);
            }
            return new DriverRow(new Coord(members), Decimal.parseScaled(text, 6));
        }

        public InMemoryInput build() {
            return new InMemoryInput(Map.copyOf(amounts), Map.copyOf(drivers));
        }
    }
}
