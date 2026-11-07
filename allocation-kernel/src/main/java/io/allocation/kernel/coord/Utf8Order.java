package io.allocation.kernel.coord;

/**
 * Code-point order over strings, which is exactly UTF-8 byte order.
 *
 * <p>{@link String#compareTo(String)} compares UTF-16 code units and therefore disagrees with UTF-8
 * byte order for supplementary characters: an emoji (U+1F600) sorts <em>below</em> a CJK ideograph
 * (U+4E00) in UTF-16 because its leading surrogate 0xD83D is small, while its UTF-8 bytes place it
 * above. Tie-breaking on the wrong order means the residual penny lands on a different party
 * depending on which characters happen to appear in a member name — a defect that would only show up
 * months later, in one plant.
 */
public final class Utf8Order {

    private Utf8Order() {
    }

    /** Compares two strings by Unicode code point. Shorter is smaller when one is a prefix. */
    public static int compare(String a, String b) {
        if (a == b) {
            return 0;
        }
        if (a == null) {
            return b == null ? 0 : -1;
        }
        if (b == null) {
            return 1;
        }
        int i = 0;
        int j = 0;
        int na = a.length();
        int nb = b.length();
        while (i < na && j < nb) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return ca < cb ? -1 : 1;
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        if (i < na) {
            return 1;
        }
        if (j < nb) {
            return -1;
        }
        return 0;
    }

    /** True when {@code a} sorts strictly before {@code b} in UTF-8 byte order. */
    public static boolean less(String a, String b) {
        return compare(a, b) < 0;
    }
}
