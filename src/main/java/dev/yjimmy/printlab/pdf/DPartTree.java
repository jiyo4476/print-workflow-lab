package dev.yjimmy.printlab.pdf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The document-part tree of a PDF/VT-style file: the {@code /DPartRoot} in the catalog, read
 * without parsing any page content (only the page tree and the nodes' own dictionaries).
 *
 * <pre>
 *   /DPartRoot &lt;&lt; /DParts [ node, ... ] &gt;&gt;
 *   node  = &lt;&lt; /Start pageRef /End pageRef /DPM &lt;&lt; /AccountNumber (...) ... &gt;&gt;
 *                  /DParts [ child, ... ] &gt;&gt;      % DParts and DPM are optional
 * </pre>
 *
 * <p>Depth 1 is the root's {@code DParts}. A node's {@code Start} and {@code End} are references to
 * the first and last page of its document, inclusive. The reader checks the structure it reads
 * (references, page order, no node reached twice, a depth cap), not that the tree covers every
 * page; {@link #atLevel} checks the documents it returns against each other.
 */
public final class DPartTree {

    /** Deeper than this is treated as a broken file, not a tree. */
    static final int MAX_DEPTH = 32;

    /**
     * One node.
     *
     * @param depth 1 for the root's children
     * @param startPage the first page of the node, 1-based
     * @param endPage the last page of the node, inclusive
     * @param hasChildren whether the node has a {@code DParts} array
     * @param dpm the node's {@code DPM} entries (strings and numbers, as text, by key)
     */
    public record DPart(int depth, int startPage, int endPage, boolean hasChildren,
            Map<String, String> dpm) {
    }

    private final List<DPart> nodes;

    private DPartTree(List<DPart> nodes) {
        this.nodes = List.copyOf(nodes);
    }

    /** Every node, in file order (depth-first). */
    public List<DPart> nodes() {
        return nodes;
    }

    /**
     * The documents at {@code level}, in page order. Fails if a node above that level has no
     * children (its pages would be in no document) or if two documents share a page.
     */
    public List<DPart> atLevel(int level) {
        if (level < 1) {
            throw new IllegalArgumentException("level must be 1 or more, not " + level);
        }
        for (DPart node : nodes) {
            if (node.depth() < level && !node.hasChildren()) {
                throw new IllegalStateException("The DPart for pages " + node.startPage() + "-"
                        + node.endPage() + " has no children, so its pages are in no document at"
                        + " level " + level);
            }
        }
        List<DPart> documents = nodes.stream()
                .filter(node -> node.depth() == level)
                .sorted(Comparator.comparingInt(DPart::startPage))
                .toList();
        for (int i = 1; i < documents.size(); i++) {
            DPart before = documents.get(i - 1);
            DPart after = documents.get(i);
            if (after.startPage() <= before.endPage()) {
                throw new IllegalStateException("DPart page ranges overlap: pages "
                        + before.startPage() + "-" + before.endPage() + " and "
                        + after.startPage() + "-" + after.endPage() + " share page "
                        + after.startPage());
            }
        }
        return documents;
    }

    /**
     * Reads the tree of {@code pdf}, or returns empty if the catalog has no {@code /DPartRoot}.
     * Fails if the tree is malformed: a node that isn't a dictionary, a {@code Start} or
     * {@code End} that isn't a page, a range that ends before it starts, a node reached twice
     * (which covers cycles), or nesting deeper than {@link #MAX_DEPTH}.
     */
    public static Optional<DPartTree> read(PdfDocumentReader pdf) {
        PdfObject entry = pdf.catalog().get("DPartRoot");
        if (entry == null) {
            return Optional.empty();
        }
        if (!(pdf.resolve(entry) instanceof PdfDictionary root)) {
            throw new IllegalStateException("/DPartRoot must be a dictionary");
        }
        Map<Integer, Integer> pageNumbers = new HashMap<>();
        for (PdfPage page : pdf.pages()) {
            if (page.reference() == null) {
                throw new IllegalStateException("Page " + page.number()
                        + " is not an indirect object, so no DPart can point to it");
            }
            pageNumbers.put(page.reference().objectNumber(), page.number());
        }
        List<DPart> nodes = new ArrayList<>();
        readChildren(pdf, root, 1, pageNumbers, new HashSet<>(), nodes);
        return Optional.of(new DPartTree(nodes));
    }

    private static void readChildren(PdfDocumentReader pdf, PdfDictionary parent, int depth,
            Map<Integer, Integer> pageNumbers, Set<Integer> seen, List<DPart> out) {
        PdfObject entry = parent.get("DParts");
        if (entry == null) {
            return;
        }
        if (!(pdf.resolve(entry) instanceof PdfArray children)) {
            throw new IllegalStateException("/DParts must be an array (under depth " + (depth - 1)
                    + ")");
        }
        for (PdfObject item : children.items()) {
            if (depth > MAX_DEPTH) {
                throw new IllegalStateException("DPart nesting is deeper than " + MAX_DEPTH);
            }
            if (item instanceof PdfReference ref && !seen.add(ref.objectNumber())) {
                throw new IllegalStateException("DPart object " + ref.objectNumber()
                        + " is reached twice in the tree");
            }
            if (!(pdf.resolve(item) instanceof PdfDictionary node)) {
                throw new IllegalStateException("A DPart at depth " + depth
                        + " isn't a dictionary");
            }
            int start = pageOf(node, "Start", pageNumbers);
            int end = pageOf(node, "End", pageNumbers);
            if (end < start) {
                throw new IllegalStateException("A DPart ends on page " + end
                        + ", before it starts on page " + start);
            }
            boolean hasChildren = node.get("DParts") != null;
            out.add(new DPart(depth, start, end, hasChildren, dpm(pdf, node)));
            readChildren(pdf, node, depth + 1, pageNumbers, seen, out);
        }
    }

    private static int pageOf(PdfDictionary node, String key, Map<Integer, Integer> pageNumbers) {
        PdfObject value = node.get(key);
        if (value == null) {
            throw new IllegalStateException("A DPart has no /" + key + " page");
        }
        if (!(value instanceof PdfReference ref)) {
            throw new IllegalStateException("A DPart's /" + key + " must be a reference to a page");
        }
        Integer page = pageNumbers.get(ref.objectNumber());
        if (page == null) {
            throw new IllegalStateException("A DPart's /" + key + " points to object "
                    + ref.objectNumber() + ", which is not a page");
        }
        return page;
    }

    private static Map<String, String> dpm(PdfDocumentReader pdf, PdfDictionary node) {
        PdfObject entry = node.get("DPM");
        if (entry == null) {
            return Map.of();
        }
        if (!(pdf.resolve(entry) instanceof PdfDictionary dpm)) {
            throw new IllegalStateException("A DPart's /DPM must be a dictionary");
        }
        Map<String, String> values = new LinkedHashMap<>();
        dpm.entries().forEach((key, value) -> {
            PdfObject resolved = pdf.resolve(value);
            if (resolved instanceof PdfString s) {
                values.put(key, s.text());
            } else if (resolved instanceof PdfNumber n) {
                values.put(key, n.value().toPlainString());
            } else if (resolved instanceof PdfName name) {
                values.put(key, name.name());
            }
        });
        return values;
    }
}
