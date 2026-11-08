package io.allocation.kernel.reconcile;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.coord.Coord;
import io.allocation.kernel.coord.Utf8Order;
import io.allocation.kernel.io.CsvWriter;
import io.allocation.kernel.io.StreamingCsvReader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;

/**
 * Coordinate-level reconciliation that does not hold either side in memory.
 *
 * <p>{@link Reconciler} is the honest implementation for a run: both sides fit in a map, the report is a
 * value, and the code is short enough to read in one sitting. It stops being honest at the scale this
 * class exists for. A ten-million-row export is several gigabytes of text and hundreds of megabytes of
 * keys; loading both sides at once does not fit and, if it did, the collector would spend the run proving
 * it.
 *
 * <p>So: two passes over disk, partitioned by key.
 *
 * <ol>
 *   <li>Each side is streamed once and every row is appended to one of N partition files chosen by the
 *       FNV-1a hash of its coordinate. Rows for the same coordinate always land in the same partition, so
 *       a partition is a self-contained comparison.</li>
 *   <li>Each partition is then loaded on its own, aggregated, compared and discarded. Peak memory follows
 *       the largest partition, which is roughly the input divided by N.</li>
 * </ol>
 *
 * <p>This is the same shape as the reference implementation's acceptance comparator, on purpose: the two
 * engines must agree about which rows differ, and the cheapest way to guarantee that is to make the same
 * decisions in the same order.
 *
 * <p>Totals are {@link BigInteger}. Summing ten million amounts into a {@code long} is fine until the day
 * it is not, and a reconciliation report that overflows while checking for discrepancies would be the
 * worst possible place to find out.
 *
 * <p>The difference file is published, not written in place: it appears atomically or not at all, so a
 * reader never sees half a report.
 */
public final class ShardedReconciler {

    /** Rows for the same coordinate must share a partition; thirty-two keeps each one small enough. */
    public static final int DEFAULT_SHARDS = 32;

    private static final byte SIDE_ACTUAL = 'A';
    private static final byte SIDE_REFERENCE = 'R';

    /** A longer key would not survive the partition record header, and would be a data fault anyway. */
    private static final int MAX_KEY_BYTES = 65_535;

    private static final String DIFFERENCES_FILE = "differences.csv";

    private ShardedReconciler() {
    }

    // ---------------------------------------------------------------- options

    /** Comparison settings. */
    public record Options(int shards, long tolerance, Reconciler.DuplicatePolicy duplicates, int top,
                          int precision, List<String> dimensions) {

        public Options {
            if (shards < 1 || shards > 4_096) {
                throw new AllocationException(ErrorCode.RULE_INVALID, "shards must be 1..4096, got " + shards);
            }
            if (tolerance < 0) {
                throw new AllocationException(ErrorCode.RULE_INVALID, "tolerance must be nonnegative");
            }
            if (top < 0) {
                throw new AllocationException(ErrorCode.RULE_INVALID, "top must be nonnegative");
            }
            if (precision < 0 || precision > 6) {
                throw new AllocationException(ErrorCode.RULE_INVALID, "precision must be 0..6, got " + precision);
            }
            dimensions = List.copyOf(dimensions);
            if (dimensions.isEmpty()) {
                throw new AllocationException(ErrorCode.RULE_INVALID, "at least one dimension is required");
            }
            if (dimensions.stream().distinct().count() != dimensions.size()) {
                throw new AllocationException(ErrorCode.RULE_INVALID, "dimensions must be distinct: " + dimensions);
            }
        }

        public static Options of(List<String> dimensions, int precision) {
            return new Options(DEFAULT_SHARDS, 0L, Reconciler.DuplicatePolicy.AGGREGATE, 20, precision,
                    dimensions);
        }

        public Options withShards(int value) {
            return new Options(value, tolerance, duplicates, top, precision, dimensions);
        }

        public Options withTolerance(long value) {
            return new Options(shards, value, duplicates, top, precision, dimensions);
        }

        public Options withDuplicates(Reconciler.DuplicatePolicy value) {
            return new Options(shards, tolerance, value, top, precision, dimensions);
        }

        public Options withTop(int value) {
            return new Options(shards, tolerance, duplicates, value, precision, dimensions);
        }
    }

