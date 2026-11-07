package io.allocation.kernel.reconcile;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.coord.Coord;
import io.allocation.kernel.coord.Utf8Order;
import io.allocation.kernel.io.CsvReader;
import io.allocation.kernel.money.Decimal;
import io.allocation.kernel.spec.AllocationSpec;
import io.allocation.kernel.spec.SpecCodec;
import io.allocation.kernel.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a result or reference CSV into canonical-coordinate amounts.
 *
 * <p>Two entry points, and the difference between them is deliberate:
 *
 * <ul>
 *   <li>{@link #load(Path, int)} derives the dimension list from the header and sorts it. Two files
 *       exported by different systems can then be compared even if their column order differs,
 *       which is the normal situation when reconciling against an incumbent.
 *   <li>{@link #load(Path, AllocationSpec)} uses the rule's dimension order, so the keys line up with
 *       the coordinate keys the engine itself emits.
 * </ul>
 *
 * <p>Accepting an external export means tolerating its quirks: a leading byte-order mark, an
 * {@code amount} or {@code value} column, and the engine's own provenance columns
 * ({@code terminal_stage}, {@code row_id}) which are not business coordinates. What it does not
 * tolerate is ambiguity — a duplicate column, an empty coordinate or a short row is a data-quality
 * failure, and a comparison built on it would be worthless.
 */
public final class ReferenceCsv {

    /** Maximum business dimensions; matches the reference implementation's header limit. */
    public static final int MAX_DIMENSIONS = 64;

    /** Maximum bytes in one coordinate key, so a malformed file cannot exhaust the heap. */
    public static final int MAX_KEY_BYTES = 8192;

    /** Provenance columns written by the engine that are never business coordinates. */
    private static final List<String> PROVENANCE = List.of("terminal_stage", "row_id");

    private ReferenceCsv() {
    }

    /**
     * A parsed table.
     *
     * @param dimensions dimension names, in canonical key order
     * @param rows       coordinate key to scaled amount, in file order and including duplicates
     * @param sha256     digest of the raw file bytes, for the evidence chain
     */
    public record Table(List<String> dimensions, List<Map.Entry<String, Long>> rows, String sha256) {

        public Table {
            dimensions = List.copyOf(dimensions);
            rows = List.copyOf(rows);
        }

        public long rowCount() {
            return rows.size();
        }
    }

    /** Loads a file whose dimensions are discovered from its header and sorted canonically. */
    public static Table load(Path path, int precision) {
        return parse(path, precision, null);
    }

    /** Loads a file using the rule's dimension order and precision. */
    public static Table load(Path path, AllocationSpec spec) {
        return parse(path, spec.precision(), spec.dimensions());
    }

    private static Table parse(Path path, int precision, List<String> requiredDimensions) {
        if (precision < 0 || precision > 6) {
            throw new AllocationException(ErrorCode.RULE_INVALID, "precision must be 0..6");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new AllocationException(ErrorCode.INTERNAL, "cannot read " + path, e);
        }
        CsvReader reader = new CsvReader(bytes);
        List<String> header = reader.read();
        if (header == null) {
            throw new AllocationException(ErrorCode.RULE_INVALID, path + ": empty file");
        }

        Map<String, Integer> positions = new LinkedHashMap<>();
        List<String> discovered = new ArrayList<>(header.size());
        int amountPosition = -1;
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i);
            if (i == 0 && !name.isEmpty() && name.charAt(0) == '\uFEFF') {
                name = name.substring(1);
            }
            if (name.isEmpty()) {
                throw new AllocationException(ErrorCode.RULE_INVALID, path + ": empty header at column " + (i + 1));
            }
            if (positions.put(name, i) != null) {
                throw new AllocationException(ErrorCode.RULE_INVALID, path + ": duplicate column \"" + name + "\"");
            }
            if ("amount".equals(name) || "value".equals(name)) {
                if (amountPosition >= 0) {
                    throw new AllocationException(ErrorCode.RULE_INVALID,
                            path + ": more than one amount column");
                }
                amountPosition = i;
                continue;
            }
            if (PROVENANCE.contains(name)) {
                continue;
            }
            discovered.add(name);
        }
        if (amountPosition < 0) {
            throw new AllocationException(ErrorCode.RULE_INVALID, path + ": no amount or value column");
        }

        List<String> dimensions;
        if (requiredDimensions == null) {
            dimensions = discovered;
        } else {
            for (String dimension : requiredDimensions) {
                if (!positions.containsKey(dimension)) {
                    throw new AllocationException(ErrorCode.RULE_INVALID,
                            path + ": missing dimension " + dimension);
                }
            }
            dimensions = requiredDimensions;
        }
        if (dimensions.isEmpty() || dimensions.size() > MAX_DIMENSIONS) {
            throw new AllocationException(ErrorCode.RULE_INVALID,
                    path + ": " + dimensions.size() + " coordinate dimension(s), expected 1.." + MAX_DIMENSIONS);
        }
        if (requiredDimensions == null) {
            dimensions = new ArrayList<>(dimensions);
            dimensions.sort(Utf8Order::compare);
        }

        int[] dimensionPositions = new int[dimensions.size()];
        for (int i = 0; i < dimensions.size(); i++) {
            dimensionPositions[i] = positions.get(dimensions.get(i));
        }

        List<Map.Entry<String, Long>> rows = new ArrayList<>();
        List<String> record;
        int line = 1;
        while ((record = reader.read()) != null) {
            line++;
            if (record.size() != header.size()) {
                throw new AllocationException(ErrorCode.RULE_INVALID,
                        path + ":" + line + " has " + record.size() + " field(s), header has " + header.size());
            }
            String[] members = new String[dimensionPositions.length];
            int keyBytes = 0;
            for (int i = 0; i < dimensionPositions.length; i++) {
                String member = record.get(dimensionPositions[i]);
                if (Text.trimSpace(member).isEmpty()) {
                    throw new AllocationException(ErrorCode.RULE_INVALID,
                            path + ":" + line + " empty coordinate " + dimensions.get(i));
                }
                keyBytes += Text.utf8Length(member) + 4;
                if (keyBytes > MAX_KEY_BYTES) {
                    throw new AllocationException(ErrorCode.RULE_INVALID, path + ":" + line + " coordinate too large");
                }
                members[i] = member;
            }
            long amount;
            try {
                amount = Decimal.parseScaled(Text.trimSpace(record.get(amountPosition)), precision);
            } catch (AllocationException e) {
                throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                        path + ":" + line + " " + e.detail(), e);
            }
            rows.add(new AbstractMap.SimpleImmutableEntry<>(new Coord(members).key(), amount));
        }
        return new Table(dimensions, rows, SpecCodec.sha256Hex(bytes));
    }

    /** Checks that two tables describe the same business coordinates. */
    public static List<String> commonDimensions(Table left, Table right) {
        List<String> a = new ArrayList<>(left.dimensions());
        List<String> b = new ArrayList<>(right.dimensions());
        a.sort(Utf8Order::compare);
        b.sort(Utf8Order::compare);
        if (!a.equals(b)) {
            throw new AllocationException(ErrorCode.RULE_INVALID,
                    "coordinate dimensions differ: " + a + " vs " + b);
        }
        return List.copyOf(a);
    }

    /** Kept for callers that want to order their own table the same way. */
    public static Comparator<String> dimensionOrder() {
        return Utf8Order::compare;
    }
}
