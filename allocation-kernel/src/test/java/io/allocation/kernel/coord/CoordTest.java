package io.allocation.kernel.coord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The business coordinate and its canonical key.
 *
 * <p>The coordinate's key is a JSON array of members, escaped exactly as the reference encoder
 * escapes them. That single decision buys the stable grouping key, the stable sort key and the
 * stable row-identifier input — and guarantees a member containing a comma, quote or newline cannot
 * collide with a different coordinate. {@code null} is not an empty string: a driver file without a
 * column must not be treated as carrying an empty value, because {@code SOURCE} targets ask whether
 * the column was there at all.
 */
class CoordTest {

    @Test
    @DisplayName("a coordinate renders members as a JSON array, in declaration order")
    void rendersJsonArrayKey() {
        Coord coord = Coord.of(List.of("plant", "sku"), new String[] {"P1", "A,B"});
        assertEquals("[\"P1\",\"A,B\"]", coord.key());
        assertEquals("P1", coord.member(0));
        assertEquals("A,B", coord.member(1));
        assertEquals(2, coord.size());
    }

    @Test
    @DisplayName("a member containing a comma, quote or newline cannot collide with another coordinate")
    void escapesReservedCharacters() {
        Coord withComma = Coord.of(List.of("sku"), new String[] {"A,B"});
        Coord twoMembers = Coord.of(List.of("sku", "plant"), new String[] {"A", "B"});
        assertNotEquals(withComma.key(), twoMembers.key());

        Coord withQuote = Coord.of(List.of("sku"), new String[] {"A\"B"});
        assertNotEquals("[\"A,B\"]", withQuote.key());
    }

    @Test
    @DisplayName("an absent member renders as empty in the key but remains null in the member")
    void absentMemberIsEmptyInKeyButNullInMember() {
        Coord coord = new Coord(new String[] {"P1", null, ""});
        assertEquals("[\"P1\",\"\",\"\"]", coord.key());
        assertTrue(coord.has(0));
        assertFalse(coord.has(1));
        assertTrue(coord.has(2)); // present, but empty
        assertEquals("", coord.memberOrEmpty(1));
        assertEquals("", coord.memberOrEmpty(2));
    }

    @Test
    @DisplayName("null and empty render the same canonical key, but has() tells them apart")
    void nullAndEmptyShareKeyButDifferByPresence() {
        // The canonical key renders an absent member as empty, so key/equals/hashCode collapse the
        // two — but they are financially different, and that difference lives in has(), not in the key.
        Coord absent = new Coord(new String[] {null});
        Coord empty = new Coord(new String[] {""});
        assertEquals(absent.key(), empty.key());
        assertEquals(absent, empty);
        assertEquals(absent.hashCode(), empty.hashCode());
        assertFalse(absent.has(0));
        assertTrue(empty.has(0));
    }

    @Test
    @DisplayName("a key round-trips through parse back to an equal coordinate")
    void keyRoundTrips() {
        Coord original = Coord.of(
                List.of("plant", "sku", "region"),
                new String[] {"P1", "A,B\"C", "R2"});
        Coord reparsed = Coord.parse(original.key());
        assertEquals(original, reparsed);
        assertEquals(original.key(), reparsed.key());
        assertEquals(original.hashCode(), reparsed.hashCode());
    }

    @Test
    @DisplayName("parse rejects a key that is not a JSON array")
    void parseRejectsNonArray() {
        assertThrows(IllegalArgumentException.class, () -> Coord.parse("\"P1\""));
        assertThrows(IllegalArgumentException.class, () -> Coord.parse("{\"a\":1}"));
    }

    @Test
    @DisplayName("parse rejects text that is not JSON at all")
    void parseRejectsMalformedJson() {
        // The JSON layer reports its own exception before Coord can even check the shape.
        assertThrows(RuntimeException.class, () -> Coord.parse("not json"));
    }

    @Test
    @DisplayName("parse rejects a key whose member is not a string")
    void parseRejectsNonStringMember() {
        assertThrows(IllegalArgumentException.class, () -> Coord.parse("[1,2]"));
        assertThrows(IllegalArgumentException.class, () -> Coord.parse("[\"ok\",true]"));
    }

    @Test
    @DisplayName("of rejects a coordinate whose arity does not match the dimensions")
    void ofRejectsArityMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> Coord.of(List.of("a", "b"), new String[] {"x"}));
        assertThrows(IllegalArgumentException.class,
                () -> Coord.of(List.of("a"), new String[] {"x", "y"}));
    }

    @Test
    @DisplayName("comparison follows UTF-8 byte order, so an emoji sorts above a CJK ideograph")
    void compareFollowsUtf8Order() {
        Coord emoji = Coord.of(List.of("sku"), new String[] {"\uD83D\uDE00"});
        Coord cjk = Coord.of(List.of("sku"), new String[] {"\u4E00"});
        assertTrue(cjk.compareTo(emoji) < 0);
        assertTrue(emoji.compareTo(cjk) > 0);
    }

    @Test
    @DisplayName("members() returns a defensive copy, not the internal array")
    void membersIsDefensiveCopy() {
        Coord coord = Coord.of(List.of("a"), new String[] {"x"});
        coord.members()[0] = "mutated";
        assertEquals("x", coord.member(0));
        assertEquals("[\"x\"]", coord.key());
    }

    @Test
    @DisplayName("toString is the canonical key")
    void toStringIsKey() {
        Coord coord = Coord.of(List.of("a"), new String[] {"x"});
        assertEquals(coord.key(), coord.toString());
    }
}
