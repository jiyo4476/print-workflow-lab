package dev.yjimmy.printlab.web;

import dev.yjimmy.printlab.store.StoredDocument;
import java.util.Map;

/**
 * A document in the API: its id (for {@code /api/documents/{id}}), its job, its position and its
 * properties. The file's path on the server is left out; the file endpoint serves the bytes.
 */
record DocumentResponse(
        long id,
        String jobId,
        int sequence,
        int inputSequence,
        String queue,
        Map<String, String> properties) {

    static DocumentResponse of(StoredDocument stored) {
        var document = stored.document();
        return new DocumentResponse(stored.id(), stored.jobId(), document.sequence(),
                document.inputSequence(), document.property("queue"), document.properties());
    }
}
