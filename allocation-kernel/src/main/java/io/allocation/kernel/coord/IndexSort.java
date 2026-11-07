package io.allocation.kernel.coord;

/**
 * Stable merge sort over an {@code int[]} of indices with a primitive comparator.
 *
 * <p>Exists so that tie-breaking never boxes and never allocates per element: sorting a 10,000-target
 * pool with {@code Integer[]} would allocate 10,000 objects per pool. Stability is required — when two
 * targets share both remainder and identity (which only happens on duplicate coordinates, itself a
 * data defect) the earlier index must still win, exactly as in the reference implementation.
 */
public final class IndexSort {

    private IndexSort() {
    }

    /** Comparator over indices, receiving the two candidate positions. */
    @FunctionalInterface
    public interface IntComparator {
        int compare(int ia, int ib);
    }

    /** Sorts {@code a[0..len)} in place, stably. */
    public static void sort(int[] a, int len, IntComparator cmp) {
        if (len < 2) {
            return;
        }
        if (len <= 16) {
            insertionSort(a, len, cmp);
            return;
        }
        int[] buffer = new int[len];
        mergeSort(a, buffer, 0, len, cmp);
    }

    private static void insertionSort(int[] a, int len, IntComparator cmp) {
        for (int i = 1; i < len; i++) {
            int value = a[i];
            int j = i - 1;
            while (j >= 0 && cmp.compare(a[j], value) > 0) {
                a[j + 1] = a[j];
                j--;
            }
            a[j + 1] = value;
        }
    }

    private static void mergeSort(int[] a, int[] buffer, int from, int to, IntComparator cmp) {
        int n = to - from;
        if (n <= 16) {
            for (int i = from + 1; i < to; i++) {
                int value = a[i];
                int j = i - 1;
                while (j >= from && cmp.compare(a[j], value) > 0) {
                    a[j + 1] = a[j];
                    j--;
                }
                a[j + 1] = value;
            }
            return;
        }
        int mid = (from + to) >>> 1;
        mergeSort(a, buffer, from, mid, cmp);
        mergeSort(a, buffer, mid, to, cmp);
        if (cmp.compare(a[mid - 1], a[mid]) <= 0) {
            return;
        }
        System.arraycopy(a, from, buffer, from, n);
        int left = from;
        int right = mid;
        int write = from;
        while (left < mid && right < to) {
            if (cmp.compare(buffer[right], buffer[left]) < 0) {
                a[write++] = buffer[right++];
            } else {
                a[write++] = buffer[left++];
            }
        }
        while (left < mid) {
            a[write++] = buffer[left++];
        }
    }
}
