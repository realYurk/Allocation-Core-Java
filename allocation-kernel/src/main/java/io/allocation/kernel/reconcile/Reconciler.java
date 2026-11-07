package io.allocation.kernel.reconcile;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.coord.Utf8Order;
import io.allocation.kernel.json.Json;
import io.allocation.kernel.json.JsonValue;
import io.allocation.kernel.money.Decimal;
import io.allocation.kernel.money.Overflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compares an allocation result against an independent reference.
 *
 * <p>This is the part of the system that is worth more than the arithmetic. A conservation check can
 * only prove that a run balanced <em>itself</em>; it cannot detect that the whole distribution is wrong
 * in a way that still sums correctly. Only a second, independently produced answer can.
 *
 * <p>Comparison is by full business coordinate, never by total. Two result sets that sum to the same
 * number but disagree about which partner received what are reported as differences — because that is
 * exactly the failure that matters.
 *
 * <p>Keys are the canonical JSON coordinate arrays the engine already uses ({@code ["2024-01","P1"]}),
 * for two reasons: they are unambiguous (a member containing the separator cannot forge a collision),
 * and they hash and sort identically to the reference implementation, so a comparison run here and a
 * comparison run on the Go engine classify the same rows the same way.
 */
public final class Reconciler {

    private Reconciler() {
    }

    /** What to do when a side contains the same coordinate twice. */
    public enum DuplicatePolicy {
        /** Sum the amounts and compare the total. The usual case for a joined export. */
        AGGREGATE,
        /** Reject either side as unusable. */
        REJECT,
        /** Reject only the produced results. */
        REJECT_ACTUAL,
        /** Reject only the reference. */
        REJECT_REFERENCE
    }

    /** Comparison settings. */
    public record Options(long tolerance, DuplicatePolicy duplicates) {

        public Options {
            if (tolerance < 0) {
                throw new AllocationException(ErrorCode.RULE_INVALID, "tolerance must be nonnegative");
            }
        }

        public static Options exact() {
            return new Options(0L, DuplicatePolicy.AGGREGATE);
        }

        public static Options within(long tolerance) {
            return new Options(tolerance, DuplicatePolicy.AGGREGATE);
        }

        /** True when a difference of this magnitude is within tolerance. */
        public boolean tolerates(long magnitude) {
            return magnitude <= tolerance;
        }
    }

    /** Why a coordinate differs. Names match the reference report. */
    public enum Kind {
        /** Present on both sides, amounts disagree beyond tolerance. */
        DIFFERENT,
        /** Expected by the reference, absent from the result. */
        MISSING,
        /** Present in the result, absent from the reference. */
        EXTRA
    }

    /** One difference. Amounts are scaled by the rule precision. */
    public record Difference(String key, String account, String coordinate,
                             long expected, long actual, long delta, Kind kind) {
    }

    /** Outcome of a comparison. */
    public record Report(String status, long matched, long differed, long missing, long extra,
                         long expectedTotal, long actualTotal, List<Difference> differences) {

        public Report {
            differences = List.copyOf(differences);
        }

        public boolean matchedExactly() {
            return "MATCH".equals(status);
        }

        /** True when some coordinate appears on only one side. */
        public boolean hasStructuralDifference() {
            return missing > 0 || extra > 0;
        }
    }

    /**
     * Compares two coordinate-to-amount maps.
     *
     * @param expected reference amounts, scaled
     * @param actual   produced amounts, scaled
     */
    public static Report compare(Map<String, Long> expected, Map<String, Long> actual, Options options) {
        long matched = 0;
        long differed = 0;
        long missing = 0;
        long extra = 0;
        long expectedTotal = 0;
        long actualTotal = 0;
        List<Difference> differences = new ArrayList<>();

        TreeMap<String, Long> ordered = new TreeMap<>(Utf8Order::compare);
        ordered.putAll(expected);
        ordered.putAll(actual);

        for (String key : ordered.keySet()) {
            Long want = expected.get(key);
            Long got = actual.get(key);
            if (want != null) {
                expectedTotal = Overflow.addExact(expectedTotal, want);
            }
            if (got != null) {
                actualTotal = Overflow.addExact(actualTotal, got);
            }
            if (want == null) {
                extra++;
                differences.add(new Difference(key, "ALL", key, 0L, got, got, Kind.EXTRA));
            } else if (got == null) {
                missing++;
                differences.add(new Difference(key, "ALL", key, want, 0L, -want, Kind.MISSING));
            } else {
                long delta = Overflow.subExact(got, want);
                if (options.tolerates(Overflow.unsignedMagnitude(delta))) {
                    matched++;
                } else {
                    differed++;
                    differences.add(new Difference(key, "ALL", key, want, got, delta, Kind.DIFFERENT));
                }
            }
        }

        String status = (differed == 0 && missing == 0 && extra == 0) ? "MATCH" : "DIFFERENCES";
        return new Report(status, matched, differed, missing, extra, expectedTotal, actualTotal, differences);
    }

