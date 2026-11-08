package io.allocation.kernel.exec;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.coord.Coord;
import io.allocation.kernel.money.Overflow;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An append-only sequence of rows that spills to disk when it outgrows a budget.
 *
 * <h2>Why not a {@code List<Row>}</h2>
 * The executor moves rows between stages. Holding them in a list means peak heap is proportional to the
 * input, so a ten-million-row file needs a ten-gigabyte JVM no matter how good the arithmetic is. This
 * store keeps at most {@code maxRowsInMemory} rows in a list and then writes the rest to a temp file,
 * which makes heap a function of the budget the caller chose rather than of the data.
 *
 * <h2>Same contract as the list it replaces</h2>
 * Insertion order is preserved across the spill boundary, and {@link #forEach} may be called any number
 * of times. That is what allows the executor to be restructured without touching a single rule about
 * ordering, tie-breaking or evidence sequence — the corpus proves the two are interchangeable.
 *
 * <h2>Format</h2>
 * Rows are encoded as {@code [memberCount][memberId...][amount][idCount][text...]}. Coordinate members
 * come from a bounded set, so they are written once into a dictionary and referenced by index; row
 * identifiers are unique by construction and are written inline. The dictionary also means every
 * coordinate reconstructed from disk shares member strings with every other, which keeps
 * {@link Coord#equals} cheap on the way back in.
 */
final class RowStore implements AutoCloseable {

    private static final int BUFFER_BYTES = 64 * 1024;
    private static final int MAX_TEXT_BYTES = 0xFFFF;

    /** Receives rows in insertion order. */
    @FunctionalInterface
    interface RowConsumer {
        void accept(Row row);
    }

    private final SpillSpace spill;
    private final long maxRowsInMemory;
    private final List<Row> memory = new ArrayList<>();
    private final Map<String, Integer> memberIds = new HashMap<>();
    private final List<String> members = new ArrayList<>();

    private DataOutputStream out;
    private Path file;
    private long spilled;
    private long size;
    private long total;
    private boolean closed;

    /**
     * @param spill           where to put the temp file when the budget is exceeded
     * @param maxRowsInMemory rows to keep in memory; {@link Long#MAX_VALUE} never spills
     */
    RowStore(SpillSpace spill, long maxRowsInMemory) {
        if (maxRowsInMemory < 1) {
            throw new IllegalArgumentException("maxRowsInMemory must be positive");
        }
        this.spill = spill;
        this.maxRowsInMemory = maxRowsInMemory;
    }

    /** Appends a row. Sums the amount so the caller does not have to walk the store again. */
    void add(Row row) {
        checkOpen();
        size++;
        total = Overflow.addExact(total, row.amount());
        if (out == null) {
            if (memory.size() < maxRowsInMemory) {
                memory.add(row);
                return;
            }
            beginSpill();
        }
        try {
            write(row);
            spilled++;
        } catch (IOException e) {
            throw new AllocationException(ErrorCode.INTERNAL, "cannot write spilled rows to " + file, e);
        }
    }

    /** Visits every row in insertion order. May be called repeatedly. */
    void forEach(RowConsumer consumer) {
        checkOpen();
        if (out == null) {
            for (Row row : memory) {
                consumer.accept(row);
            }
            return;
        }
        flush();
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file), BUFFER_BYTES))) {
            for (long i = 0; i < spilled; i++) {
                consumer.accept(read(in));
            }
        } catch (IOException e) {
            throw new AllocationException(ErrorCode.INTERNAL, "cannot read spilled rows from " + file, e);
        }
    }

    /** Number of rows appended, including the ones on disk. */
    long size() {
        return size;
    }

    /** Sum of every amount appended, checked for overflow at insertion time. */
    long total() {
        return total;
    }

    /** True when at least one row went to disk, which is what a run report should mention. */
    boolean spilled() {
        return out != null;
    }

    /** Location of the spill file, or {@code null} when everything stayed in memory. */
    Path spillFile() {
        return file;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        memory.clear();
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // the file is deleted below; a failed flush cannot be repaired here
            }
            out = null;
        }
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // a leftover temp file is untidy, not incorrect; the spill directory is swept at the end
            }
            file = null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Encoding
    // ---------------------------------------------------------------------------------------------

    private void beginSpill() {
        file = spill.newFile();
        try {
            out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), BUFFER_BYTES));
            for (Row row : memory) {
                write(row);
                spilled++;
            }
            memory.clear();
        } catch (IOException e) {
            throw new AllocationException(ErrorCode.INTERNAL, "cannot open spill file " + file, e);
        }
    }

    private void write(Row row) throws IOException {
        String[] parts = row.coord().members();
        out.writeShort(parts.length);
        for (String part : parts) {
            out.writeInt(memberId(part));
        }
        out.writeLong(row.amount());
        String[] ids = row.ids();
        out.writeShort(ids.length);
        for (String id : ids) {
            writeText(id);
        }
    }

    private Row read(DataInputStream in) throws IOException {
        int arity = in.readShort();
        String[] parts = new String[arity];
        for (int i = 0; i < arity; i++) {
            parts[i] = member(in.readInt());
        }
        long amount = in.readLong();
        int idCount = in.readShort();
        String[] ids = new String[idCount];
        for (int i = 0; i < idCount; i++) {
            ids[i] = readText(in);
        }
        return new Row(new Coord(parts), amount, ids);
    }

    private int memberId(String member) {
        Integer known = memberIds.get(member);
        if (known != null) {
            return known;
        }
        int id = members.size();
        memberIds.put(member, id);
        members.add(member);
        return id;
    }

    private String member(int id) {
        if (id < 0 || id >= members.size()) {
            throw new AllocationException(ErrorCode.INTERNAL, "corrupt spill file: unknown member " + id);
        }
        return members.get(id);
    }

    private void writeText(String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) {
            throw new AllocationException(ErrorCode.INTERNAL,
                    "a row identifier of " + bytes.length + " bytes cannot be spilled");
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readText(DataInputStream in) throws IOException {
        int length = in.readShort();
        if (length < 0) {
            throw new AllocationException(ErrorCode.INTERNAL, "corrupt spill file: negative text length");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void flush() {
        try {
            out.flush();
        } catch (IOException e) {
            throw new AllocationException(ErrorCode.INTERNAL, "cannot flush spill file " + file, e);
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("row store is closed");
        }
    }
}
