package io.allocation.kernel.reconcile;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.coord.Coord;
import io.allocation.kernel.io.StreamingCsvReader;
import io.allocation.kernel.money.Decimal;
import io.allocation.kernel.text.Text;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Streams a results or reference file as coordinates and amounts.
 *
 * <p>Reads only the columns it needs: the declared dimensions, in the declared order, plus whichever of
 * {@code amount} or {@code value} the file offers. Everything else — {@code terminal_stage}, {@code row_id},
 * or any other export artefact — is ignored rather than rejected, because the file this reads is
 * typically produced by a system this engine does not control.
 *
 * <p>The column order in the file does not matter; the dimension order in the rule does, because it
 * decides the canonical key. Two files that list the same dimensions in different orders therefore
 * produce the same keys, which is the point of comparing by coordinate rather than by row position.
 */
public final class CsvAmountRows implements AmountRows {

    private final Path path;
    private final List<String> dimensions;
    private final int precision;
    private String digest;

    public CsvAmountRows(Path path, List<String> dimensions, int precision) {
        if (dimensions.isEmpty()) {
            throw new AllocationException(ErrorCode.RULE_INVALID, "at least one dimension is required");
        }
        this.path = path;
        this.dimensions = List.copyOf(dimensions);
        this.precision = precision;
    }

    @Override
    public String label() {
        return path.toString();
    }

    @Override
    public String digest() {
        return digest;
    }

    @Override
    public void stream(RowConsumer consumer) throws IOException {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AllocationException(ErrorCode.INTERNAL, "SHA-256 is unavailable", e);
        }
        try (InputStream raw = new DigestInputStream(
                new BufferedInputStream(Files.newInputStream(path), 256 * 1024), sha256);
             StreamingCsvReader reader = new StreamingCsvReader(raw)) {
            List<String> header = reader.read();
            if (header == null) {
                throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE, path + ": empty file");
            }
            Columns columns = Columns.of(path, header, dimensions);

            List<String> record;
            while ((record = reader.read()) != null) {
                int line = reader.recordNumber();
                if (record.size() != header.size()) {
                    throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                            path + ":" + line + " field count mismatch: expected " + header.size()
                                    + ", got " + record.size());
                }
                String[] members = new String[dimensions.size()];
                for (int i = 0; i < members.length; i++) {
                    String value = record.get(columns.dimensionPositions[i]);
                    if (Text.trimSpace(value).isEmpty()) {
                        // An empty member is not a coordinate: it would silently merge unrelated rows.
                        throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                                path + ":" + line + " empty coordinate " + dimensions.get(i));
                    }
                    members[i] = value;
                }
                long amount;
                try {
                    amount = Decimal.parseScaled(Text.trimSpace(record.get(columns.amountPosition)), precision);
                } catch (AllocationException e) {
                    throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                            path + ":" + line + " invalid amount: " + e.detail());
                }
                consumer.accept(new Coord(members).key(), amount);
            }
            // The digest covers exactly the bytes that were parsed, because a wrapper sits in the stream
            // the reader consumes.
            digest = HexFormat.of().formatHex(sha256.digest());
        }
    }

    /** Resolved column positions, computed once per file. */
    private record Columns(int[] dimensionPositions, int amountPosition) {

        static Columns of(Path path, List<String> header, List<String> dimensions) {
            Map<String, Integer> positions = new HashMap<>();
            int amount = -1;
            int amountColumns = 0;
            for (int i = 0; i < header.size(); i++) {
                String name = header.get(i);
                if (i == 0) {
                    // Excel writes a byte-order mark that survives into the first header cell.
                    name = name.startsWith("\ufeff") ? name.substring(1) : name;
                }
                if (name.isEmpty()) {
                    throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                            path + " has an empty column name at position " + (i + 1));
                }
                if (positions.putIfAbsent(name, i) != null) {
                    throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                            path + " has a duplicate column " + name);
                }
                if (name.equals("amount") || name.equals("value")) {
                    amount = i;
                    amountColumns++;
                }
            }
            if (amountColumns != 1) {
                throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                        path + " must have exactly one amount or value column, found " + amountColumns);
            }
            int[] dimensionPositions = new int[dimensions.size()];
            for (int i = 0; i < dimensionPositions.length; i++) {
                Integer position = positions.get(dimensions.get(i));
                if (position == null) {
                    throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                            path + " is missing coordinate dimension " + dimensions.get(i));
                }
                dimensionPositions[i] = position;
            }
            return new Columns(dimensionPositions, amount);
        }
    }

    /** Convenience for a caller that just wants the rows as a list. Only for small files. */
    public List<Map.Entry<String, Long>> load() throws IOException {
        List<Map.Entry<String, Long>> rows = new ArrayList<>();
        stream((coordinate, amount) -> rows.add(Map.entry(coordinate, amount)));
        return rows;
    }
}
