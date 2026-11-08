package io.allocation.bench;

import io.allocation.kernel.money.Apportion;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * The kernel's hot path: one pool's apportionment.
 *
 * <p>Everything the engine does per pool is bounded by this function, so its throughput is the number
 * that decides whether a million-row run takes seconds or minutes. Three shapes are measured because they
 * fail differently:
 *
 * <ul>
 *   <li>{@code split} — the allocating entry point, which is what a caller holding no scratch space
 *       uses.</li>
 *   <li>{@code splitInto} — the same arithmetic into a reused buffer. The gap between the two is the
 *       cost of the allocation, not of the maths, and it is the reason the executor keeps a workspace
 *       per worker.</li>
 *   <li>{@code settlementOrder} — the tie-break, which is only paid once per pool but sorts every
 *       target.</li>
 * </ul>
 *
 * <p>{@code targets} covers the practical range: 8 (a small cost centre), 64 (a plant), 512 (a wide
 * report). Beyond that a pool is not a pool, it is a dataset, and the answer is to fix the rule.
 *
 * <p>Run it with:
 * <pre>
 * mvn -q -pl allocation-bench -am package
 * java -jar allocation-bench/target/allocation-bench-1.0.0-SNAPSHOT.jar -f 1 -wi 3 -i 5
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
@State(Scope.Thread)
public class ApportionBench {

    @Param({"8", "64", "512"})
    public int targets;

    private long[] weights;
    private String[] identities;
    private long[] out;
    private Apportion.Workspace workspace;

    /** A pool amount in cents chosen so the split does not divide evenly: residuals must be settled. */
    private long total;

    @Setup
    public void setUp() {
        weights = new long[targets];
        identities = new String[targets];
        out = new long[targets];
        workspace = new Apportion.Workspace();
        long sum = 0L;
        for (int i = 0; i < targets; i++) {
            // Uneven weights, so the remainder path is exercised rather than a clean division.
            weights[i] = (i % 7) + 1L;
            identities[i] = "P1|A" + i;
            sum += weights[i];
        }
        total = sum * 1_000L + 7L;
    }

    @Benchmark
    public long[] split() {
        return Apportion.split(total, weights, identities, false);
    }

    @Benchmark
    public long[] splitReusingBuffer() {
        Apportion.splitInto(total, weights, identities, false, out, workspace);
        return out;
    }

    @Benchmark
    public int[] settlementOrder() {
        return Apportion.settlementOrder(total, weights, identities, false);
    }
}
