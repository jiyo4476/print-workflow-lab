package dev.jimmyyoung.printlab.workflow;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One logical document inside a job, e.g. a single customer statement split out of a print run.
 *
 * @param sequence 1-based position in the original input file (stable across sorting, so a
 *     document can always be traced back for reprint)
 * @param file the document's own file once it has been split out
 * @param properties document properties such as accountNumber, name, zip, pageCount
 */
public record Document(int sequence, Path file, Map<String, String> properties) {

    public Document {
        properties = new LinkedHashMap<>(properties);
    }

    public String property(String name) {
        return properties.get(name);
    }
}
