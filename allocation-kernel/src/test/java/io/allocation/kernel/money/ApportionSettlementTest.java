package io.allocation.kernel.money;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ties the explanation path to the allocation path.
 *
 * <p>{@link Apportion#settlement} exists so an explanation can name the recipient of a residual unit
 * without a second implementation of the tie-break rule. That only holds if the order it reports is the
 * order the split actually used, which is what these tests assert: whoever is within
 * {@code |residual|} of the front of the queue is exactly whoever received a unit.
 */
class ApportionSettlementTest {

    private static void assertAgreesWithSplit(long total, long[] weights, String[] identities) {
        long[] allocated = Apportion.split(total, weights, identities, false);
        Apportion.Settlement settlement = Apportion.settlement(total, weights, identities, false);
        long[] truncated = new long[weights.length];
        long denominator = 0L;
        for (long weight : weights) {
            denominator += weight;
        }
        long sum = 0L;
        for (int i = 0; i < weights.length; i++) {
            truncated[i] = Math.multiplyExact(total, weights[i]) / denominator;
            sum += truncated[i];
        }
        long residual = total - sum;
        long magnitude = Math.abs(residual);

        assertEquals(residual, settlement.residual(), "residual must match a plain truncating split");
        for (int i = 0; i < weights.length; i++) {
            boolean receivedResidual = allocated[i] != truncated[i];
            boolean explainedResidual = settlement.rank()[i] < magnitude;
            assertEquals(receivedResidual, explainedResidual,
                    "entry " + i + " of " + total + " over " + java.util.Arrays.toString(weights));
            assertEquals(allocated[i], truncated[i] + (receivedResidual ? (residual > 0 ? 1 : -1) : 0));
        }
    }

    @Test
    void orderExplainsWhoReceivedTheResidual() {
        assertAgreesWithSplit(100L, new long[]{1, 1, 1}, new String[]{"A", "B", "C"});
        assertAgreesWithSplit(101L, new long[]{1, 1, 1}, new String[]{"A", "B", "C"});
        assertAgreesWithSplit(-101L, new long[]{1, 1, 1}, new String[]{"A", "B", "C"});
        assertAgreesWithSplit(10_000L, new long[]{1_000_000L, 2_000_000L}, new String[]{"A", "B"});
        assertAgreesWithSplit(7L, new long[]{3, 5, 7, 11}, new String[]{"W", "X", "Y", "Z"});
        assertAgreesWithSplit(-7L, new long[]{3, 5, 7, 11}, new String[]{"W", "X", "Y", "Z"});
    }

    @Test
    void ranksAreDenseAndUnique() {
        Apportion.Settlement settlement = Apportion.settlement(1000L,
                new long[]{1, 1, 1, 1}, new String[]{"D", "C", "B", "A"}, false);

        boolean[] seen = new boolean[4];
        for (int rank : settlement.rank()) {
            assertTrue(rank >= 0 && rank < 4, "rank out of range: " + rank);
            assertTrue(!seen[rank], "duplicate rank " + rank);
            seen[rank] = true;
        }
    }

    @Test
    void tiesBreakOnIdentityNotOnInputOrder() {
        Apportion.Settlement forward = Apportion.settlement(100L,
                new long[]{1, 1, 1}, new String[]{"A", "B", "C"}, false);
        Apportion.Settlement reversed = Apportion.settlement(100L,
                new long[]{1, 1, 1}, new String[]{"C", "B", "A"}, false);

        // A is first in line in both, regardless of where it sat in the array.
        int rankOfAForward = forward.rank()[0];
        int rankOfAReversed = reversed.rank()[2];
        assertEquals(0, rankOfAForward);
        assertEquals(0, rankOfAReversed);
    }
}
