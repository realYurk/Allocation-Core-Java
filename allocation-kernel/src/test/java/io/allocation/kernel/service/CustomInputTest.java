package io.allocation.kernel.service;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.coord.Coord;
import io.allocation.kernel.exec.AllocationInput;
import io.allocation.kernel.exec.DriverRow;
import io.allocation.kernel.exec.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A host's own {@link AllocationInput}, through the service layer.
 *
 * <p>`docs/API.md` §6 has always told hosts whose data lives in a database to implement {@code
 * forEachAmount} and keep memory flat. Until {@code AmountSource.Custom} existed there was no way to hand
 * such an input to {@code submit} — the sealed source only accepted a directory or rows already decoded
 * into a list — so the advice was unactionable. These tests are the door, not the advice.
 */
class CustomInputTest {

    @TempDir
    Path directory;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(directory.resolve("rule.json"), TwoPoolRule.JSON, StandardCharsets.UTF_8);
    }

    @Test
    void aHostInputRunsAndIsStreamedRatherThanMaterialised() {
        HostInput input = new HostInput("sha-A");
        InMemoryRunRepository repository = new InMemoryRunRepository();
        DefaultAllocationTemplate template = template(repository);
        try {
            AllocationTemplate.SubmitResult submitted = template.submit(RunCommand.builder()
                    .ruleCode("rule")
                    .source(new AmountSource.Custom(input))
                    .build());
            template.await(submitted.runId(), Duration.ofSeconds(10));

            RunRepository.RunRecord record = repository.find(submitted.runId()).orElseThrow();
            assertEquals(RunRepository.Status.PUBLISHED, record.status());
            assertEquals(30_000L, record.inputAmount(), "100.00 + 200.00 at precision 2");
            assertEquals(30_000L, record.outputAmount());
            assertEquals(2, record.poolCount(), "one pool per plant");
            assertEquals(4, repository.resultCount(submitted.runId()));

            assertEquals(1, input.amountStreams, "the engine must use forEachAmount");
            assertTrue(input.driverStreams >= 1, "and forEachDriver");
        } finally {
            template.close();
        }
    }

    @Test
    void theSameKeyWithTheSameFingerprintIsAReplayAndWithADifferentOneIsAConflict() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        DefaultAllocationTemplate template = template(repository);
        try {
            AllocationTemplate.SubmitResult first = template.submit(command(new HostInput("sha-A"), "k1"));
            template.await(first.runId(), Duration.ofSeconds(10));

            AllocationTemplate.SubmitResult replay = template.submit(command(new HostInput("sha-A"), "k1"));
            assertEquals(first.runId(), replay.runId());
            assertTrue(replay.idempotentReplay());

            // The table changed between the two calls. Answering the second with the first's numbers is the
            // failure a fingerprint exists to prevent.
            AllocationException conflict = assertThrows(AllocationException.class,
                    () -> template.submit(command(new HostInput("sha-B"), "k1")));
            assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, conflict.code());
        } finally {
            template.close();
        }
    }

    @Test
    void aKeyOnAnInputThatCannotSayWhatItReadIsRefusedNotAnswered() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        DefaultAllocationTemplate template = template(repository);
        try {
            AllocationException refused = assertThrows(AllocationException.class,
                    () -> template.submit(command(new HostInput(null), "k2")));
            assertEquals(ErrorCode.IDEMPOTENCY_UNVERIFIABLE, refused.code());
            assertTrue(refused.detail().contains("amounts"), "the message names what is missing: "
                    + refused.detail());
            assertTrue(repository.findByIdempotencyKey("k2").isEmpty(),
                    "nothing may be recorded for a submission that was refused");
        } finally {
            template.close();
        }
    }

    @Test
    void withoutAKeyTheSameInputRunsFine() {
        // A fingerprint is only needed when a promise is made. A batch job that submits once should not
        // have to hash a table to satisfy a key it never used.
        HostInput input = new HostInput(null);
        InMemoryRunRepository repository = new InMemoryRunRepository();
        DefaultAllocationTemplate template = template(repository);
        try {
            AllocationTemplate.SubmitResult submitted = template.submit(RunCommand.builder()
                    .ruleCode("rule")
                    .source(new AmountSource.Custom(input))
                    .build());
            template.await(submitted.runId(), Duration.ofSeconds(10));

            RunRepository.RunRecord record = repository.find(submitted.runId()).orElseThrow();
            assertEquals(RunRepository.Status.PUBLISHED, record.status());
            assertEquals(30_000L, record.outputAmount());
        } finally {
            template.close();
        }
    }

    @Test
    void aCustomSourceNeedsAnInput() {
        assertThrows(IllegalArgumentException.class, () -> new AmountSource.Custom(null));
    }

    // ------------------------------------------------------------------ helpers

    private static RunCommand command(HostInput input, String key) {
        return RunCommand.builder()
                .ruleCode("rule")
                .source(new AmountSource.Custom(input))
                .idempotencyKey(key)
                .build();
    }

    private DefaultAllocationTemplate template(RunRepository repository) {
        return new DefaultAllocationTemplate(repository, new DirectoryRuleResolver(directory),
                directory.resolve("work"));
    }

    /**
     * What a host writes for a JDBC cursor: two streaming methods and a fingerprint.
     *
     * <p>{@code readAmounts} and {@code readDrivers} throw, because "the engine called the materialising
     * path" is exactly the regression this fixture exists to catch — a host that overrides the streaming
     * methods and still pays for a full list has gained nothing.
     */
    private static final class HostInput implements AllocationInput {

        private final String fingerprint;
        private int amountStreams;
        private int driverStreams;

        HostInput(String fingerprint) {
            this.fingerprint = fingerprint;
        }

        @Override
        public List<Row> readAmounts(String dataset) {
            throw new AssertionError("the engine must stream amounts, not materialise them");
        }

        @Override
        public void forEachAmount(String dataset, Consumer<Row> consumer) {
            amountStreams++;
            consumer.accept(new Row(new Coord(new String[]{"P1", "SRC"}), 10_000L, new String[]{"1"}));
            consumer.accept(new Row(new Coord(new String[]{"P2", "SRC"}), 20_000L, new String[]{"2"}));
        }

        @Override
        public List<DriverRow> readDrivers(String dataset, String weightField) {
            throw new AssertionError("the engine must stream drivers, not materialise them");
        }

        @Override
        public void forEachDriver(String dataset, String weightField, Consumer<DriverRow> consumer) {
            driverStreams++;
            consumer.accept(new DriverRow(new Coord(new String[]{"P1", "A1"}), 1_000_000L));
            consumer.accept(new DriverRow(new Coord(new String[]{"P1", "A2"}), 1_000_000L));
            consumer.accept(new DriverRow(new Coord(new String[]{"P2", "A1"}), 1_000_000L));
            consumer.accept(new DriverRow(new Coord(new String[]{"P2", "A2"}), 1_000_000L));
        }

        @Override
        public String fingerprint(String dataset, String weightField) {
            return fingerprint;
        }
    }
}