    // ---------------------------------------------------------------- results

    /** One difference, with amounts already formatted at the rule's precision. */
    public record Difference(String kind, String account, String coordinate,
                             String referenceAmount, String actualAmount, String delta) {
    }

    /** Per-account outcome counts. */
    public record Account(String account, long matched, long differed, long missing, long extra) {

        public long differences() {
            return differed + missing + extra;
        }
    }

    /**
     * What the comparison found.
     *
     * @param top          the largest differences, ordered by magnitude then coordinate
     * @param differences  the published difference file, or {@code null} when nothing was published
     */
    public record Summary(String status, long matched, long differed, long missing, long extra,
                          long actualRows, long referenceRows, long actualDuplicateRows,
                          long referenceDuplicateRows, String actualTotal, String referenceTotal,
                          String actualSha256, String referenceSha256, int shards, int partitionCount,
                          List<Account> accounts, List<Difference> top, Path differences,
                          long elapsedMillis) {

        public Summary {
            accounts = List.copyOf(accounts);
            top = List.copyOf(top);
        }

        public boolean matchedExactly() {
            return "MATCH".equals(status);
        }

        public long totalDifferences() {
            return differed + missing + extra;
        }
    }

    // ---------------------------------------------------------------- entry points

    /**
     * Compares two sources and publishes a difference report.
     *
     * @param reportDirectory created by this call; must not already exist
     */
    public static Summary compare(AmountRows actual, AmountRows reference, Options options,
                                  Path reportDirectory) throws IOException {
        return compare(actual, reference, options, reportDirectory, true);
    }

    /**
     * Compares two sources without publishing anything.
     *
     * <p>Differences still go to a scratch file so that memory does not depend on how many there are; it
     * is deleted before returning, and {@link Summary#differences()} is {@code null}.
     */
    public static Summary compare(AmountRows actual, AmountRows reference, Options options)
            throws IOException {
        return compare(actual, reference, options, null, false);
    }

    /** Convenience overload for two files. */
    public static Summary compare(Path actualCsv, Path referenceCsv, Options options, Path reportDirectory)
            throws IOException {
        return compare(new CsvAmountRows(actualCsv, options.dimensions(), options.precision()),
                new CsvAmountRows(referenceCsv, options.dimensions(), options.precision()),
                options, reportDirectory);
    }

    private static Summary compare(AmountRows actual, AmountRows reference, Options options,
                                   Path reportDirectory, boolean publish) throws IOException {
        long started = System.nanoTime();
        Path target = reportDirectory == null ? null : reportDirectory.toAbsolutePath().normalize();
        if (target != null && Files.exists(target)) {
            throw new AllocationException(ErrorCode.RULE_INVALID, "report directory already exists: " + target);
        }
        Path parent = target == null
                ? Path.of(System.getProperty("java.io.tmpdir"))
                : target.getParent();
        if (parent == null) {
            throw new AllocationException(ErrorCode.RULE_INVALID, "report directory needs a parent: " + target);
        }
        Files.createDirectories(parent);
        Path scratch = Files.createTempDirectory(parent, ".reconcile-");
        boolean published = false;
        try {
            Side[] sides = partition(actual, reference, options, scratch);
            Side actualSide = sides[0];
            Side referenceSide = sides[1];

            Path differences = scratch.resolve(DIFFERENCES_FILE);
            Comparison comparison = comparePartitions(scratch, options, differences);

            Path publishedFile = null;
            if (publish) {
                deletePartitions(scratch, options.shards());
                publishDirectory(scratch, target);
                published = true;
                publishedFile = target.resolve(DIFFERENCES_FILE);
            }
            String status = comparison.differed == 0 && comparison.missing == 0 && comparison.extra == 0
                    ? "MATCH" : "DIFFERENCES";
            return new Summary(status, comparison.matched, comparison.differed, comparison.missing,
                    comparison.extra, actualSide.rows, referenceSide.rows, comparison.actualDuplicates,
                    comparison.referenceDuplicates, format(actualSide.total, options.precision()),
                    format(referenceSide.total, options.precision()), actualSide.sha256, referenceSide.sha256,
                    options.shards(), options.shards(), comparison.accounts, comparison.top, publishedFile,
                    (System.nanoTime() - started) / 1_000_000L);
        } finally {
            if (!published) {
                deleteQuietly(scratch);
            }
        }
    }

