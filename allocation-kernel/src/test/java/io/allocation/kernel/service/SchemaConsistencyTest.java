package io.allocation.kernel.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three dialects must describe the same schema.
 *
 * <p>CI can execute one of them — SQLite, because it is embeddable. The other two are the ones that
 * actually get deployed (MySQL in a RuoYi application, PostgreSQL where the financial team runs), and
 * until now the only thing keeping them in step with SQLite was that somebody looked. That is not a
 * mechanism: a column added to one file and not the others compiles, passes every test, and fails on the
 * customer's database at the worst possible moment — the first deploy.
 *
 * <p>What this proves is narrow and worth stating precisely: the three files declare the same tables with
 * the same columns in the same order. It does <em>not</em> prove MySQL will accept the syntax; only a
 * server does that, and CI has none. Column types are also not compared, because they legitimately
 * differ ({@code TEXT} versus {@code VARCHAR(1024)}, {@code INTEGER} versus {@code BIGINT} returning the
 * same Java value). Comparing names catches the mistake that actually happens — a forgotten column —
 * without forcing the dialects into a shape none of them wants.
 */
class SchemaConsistencyTest {

    /** A new dialect is added here on purpose: silently including a file would also include migrations. */
    private static final List<Path> DIALECTS = List.of(
            Path.of("..", "ddl", "allocation-sqlite.sql"),
            Path.of("..", "ddl", "allocation-mysql8.sql"),
            Path.of("..", "ddl", "allocation-postgres.sql"));

    /** Table constraints, which start a line inside the parentheses but are not columns. */
    private static final Set<String> NOT_COLUMNS =
            Set.of("PRIMARY", "UNIQUE", "KEY", "CONSTRAINT", "INDEX", "FOREIGN", "CHECK");

    @Test
    void everyDialectDeclaresTheSameTables() {
        Map<Path, Map<String, List<String>>> schemas = schemas();

        Set<String> expected = new TreeSet<>(schemas.get(DIALECTS.get(0)).keySet());
        assertTrue(expected.containsAll(List.of("allocation_run", "allocation_pool",
                "allocation_line", "allocation_result")), "the engine's tables: " + expected);

        for (Path dialect : DIALECTS) {
            assertEquals(expected, new TreeSet<>(schemas.get(dialect).keySet()),
                    dialect.getFileName() + " declares a different set of tables");
        }
    }

    @Test
    void everyDialectDeclaresTheSameColumns() {
        Map<Path, Map<String, List<String>>> schemas = schemas();
        Map<String, List<String>> reference = schemas.get(DIALECTS.get(0));

        for (Map.Entry<String, List<String>> table : reference.entrySet()) {
            for (Path dialect : DIALECTS) {
                assertEquals(table.getValue(), schemas.get(dialect).get(table.getKey()),
                        dialect.getFileName() + " disagrees about the columns of " + table.getKey());
            }
        }
    }

    @Test
    void everyTableHasAPrimaryKey() {
        // Not a parsing formality: the repository relies on a duplicate insert failing, and a table
        // without a primary key would let a second write of the same evidence rows succeed.
        for (Path dialect : DIALECTS) {
            String text = read(dialect);
            assertTrue(text.contains("PRIMARY KEY"), dialect.getFileName() + " declares no primary key");
        }
    }

    // ---------------------------------------------------------------- parsing

    private static Map<Path, Map<String, List<String>>> schemas() {
        Map<Path, Map<String, List<String>>> out = new LinkedHashMap<>();
        for (Path dialect : DIALECTS) {
            out.put(dialect, tables(read(dialect)));
        }
        return out;
    }

    private static String read(Path dialect) {
        assertTrue(Files.isRegularFile(dialect), "missing " + dialect.toAbsolutePath());
        try {
            return Files.readString(dialect, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("cannot read " + dialect, e);
        }
    }

    /**
     * Table name to column names, in declaration order.
     *
     * <p>Blunt on purpose, and the same trade {@code SchemaFixture.statements} makes: the shipped DDL has
     * one column per line and no nested parentheses that span lines, so a real SQL parser would be more
     * machinery than the file justifies. It is written to fail loudly — a {@code CREATE TABLE} whose body
     * this cannot follow yields a table with no columns, which fails the comparison above.
     */
    static Map<String, List<String>> tables(String text) {
        Map<String, List<String>> tables = new LinkedHashMap<>();
        String current = null;
        List<String> columns = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("--") || line.isEmpty()) {
                continue;
            }
            if (current == null) {
                String name = createdTable(line);
                if (name != null) {
                    current = name;
                    columns = new ArrayList<>();
                }
                continue;
            }
            if (line.startsWith(")")) {
                tables.put(current, List.copyOf(columns));
                current = null;
                columns = null;
                continue;
            }
            String token = line.split("[\\s,(]", 2)[0].replace("`", "").replace("\"", "");
            if (token.isEmpty() || NOT_COLUMNS.contains(token.toUpperCase(java.util.Locale.ROOT))) {
                continue;
            }
            columns.add(token);
        }
        return tables;
    }

    /** {@code CREATE TABLE IF NOT EXISTS allocation_run (} to {@code allocation_run}, or null. */
    private static String createdTable(String line) {
        String upper = line.toUpperCase(java.util.Locale.ROOT);
        if (!upper.startsWith("CREATE TABLE")) {
            return null;
        }
        String rest = line.substring("CREATE TABLE".length()).trim();
        if (rest.toUpperCase(java.util.Locale.ROOT).startsWith("IF NOT EXISTS")) {
            rest = rest.substring("IF NOT EXISTS".length()).trim();
        }
        int end = rest.indexOf('(');
        return end < 0 ? null : rest.substring(0, end).trim().replace("`", "").replace("\"", "");
    }
}
