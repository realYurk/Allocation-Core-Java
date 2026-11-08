package io.allocation.kernel.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256, the one hash this engine uses to name things.
 *
 * <p>Exists for a boring but load-bearing reason: hashing a file must never mean loading it. The
 * original implementation called {@code Files.readAllBytes}, which made "fingerprint the dataset" cost
 * the size of the dataset — a 460MB export needed a 460MB heap before a single row was allocated. Every
 * caller here streams through a fixed buffer instead, so the cost is a function of the file, not of the
 * heap.
 */
public final class Digests {

    private static final int BUFFER_BYTES = 64 * 1024;

    private Digests() {
    }

    /** A SHA-256 digest instance. */
    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256; failing here is not a runtime condition.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Lower-case hex of the SHA-256 of the given bytes. */
    public static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    /** Lower-case hex of the SHA-256 of a file's contents, read once through a fixed buffer. */
    public static String sha256Hex(Path path) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
