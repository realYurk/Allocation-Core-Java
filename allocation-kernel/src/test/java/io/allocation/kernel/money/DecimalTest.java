package io.allocation.kernel.money;

import io.allocation.kernel.AllocationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Numeric-domain acceptance vectors; each row corresponds to one line of the specification table. */
class DecimalTest {

    @ParameterizedTest(name = "\"{0}\" at scale {1} -> {2}")
    @CsvSource({
            "'0',0,0",
            "'1',0,1",
            "'-1',0,-1",
            "'100.00',2,10000",
            "'1.5',2,150",
            "'1.50',2,150",
            "'1.',2,100",
            "'+1.25',2,125",
            "' 42 ',0,42",
            "'  3.7  ',1,37",
            "'-0.01',2,-1",
            "'0.000001',6,1",
            "'9223372036854775807',0,9223372036854775807",
            "'-9223372036854775808',0,-9223372036854775808",
    })
    void parsesAcceptedLiterals(String text, int scale, long expected) {
        assertEquals(expected, Decimal.parseScaled(text, scale));
    }

    @Test
    @DisplayName("the minimum int64 is accepted; its magnitude is one larger than the maximum")
    void acceptsMinInt64() {
        assertEquals(Long.MIN_VALUE, Decimal.parseScaled("-9223372036854775808", 0));
    }

    @ParameterizedTest(name = "rejects \"{0}\" at scale {1}")
    @CsvSource({
            "'1.230',2",
            "'1.2345',3",
            "'',2",
            "'   ',2",
            "'.5',2",
            "'-.5',2",
            "'1.2.3',2",
            "'1,5',2",
            "'abc',2",
            "'1e5',2",
            "'0x10',2",
            "'--1',2",
            "'1-',2",
            "'9223372036854775808',0",
            "'-9223372036854775809',0",
            "'999999999999999999999.00',2",
    })
    void rejectsMalformedLiterals(String text, int scale) {
        assertThrows(AllocationException.class, () -> Decimal.parseScaled(text, scale));
    }

    @Test
    @DisplayName("a value with more fractional digits is rejected, never silently rounded")
    void neverRoundsSilently() {
        AllocationException ex = assertThrows(AllocationException.class,
                () -> Decimal.parseScaled("100.005", 2));
        assertEquals("ALLOC_002", ex.code().code());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 7, 100})
    void rejectsUnsupportedScale(int scale) {
        assertThrows(AllocationException.class, () -> Decimal.parseScaled("1", scale));
    }

    @Test
    void rejectsOverlongText() {
        String huge = "1" + "0".repeat(200);
        assertThrows(AllocationException.class, () -> Decimal.parseScaled(huge, 2));
    }

    @ParameterizedTest(name = "format({0}, {1}) -> {2}")
    @CsvSource({
            "0,2,'0.00'",
            "1,2,'0.01'",
            "-1,2,'-0.01'",
            "10000,2,'100.00'",
            "-10000,2,'-100.00'",
            "12345,2,'123.45'",
            "7,6,'0.000007'",
            "1234567,6,'1.234567'",
            "-9223372036854775808,0,'-9223372036854775808'",
            "-9223372036854775808,2,'-92233720368547758.08'",
            "9223372036854775807,0,'9223372036854775807'",
    })
    void formatsFixedPoint(long units, int scale, String expected) {
        assertEquals(expected, Decimal.format(units, scale));
    }

    @Test
    @DisplayName("parse and format round-trip for every representable magnitude at every scale")
    void roundTrips() {
        long[] samples = {0, 1, -1, 9, -9, 10, -10, 99, 100, -100, 999_999, 1_000_000,
                123_456_789, -123_456_789, Long.MAX_VALUE, Long.MIN_VALUE};
        for (int scale = 0; scale <= 6; scale++) {
            for (long sample : samples) {
                String text = Decimal.format(sample, scale);
                assertEquals(sample, Decimal.parseScaled(text, scale),
                        "round-trip failed for " + sample + " at scale " + scale + " -> " + text);
            }
        }
    }

    @Test
    @DisplayName("only the reference character set counts as surrounding whitespace")
    void trimsOnlyReferenceWhitespace() {
        assertEquals(1L, Decimal.parseScaled("\u00A01\u00A0", 0), "NBSP is trimmed");
        assertEquals(1L, Decimal.parseScaled("\u30001\u3000", 0), "ideographic space is trimmed");
        assertThrows(AllocationException.class, () -> Decimal.parseScaled("\u001C1", 0),
                "U+001C is whitespace in Java but not in the reference implementation");
    }
}