    // ---------------------------------------------------------------- pass 1

    /** Per-side totals gathered while partitioning. */
    private record Side(long rows, BigInteger total, String sha256) {
    }

    /**
     * Streams both sides into one set of partition files.
     *
     * <p>Both sides must go through the same open writers. Opening the files twice would truncate what
     * the first pass wrote, which is exactly the bug this shape exists to prevent.
     *
     * @return the actual side's totals, then the reference side's
     */
    private static Side[] partition(AmountRows actual, AmountRows reference, Options options, Path scratch)
            throws IOException {
        DataOutputStream[] out = new DataOutputStream[options.shards()];
        try {
            for (int i = 0; i < out.length; i++) {
                out[i] = new DataOutputStream(new BufferedOutputStream(
                        Files.newOutputStream(scratch.resolve(partName(i))), 64 * 1024));
            }
            Side actualSide = streamSide(actual, SIDE_ACTUAL, options, out);
            Side referenceSide = streamSide(reference, SIDE_REFERENCE, options, out);
            return new Side[]{actualSide, referenceSide};
        } catch (PartitionWriteFailure e) {
            throw new IOException("cannot write reconciliation partition: " + e.getCause().getMessage(),
                    e.getCause());
        } finally {
            for (DataOutputStream stream : out) {
                if (stream != null) {
                    stream.close();
                }
            }
        }
    }

