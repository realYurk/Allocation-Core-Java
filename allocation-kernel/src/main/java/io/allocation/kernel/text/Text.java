package io.allocation.kernel.text;

/**
 * Character predicates that must agree with the reference implementation exactly.
 *
 * <p>Java and Go disagree about what "whitespace" means: {@link Character#isWhitespace(int)} excludes
 * U+00A0 and U+2007 but includes U+001C..U+001F, while Go's {@code unicode.IsSpace} does the opposite.
 * Since both the decimal parser and the CSV quoter depend on this predicate, a one-character difference
 * here shows up as a diff in a byte-level reconciliation report.
 */
public final class Text {

    private Text() {
    }

    /** Unicode {@code White_Space} property, matching Go's {@code unicode.IsSpace}. */
    public static boolean isSpace(int codePoint) {
        return codePoint == 0x09 || codePoint == 0x0A || codePoint == 0x0B || codePoint == 0x0C
                || codePoint == 0x0D || codePoint == 0x20 || codePoint == 0x85 || codePoint == 0xA0
                || codePoint == 0x1680
                || (codePoint >= 0x2000 && codePoint <= 0x200A)
                || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202F
                || codePoint == 0x205F || codePoint == 0x3000;
    }

    /** Trims exactly the characters Go's {@code strings.TrimSpace} trims. */
    public static String trimSpace(String s) {
        int begin = 0;
        int end = s.length();
        while (begin < end) {
            int cp = s.codePointAt(begin);
            if (!isSpace(cp)) {
                break;
            }
            begin += Character.charCount(cp);
        }
        while (end > begin) {
            int cp = s.codePointBefore(end);
            if (!isSpace(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return s.substring(begin, end);
    }

    /** UTF-8 byte length of a string, without allocating. */
    public static int utf8Length(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp < 0x80) {
                n += 1;
            } else if (cp < 0x800) {
                n += 2;
            } else if (cp < 0x10000) {
                n += 3;
            } else {
                n += 4;
            }
            i += Character.charCount(cp);
        }
        return n;
    }
}
