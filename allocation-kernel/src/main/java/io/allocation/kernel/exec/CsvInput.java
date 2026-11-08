package io.allocation.kernel.exec;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.coord.Coord;
import io.allocation.kernel.io.StreamingCsvReader;
import io.allocation.kernel.money.Decimal;
import io.allocation.kernel.spec.AllocationSpec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reads amount and driver datasets from delimited files under a base directory.
 *
 * <p>Header handling is stricter than most CSV consumers, deliberately. A duplicated column name or an
 * empty one is rejected rather than resolved by "last one wins", because the wrong choice silently
 * changes which column is treated as the amount. A leading byte-order mark on the first header is
 * stripped, which is the one concession made to real-world Excel exports.
 */
public final class CsvInput implements AllocationInput {

    private final Path baseDir;
    private final AllocationSpec spec;
    private final Map<String, Path> amountPaths = new LinkedHashMap<>();
    private final Map<String, Path> driverPaths = new LinkedHashMap<>();
    private final Map<String, String> fingerprints = new HashMap<>();

    private CsvInput(Path baseDir, AllocationSpec spec) {
        this.baseDir = baseDir;
        this.spec = spec;
        spec.amountDatasets().forEach((name, relative) -> amountPaths.put(name, resolve(relative)));
        spec.driverDatasets().forEach((name, relative) -> driverPaths.put(name, resolve(relative)));
    }

    /** Creates an input over {@code baseDir}, resolving dataset paths relative to it. */
    public static CsvInput of(Path baseDir, AllocationSpec spec) {
        return new CsvInput(baseDir, spec);
    }

    /** Resolves a dataset path relative to the base directory. */
    public Path resolve(String relative) {
        Path path = Path.of(relative);
        return path.isAbsolute() ? path.normalize() : baseDir.resolve(path).normalize();
    }

    @Override
    public List<Row> readAmounts(String dataset) {
        List<Row> rows = new ArrayList<>(1024);
        forEachAmount(dataset, rows::add);
        return rows;
    }

