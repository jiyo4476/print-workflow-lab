package dev.yjimmy.printlab.store;

import java.util.List;

/**
 * One page of documents with the total they're a page of. Both are read in one snapshot, so the
 * total and the page agree.
 *
 * @param total how many documents match, across all pages
 * @param documents the documents on this page
 */
public record DocumentPage(long total, List<StoredDocument> documents) {}
