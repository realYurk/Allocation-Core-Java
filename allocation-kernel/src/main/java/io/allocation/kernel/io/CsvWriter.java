package io.allocation.kernel.io;

import io.allocation.kernel.text.Text;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * RFC 4180 writer matching the reference dialect byte for byte.
 *
 * <p>Quoting rule: a field is quoted when it contains a comma, a quote, CR or LF, when its first code
 * point is whitespace, or when it is the literal {@code \.} (a PostgreSQL end-of-data marker that
 * <em>must</em> not appear unquoted in a copy stream). Everything else is written raw. Quotes inside a
 * quoted field are doubled.
 *
 * <p>Records are terminated with {@code \n}, not the platform separator: output must be identical on
 * Windows and Linux or the evidence hashes will not match across environments.
 */
public final class CsvWriter implements Closeable {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte COMMA = ',';
    private static final byte QUOTE = '"';
    private static final byte LF = '\n';

    private final OutputStream out;
    private final StringBuilder line = new StringBuilder(256);

    /** Creates a writer, optionally emitting a UTF-8 byte-order mark first. */
    public CsvWriter(OutputStream out, boolean withBom) {
        this.out = new BufferedOutputStream(out, 1 << 16);
        if (withBom) {
            try {
                this.out.write(BOM);
            } catch (IOException e) {
                throw new CsvWriteException("cannot write BOM", e);
            }
        }
    }

    /** Writes the header row, then returns this writer. */
    public CsvWriter withHeader(java.util.List<String> header) {
        write(header);
        return this;
    }

    /** Writes the header row, then returns this writer. */
    public CsvWriter withHeader(String... header) {
        write(header);
        return this;
    }

    /** Writes one record. */
    public void write(String... fields) {
        line.setLength(0);
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            appendField(line, fields[i]);
        }
        line.append('\n');
        try {
            out.write(line.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CsvWriteException("cannot write CSV record", e);
        }
    }

    /** Writes one record. */
    public void write(java.util.List<String> fields) {
        line.setLength(0);
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            appendField(line, fields.get(i));
        }
        line.append('\n');
        try {
            out.write(line.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CsvWriteException("cannot write CSV record", e);
        }
    }

    /** True when the field must be quoted under the reference dialect. */
    public static boolean needsQuotes(String field) {
        if (field.isEmpty()) {
            return false;
        }
        if (field.equals("\\.")) {
            return true;
        }
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c == COMMA || c == QUOTE || c == '\r' || c == '\n') {
                return true;
            }
        }
        return Text.isSpace(field.codePointAt(0));
    }

    private static void appendField(StringBuilder out, String field) {
        if (!needsQuotes(field)) {
            out.append(field);
            return;
        }
        out.append('"');
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c == '"') {
                out.append("\"\"");
            } else {
                out.append(c);
            }
        }
        out.append('"');
    }

    /** Flushes buffered bytes without closing the underlying stream. */
    public void flush() {
        try {
            out.flush();
        } catch (IOException e) {
            throw new CsvWriteException("cannot flush CSV output", e);
        }
    }

    @Override
    public void close() {
        try {
            out.flush();
            out.close();
        } catch (IOException e) {
            throw new CsvWriteException("cannot close CSV output", e);
        }
    }
}
