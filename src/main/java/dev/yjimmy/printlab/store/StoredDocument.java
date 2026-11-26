package dev.yjimmy.printlab.store;

import dev.yjimmy.printlab.workflow.Document;

/**
 * A document as the database holds it, with the job it belongs to.
 *
 * @param id the document's database id, which the API uses in its URLs
 * @param jobId the job the document came from
 * @param document the document, with its properties
 */
public record StoredDocument(long id, String jobId, Document document) {}
