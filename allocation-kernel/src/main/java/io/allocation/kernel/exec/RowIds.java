package io.allocation.kernel.exec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic identifiers for rows and edges.
 *
 * <p>Row identifiers are derived, never generated: identical inputs must produce identical identifiers
 * on every machine and every run, otherwise the evidence files cannot be compared across environments.
 * The construction is a SHA-256 over length-prefixed fields, truncated to 96 bits, which matches the
 * reference implementation. Length prefixing matters — concatenating {@code "ab"+"c"} and
 * {@code "a"+"bc"} must not collide.
 */
public final class RowIds {

    private static final int ID_BYTES = 12;

    private RowIds() {
    }

    /** Identifier of an allocated output row. */
    public static String of(String stage, String pool, String rule, String target) {
        MessageDigest digest = sha256();
        update(digest, stage);
        update(digest, pool);
        update(digest, rule);
        update(digest, target);
        return truncate(digest.digest());
    }

    /** Identifier of a row read from a source dataset. */
    public static String ofSource(String dataset, String rowId, int ordinal) {
        return "[" + io.allocation.kernel.json.Json.quote(dataset)
                + "," + io.allocation.kernel.json.Json.quote(rowId)
                + "," + ordinal + "]";
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] length = new byte[8];
        long n = bytes.length;
        for (int i = 7; i >= 0; i--) {
            length[i] = (byte) (n & 0xFF);
            n >>>= 8;
        }
        digest.update(length);
        digest.update(bytes);
    }

    private static String truncate(byte[] digest) {
        byte[] head = new byte[ID_BYTES];
        System.arraycopy(digest, 0, head, 0, ID_BYTES);
        return HexFormat.of().formatHex(head);
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