    @Override
    public void forEachAmount(String dataset, Consumer<Row> consumer) {
        Path path = amountPaths.get(dataset);
        if (path == null) {
            throw new AllocationException(ErrorCode.RULE_INVALID, "unknown amount dataset " + dataset);
        }
        List<String> dimensions = spec.dimensions();
        int precision = spec.precision();
        // The streaming reader exists so that "read the amounts" does not mean "hold the file": it
        // splits records from a stream and delegates each one to the same parser the reference uses,
        // so the grammar is identical and the memory is one record.
        try (StreamingCsvReader reader = new StreamingCsvReader(open(path))) {
            List<String> header = reader.read();
            if (header == null) {
                throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE, path + ": empty file");
            }
            Map<String, Integer> positions = headerPositions(header, path);
            int[] dimensionPositions = new int[dimensions.size()];
            for (int i = 0; i < dimensions.size(); i++) {
                Integer position = positions.get(dimensions.get(i));
                if (position == null) {
                    throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                            path + " missing dimension " + dimensions.get(i));
                }
                dimensionPositions[i] = position;
            }
            Integer amountPosition = positions.get("amount");
            if (amountPosition == null) {
                throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE, path + " missing amount");
            }
            Integer idPosition = positions.get("row_id");
            readAmounts(path, reader, dimensions, precision, dimensionPositions, amountPosition, idPosition,
                    consumer);
        } catch (IOException e) {
            throw new AllocationException(ErrorCode.INTERNAL, "cannot read " + path, e);
        }
    }

    private void readAmounts(Path path, StreamingCsvReader reader, List<String> dimensions, int precision,
                             int[] dimensionPositions, int amountPosition, Integer idPosition,
                             Consumer<Row> consumer) throws IOException {
        MemberDictionary dictionary = new MemberDictionary(dimensionPositions.length);
        List<String> record;
        int line = 1;
        while ((record = reader.read()) != null) {
            line++;
            String[] members = new String[dimensionPositions.length];
            for (int i = 0; i < dimensionPositions.length; i++) {
                int position = dimensionPositions[i];
                if (position >= record.size()) {
                    throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE, path + ":" + line + " short row");
                }
                String value = record.get(position);
                if (value.isEmpty() || value.equals(AllocationSpec.POOL_MEMBER)) {
                    throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                            path + ":" + line + " invalid member " + dimensions.get(i) + ":" + value);
                }
                members[i] = dictionary.intern(i, value);
            }
            if (amountPosition >= record.size()) {
                throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE, path + ":" + line + " missing amount");
            }
            long amount;
            try {
                amount = Decimal.parseScaled(record.get(amountPosition), precision);
            } catch (AllocationException e) {
                throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                        path + ":" + line + " " + e.detail());
            }
            String id = Integer.toString(line - 1);
            if (idPosition != null && idPosition < record.size() && !record.get(idPosition).isEmpty()) {
                id = record.get(idPosition);
            }
            consumer.accept(new Row(new Coord(members), amount, new String[]{id}));
        }
    }

    @Override
    public List<DriverRow> readDrivers(String dataset, String weightField) {
        Path path = driverPaths.get(dataset);
        if (path == null) {
            throw new AllocationException(ErrorCode.RULE_INVALID, "unknown driver dataset " + dataset);
        }
        List<String> dimensions = spec.dimensions();
        try (StreamingCsvReader reader = new StreamingCsvReader(open(path))) {
            List<String> header = reader.read();
            if (header == null) {
                throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE, path + ": empty file");
            }
            Map<String, Integer> positions = headerPositions(header, path);
            Integer weightPosition = positions.get(weightField);
            if (weightPosition == null) {
                throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                        path + " missing weight column " + weightField);
            }
            int[] dimensionPositions = new int[dimensions.size()];
            int found = 0;
            for (int i = 0; i < dimensions.size(); i++) {
                Integer position = positions.get(dimensions.get(i));
                dimensionPositions[i] = position == null ? -1 : position;
                if (position != null) {
                    found++;
                }
            }
            if (found == 0) {
                throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                        path + " has no matching dimensions");
            }
            return readDrivers(path, reader, dimensions, weightPosition, dimensionPositions);
        } catch (IOException e) {
            throw new AllocationException(ErrorCode.INTERNAL, "cannot read " + path, e);
        }
    }

    private List<DriverRow> readDrivers(Path path, StreamingCsvReader reader, List<String> dimensions,
                                        int weightPosition, int[] dimensionPositions) throws IOException {
        MemberDictionary dictionary = new MemberDictionary(dimensionPositions.length);
        List<DriverRow> rows = new ArrayList<>(4096);
        List<String> record;
        int line = 1;
        while ((record = reader.read()) != null) {
            line++;
            String[] members = new String[dimensionPositions.length];
            for (int i = 0; i < dimensionPositions.length; i++) {
                int position = dimensionPositions[i];
                if (position < 0) {
                    continue;
                }
                if (position >= record.size() || record.get(position).isEmpty()) {
                    throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                            path + ":" + line + " missing coordinate " + dimensions.get(i));
                }
                members[i] = dictionary.intern(i, record.get(position));
            }
            if (weightPosition >= record.size()) {
                throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE, path + ":" + line + " missing weight");
            }
            long weight;
            try {
                weight = Decimal.parseScaled(record.get(weightPosition), 6);
            } catch (AllocationException e) {
                throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                        path + ":" + line + " invalid weight: " + e.detail());
            }
            rows.add(new DriverRow(new Coord(members), weight));
        }
        return rows;
    }

    @Override
    public String label(String dataset, String weightField) {
        Path path = weightField == null ? amountPaths.get(dataset) : driverPaths.get(dataset);
        return path == null ? dataset : path.toString();
    }

    @Override
    public String fingerprint(String dataset, String weightField) {
        String label = label(dataset, weightField);
        String cached = fingerprints.get(label);
        if (cached != null) {
            return cached;
        }
        Path path = weightField == null ? amountPaths.get(dataset) : driverPaths.get(dataset);
        if (path == null) {
            return null;
        }
        try {
            // Streamed, not slurped: a dataset fingerprint must not cost the size of the dataset.
            String hash = io.allocation.kernel.io.Digests.sha256Hex(path);
            fingerprints.put(label, hash);
            return hash;
        } catch (IOException e) {
            return null;
        }
    }

    private static InputStream open(Path path) {
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new AllocationException(ErrorCode.INTERNAL, "cannot open " + path, e);
        }
    }

    private static Map<String, Integer> headerPositions(List<String> header, Path path) {
        Map<String, Integer> positions = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i);
            if (i == 0 && !name.isEmpty() && name.charAt(0) == '\uFEFF') {
                name = name.substring(1);
            }
            if (name.isEmpty()) {
                throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                        path + ": empty CSV column at position " + (i + 1));
            }
            if (positions.put(name, i) != null) {
                throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                        path + ": duplicate CSV column \"" + name + "\"");
            }
        }
        return positions;
    }
}
