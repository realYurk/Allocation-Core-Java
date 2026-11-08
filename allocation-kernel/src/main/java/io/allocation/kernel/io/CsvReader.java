package io.allocation.kernel.io;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 reader with the exact dialect of the reference implementation.
 *
 * <p>Dialect: comma separator, double-quote quoting, {@code ""} escapes a quote inside a quoted field,
 * records end at {@code \n} or {@code \r\n}, {@code \r\n} inside a quoted field becomes {@code \n},
 * blank lines between records are skipped, and the field count may vary per record.
 *
 * <p>Input is consumed whole. A streaming variant is a separate concern (milestone M4): it changes how
 * bytes arrive, not how they are interpreted, and mixing the two in one class is how dialect drift
 * starts. The byte array is also what makes the parser verifiable in a unit test with three lines of
 * setup.
 */
public final class CsvReader {

    private final byte[] data;
    private int position;
    private int recordNumber;

    /** Reads all bytes from {@code in}. The stream is closed. */
    public CsvReader(InputStream in) {
        try (InputStream stream = in) {
            this.data = stream.readAllBytes();
        } catch (IOException e) {
            throw new AllocationException(ErrorCode.INTERNAL, "cannot read CSV input", e);
        }
    }

    /** Parses the given bytes directly. */
    public CsvReader(byte[] data) {
        this.data = data;
    }

    /**
     * Parses one record that has already been extracted from a larger document.
     *
     * <p>Exists so {@link StreamingCsvReader} can reuse this grammar without renumbering its own errors:
     * a diagnostic that says "record 1" for the thousandth row of a file is worse than no diagnostic.
     *
     * @param recordNumberInFile the position this record occupies in the whole document
     */
    CsvReader(byte[] data, int recordNumberInFile) {
        this.data = data;
        this.recordNumber = Math.max(0, recordNumberInFile - 1);
    }

    /** Number of the last record returned; the header counts as record 1. */
    public int recordNumber() {
        return recordNumber;
    }

    /**
     * Returns the next record, or {@code null} at end of input.
     *
     * <p>Blank lines (a bare {@code \n} or {@code \r\n}) between records are skipped, matching the
     * reference reader's tolerance for trailing newlines and hand-edited files.
     */
    public List<String> read() {
        while (position < data.length && (data[position] == '\n'
                || (data[position] == '\r' && position + 1 < data.length && data[position + 1] == '\n'))) {
            position += data[position] == '\r' ? 2 : 1;
        }
        if (position >= data.length) {
            return null;
        }
        recordNumber++;
        List<String> fields = new ArrayList<>(16);
        while (true) {
            fields.add(parseField());
            if (position >= data.length) {
                return fields;
            }
            byte b = data[position];
            if (b == ',') {
                position++;
                continue;
            }
            if (b == '\n') {
                position++;
                return fields;
            }
            if (b == '\r' && position + 1 < data.length && data[position + 1] == '\n') {
                position += 2;
                return fields;
            }
            // Only reachable when the field parser stopped at a character it does not own.
            throw malformed("unexpected character '" + (char) b + "' after field");
        }
    }

    private String parseField() {
        if (position < data.length && data[position] == '"') {
            return parseQuotedField();
        }
        int start = position;
        while (position < data.length) {
            byte b = data[position];
            if (b == ',') {
                break;
            }
            if (b == '\n') {
                break;
            }
            if (b == '\r' && position + 1 < data.length && data[position + 1] == '\n') {
                break;
            }
            if (b == '"') {
                throw malformed("bare quote in non-quoted field");
            }
            position++;
        }
        return new String(data, start, position - start, StandardCharsets.UTF_8);
    }

    private String parseQuotedField() {
        position++; // opening quote
        StringBuilder out = null;
        int segmentStart = position;
        while (true) {
            if (position >= data.length) {
                throw malformed("unexpected end of input inside quoted field");
            }
            byte b = data[position];
            if (b == '"') {
                if (position + 1 < data.length && data[position + 1] == '"') {
                    out = append(out, data, segmentStart, position);
                    position += 2;
                    out.append('"');
                    segmentStart = position;
                    continue;
                }
                position++;
                return commit(out, segmentStart, position - segmentStart - 1);
            }
            if (b == '\r' && position + 1 < data.length && data[position + 1] == '\n') {
                out = append(out, data, segmentStart, position);
                position += 2;
                out.append('\n');
                segmentStart = position;
                continue;
            }
            position++;
        }
    }

    private static StringBuilder append(StringBuilder existing, byte[] data, int start, int end) {
        StringBuilder sb = existing == null ? new StringBuilder(32) : existing;
        if (end > start) {
            // Segments always break at ASCII bytes ('"' or CR), never inside a multi-byte sequence,
            // so each segment decodes independently.
            sb.append(new String(data, start, end - start, StandardCharsets.UTF_8));
        }
        return sb;
    }

    private String commit(StringBuilder existing, int start, int length) {
        if (existing == null) {
            return new String(data, start, length, StandardCharsets.UTF_8);
        }
        if (length > 0) {
            existing.append(new String(data, start, length, StandardCharsets.UTF_8));
        }
        return existing.toString();
    }

    private AllocationException malformed(String reason) {
        return new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                "malformed CSV at record " + recordNumber + ": " + reason);
    }
}
