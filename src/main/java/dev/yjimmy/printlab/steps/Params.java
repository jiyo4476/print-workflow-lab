package dev.yjimmy.printlab.steps;

import java.util.List;
import java.util.Map;

/** Small typed accessors over a step's YAML params, with errors that name the missing param. */
final class Params {

    private final Map<String, Object> raw;

    Params(Map<String, Object> raw) {
        this.raw = raw;
    }

    String string(String key) {
        Object v = raw.get(key);
        if (v == null) {
            throw new IllegalArgumentException("Missing required step param '" + key + "'");
        }
        return v.toString();
    }

    String string(String key, String fallback) {
        Object v = raw.get(key);
        return v == null ? fallback : v.toString();
    }

    int integer(String key, int fallback) {
        Object v = raw.get(key);
        return v == null ? fallback : Integer.parseInt(v.toString());
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> map(String key) {
        Object v = raw.get(key);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("Step param '" + key + "' must be a map");
        }
        return (Map<String, Object>) v;
    }

    List<String> stringList(String key) {
        Object v = raw.get(key);
        if (!(v instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("Step param '" + key + "' must be a non-empty list");
        }
        return list.stream().map(Object::toString).toList();
    }
}
