package io.allocation.spring;

import io.allocation.kernel.exec.Executor;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Everything a host may configure, under the {@code allocation} prefix.
 *
 * <p>Deliberately small. A starter that exposes thirty knobs is a starter whose defaults nobody has
 * tested; these are the five decisions that genuinely differ between deployments, and every one of them
 * has a default that works out of the box.
 */
@ConfigurationProperties(prefix = "allocation")
public class AllocationProperties {

    /**
     * Where runs write their evidence (difference reports, exported files).
     *
     * <p>Required to be a directory the process can create and delete: every run publishes through a
     * staging directory and an atomic rename, which means a network share without atomic rename will
     * fall back to a plain move rather than fail.
     */
    private Path workingDirectory = Path.of("allocation-data");

    /** Directory holding {@code <ruleCode>.json} rule documents. */
    private Path rulesDirectory = Path.of("allocation-rules");

    /**
     * Default pool-level parallelism for runs that do not specify their own.
     *
     * <p>Never changes the output — only the wall clock.
     */
    private int workers = 1;

    /**
     * Rows a stage buffer may hold in memory before it spills to a temp file.
     *
     * <p>The one setting that decides whether a very large run finishes: peak heap tracks this number,
     * not the input size. Lower it in a small container; raise it to avoid disk I/O in a batch job.
     */
    private long maxRowsInMemory = Executor.Options.DEFAULT_MAX_ROWS_IN_MEMORY;

    /**
     * How many runs may be queued before submissions are refused with {@code ALLOC_007}.
     *
     * <p>A bound is not a throttle: it is the difference between "busy" and an out-of-memory outage
     * caused by a thousand queued runs each holding an input.
     */
    private int queueCapacity = 16;

    public Path getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public Path getRulesDirectory() {
        return rulesDirectory;
    }

    public void setRulesDirectory(Path rulesDirectory) {
        this.rulesDirectory = rulesDirectory;
    }

    public int getWorkers() {
        return workers;
    }

    public void setWorkers(int workers) {
        this.workers = workers;
    }

    public long getMaxRowsInMemory() {
        return maxRowsInMemory;
    }

    public void setMaxRowsInMemory(long maxRowsInMemory) {
        this.maxRowsInMemory = maxRowsInMemory;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }
}
