package io.allocation.kernel.coord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order that decides where the residual penny lands.
 *
 * <p>Every tie-break in the engine funnels through {@link Utf8Order.compare}: when two targets share
 * a remainder, the one whose identity sorts first takes the penny. Getting this order wrong does not
 * change the total — it changes <em>who receives</em> the remainder, and it would only surface in one
 * plant months later. These tests pin the exact ordering promised by the class.
 */
class Utf8OrderTest {

    @Test
    @DisplayName("identical references and equal strings compare as zero")
    void identicalAndEqualAreZero() {
        String a = "same";
        assertEquals(0, Utf8Order.compare(a, a));
        assertEquals(0, Utf8Order.compare("same", "same"));
        assertEquals(0, Utf8Order.compare(null, null));
    }

    @Test
    @DisplayName("null sorts before every non-null string, after no string")
    void nullSortsFirst() {
        assertEquals(-1, Utf8Order.compare(null, ""));
        assertEquals(-1, Utf8Order.compare(null, "a"));
        assertEquals(1, Utf8Order.compare("a", null));
        assertEquals(1, Utf8Order.compare("", null));
        assertTrue(Utf8Order.less(null, "a"));
        assertFalse(Utf8Order.less("a", null));
    }

    @Test
    @DisplayName("a prefix sorts before the string that extends it")
    void prefixSortsFirst() {
        assertEquals(-1, Utf8Order.compare("a", "ab"));
        assertEquals(1, Utf8Order.compare("ab", "a"));
        assertEquals(-1, Utf8Order.compare("", "a"));
        assertTrue(Utf8Order.less("a", "ab"));
        assertFalse(Utf8Order.less("ab", "a"));
    }

    @Test
    @DisplayName("an emoji sorts above a CJK ideograph — UTF-8 byte order, not UTF-16")
    void emojiSortsAboveCjk() {
        // U+1F600 (😀) encodes as four UTF-8 bytes starting 0xF0; U+4E00 (一) starts 0xE4.
        // UTF-16 compares surrogate 0xD83D against 0x4E00 and would put the emoji *below* the ideograph.
        String emoji = "\uD83D\uDE00"; // 😀
        String cjk = "\u4E00"; // 一
        // The engine's order, by contrast, must place the emoji above the ideograph.
        assertTrue(Utf8Order.compare(cjk, emoji) < 0);
        assertTrue(Utf8Order.compare(emoji, cjk) > 0);
        assertTrue(Utf8Order.less(cjk, emoji));
    }

    @Test
    @DisplayName("two supplementary characters compare by their code points, not their surrogates")
    void supplementaryCharactersCompareByCodePoint() {
        String smile = "\uD83D\uDE00"; // U+1F600
        String grin = "\uD83D\uDE01"; // U+1F601
        assertTrue(Utf8Order.compare(smile, grin) < 0);
        assertTrue(Utf8Order.compare(grin, smile) > 0);
    }

    @Test
    @DisplayName("a BMP character sorts below a supplementary character")
    void bmpSortsBelowSupplementary() {
        String bmp = "\u007F"; // U+007F
        String supplementary = "\uD83D\uDE00"; // U+1F600
        assertTrue(Utf8Order.compare(bmp, supplementary) < 0);
    }

    @Test
    @DisplayName("the order is a total order: antisymmetric and transitive")
    void orderIsConsistent() {
        String[] values = {"", "a", "b", "ab", "\u4E00", "\uD83D\uDE00"};
        for (int i = 0; i < values.length; i++) {
            for (int j = 0; j < values.length; j++) {
                int ij = Utf8Order.compare(values[i], values[j]);
                int ji = Utf8Order.compare(values[j], values[i]);
                assertEquals(-signum(ij), signum(ji),
                        "antisymmetry violated between " + values[i] + " and " + values[j]);
            }
        }
    }

    private static int signum(int value) {
        return Integer.compare(value, 0);
    }
}
