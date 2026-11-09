package io.allocation.kernel.coord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Canonical keys over a subset of a coordinate's dimensions.
 *
 * <p>{@link CoordKeys.at} buckets driver rows by the dimensions a rule inherits from its pool. An
 * absent member renders as an empty string — exactly the reference implementation's map lookup — so
 * the bucketing key must agree with that, or a driver row could land in the wrong pool.
 */
class CoordKeysTest {

    @Test
    @DisplayName("a key covers only the requested positions, in the requested order")
    void coversRequestedPositions() {
        Coord coord = Coord.of(List.of("plant", "sku", "region"), new String[] {"P1", "S2", "R3"});
        assertEquals("[\"S2\"]", CoordKeys.at(coord, new int[] {1}));
        assertEquals("[\"R3\",\"P1\"]", CoordKeys.at(coord, new int[] {2, 0}));
    }

    @Test
    @DisplayName("an empty position list yields the empty key")
    void emptyPositionsYieldEmptyKey() {
        Coord coord = Coord.of(List.of("plant"), new String[] {"P1"});
        assertEquals("[]", CoordKeys.at(coord, new int[0]));
    }

    @Test
    @DisplayName("an absent member renders as empty, matching the reference map lookup")
    void absentMemberRendersAsEmpty() {
        Coord coord = new Coord(new String[] {"P1", null, ""});
        assertEquals("[\"\"]", CoordKeys.at(coord, new int[] {1}));
        assertEquals("[\"\"]", CoordKeys.at(coord, new int[] {2}));
    }

    @Test
    @DisplayName("the key escapes members exactly like the full coordinate key")
    void escapesLikeFullKey() {
        Coord coord = Coord.of(List.of("plant", "sku"), new String[] {"P1", "A,B\"C"});
        assertEquals("[\"A,B\\\"C\"]", CoordKeys.at(coord, new int[] {1}));
    }
}
