package io.allocation.kernel.service;

import java.security.SecureRandom;

/**
 * Generates run identifiers that sort by creation time.
 *
 * <p>A ULID-shaped id rather than a database sequence: the id is minted before the row is inserted, so
 * a submission can be answered without waiting for the database, and lexicographic order still matches
 * insertion order. That matters when someone scans the run table by primary key to find "what happened
 * yesterday".
 */
public final class RunIds {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private RunIds() {
    }

    /** Returns a 26-character identifier: 48-bit timestamp followed by 80 random bits. */
    public static String next() {
        char[] out = new char[26];
        long timestamp = System.currentTimeMillis();
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (timestamp & 31)];
            timestamp >>>= 5;
        }
        long high = RANDOM.nextLong();
        long low = RANDOM.nextLong();
        for (int i = 0; i < 8; i++) {
            out[10 + i] = ALPHABET[(int) (high & 31)];
            high >>>= 5;
            out[18 + i] = ALPHABET[(int) (low & 31)];
            low >>>= 5;
        }
        return new String(out);
    }
}
