package dev.yjimmy.printlab.store;

import dev.yjimmy.printlab.workflow.Document;

/**
 * A document found by a search, with the job it belongs to.
 *
 * @param jobId the job the document came from
 * @param document the document, with its properties
 */
public record StoredDocument(String jobId, Document document) {}
