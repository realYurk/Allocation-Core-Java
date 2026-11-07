package io.allocation.kernel.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable JSON tree.
 *
 * <p>Numbers keep their original text. Parsing them into {@code double} would silently destroy an
 * amount; the rule format contains no floating-point numbers today, and the only correct way to keep
 * that invariant is to never convert.
 */
public sealed interface JsonValue {

    /** Object with insertion-ordered fields, so serialisation is reproducible. */
    record Obj(Map<String, JsonValue> fields) implements JsonValue {
        public Obj {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        public boolean has(String name) {
            return fields.containsKey(name);
        }

        public JsonValue get(String name) {
            return fields.get(name);
        }

        public java.util.Set<String> names() {
            return fields.keySet();
        }
    }

    /** Array of values. */
    record Arr(List<JsonValue> items) implements JsonValue {
        public Arr {
            items = List.copyOf(items);
        }

        public int size() {
            return items.size();
        }

        public JsonValue at(int index) {
            return items.get(index);
        }
    }

    /** String, already unescaped. */
    record Str(String value) implements JsonValue {
    }

    /** Number, retained as its original lexical form. */
    record Num(String raw) implements JsonValue {
    }

    /** Boolean. */
    record Bool(boolean value) implements JsonValue {
    }

    /** JSON {@code null}. */
    final class Null implements JsonValue {
        public static final Null INSTANCE = new Null();

        private Null() {
        }

        @Override
        public String toString() {
            return "null";
        }
    }

    /** Convenience factory for an empty object. */
    static Obj emptyObject() {
        return new Obj(Map.of());
    }

    /** Convenience factory for an array from varargs. */
    static Arr arrayOf(JsonValue... values) {
        List<JsonValue> list = new ArrayList<>(values.length);
        Collections.addAll(list, values);
        return new Arr(list);
    }
}