    /**
     * Builds a coordinate-to-amount map from raw lines, applying the duplicate policy.
     *
     * <p>Aggregating duplicates is not a convenience: an incumbent system's export is routinely a join,
     * and the same business coordinate appearing twice with different amounts is normal. What is not
     * normal is ambiguity, so {@code REJECT} remains available for datasets that are supposed to be
     * unique per coordinate.
     */
    public static Map<String, Long> index(List<Map.Entry<String, Long>> lines, DuplicatePolicy policy) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> line : lines) {
            Long existing = out.get(line.getKey());
            if (existing == null) {
                out.put(line.getKey(), line.getValue());
                continue;
            }
            if (policy == DuplicatePolicy.REJECT || policy == DuplicatePolicy.REJECT_ACTUAL
                    || policy == DuplicatePolicy.REJECT_REFERENCE) {
                throw new AllocationException(ErrorCode.RULE_INVALID,
                        "duplicate coordinate rejected: " + render(line.getKey(), null));
            }
            out.put(line.getKey(), Overflow.addExact(existing, line.getValue()));
        }
        return out;
    }

    /**
     * Attaches account labels and human coordinates to the differences of a report.
     *
     * <p>The account breakdown is the shape a controller actually asks for: not "there are 412
     * differences", but "which accounts are wrong". It is derived from the coordinate member named
     * {@code account} when the rule has one, and reported as {@code ALL} otherwise.
     *
     * @param dimensions ordered dimension names, matching the key array positions
     */
    public static Report describe(Report report, List<String> dimensions) {
        int accountIndex = dimensions.indexOf("account");
        List<Difference> described = new ArrayList<>(report.differences().size());
        for (Difference d : report.differences()) {
            String[] members = members(d.key(), dimensions.size());
            String account = accountIndex >= 0 ? members[accountIndex] : "ALL";
            described.add(new Difference(d.key(), account, renderMembers(dimensions, members),
                    d.expected(), d.actual(), d.delta(), d.kind()));
        }
        return new Report(report.status(), report.matched(), report.differed(), report.missing(),
                report.extra(), report.expectedTotal(), report.actualTotal(), described);
    }

    /**
     * Per-account counts, ordered by account name in UTF-8 byte order.
     *
     * <p>Expects a report that has been through {@link #describe}; a raw report carries no account
     * labels and yields a single {@code ALL} row.
     */
    public static List<Account> accounts(Report report) {
        Map<String, long[]> counts = new TreeMap<>(Utf8Order::compare);
        for (Difference difference : report.differences()) {
            long[] bucket = counts.computeIfAbsent(difference.account(), k -> new long[3]);
            bucket[indexOf(difference.kind())]++;
        }
        List<Account> out = new ArrayList<>(counts.size());
        for (Map.Entry<String, long[]> entry : counts.entrySet()) {
            long[] bucket = entry.getValue();
            out.add(new Account(entry.getKey(), bucket[0], bucket[1], bucket[2]));
        }
        return out;
    }

    private static int indexOf(Kind kind) {
        return switch (kind) {
            case DIFFERENT -> 0;
            case MISSING -> 1;
            case EXTRA -> 2;
        };
    }

    /** One account's share of the differences. */
    public record Account(String account, long differed, long missing, long extra) {

        public long total() {
            return differed + missing + extra;
        }
    }

    /**
     * Keeps the largest differences, so a report leads with what matters.
     *
     * <p>Ordering is by absolute delta descending, then coordinate ascending, which makes the list
     * stable across runs — a report that reshuffles between two identical comparisons is not evidence.
     */
    public static List<Difference> top(Report report, int limit) {
        List<Difference> sorted = new ArrayList<>(report.differences());
        sorted.sort((a, b) -> {
            int byMagnitude = Long.compareUnsigned(Overflow.unsignedMagnitude(b.delta()),
                    Overflow.unsignedMagnitude(a.delta()));
            if (byMagnitude != 0) {
                return byMagnitude;
            }
            return Utf8Order.compare(a.coordinate(), b.coordinate());
        });
        return sorted.size() <= limit ? sorted : List.copyOf(sorted.subList(0, limit));
    }

    /** Renders a report as delimited text, using the reference column names. */
    public static String toCsv(Report report, int precision, List<String> dimensions) {
        Report described = describe(report, dimensions);
        StringBuilder sb = new StringBuilder(256);
        sb.append("kind,account,coordinate,reference_amount,actual_amount,difference\n");
        for (Difference difference : described.differences()) {
            sb.append(difference.kind()).append(',')
                    .append(csv(difference.account())).append(',')
                    .append(csv(difference.coordinate())).append(',')
                    .append(Decimal.format(difference.expected(), precision)).append(',')
                    .append(Decimal.format(difference.actual(), precision)).append(',')
                    .append(Decimal.format(difference.delta(), precision)).append('\n');
        }
        return sb.toString();
    }

    private static String csv(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /**
     * Renders a canonical key for humans: {@code plant=P1 | sku=S1}.
     *
     * @param dimensions dimension names, or {@code null} to fall back to the raw key
     */
    public static String render(String key, List<String> dimensions) {
        if (dimensions == null) {
            return key;
        }
        return renderMembers(dimensions, members(key, dimensions.size()));
    }

    private static String renderMembers(List<String> dimensions, String[] members) {
        StringBuilder sb = new StringBuilder(48);
        for (int i = 0; i < members.length; i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(i < dimensions.size() ? dimensions.get(i) : "?").append('=').append(members[i]);
        }
        return sb.toString();
    }

    /** Decodes a canonical key back into its members, checking arity. */
    static String[] members(String key, int expected) {
        JsonValue value;
        try {
            value = Json.parse(key);
        } catch (RuntimeException e) {
            throw new AllocationException(ErrorCode.RULE_INVALID, "malformed coordinate key " + key, e);
        }
        if (!(value instanceof JsonValue.Arr array) || array.items().size() != expected) {
            throw new AllocationException(ErrorCode.RULE_INVALID,
                    "coordinate key has " + (value instanceof JsonValue.Arr a ? a.items().size() : -1)
                            + " members, expected " + expected + ": " + key);
        }
        String[] members = new String[expected];
        for (int i = 0; i < expected; i++) {
            if (!(array.items().get(i) instanceof JsonValue.Str s)) {
                throw new AllocationException(ErrorCode.RULE_INVALID,
                        "coordinate member " + i + " is not a string in " + key);
            }
            members[i] = s.value();
        }
        return members;
    }
}
