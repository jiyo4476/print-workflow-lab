package dev.yjimmy.printlab.workflow;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One logical document inside a job, e.g. a single customer statement split out of a print run.
 *
 * @param sequence 1-based position: in the original input file after a split, then in mailing
 *     order after {@code SortDocuments} (which renumbers; see that step)
 * @param file the document's own file once it has been split out
 * @param properties document properties such as accountNumber, name, zip, pageCount
 * @param inputSequence the position the document had in the input, before any renumbering.
 *     Equal to {@code sequence} until {@code SortDocuments} renumbers it, and written to
 *     {@code documents.csv} as {@code inputSequence}
 */
public record Document(int sequence, Path file, Map<String, String> properties,
        int inputSequence) {

    public Document {
        properties = new LinkedHashMap<>(properties);
    }

    public String property(String name) {
        return properties.get(name);
    }
}
