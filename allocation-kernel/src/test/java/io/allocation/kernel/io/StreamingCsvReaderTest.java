package io.allocation.kernel.io;

import io.allocation.kernel.AllocationException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential test: the streaming reader must agree with the reference reader, record for record.
 *
 * <p>The point of a second parser is a second chance to get it wrong, so it is not enough to test the
 * streaming reader against hand-written expectations — those expectations would drift with it. Every case
 * here is asserted against {@link CsvReader}, which is the definition of the dialect, and every case is
 * replayed at buffer sizes from one byte upwards so that a record, a field, a quoted CRLF and a multi-byte
 * character all get split at every possible offset.
 *
 * <p>One byte is not a curiosity: it is the only size that exercises every boundary, and the bugs this
 * class could have are exactly boundary bugs.
 */
class StreamingCsvReaderTest {

    /** Sizes chosen to be coprime with typical field lengths, plus one to force every split. */
    private static final int[] BUFFER_SIZES = {1, 2, 3, 5, 7, 8, 13, 64, 4_096, 65_536};

    @Test
    void agreesWithTheReferenceReaderOnEveryCorpusFile() throws IOException {
        Path corpus = Path.of("..", "allocation-conformance", "src", "test", "resources", "corpus");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(corpus)) {
            files = walk.filter(path -> path.toString().endsWith(".csv")).toList();
        }

        assertTrue(files.size() >= 30, "expected the frozen corpus to be present, found " + files.size());
        for (Path file : files) {
            assertSameDialect(Files.readAllBytes(file), file.toString());
        }
    }

    @Test
    void agreesOnEveryDialectCase() {
        for (TestCase testCase : cases()) {
            assertSameDialect(testCase.document(), testCase.name());
        }
    }

    @Test
    void agreesOnAFewHundredThousandRows() {
        StringBuilder sb = new StringBuilder(8 << 20);
        sb.append("plant,account,amount\n");
        for (int i = 0; i < 200_000; i++) {
            sb.append("P").append(i % 97).append(",A").append(i % 13).append(',').append(i).append('\n');
        }
        byte[] document = sb.toString().getBytes(StandardCharsets.UTF_8);

        List<List<String>> expected = drain(new CsvReader(document));
        // A buffer far smaller than a record, to prove the collector is not quadratic in practice.
        assertEquals(expected, drain(new StreamingCsvReader(new ByteArrayInputStream(document), 4_096)));
        assertEquals(200_001, expected.size());
    }

    @Test
    void rejectsABareQuoteWithTheSameMessageAsTheReference() {
        byte[] document = "a,b\nx\"y,2\n".getBytes(StandardCharsets.UTF_8);

        AllocationException expected = assertThrows(AllocationException.class, () -> drain(new CsvReader(document)));
        AllocationException actual = assertThrows(AllocationException.class,
                () -> drain(new StreamingCsvReader(new ByteArrayInputStream(document), 3)));

        assertEquals(expected.getMessage(), actual.getMessage());
        assertEquals(expected.code(), actual.code());
    }

    @Test
    void rejectsAnUnterminatedQuoteWithTheSameMessageAsTheReference() {
        byte[] document = "a,b\n\"x,2\n".getBytes(StandardCharsets.UTF_8);

        AllocationException expected = assertThrows(AllocationException.class, () -> drain(new CsvReader(document)));
        AllocationException actual = assertThrows(AllocationException.class,
                () -> drain(new StreamingCsvReader(new ByteArrayInputStream(document), 2)));

        assertEquals(expected.getMessage(), actual.getMessage());
    }

    @Test
    void numbersRecordsTheSameWayIncludingSkippedBlankLines() {
        byte[] document = "a,b\n\n1,2\n\n3,4\n".getBytes(StandardCharsets.UTF_8);

        CsvReader reference = new CsvReader(document);
        StreamingCsvReader streaming = new StreamingCsvReader(new ByteArrayInputStream(document), 1);
        while (true) {
            List<String> left = reference.read();
            List<String> right = streaming.read();
            assertEquals(left, right);
            if (left == null) {
                break;
            }
            assertEquals(reference.recordNumber(), streaming.recordNumber());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void assertSameDialect(byte[] document, String what) {
        List<List<String>> expected = drain(new CsvReader(document));
        for (int size : BUFFER_SIZES) {
            List<List<String>> actual = drain(new StreamingCsvReader(new ByteArrayInputStream(document), size));
            assertEquals(expected, actual, what + " differs at buffer size " + size);
        }
    }

    private static List<List<String>> drain(CsvReader reader) {
        List<List<String>> out = new ArrayList<>();
        List<String> record;
        while ((record = reader.read()) != null) {
            out.add(record);
        }
        return out;
    }

    private static List<List<String>> drain(StreamingCsvReader reader) {
        List<List<String>> out = new ArrayList<>();
        try (reader) {
            List<String> record;
            while ((record = reader.read()) != null) {
                out.add(record);
            }
        }
        return out;
    }

    private record TestCase(String name, byte[] document) {
        static TestCase of(String name, String text) {
            return new TestCase(name, text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static List<TestCase> cases() {
        return List.of(
                TestCase.of("plain", "a,b\n1,2\n"),
                TestCase.of("crlf endings", "a,b\r\n1,2\r\n"),
                TestCase.of("no trailing newline", "a,b\n1,2"),
                TestCase.of("blank lines", "a,b\n\n1,2\n\n3,4\n\n"),
                TestCase.of("quoted comma", "a,b\n\"x,y\",2\n"),
                TestCase.of("doubled quote", "a,b\n\"he said \"\"hi\"\"\",2\n"),
                TestCase.of("crlf inside quotes", "a,b\n\"line\r\nbreak\",2\n"),
                TestCase.of("lone cr inside quotes", "a,b\n\"line\rbreak\",2\n"),
                TestCase.of("lone cr in a field", "a,b\nx\ry,2\n"),
                TestCase.of("empty quoted fields", "a,b\n\"\",\"\"\n"),
                TestCase.of("spaces are content", "a,b\n x , y \n"),
                TestCase.of("empty unquoted fields", "a,b\n,\n"),
                TestCase.of("trailing empty field", "a,b,c\n1,2,\n"),
                TestCase.of("non-bmp outside quotes", "d\n𝄞\n"),
                TestCase.of("non-bmp inside quotes", "d\n\"𝄞和\"\n"),
                TestCase.of("single column", "amount\n1\n2\n"),
                TestCase.of("only a newline", "\n"),
                TestCase.of("empty document", ""),
                TestCase.of("record ending in a quote", "a,b\n1,\"x\"\n"),
                TestCase.of("many columns", "a,b,c,d,e\n1,2,3,4,5\n"));
    }

    /** Guards the assumption that the corpus really does contain the awkward cases. */
    @Test
    void corpusContainsQuotedAndUnicodeData() throws IOException {
        Path corpus = Path.of("..", "allocation-conformance", "src", "test", "resources", "corpus");
        String text;
        try (Stream<Path> walk = Files.walk(corpus)) {
            StringBuilder all = new StringBuilder();
            for (Path path : walk.filter(p -> p.toString().endsWith(".csv")).toList()) {
                all.append(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            }
            text = all.toString();
        }

        assertTrue(text.contains("\""), "no quoted field anywhere in the corpus");
        assertTrue(text.chars().anyMatch(c -> c > 0x7f), "no non-ascii byte anywhere in the corpus");
    }
}
