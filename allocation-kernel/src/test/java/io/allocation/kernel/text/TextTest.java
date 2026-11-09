package io.allocation.kernel.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Character predicates that must agree with the Go reference exactly.
 *
 * <p>{@link Text#isSpace} pins the difference between Java's {@code Character.isWhitespace} and Go's
 * {@code unicode.IsSpace}: Java excludes U+00A0 and U+2007 but includes U+001C..U+001F, while Go does
 * the opposite. A one-character difference here shows up as a diff in a byte-level reconciliation
 * report, so the exact set is locked by these tests.
 */
class TextTest {

    @ParameterizedTest(name = "U+{0} is white space")
    @CsvSource({
            "0009", "000A", "000B", "000C", "000D", "0020", // ASCII
            "0085", "00A0", "1680", // U+00A0: NBSP — Java excludes it, Go includes it
            "2000", "2001", "2002", "2003", "2004", "2005", "2006", "2007", // U+2007: figure space
            "2008", "2009", "200A", "2028", "2029", "202F", "205F", "3000",
    })
    void classifiesGoWhiteSpace(String hex) {
        int codePoint = Integer.parseInt(hex, 16);
        assertTrue(Text.isSpace(codePoint), "U+" + hex + " must be space");
    }

    @ParameterizedTest(name = "U+{0} is not white space")
    @CsvSource({
            "001C", "001D", "001E", "001F", // Java includes these, Go excludes them
            "0000", "0008", "007F", // control characters and DEL
            "0041", "0061", // 'A', 'a'
            "4E00", "1F600", // a CJK ideograph and an emoji
    })
    void rejectsNonGoWhiteSpace(String hex) {
        int codePoint = Integer.parseInt(hex, 16);
        assertFalse(Text.isSpace(codePoint), "U+" + hex + " must not be space");
    }

    @Test
    @DisplayName("disagrees with Character.isWhitespace exactly where Go does")
    void disagreesWithJavaWhitespace() {
        // U+00A0 (NBSP) and U+2007 (figure space): Java says no, Go says yes.
        assertFalse(Character.isWhitespace(0x00A0));
        assertTrue(Text.isSpace(0x00A0));
        assertFalse(Character.isWhitespace(0x2007));
        assertTrue(Text.isSpace(0x2007));
        // U+001C..U+001F: Java says yes, Go says no.
        assertTrue(Character.isWhitespace(0x001C));
        assertFalse(Text.isSpace(0x001C));
    }

    @Test
    @DisplayName("trimSpace trims exactly Go's strings.TrimSpace")
    void trimsGoSpace() {
        assertEquals("a", Text.trimSpace(" \t\r\na \t\r\n"));
        assertEquals("", Text.trimSpace(" \t\r\n"));
        assertEquals("a b", Text.trimSpace("a b"));
        // U+00A0 is space to Go, so it must be trimmed too.
        assertEquals("a", Text.trimSpace("\u00A0a\u00A0"));
        // U+001C is not space to Go, so it must be kept.
        assertEquals("\u001Ca\u001C", Text.trimSpace("\u001Ca\u001C"));
        // Supplementary space (U+3000, ideographic space) is trimmed.
        assertEquals("a", Text.trimSpace("\u3000a\u3000"));
    }

    @ParameterizedTest(name = "\"{0}\" is {1} UTF-8 bytes")
    @CsvSource({
            "'',0",
            "'a',1",
            "'ab',2",
            "'é',2", // U+00E9 is two UTF-8 bytes
            "'中',3", // U+4E2D is three UTF-8 bytes
            "'😀',4", // U+1F600 is four UTF-8 bytes
            "'a中😀',8", // 1 + 3 + 4
    })
    void countsUtf8Bytes(String s, int expected) {
        assertEquals(expected, Text.utf8Length(s));
    }

    @Test
    @DisplayName("utf8Length agrees with the encoded byte length for a mixed sample")
    void utf8LengthMatchesEncoding() {
        String sample = "plain text é 中 😀 \u2028 \u00A0";
        assertEquals(sample.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                Text.utf8Length(sample));
    }
}
