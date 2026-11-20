package dev.yjimmy.printlab.steps;

import java.util.List;
import java.util.Map;

/**
 * The {@code indexError} document property: why a document's index is incomplete or doubtful.
 * Steps mark the document instead of failing the job, and routing rules send marked documents to
 * an exception queue. Each problem is appended, separated by {@code "; "}, so one step's mark
 * never hides another's.
 */
public final class IndexErrors {

    /** The document property that holds the problems. */
    public static final String PROPERTY = "indexError";

    private IndexErrors() {
    }

    /** Appends {@code problem} to the document's {@code indexError}. */
    static void mark(Map<String, String> properties, String problem) {
        properties.merge(PROPERTY, problem, (existing, added) -> existing + "; " + added);
    }

    /**
     * Marks the document {@code missing: a, b} for each required property that is absent or
     * blank, the same test as the {@code missing} rule operator. Does nothing if none is.
     */
    static void markMissing(Map<String, String> properties, List<String> required) {
        List<String> missing = required.stream()
                .filter(name -> properties.get(name) == null || properties.get(name).isBlank())
                .toList();
        if (!missing.isEmpty()) {
            mark(properties, "missing: " + String.join(", ", missing));
        }
    }
}
