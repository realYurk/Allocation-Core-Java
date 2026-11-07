package io.allocation.kernel.exec;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.io.CsvWriteException;
import io.allocation.kernel.plan.Plan;
import io.allocation.kernel.plan.Planner;
import io.allocation.kernel.spec.AllocationSpec;
import io.allocation.kernel.spec.SpecCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * End-to-end entry point: a rule file in, an evidence directory out.
 *
 * <h2>Publishing</h2>
 * Artefacts are written into a sibling staging directory and the whole directory is renamed into place
 * only after the run passes. There is no moment at which a caller can observe a partially written result
 * set, and an aborted run leaves no directory at all. Renaming a directory is atomic on both POSIX
 * filesystems and NTFS, which is why this is preferred over writing files in place and deleting on
 * failure.
 *
 * <p>Refusing to overwrite an existing output directory is a feature: two runs must never share a
 * directory, because the evidence of the second would silently replace the evidence of the first.
 */
public final class AllocationRunner {

    private AllocationRunner() {
    }

    /**
     * Runs the rule at {@code rulePath} into {@code outputDir}.
     *
     * @param rulePath  path to the {@code allocation-ir/2.0} document
     * @param outputDir directory to create; must not already exist
     * @param options   execution options
     * @return the run summary
     */
    public static RunSummary run(Path rulePath, Path outputDir, Executor.Options options) {
        byte[] ruleBytes;
        try {
            ruleBytes = Files.readAllBytes(rulePath);
        } catch (IOException e) {
            throw new AllocationException(ErrorCode.RULE_INVALID, "cannot read rule " + rulePath, e);
        }
        String ruleText = new String(ruleBytes, StandardCharsets.UTF_8);
        String ruleSha = SpecCodec.sha256Hex(ruleBytes);
        AllocationSpec spec = SpecCodec.parse(ruleText);
        Plan plan = Planner.compile(spec);

        if (Files.exists(outputDir)) {
            throw new AllocationException(ErrorCode.INTERNAL,
                    "output directory already exists, choose a new folder: " + outputDir);
        }
        Path parent = outputDir.toAbsolutePath().getParent();
        Path staging;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            staging = Files.createTempDirectory(parent, ".allocation-incomplete-");
        } catch (IOException e) {
            throw new AllocationException(ErrorCode.INTERNAL, "cannot create staging directory", e);
        }

        CsvInput input = CsvInput.of(rulePath.toAbsolutePath().getParent(), spec);
        CsvDirectorySink sink = CsvDirectorySink.create(staging, spec);
        RunSummary summary;
        try {
            summary = Executor.execute(plan, ruleSha, input, sink, options);
        } catch (RuntimeException failure) {
            sink.abort();
            deleteQuietly(staging);
            throw failure;
        }
        // Files must be closed before the directory can be renamed, on every platform.
        sink.close();
        try {
            Files.writeString(staging.resolve("run_summary.json"), summary.toJson(), StandardCharsets.UTF_8);
            publish(staging, outputDir);
            return summary;
        } catch (IOException | RuntimeException failure) {
            deleteQuietly(staging);
            throw failure instanceof AllocationException allocationException
                    ? allocationException
                    : new AllocationException(ErrorCode.INTERNAL, "cannot publish output directory", failure);
        }
    }

    /** Renames the staging directory into place, falling back to a plain move when atomic is unavailable. */
    private static void publish(Path staging, Path outputDir) throws IOException {
        try {
            Files.move(staging, outputDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(staging, outputDir);
        }
    }

    /** Runs sequentially. */
    public static RunSummary run(Path rulePath, Path outputDir) {
        return run(rulePath, outputDir, Executor.Options.sequential());
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort: the staging name marks it as not-a-result regardless
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
