package dev.yjimmy.printlab.pdf;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code << /Type /Page /MediaBox [0 0 612 792] >>}: name keys (held without the slash) to
 * values, in the order written. An entry whose value is {@code null} is the same as no entry
 * (ISO 32000-1, 7.3.7), so it isn't kept.
 */
public record PdfDictionary(Map<String, PdfObject> entries) implements PdfObject {

    public PdfDictionary {
        Map<String, PdfObject> copy = new LinkedHashMap<>();
        entries.forEach((key, value) -> {
            if (value != null && value != PdfNull.INSTANCE) {
                copy.put(key, value);
            }
        });
        entries = Collections.unmodifiableMap(copy);
    }

    /** A dictionary from alternating keys and values: {@code of("Type", PdfName.of("Page"))}. */
    public static PdfDictionary of(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("keys and values must come in pairs");
        }
        Map<String, PdfObject> entries = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            entries.put((String) keysAndValues[i], (PdfObject) keysAndValues[i + 1]);
        }
        return new PdfDictionary(entries);
    }

    /** The value for {@code key} (without its slash), or null if there's none. */
    public PdfObject get(String key) {
        return entries.get(key);
    }

    @Override
    public String toString() {
        return entries.entrySet().stream()
                .map(e -> "/" + e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(" ", "<<", ">>"));
    }
}
