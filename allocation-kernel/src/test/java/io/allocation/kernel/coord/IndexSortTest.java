package io.allocation.kernel.coord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The primitive-index sort used by tie-breaking.
 *
 * <p>{@link IndexSort} exists for two reasons the engine cannot do without: it never boxes an
 * {@code Integer} (a 10,000-target pool would otherwise allocate 10,000 objects), and it is
 * <em>stable</em> — when two targets share both remainder and identity, the earlier index must win,
 * exactly as the reference implementation does. A non-stable sort would silently move the residual
 * penny on duplicate coordinates.
 */
class IndexSortTest {

    @Test
    @DisplayName("an empty slice and a single element are already sorted")
    void trivialSlicesAreNoOps() {
        int[] empty = {};
        IndexSort.sort(empty, 0, byKey(empty));
        assertArrayEquals(new int[0], empty);

        int[] single = {42};
        IndexSort.sort(single, 1, byKey(single));
        assertArrayEquals(new int[] {42}, single);
    }

    @Test
    @DisplayName("sorts indices by key through the insertion-sort path")
    void sortsSmallArrays() {
        int[] keys = {3, 1, 2, 0};
        int[] indices = {0, 1, 2, 3};
        IndexSort.sort(indices, 4, byKey(keys));
        assertArrayEquals(new int[] {3, 1, 2, 0}, indices);
    }

    @Test
    @DisplayName("sorts indices by key through the merge-sort path (past the 16 threshold)")
    void sortsLargeArrays() {
        int n = 100;
        int[] keys = new int[n];
        int[] indices = new int[n];
        for (int i = 0; i < n; i++) {
            keys[i] = (i * 37) % 100; // a permutation, not sorted
            indices[i] = i;
        }
        int[] expected = expectedSorted(indices, keys);
        IndexSort.sort(indices, n, byKey(keys));
        assertArrayEquals(expected, indices);
    }

    @Test
    @DisplayName("is stable: equal keys keep their original index order")
    void isStable() {
        int[] keys = {1, 1, 0, 1, 0};
        int[] indices = {0, 1, 2, 3, 4};
        IndexSort.sort(indices, 5, byKey(keys));
        // Keys {1,1,0,1,0} sort to {0,0,1,1,1}; the zero-key indices 2 then 4, the one-key 0,1,3.
        assertArrayEquals(new int[] {2, 4, 0, 1, 3}, indices);
    }

    @Test
    @DisplayName("stability holds across the merge threshold with many duplicate keys")
    void isStableAcrossMergeThreshold() {
        int n = 64;
        int[] keys = new int[n];
        int[] indices = new int[n];
        for (int i = 0; i < n; i++) {
            keys[i] = i % 4; // four keys, sixteen copies each
            indices[i] = i;
        }
        IndexSort.sort(indices, n, byKey(keys));
        int i = 0;
        while (i < n) {
            int key = keys[indices[i]];
            int j = i;
            while (j < n && keys[indices[j]] == key) {
                j++;
            }
            for (int k = i + 1; k < j; k++) {
                int finalK = k;
                assertTrue(indices[k - 1] < indices[k], "stability violated for key " + key + " at " + finalK);
            }
            i = j;
        }
    }

    @Test
    @DisplayName("an already sorted array is left untouched, even past the threshold")
    void alreadySortedIsShortCircuited() {
        int n = 32;
        int[] keys = new int[n];
        int[] indices = new int[n];
        for (int i = 0; i < n; i++) {
            keys[i] = i;
            indices[i] = i;
        }
        int[] copy = Arrays.copyOf(indices, n);
        IndexSort.sort(indices, n, byKey(keys));
        assertArrayEquals(copy, indices);
    }

    @Test
    @DisplayName("matches Arrays.sort on random input, which a buggy sort cannot")
    void agreesWithArraysSort() {
        Random random = new Random(20261109);
        for (int round = 0; round < 200; round++) {
            int n = 1 + random.nextInt(120);
            int[] keys = new int[n];
            int[] indices = new int[n];
            for (int i = 0; i < n; i++) {
                keys[i] = random.nextInt(1000);
                indices[i] = i;
            }
            int[] expected = expectedSorted(indices, keys);
            IndexSort.sort(indices, n, byKey(keys));
            assertArrayEquals(expected, indices, "round " + round + ", n=" + n);
        }
    }

    /** The reference result via {@link Arrays#sort}, which is stable and correct. */
    private static int[] expectedSorted(int[] indices, int[] keys) {
        Integer[] boxed = new Integer[indices.length];
        for (int i = 0; i < indices.length; i++) {
            boxed[i] = indices[i];
        }
        Arrays.sort(boxed, (a, b) -> Integer.compare(keys[a], keys[b]));
        int[] result = new int[indices.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = boxed[i];
        }
        return result;
    }

    private static IndexSort.IntComparator byKey(int[] keys) {
        return (ia, ib) -> Integer.compare(keys[ia], keys[ib]);
    }
}
