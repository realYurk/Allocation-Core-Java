package io.allocation.kernel.io;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

/**
 * The same CSV dialect as {@link CsvReader}, read incrementally.
 *
 * <p>{@link CsvReader} holds the entire file in memory: correct, simple, and fine for the rules and
 * datasets of a normal run. It is not fine for reconciliation against a ten-million-row export, where the
 * file itself is larger than the heap — so this class exists, and it exists <em>only</em> to change how
 * bytes arrive.
 *
 * <p>Dialect drift is the real risk in having two parsers, so this class does not parse fields at all.
 * It finds record boundaries and hands each record's raw bytes to {@link CsvReader}, which remains the
 * single definition of the grammar. There is therefore one place where a quoted {@code CRLF} becomes
 * {@code \n}, one place that rejects a bare quote, and one place to fix a bug.
 *
 * <p>What this class does have to know is enough of the grammar to find boundaries: a quote toggles
 * quoting (a doubled quote toggles twice, which is why escapes need no special case), a newline ends a
 * record only outside quotes, and a {@code CR} ends a record only when a {@code LF} follows.
 *
 * <p>Memory is bounded by the largest single record, not by the file. A record is the unit of work
 * everywhere else in this engine, so that is the natural ceiling.
 *
 * <p>Not thread safe; one reader per stream.
 */
public final class StreamingCsvReader implements AutoCloseable {

    /** Default read buffer. Large enough that a typical record never straddles two refills. */
    private static final int DEFAULT_BUFFER_SIZE = 64 * 1024;

    private static final byte QUOTE = '"';
    private static final byte COMMA = ',';
    private static final byte LF = '\n';
    private static final byte CR = '\r';

    private final InputStream in;
    private final byte[] buffer;
    private int position;
    private int limit;
    private boolean exhausted;

    /** Bytes of the record currently being collected. Reused across records. */
    private byte[] record = new byte[256];
    private int recordLength;
    private int recordNumber;

    public StreamingCsvReader(InputStream in) {
        this(in, DEFAULT_BUFFER_SIZE);
    }

    /**
     * @param bufferSize bytes per refill; exposed so a test can force every boundary case
     */
    public StreamingCsvReader(InputStream in, int bufferSize) {
        if (bufferSize < 1) {
            throw new IllegalArgumentException("bufferSize must be at least 1, got " + bufferSize);
        }
        this.in = in;
        this.buffer = new byte[bufferSize];
    }

    /** Number of the last record returned; the header counts as record 1. Blank lines are skipped. */
    public int recordNumber() {
        return recordNumber;
    }

    /**
     * Returns the next record, or {@code null} at end of input.
     *
     * <p>Behaviour matches {@link CsvReader#read()}: blank lines between records are skipped, a missing
     * trailing newline still yields the final record, and a malformed record raises the same
     * {@code ALLOC_002} with the same message.
     */
    public List<String> read() {
        while (true) {
            recordLength = 0;
            boolean inQuotes = false;
            boolean atFieldStart = true;
            boolean sawContent = false;
            boolean complete = false;

            while (!complete) {
                if (position >= limit && !fill()) {
                    if (!sawContent) {
                        return null;
                    }
                    break;
                }
                byte b = buffer[position];

                if (inQuotes) {
                    if (b == QUOTE) {
                        append(b);
                        position++;
                        if (peek() == QUOTE) {
                            // A doubled quote is an escaped quote and the field stays quoted. Consuming
                            // both here is what keeps the second one from looking like a bare quote.
                            append(QUOTE);
                            position++;
                        } else {
                            inQuotes = false;
                            atFieldStart = false;
                        }
                        sawContent = true;
                        continue;
                    }
                    append(b);
                    position++;
                    sawContent = true;
                    continue;
                }

                switch (b) {
                    case QUOTE -> {
                        if (!atFieldStart) {
                            // Same rejection the reference reader makes, and it also stops an
                            // unterminated quote from swallowing the rest of the file.
                            throw malformed("bare quote in non-quoted field");
                        }
                        inQuotes = true;
                        append(b);
                        position++;
                        sawContent = true;
                    }
                    case COMMA -> {
                        append(b);
                        position++;
                        atFieldStart = true;
                        sawContent = true;
                    }
                    case LF -> {
                        position++;
                        complete = true;
                    }
                    case CR -> {
                        position++;
                        if (peek() == LF) {
                            position++;
                            complete = true;
                        } else {
                            // A CR not followed by LF is field content, exactly as the reference parser
                            // treats it (and a trailing CR at end of input likewise).
                            append(CR);
                            sawContent = true;
                        }
                    }
                    default -> {
                        append(b);
                        position++;
                        atFieldStart = false;
                        sawContent = true;
                    }
                }
            }

            if (recordLength == 0) {
                // A blank line. The reference reader skips these rather than yielding an empty record.
                continue;
            }
            recordNumber++;
            // One grammar, one implementation: the collected bytes go through the reference parser,
            // which is told where this record sits so its diagnostics name the right row.
            return new CsvReader(Arrays.copyOf(record, recordLength), recordNumber).read();
        }
    }

    private void append(byte b) {
        if (recordLength == record.length) {
            record = Arrays.copyOf(record, record.length * 2);
        }
        record[recordLength++] = b;
    }

    /**
     * Next byte without consuming it, refilling if necessary.
     *
     * @return the byte as 0..255, or -1 at end of input
     */
    private int peek() {
        if (position >= limit && !fill()) {
            return -1;
        }
        return buffer[position] & 0xff;
    }

    /** Refills the buffer, returning false at end of input. */
    private boolean fill() {
        if (exhausted) {
            return false;
        }
        try {
            int read = in.read(buffer, 0, buffer.length);
            if (read <= 0) {
                exhausted = true;
                return false;
            }
            position = 0;
            limit = read;
            return true;
        } catch (IOException e) {
            exhausted = true;
            throw new AllocationException(ErrorCode.INTERNAL, "cannot read CSV input", e);
        }
    }

    private AllocationException malformed(String reason) {
        return new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                "malformed CSV at record " + (recordNumber + 1) + ": " + reason);
    }

    @Override
    public void close() {
        exhausted = true;
        try {
            in.close();
        } catch (IOException e) {
            // Closing a read-only stream cannot fail in a way the caller can act on.
        }
    }
}