    private static Side streamSide(AmountRows rows, byte side, Options options, DataOutputStream[] out)
            throws IOException {
        long[] count = {0L};
        BigInteger[] total = {BigInteger.ZERO};
        rows.stream((coordinate, amount) -> {
            byte[] key = coordinate.getBytes(StandardCharsets.UTF_8);
            if (key.length > MAX_KEY_BYTES) {
                throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                        "coordinate longer than " + MAX_KEY_BYTES + " bytes: " + coordinate);
            }
            int shard = (int) Long.remainderUnsigned(hash(key), options.shards());
            try {
                DataOutputStream stream = out[shard];
                stream.writeByte(side);
                stream.writeShort(key.length);
                stream.write(key);
                stream.writeLong(amount);
            } catch (IOException e) {
                throw new PartitionWriteFailure(e);
            }
            count[0]++;
            total[0] = total[0].add(BigInteger.valueOf(amount));
        });
        return new Side(count[0], total[0], rows.digest());
    }

    /** Unchecked carrier so the streaming lambda can report an I/O failure without declaring one. */
    private static final class PartitionWriteFailure extends RuntimeException {
        PartitionWriteFailure(IOException cause) {
            super(cause);
        }
    }

    private static String partName(int index) {
        return String.format("part-%04d.bin", index);
    }

    private static String diffName(int index) {
        return String.format("diff-%04d.csv", index);
    }

    /**
     * FNV-1a over the key bytes.
     *
     * <p>Chosen for the same reason the reference implementation chose it: a few instructions, no
     * allocation, distribution good enough for partitioning. It decides nothing about correctness — only
     * which partition a coordinate lands in.
     */
    private static long hash(byte[] key) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : key) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    // ---------------------------------------------------------------- pass 2

    private record Comparison(long matched, long differed, long missing, long extra,
                              long actualDuplicates, long referenceDuplicates,
                              List<Account> accounts, List<Difference> top) {
    }

    private static Comparison comparePartitions(Path scratch, Options options, Path differencesFile)
            throws IOException {
        int accountIndex = options.dimensions().indexOf("account");
        BigInteger tolerance = BigInteger.valueOf(options.tolerance());
        Totals totals = new Totals();
        Top top = new Top(options.top());

        // Each partition is internally ordered by coordinate, but which partition a coordinate lands in
        // depends on the shard count. Writing straight to the report would therefore make the report's
        // order depend on a performance knob, so the partitions are merged on coordinate before publishing.
        List<Path> partitionDiffs = new ArrayList<>(options.shards());
        for (int part = 0; part < options.shards(); part++) {
            Path diff = scratch.resolve(diffName(part));
            partitionDiffs.add(diff);
            try (CsvWriter writer = new CsvWriter(Files.newOutputStream(diff), false)) {
                comparePartition(scratch.resolve(partName(part)), options, accountIndex, tolerance, totals,
                        top, writer);
            }
        }
        mergeInCoordinateOrder(partitionDiffs, differencesFile);

        return new Comparison(totals.matched, totals.differed, totals.missing, totals.extra,
                totals.actualDuplicates, totals.referenceDuplicates, totals.accounts(), top.ordered());
    }

    private static void comparePartition(Path partFile, Options options, int accountIndex, BigInteger tolerance,
                                         Totals totals, Top top, CsvWriter writer) throws IOException {
        TreeMap<String, Accumulator> rows = new TreeMap<>(Utf8Order::compare);
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(partFile), 256 * 1024))) {
            while (true) {
                int side;
                try {
                    side = in.readUnsignedByte();
                } catch (EOFException end) {
                    break;
                }
                int length = in.readUnsignedShort();
                byte[] key = new byte[length];
                in.readFully(key);
                long amount = in.readLong();
                String coordinate = new String(key, StandardCharsets.UTF_8);
                rows.computeIfAbsent(coordinate, k -> new Accumulator()).add(side, amount, coordinate);
            }
        }

        for (Map.Entry<String, Accumulator> entry : rows.entrySet()) {
            String coordinate = entry.getKey();
            Accumulator accumulator = entry.getValue();
            boolean rejectActual = options.duplicates() == Reconciler.DuplicatePolicy.REJECT
                    || options.duplicates() == Reconciler.DuplicatePolicy.REJECT_ACTUAL;
            boolean rejectReference = options.duplicates() == Reconciler.DuplicatePolicy.REJECT
                    || options.duplicates() == Reconciler.DuplicatePolicy.REJECT_REFERENCE;
            if ((rejectActual && accumulator.actualRows > 1)
                    || (rejectReference && accumulator.referenceRows > 1)) {
                throw new AllocationException(ErrorCode.RULE_INVALID,
                        "duplicate coordinate rejected: actual=" + accumulator.actualRows
                                + " reference=" + accumulator.referenceRows + " key=" + coordinate);
            }
            if (accumulator.actualRows > 1) {
                totals.actualDuplicates += accumulator.actualRows - 1;
            }
            if (accumulator.referenceRows > 1) {
                totals.referenceDuplicates += accumulator.referenceRows - 1;
            }

            String account = accountOf(coordinate, accountIndex);
            BigInteger actual = BigInteger.valueOf(accumulator.actual);
            BigInteger reference = BigInteger.valueOf(accumulator.reference);
            BigInteger delta = actual.subtract(reference);

            String kind;
            if (accumulator.referenceRows == 0) {
                kind = "EXTRA";
                totals.extra++;
                totals.extra(account);
            } else if (accumulator.actualRows == 0) {
                kind = "MISSING";
                totals.missing++;
                totals.missing(account);
            } else if (delta.abs().compareTo(tolerance) > 0) {
                kind = "DIFFERENT";
                totals.differed++;
                totals.differed(account);
            } else {
                totals.matched++;
                totals.matched(account);
                continue;
            }

            Difference difference = new Difference(kind, account, render(coordinate, options.dimensions()),
                    format(reference, options.precision()), format(actual, options.precision()),
                    format(delta, options.precision()));
            writer.write(kind, difference.account(), difference.coordinate(), difference.referenceAmount(),
                    difference.actualAmount(), difference.delta());
            top.offer(difference);
        }
    }

    /**
     * Merges per-partition difference files into one, ordered by coordinate.
     *
     * <p>A k-way merge, not a load: the point of this class is that the difference list can be larger than
     * memory too. Each input is already sorted, so a heap of cursors produces the global order.
     */
    private static void mergeInCoordinateOrder(List<Path> parts, Path out) throws IOException {
        record Cursor(StreamingCsvReader reader, String[] fields) {
        }
        PriorityQueue<Cursor> heap = new PriorityQueue<>(
                (a, b) -> Utf8Order.compare(a.fields()[2], b.fields()[2]));
        List<StreamingCsvReader> readers = new ArrayList<>(parts.size());
        try (CsvWriter writer = new CsvWriter(Files.newOutputStream(out), false)) {
            writer.withHeader("kind", "account", "coordinate", "reference_amount", "actual_amount",
                    "difference");
            for (Path part : parts) {
                StreamingCsvReader reader = new StreamingCsvReader(Files.newInputStream(part), 64 * 1024);
                readers.add(reader);
                List<String> record = reader.read();
                if (record != null) {
                    heap.add(new Cursor(reader, record.toArray(new String[0])));
                }
            }
            while (!heap.isEmpty()) {
                Cursor cursor = heap.poll();
                writer.write(cursor.fields());
                List<String> next = cursor.reader().read();
                if (next == null) {
                    cursor.reader().close();
                } else {
                    heap.add(new Cursor(cursor.reader(), next.toArray(new String[0])));
                }
            }
        } finally {
            for (StreamingCsvReader reader : readers) {
                reader.close();
            }
        }
    }

    /** Running totals plus the per-account breakdown a controller actually asks for. */
    private static final class Totals {
        long matched;
        long differed;
        long missing;
        long extra;
        long actualDuplicates;
        long referenceDuplicates;
        private final TreeMap<String, long[]> byAccount = new TreeMap<>(Utf8Order::compare);

        /** Slot order: matched, differed, missing, extra. */
        private long[] slot(String account) {
            return byAccount.computeIfAbsent(account, k -> new long[4]);
        }

        void matched(String account) {
            slot(account)[0]++;
        }

        void differed(String account) {
            slot(account)[1]++;
        }

        void missing(String account) {
            slot(account)[2]++;
        }

        void extra(String account) {
            slot(account)[3]++;
        }

        List<Account> accounts() {
            List<Account> out = new ArrayList<>(byAccount.size());
            for (Map.Entry<String, long[]> entry : byAccount.entrySet()) {
                long[] counts = entry.getValue();
                out.add(new Account(entry.getKey(), counts[0], counts[1], counts[2], counts[3]));
            }
            return out;
        }
    }

    /**
     * The largest differences, kept in a bounded heap.
     *
     * <p>Ten million differences will not fit in a report anyone reads, and keeping them all in memory to
     * then sort and discard is the memory bug this class exists to avoid. The heap keeps {@code capacity}
     * candidates; the worst of them is evicted when a better one arrives.
     */
    private static final class Top {
        private final int capacity;
        private final PriorityQueue<Difference> heap;

        Top(int capacity) {
            this.capacity = capacity;
            // Smallest magnitude first, so the head is the entry to evict.
            this.heap = new PriorityQueue<>(Comparator
                    .comparing((Difference d) -> magnitude(d.delta()))
                    .thenComparing(Difference::coordinate, Comparator.reverseOrder()));
        }

        void offer(Difference difference) {
            if (capacity == 0) {
                return;
            }
            if (heap.size() < capacity) {
                heap.add(difference);
                return;
            }
            Difference weakest = heap.peek();
            if (weakest == null) {
                return;
            }
            BigInteger candidate = magnitude(difference.delta());
            BigInteger current = magnitude(weakest.delta());
            int order = candidate.compareTo(current);
            if (order > 0 || (order == 0 && difference.coordinate().compareTo(weakest.coordinate()) < 0)) {
                heap.poll();
                heap.add(difference);
            }
        }

        List<Difference> ordered() {
            List<Difference> out = new ArrayList<>(heap);
            out.sort(Comparator.comparing((Difference d) -> magnitude(d.delta())).reversed()
                    .thenComparing(Difference::coordinate));
            return out;
        }
    }

    /** Amounts for one coordinate, summed per side. */
    private static final class Accumulator {
        long actual;
        long reference;
        int actualRows;
        int referenceRows;

        void add(int side, long amount, String coordinate) {
            if (side == SIDE_ACTUAL) {
                actual = addExact(actual, amount, coordinate);
                actualRows++;
            } else if (side == SIDE_REFERENCE) {
                reference = addExact(reference, amount, coordinate);
                referenceRows++;
            } else {
                throw new AllocationException(ErrorCode.INTERNAL, "corrupt partition side " + side);
            }
        }

        private static long addExact(long left, long right, String coordinate) {
            try {
                return Math.addExact(left, right);
            } catch (ArithmeticException e) {
                // Summing the duplicate rows for one coordinate exceeded a signed 64-bit integer. Each row
                // is valid; the aggregate is not representable, and saying so beats wrapping around and
                // reporting a difference that does not exist.
                throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                        "duplicate rows for " + coordinate + " overflow a 64-bit amount", e);
            }
        }
    }

    // ---------------------------------------------------------------- reporting

    private static String accountOf(String coordinate, int accountIndex) {
        if (accountIndex < 0) {
            return "ALL";
        }
        return parse(coordinate).memberOrEmpty(accountIndex);
    }

    private static Coord parse(String coordinate) {
        try {
            return Coord.parse(coordinate);
        } catch (RuntimeException e) {
            throw new AllocationException(ErrorCode.INTERNAL, "malformed coordinate key: " + coordinate, e);
        }
    }

    /** Renders a key as {@code plant=P1 | account=A2}, the shape used in tickets and difference files. */
    private static String render(String coordinate, List<String> dimensions) {
        Coord coord = parse(coordinate);
        if (coord.size() != dimensions.size()) {
            throw new AllocationException(ErrorCode.INTERNAL, "coordinate " + coordinate + " has "
                    + coord.size() + " members but the comparison declares " + dimensions.size());
        }
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < dimensions.size(); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(dimensions.get(i)).append('=').append(coord.memberOrEmpty(i));
        }
        return sb.toString();
    }

    /** Absolute value of a formatted amount, for ordering. */
    private static BigInteger magnitude(String formatted) {
        String digits = formatted.startsWith("-") ? formatted.substring(1) : formatted;
        return new BigInteger(digits.contains(".") ? digits.replace(".", "") : digits);
    }

    /**
     * Formats a total or difference at the rule's precision.
     *
     * <p>Hand-written rather than delegated to {@code Decimal.format} because the value is a
     * {@link BigInteger}: the whole point of the type is that it is not a {@code long}.
     */
    static String format(BigInteger value, int precision) {
        if (precision == 0) {
            return value.toString();
        }
        BigInteger[] parts = value.abs().divideAndRemainder(BigInteger.TEN.pow(precision));
        String fraction = parts[1].toString();
        StringBuilder sb = new StringBuilder(parts[0].toString()).append('.');
        for (int i = fraction.length(); i < precision; i++) {
            sb.append('0');
        }
        sb.append(fraction);
        return value.signum() < 0 ? "-" + sb : sb.toString();
    }

    // ---------------------------------------------------------------- publishing

    private static void deletePartitions(Path scratch, int shards) throws IOException {
        for (int i = 0; i < shards; i++) {
            Files.deleteIfExists(scratch.resolve(partName(i)));
            Files.deleteIfExists(scratch.resolve(diffName(i)));
        }
    }

    private static void publishDirectory(Path scratch, Path target) throws IOException {
        try {
            Files.move(scratch, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(scratch, target);
        } catch (java.nio.file.FileSystemException e) {
            // Windows refuses a directory rename while another handle is open — usually an indexer or a
            // virus scanner that just saw the file appear. One short retry, then fail loudly.
            sleepQuietly();
            Files.move(scratch, target);
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(50L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteQuietly(Path directory) {
        try {
            if (!Files.exists(directory)) {
                return;
            }
            try (var entries = Files.list(directory)) {
                for (Path entry : entries.toList()) {
                    Files.deleteIfExists(entry);
                }
            }
            Files.deleteIfExists(directory);
        } catch (IOException e) {
            // A temporary directory that survives is housekeeping, not a result; let the OS reclaim it.
            directory.toFile().deleteOnExit();
        }
    }

    /** Renders the largest differences as delimited text, for a ticket or a dashboard. */
    public static String toCsv(Summary summary) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("kind,account,coordinate,reference_amount,actual_amount,difference\n");
        for (Difference difference : summary.top()) {
            sb.append(difference.kind()).append(',').append(difference.account()).append(',')
                    .append(difference.coordinate()).append(',').append(difference.referenceAmount())
                    .append(',').append(difference.actualAmount()).append(',').append(difference.delta())
                    .append('\n');
        }
        return sb.toString();
    }
}
