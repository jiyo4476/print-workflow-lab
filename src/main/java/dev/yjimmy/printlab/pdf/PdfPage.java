package dev.yjimmy.printlab.pdf;

import java.util.Optional;

/**
 * One page from the page tree.
 *
 * @param number 1 for the first page
 * @param reference the page object's number, or null for a page that isn't an indirect object
 * @param dictionary the page's own entries, plus the attributes it inherits from the page-tree
 *     nodes above it ({@link PdfDocumentReader#INHERITABLE}); those are resolved, so
 *     {@code MediaBox} is an array and {@code Resources} a dictionary, not references
 */
public record PdfPage(int number, PdfReference reference, PdfDictionary dictionary) {

    /** The page's size in default user space units (1/72 inch): {@code [x1 y1 x2 y2]}. */
    public Optional<PdfArray> mediaBox() {
        return dictionary.get("MediaBox") instanceof PdfArray box ? Optional.of(box) : Optional.empty();
    }

    /** The fonts, images and other resources the content uses; empty if the page has none. */
    public PdfDictionary resources() {
        return dictionary.get("Resources") instanceof PdfDictionary resources ? resources
                : PdfDictionary.of();
    }
}
