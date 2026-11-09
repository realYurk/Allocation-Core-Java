package io.allocation.kernel.json;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strict JSON reader and writer the kernel carries instead of a dependency.
 *
 * <p>A rule file is a financial control document, so the parser rejects what a lenient parser would
 * silently accept: unknown keys, duplicate keys, trailing data and non-integral numbers. The writer
 * escapes output byte-for-byte as the Go reference encoder does — including {@code <}, {@code >} and
 * {@code &} as {@code \u003c}-style sequences, because reconciliation compares generated documents
 * byte-wise.
 */
class JsonTest {

    @Test
    @DisplayName("parses every value shape into the sealed JsonValue hierarchy")
    void parsesAllShapes() {
        assertEquals(JsonValue.Null.INSTANCE, Json.parse("null"));
        assertEquals(new JsonValue.Bool(true), Json.parse("true"));
        assertEquals(new JsonValue.Bool(false), Json.parse("false"));
        assertEquals(new JsonValue.Num("42"), Json.parse("42"));
        assertEquals(new JsonValue.Str("hi"), Json.parse("\"hi\""));
        assertEquals(new JsonValue.Arr(List.of(new JsonValue.Num("1"), new JsonValue.Num("2"))),
                Json.parse("[1,2]"));
        assertEquals(new JsonValue.Obj(new java.util.LinkedHashMap<>()), Json.parse("{}"));
    }

    @Test
    @DisplayName("rejects trailing content after a complete value")
    void rejectsTrailingContent() {
        assertThrows(JsonException.class, () -> Json.parse("1 2"));
        assertThrows(JsonException.class, () -> Json.parse("{} {}"));
        assertThrows(JsonException.class, () -> Json.parse("\"a\"x"));
    }

    @Test
    @DisplayName("rejects a duplicate object key")
    void rejectsDuplicateKey() {
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":1,\"a\":2}"));
    }

    @Test
    @DisplayName("rejects malformed input with a positioned message")
    void rejectsMalformedInput() {
        assertThrows(JsonException.class, () -> Json.parse(""));
        assertThrows(JsonException.class, () -> Json.parse("{"));
        assertThrows(JsonException.class, () -> Json.parse("[1,"));
        assertThrows(JsonException.class, () -> Json.parse("\"unterminated"));
        assertThrows(JsonException.class, () -> Json.parse("tru"));
        assertThrows(JsonException.class, () -> Json.parse("nul"));
        assertThrows(JsonException.class, () -> Json.parse("{\"a\" 1}"));
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":1"));
    }

    @Test
    @DisplayName("rejects a non-integral or malformed number")
    void rejectsNonIntegralNumber() {
        // Leading zeros and a bare minus are not JSON numbers.
        assertThrows(JsonException.class, () -> Json.parse("01"));
        assertThrows(JsonException.class, () -> Json.parse("-"));
        assertThrows(JsonException.class, () -> Json.parse("1."));
        assertThrows(JsonException.class, () -> Json.parse("1e"));
        assertThrows(JsonException.class, () -> Json.parse("+1"));
    }

    @Test
    @DisplayName("rejects an invalid escape or unescaped control character in a string")
    void rejectsInvalidStringEscapes() {
        assertThrows(JsonException.class, () -> Json.parse("\"\\x\""));
        assertThrows(JsonException.class, () -> Json.parse("\"\\u12\""));
        assertThrows(JsonException.class, () -> Json.parse("\"\\u12g4\""));
        assertThrows(JsonException.class, () -> Json.parse("\"a\u0001b\"")); // raw control char
    }

    @Test
    @DisplayName("rejects documents nested deeper than the limit")
    void rejectsDeepNesting() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            sb.append('[');
        }
        for (int i = 0; i < 600; i++) {
            sb.append(']');
        }
        assertThrows(JsonException.class, () -> Json.parse(sb.toString()));
    }

    @Test
    @DisplayName("writeString escapes <, >, &, and line separators as Go's encoding/json does")
    void escapesHtmlAndLineSeparators() {
        assertEquals("\"\\u003c\"", Json.quote("<"));
        assertEquals("\"\\u003e\"", Json.quote(">"));
        assertEquals("\"\\u0026\"", Json.quote("&"));
        assertEquals("\"\\u2028\"", Json.quote("\u2028"));
        assertEquals("\"\\u2029\"", Json.quote("\u2029"));
    }

    @Test
    @DisplayName("a lone surrogate is replaced with U+FFFD, exactly as the reference encoder does")
    void replacesLoneSurrogate() {
        assertEquals("\"\\ufffd\"", Json.quote("\uD800")); // high surrogate alone
        assertEquals("\"\\ufffd\"", Json.quote("\uDC00")); // low surrogate alone
    }

    @Test
    @DisplayName("a well-formed surrogate pair passes through unescaped")
    void keepsWellFormedSurrogatePair() {
        assertEquals("\"\uD83D\uDE00\"", Json.quote("\uD83D\uDE00"));
    }

    @Test
    @DisplayName("quote escapes a quote and backslash")
    void escapesQuoteAndBackslash() {
        assertEquals("\"\\\"\"", Json.quote("\""));
        assertEquals("\"\\\\\"", Json.quote("\\"));
    }

    @Test
    @DisplayName("a value round-trips through write and parse")
    void roundTrips() {
        JsonValue original = Json.parse("{\"a\":[1,true,null,\"x\"],\"b\":{\"c\":\"y\"}}");
        JsonValue reparsed = Json.parse(Json.write(original));
        assertEquals(original, reparsed);
        assertEquals(Json.write(original), Json.write(reparsed));
    }

    @Test
    @DisplayName("an escaped string round-trips to its unescaped value")
    void escapedStringRoundTrips() {
        String escaped = Json.quote("a<b>&\"c\"\u2028");
        JsonValue parsed = Json.parse(escaped);
        assertInstanceOf(JsonValue.Str.class, parsed);
        assertEquals("a<b>&\"c\"\u2028", ((JsonValue.Str) parsed).value());
    }

    @Test
    @DisplayName("writeIndented produces two-space indented output with newlines")
    void writeIndentedProducesIndentation() {
        JsonValue value = Json.parse("{\"a\":[1,2]}");
        String indented = Json.writeIndented(value, "  ");
        assertTrue(indented.contains("\n"), "indented output must contain newlines");
        assertTrue(indented.contains("  "), "indented output must contain indentation");
        // Indentation must not change the parsed value.
        assertEquals(value, Json.parse(indented));
    }
}
