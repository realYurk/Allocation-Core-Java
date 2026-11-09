package io.allocation.kernel.json;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The typed, strict view over one JSON object.
 *
 * <p>{@link JsonFields#done()} is the enforcement point for the rule-file strictness the kernel
 * promises: a misspelled key such as {@code "nmode": "TOTAL"} must fail loudly, because the
 * alternative is a silently different allocation that nobody notices until the month is closed.
 */
class JsonFieldsTest {

    private static JsonFields fields(String json) {
        return JsonFields.of(Json.parse(json), "");
    }

    @Test
    @DisplayName("done() is silent when every field was read")
    void donePassesWhenAllRead() {
        JsonFields f = fields("{\"a\":1,\"b\":\"x\"}");
        assertEquals(1, f.optInt("a", -1));
        assertEquals("x", f.requireString("b"));
        f.done(); // must not throw
    }

    @Test
    @DisplayName("done() rejects any field that was never read, listing them sorted")
    void doneRejectsUnreadFields() {
        JsonFields f = fields("{\"nmode\":\"TOTAL\",\"plant\":\"P1\"}");
        f.requireString("plant");
        JsonException e = assertThrows(JsonException.class, f::done);
        assertTrue(e.getMessage().contains("nmode"), "message must name the misspelled key");
    }

    @Test
    @DisplayName("of() rejects a value that is not an object")
    void ofRejectsNonObject() {
        assertThrows(JsonException.class, () -> JsonFields.of(Json.parse("[1]"), "root"));
        assertThrows(JsonException.class, () -> JsonFields.of(Json.parse("\"x\""), "root"));
    }

    @Test
    @DisplayName("requireString rejects a missing or non-string field")
    void requireStringRejectsWrongShape() {
        JsonFields missing = fields("{\"a\":1}");
        assertThrows(JsonException.class, () -> missing.requireString("nope"));

        JsonFields wrong = fields("{\"a\":1}");
        assertThrows(JsonException.class, () -> wrong.requireString("a"));
    }

    @Test
    @DisplayName("optString returns null for an absent or null field, the fallback otherwise")
    void optStringSemantics() {
        JsonFields f = fields("{\"a\":\"x\",\"b\":null}");
        assertEquals("x", f.optString("a"));
        assertNull(f.optString("absent"));
        assertNull(f.optString("b"));
        assertEquals("fallback", f.optString("absent", "fallback"));
        assertEquals("fallback", f.optString("b", "fallback"));
    }

    @Test
    @DisplayName("optInt rejects a non-integer or out-of-range number")
    void optIntRejectsWrongShape() {
        JsonFields nonInt = fields("{\"a\":\"42\"}");
        assertThrows(JsonException.class, () -> nonInt.optInt("a", -1));

        JsonFields outOfRange = fields("{\"a\":2147483648}");
        assertThrows(JsonException.class, () -> outOfRange.optInt("a", -1));

        JsonFields fine = fields("{\"a\":42}");
        assertEquals(42, fine.optInt("a", -1));
        assertEquals(-1, fine.optInt("absent", -1));
    }

    @Test
    @DisplayName("optStringList returns the strings, rejecting a non-string member")
    void optStringListSemantics() {
        JsonFields f = fields("{\"a\":[\"x\",\"y\"]}");
        assertEquals(List.of("x", "y"), f.optStringList("a"));
        assertNull(f.optStringList("absent"));

        JsonFields mixed = fields("{\"a\":[\"x\",1]}");
        JsonException e = assertThrows(JsonException.class, () -> mixed.optStringList("a"));
        assertTrue(e.getMessage().contains("[1]"), "message must locate the offending index");
    }

    @Test
    @DisplayName("optObject and optObjectList return the nested objects, rejecting wrong shapes")
    void optObjectSemantics() {
        JsonFields f = fields("{\"o\":{\"k\":\"v\"},\"list\":[{\"x\":1},{\"x\":2}]}");
        JsonValue.Obj o = f.optObject("o");
        assertEquals("v", ((JsonValue.Str) o.get("k")).value());
        assertEquals(2, f.optObjectList("list").size());
        assertNull(f.optObject("absent"));
        assertNull(f.optObjectList("absent"));

        JsonFields wrong = fields("{\"o\":[1]}");
        assertThrows(JsonException.class, () -> wrong.optObject("o"));
    }

    @Test
    @DisplayName("optStringMap preserves document order and rejects non-string values")
    void optStringMapSemantics() {
        JsonFields f = fields("{\"m\":{\"b\":\"2\",\"a\":\"1\"}}");
        Map<String, String> map = f.optStringMap("m");
        assertEquals(List.of("b", "a"), List.copyOf(map.keySet()));
        assertEquals("1", map.get("a"));

        JsonFields wrong = fields("{\"m\":{\"a\":1}}");
        assertThrows(JsonException.class, () -> wrong.optStringMap("m"));
    }

    @Test
    @DisplayName("has() reports presence without consuming the field")
    void hasDoesNotConsume() {
        JsonFields f = fields("{\"a\":1}");
        assertTrue(f.has("a"));
        assertFalse(f.has("b"));
        // has() must not mark the field consumed, so done() still flags it.
        assertThrows(JsonException.class, f::done);
    }

    @Test
    @DisplayName("child() builds the dotted path used in error messages")
    void childBuildsPath() {
        JsonFields f = fields("{\"a\":{\"b\":1}}");
        assertEquals("a", f.child("a"));
        JsonFields nested = JsonFields.of(f.optObject("a"), "a");
        assertEquals("a.b", nested.child("b"));
    }
}
