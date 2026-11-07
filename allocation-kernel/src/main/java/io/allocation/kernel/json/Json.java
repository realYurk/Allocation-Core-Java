package io.allocation.kernel.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, strict JSON reader and writer.
 *
 * <p>The kernel deliberately carries no JSON dependency. That keeps {@code allocation-kernel} a
 * zero-dependency jar and, more importantly, keeps the parser's strictness under our control: a rule
 * file with a typo is a financial incident, so unknown keys, duplicate keys, trailing data and
 * non-integral numbers are all rejected rather than ignored.
 *
 * <p>Output escaping follows the Go reference encoder byte for byte — including {@code <}, {@code >}
 * and {@code &} escaped as {@code \}{@code u003c} style sequences — because reconciliation compares
 * generated documents byte-wise.
 */
public final class Json {

    private static final int MAX_DEPTH = 512;

    private Json() {
    }

    /**
     * Parses exactly one JSON value from {@code text}.
     *
     * @throws JsonException on malformed input, duplicate keys, or trailing content
     */
    public static JsonValue parse(String text) {
        Parser parser = new Parser(text);
        JsonValue value = parser.parseValue(0);
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("unexpected trailing content");
        }
        return value;
    }

    /** Compact serialisation. */
    public static String write(JsonValue value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value, -1, 0);
        return sb.toString();
    }

    /** Serialisation with two-space indentation, matching the reference summary format. */
    public static String writeIndented(JsonValue value, String indent) {
        StringBuilder sb = new StringBuilder(1024);
        writeValue(sb, value, indent.length(), 0);
        return sb.toString();
    }

    /**
     * Escapes a string into a JSON literal, opening and closing it with quotes.
     *
     * <p>Matches Go's {@code encoding/json} with HTML escaping enabled, which is what produces the
     * canonical coordinate keys used for ordering and row identifiers.
     */
    public static void writeString(StringBuilder out, String s) {
        out.append('"');
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == '<' || c == '>' || c == '&') {
                        appendUnicodeEscape(out, c);
                    } else if (Character.isSurrogate(c)) {
                        if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                            out.append(c).append(s.charAt(i + 1));
                            i++;
                        } else {
                            // Lone surrogate: the reference encoder substitutes U+FFFD.
                            appendUnicodeEscape(out, '\uFFFD');
                        }
                    } else if (c == '\u2028' || c == '\u2029') {
                        appendUnicodeEscape(out, c);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** Returns a quoted JSON string literal. */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        writeString(sb, s);
        return sb.toString();
    }

    private static void appendUnicodeEscape(StringBuilder out, char c) {
        out.append("\\u");
        String hex = Integer.toHexString(c);
        for (int i = hex.length(); i < 4; i++) {
            out.append('0');
        }
        out.append(hex);
    }

    private static void writeValue(StringBuilder out, JsonValue value, int indentWidth, int depth) {
        switch (value) {
            case JsonValue.Null ignored -> out.append("null");
            case JsonValue.Bool b -> out.append(b.value());
            case JsonValue.Num n -> out.append(n.raw());
            case JsonValue.Str s -> writeString(out, s.value());
            case JsonValue.Arr a -> {
                if (a.items().isEmpty()) {
                    out.append("[]");
                    return;
                }
                out.append('[');
                for (int i = 0; i < a.items().size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    newline(out, indentWidth, depth + 1);
                    writeValue(out, a.items().get(i), indentWidth, depth + 1);
                }
                newline(out, indentWidth, depth);
                out.append(']');
            }
            case JsonValue.Obj o -> {
                if (o.fields().isEmpty()) {
                    out.append("{}");
                    return;
                }
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, JsonValue> entry : o.fields().entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    newline(out, indentWidth, depth + 1);
                    writeString(out, entry.getKey());
                    out.append(':');
                    if (indentWidth >= 0) {
                        out.append(' ');
                    }
                    writeValue(out, entry.getValue(), indentWidth, depth + 1);
                }
                newline(out, indentWidth, depth);
                out.append('}');
            }
        }
    }

    private static void newline(StringBuilder out, int indentWidth, int depth) {
        if (indentWidth < 0) {
            return;
        }
        out.append('\n');
        for (int i = 0; i < indentWidth * depth; i++) {
            out.append(' ');
        }
    }

    /** Recursive-descent parser over a character buffer. */
    private static final class Parser {

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        JsonException error(String message) {
            return new JsonException(message + " at offset " + pos);
        }

        JsonValue parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("document nested deeper than " + MAX_DEPTH);
            }
            skipWhitespace();
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> parseObject(depth);
                case '[' -> parseArray(depth);
                case '"' -> new JsonValue.Str(parseString());
                case 't' -> parseKeyword("true", new JsonValue.Bool(true));
                case 'f' -> parseKeyword("false", new JsonValue.Bool(false));
                case 'n' -> parseKeyword("null", JsonValue.Null.INSTANCE);
                default -> parseNumber();
            };
        }

        private JsonValue parseKeyword(String keyword, JsonValue value) {
            if (!text.startsWith(keyword, pos)) {
                throw error("invalid literal");
            }
            pos += keyword.length();
            return value;
        }

        private JsonValue.Obj parseObject(int depth) {
            pos++; // '{'
            Map<String, JsonValue> fields = new LinkedHashMap<>();
            skipWhitespace();
            if (!atEnd() && text.charAt(pos) == '}') {
                pos++;
                return new JsonValue.Obj(fields);
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || text.charAt(pos) != '"') {
                    throw error("expected object key");
                }
                String key = parseString();
                if (fields.containsKey(key)) {
                    throw error("duplicate key \"" + key + "\"");
                }
                skipWhitespace();
                if (atEnd() || text.charAt(pos) != ':') {
                    throw error("expected ':'");
                }
                pos++;
                fields.put(key, parseValue(depth + 1));
                skipWhitespace();
                if (atEnd()) {
                    throw error("unterminated object");
                }
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return new JsonValue.Obj(fields);
                } else {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private JsonValue.Arr parseArray(int depth) {
            pos++; // '['
            List<JsonValue> items = new ArrayList<>();
            skipWhitespace();
            if (!atEnd() && text.charAt(pos) == ']') {
                pos++;
                return new JsonValue.Arr(items);
            }
            while (true) {
                items.add(parseValue(depth + 1));
                skipWhitespace();
                if (atEnd()) {
                    throw error("unterminated array");
                }
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return new JsonValue.Arr(items);
                } else {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private String parseString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw error("unterminated escape");
                    }
                    char esc = text.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> sb.append(parseUnicodeEscape());
                        default -> throw error("invalid escape \\" + esc);
                    }
                    continue;
                }
                if (c < 0x20) {
                    throw error("unescaped control character U+" + String.format("%04X", (int) c));
                }
                sb.append(c);
            }
        }

        private char parseUnicodeEscape() {
            if (pos + 4 > text.length()) {
                throw error("truncated \\u escape");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(text.charAt(pos + i), 16);
                if (digit < 0) {
                    throw error("invalid \\u escape");
                }
                value = value * 16 + digit;
            }
            pos += 4;
            return (char) value;
        }

        private JsonValue parseNumber() {
            int start = pos;
            if (!atEnd() && text.charAt(pos) == '-') {
                pos++;
            }
            if (atEnd()) {
                throw error("truncated number");
            }
            if (text.charAt(pos) == '0') {
                pos++;
            } else if (isDigitOneToNine(text.charAt(pos))) {
                while (!atEnd() && isDigit(text.charAt(pos))) {
                    pos++;
                }
            } else {
                throw error("unexpected character '" + text.charAt(pos) + "'");
            }
            if (!atEnd() && text.charAt(pos) == '.') {
                pos++;
                if (atEnd() || !isDigit(text.charAt(pos))) {
                    throw error("expected digit after decimal point");
                }
                while (!atEnd() && isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            if (!atEnd() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++;
                if (!atEnd() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                if (atEnd() || !isDigit(text.charAt(pos))) {
                    throw error("expected digit in exponent");
                }
                while (!atEnd() && isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            return new JsonValue.Num(text.substring(start, pos));
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private static boolean isDigitOneToNine(char c) {
            return c >= '1' && c <= '9';
        }
    }
}
