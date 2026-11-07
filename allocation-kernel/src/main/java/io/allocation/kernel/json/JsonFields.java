package io.allocation.kernel.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Typed, strict view over one JSON object.
 *
 * <p>Tracks which fields were read so that {@link #done()} can reject anything left over. A rule file
 * is a financial control document: a misspelled key such as {@code "nmode": "TOTAL"} must fail loudly,
 * because the alternative is a silently different allocation that nobody notices until the month is
 * closed.
 */
public final class JsonFields {

    private final JsonValue.Obj obj;
    private final String path;
    private final Set<String> consumed = new LinkedHashSet<>();

    private JsonFields(JsonValue.Obj obj, String path) {
        this.obj = obj;
        this.path = path;
    }

    public static JsonFields of(JsonValue value, String path) {
        if (!(value instanceof JsonValue.Obj o)) {
            throw new JsonException(path + ": expected object, found " + kind(value));
        }
        return new JsonFields(o, path);
    }

    public String path() {
        return path;
    }

    public String child(String name) {
        return path.isEmpty() ? name : path + "." + name;
    }

    private JsonValue take(String name) {
        JsonValue value = obj.get(name);
        if (value != null) {
            consumed.add(name);
        }
        return value;
    }

    public JsonValue raw(String name) {
        return take(name);
    }

    public boolean has(String name) {
        return obj.has(name);
    }

    public String requireString(String name) {
        JsonValue value = take(name);
        if (value == null) {
            throw new JsonException(child(name) + ": required string is missing");
        }
        if (!(value instanceof JsonValue.Str s)) {
            throw new JsonException(child(name) + ": expected string, found " + kind(value));
        }
        return s.value();
    }

    public String optString(String name) {
        JsonValue value = take(name);
        if (value == null || value instanceof JsonValue.Null) {
            return null;
        }
        if (!(value instanceof JsonValue.Str s)) {
            throw new JsonException(child(name) + ": expected string, found " + kind(value));
        }
        return s.value();
    }

    public String optString(String name, String fallback) {
        String value = optString(name);
        return value == null ? fallback : value;
    }

    public int optInt(String name, int fallback) {
        JsonValue value = take(name);
        if (value == null || value instanceof JsonValue.Null) {
            return fallback;
        }
        if (!(value instanceof JsonValue.Num n)) {
            throw new JsonException(child(name) + ": expected integer, found " + kind(value));
        }
        return toIntExact(n.raw(), child(name));
    }

    public Boolean optBoolean(String name) {
        JsonValue value = take(name);
        if (value == null || value instanceof JsonValue.Null) {
            return null;
        }
        if (!(value instanceof JsonValue.Bool b)) {
            throw new JsonException(child(name) + ": expected boolean, found " + kind(value));
        }
        return b.value();
    }

    public List<String> optStringList(String name) {
        JsonValue value = take(name);
        if (value == null || value instanceof JsonValue.Null) {
            return null;
        }
        if (!(value instanceof JsonValue.Arr arr)) {
            throw new JsonException(child(name) + ": expected array, found " + kind(value));
        }
        List<String> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonValue item = arr.at(i);
            if (!(item instanceof JsonValue.Str s)) {
                throw new JsonException(child(name) + "[" + i + "]: expected string, found " + kind(item));
            }
            out.add(s.value());
        }
        return out;
    }

    public JsonValue.Obj optObject(String name) {
        JsonValue value = take(name);
        if (value == null || value instanceof JsonValue.Null) {
            return null;
        }
        if (!(value instanceof JsonValue.Obj o)) {
            throw new JsonException(child(name) + ": expected object, found " + kind(value));
        }
        return o;
    }

    public List<JsonValue.Obj> optObjectList(String name) {
        JsonValue value = take(name);
        if (value == null || value instanceof JsonValue.Null) {
            return null;
        }
        if (!(value instanceof JsonValue.Arr arr)) {
            throw new JsonException(child(name) + ": expected array, found " + kind(value));
        }
        List<JsonValue.Obj> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonValue item = arr.at(i);
            if (!(item instanceof JsonValue.Obj o)) {
                throw new JsonException(child(name) + "[" + i + "]: expected object, found " + kind(item));
            }
            out.add(o);
        }
        return out;
    }

    /** String-to-string map, preserving document order. */
    public Map<String, String> optStringMap(String name) {
        JsonValue.Obj nested = optObject(name);
        if (nested == null) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : nested.fields().entrySet()) {
            if (!(entry.getValue() instanceof JsonValue.Str s)) {
                throw new JsonException(child(name) + "." + entry.getKey()
                        + ": expected string, found " + kind(entry.getValue()));
            }
            out.put(entry.getKey(), s.value());
        }
        return out;
    }

    /** String-to-object map, preserving document order. */
    public Map<String, JsonValue.Obj> optObjectMap(String name) {
        JsonValue.Obj nested = optObject(name);
        if (nested == null) {
            return null;
        }
        Map<String, JsonValue.Obj> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : nested.fields().entrySet()) {
            if (!(entry.getValue() instanceof JsonValue.Obj o)) {
                throw new JsonException(child(name) + "." + entry.getKey()
                        + ": expected object, found " + kind(entry.getValue()));
            }
            out.put(entry.getKey(), o);
        }
        return out;
    }

    /** Rejects every field that was never read. Call once, last. */
    public void done() {
        if (consumed.size() == obj.fields().size()) {
            return;
        }
        List<String> unknown = new ArrayList<>();
        for (String name : obj.fields().keySet()) {
            if (!consumed.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            Collections.sort(unknown);
            throw new JsonException(path + ": unknown field(s) " + unknown);
        }
    }

    private static int toIntExact(String raw, String path) {
        try {
            long value = Long.parseLong(raw);
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new NumberFormatException("out of int range");
            }
            return (int) value;
        } catch (NumberFormatException e) {
            throw new JsonException(path + ": expected integer, found " + raw);
        }
    }

    private static String kind(JsonValue value) {
        return switch (value) {
            case JsonValue.Obj o -> "object";
            case JsonValue.Arr a -> "array";
            case JsonValue.Str s -> "string";
            case JsonValue.Num n -> "number";
            case JsonValue.Bool b -> "boolean";
            case JsonValue.Null n -> "null";
        };
    }
}
