package dev.yjimmy.printlab.pdf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The document-part (DPart) hierarchy of a PDF 2.0 or PDF/VT file, read without parsing any page
 * content (only the page tree and the nodes' own dictionaries).
 *
 * <pre>
 *   catalog   /DPartRoot root
 *   root      &lt;&lt; /Type /DPartRoot /DPartRootNode node /RecordLevel 1 /NodeNameList [/root /record] &gt;&gt;
 *   node      &lt;&lt; /Type /DPart /Parent ref /DParts [[ child child ... ]] &gt;&gt;       % a branch
 *   leaf      &lt;&lt; /Type /DPart /Parent ref /Start pageRef /End pageRef /DPM &lt;&lt; ... &gt;&gt; &gt;&gt;
 * </pre>
 *
 * <p>Depth 1 is the children of the root node, which is what {@code /RecordLevel 1} means in a
 * root node with one level of records below it (this reader takes the root node as level 0). A
 * node's {@code Start} and {@code End} reference the first and last page of its pages, inclusive.
 *
 * <p>What this reader accepts beyond that, so files from different writers read the same way:
 * <ul>
 *   <li>{@code /DParts} as a flat array of nodes or as an array of arrays of nodes (the
 *       PDF 2.0 form); both give the same nodes, in order;</li>
 *   <li>a root dictionary that carries {@code /DParts} itself, with no {@code /DPartRootNode}
 *       (what earlier versions of {@code generate --dpart} wrote);</li>
 *   <li>a branch node with no {@code /Start} or {@code /End}: its range is taken from its
 *       descendants. A leaf must have both.</li>
 * </ul>
 *
 * <p>The reader checks the structure it reads: references that aren't pages, a range that ends
 * before it starts, a branch range that doesn't contain its descendants, a node or array reached
 * twice (which also covers cycles and shared sub-trees), and nesting beyond {@link #MAX_DEPTH}.
 * It does <b>not</b> check that the tree covers every page, that siblings are in order, or that
 * {@code /Parent} agrees with where a node sits; {@link #atLevel} checks the documents it returns
 * against each other. Only the {@code DPM} entries that are strings, numbers or names are read
 * (as text); any other value is ignored. A numeric entry loses leading zeros, because PDF
 * numbers do, so writers should store such values as strings.
 */
public final class DPartTree {

    /** Deeper than this is treated as a broken file, not a tree. */
    static final int MAX_DEPTH = 32;

    /**
     * One node.
     *
     * @param depth 1 for the children of the root node
     * @param startPage the first page of the node, 1-based
     * @param endPage the last page of the node, inclusive
     * @param hasChildren whether the node has a {@code DParts} array
     * @param dpm the node's {@code DPM} entries (strings, numbers and names, as text, by key)
     */
    public record DPart(int depth, int startPage, int endPage, boolean hasChildren,
            Map<String, String> dpm) {
    }

    private final List<DPart> nodes;
    private final Integer recordLevel;

    private DPartTree(List<DPart> nodes, Integer recordLevel) {
        this.nodes = List.copyOf(nodes);
        this.recordLevel = recordLevel;
    }

    /** Every node, in file order (depth-first). */
    public List<DPart> nodes() {
        return nodes;
    }

    /**
     * The root's {@code /RecordLevel}: the depth whose nodes are the print records (documents),
     * if the file says. Not checked against the tree.
     */
    public OptionalInt recordLevel() {
        return recordLevel == null ? OptionalInt.empty() : OptionalInt.of(recordLevel);
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
     * Fails if the tree is malformed (see the class comment).
     */
    public static Optional<DPartTree> read(PdfDocumentReader pdf) {
        PdfObject entry = pdf.catalog().get("DPartRoot");
        if (entry == null) {
            return Optional.empty();
        }
        Set<PdfReference> seen = new HashSet<>();
        markSeen(entry, seen, "DPartRoot");
        if (!(pdf.resolve(entry) instanceof PdfDictionary root)) {
            throw new IllegalStateException("/DPartRoot must be a dictionary");
        }
        Map<PdfReference, Integer> pageNumbers = new HashMap<>();
        for (PdfPage page : pdf.pages()) {
            if (page.reference() == null) {
                throw new IllegalStateException("Page " + page.number()
                        + " is not an indirect object, so no DPart can point to it");
            }
            pageNumbers.put(page.reference(), page.number());
        }
        PdfDictionary top = root;
        PdfObject rootNode = root.get("DPartRootNode");
        if (rootNode != null) {
            markSeen(rootNode, seen, "DPartRootNode");
            if (!(pdf.resolve(rootNode) instanceof PdfDictionary node)) {
                throw new IllegalStateException("/DPartRootNode must be a dictionary");
            }
            top = node;
        }
        PdfObject level = root.get("RecordLevel");
        Integer recordLevel = level != null && pdf.resolve(level) instanceof PdfNumber n
                && n.isInt() ? n.intValue() : null;
        List<DPart> nodes = new ArrayList<>();
        new Walk(pdf, pageNumbers, seen, nodes).children(top, 1);
        return Optional.of(new DPartTree(nodes, recordLevel));
    }

    /** Fails if {@code object} is a reference already in {@code seen}; otherwise records it. */
    private static void markSeen(PdfObject object, Set<PdfReference> seen, String what) {
        if (object instanceof PdfReference ref && !seen.add(ref)) {
            throw new IllegalStateException("The " + what + " object " + ref.objectNumber()
                    + " is reached twice in the DPart tree");
        }
    }

    /** One pass over the tree: the file, what it resolves pages with, and what it has read. */
    private static final class Walk {

        private final PdfDocumentReader pdf;
        private final Map<PdfReference, Integer> pageNumbers;
        private final Set<PdfReference> seen;
        private final List<DPart> out;

        Walk(PdfDocumentReader pdf, Map<PdfReference, Integer> pageNumbers, Set<PdfReference> seen,
                List<DPart> out) {
            this.pdf = pdf;
            this.pageNumbers = pageNumbers;
            this.seen = seen;
            this.out = out;
        }

        /** Reads the nodes in {@code parent}'s {@code /DParts} (flat, or one level of arrays). */
        void children(PdfDictionary parent, int depth) {
            PdfObject entry = parent.get("DParts");
            if (entry == null) {
                return;
            }
            markSeen(entry, seen, "DParts array");
            if (!(pdf.resolve(entry) instanceof PdfArray items)) {
                throw new IllegalStateException("/DParts must be an array (under depth "
                        + (depth - 1) + ")");
            }
            for (PdfObject item : items.items()) {
                if (pdf.resolve(item) instanceof PdfArray group) {
                    markSeen(item, seen, "DParts group");
                    for (PdfObject member : group.items()) {
                        node(member, depth);
                    }
                } else {
                    node(item, depth);
                }
            }
        }

        /** Reads one node (a dictionary, or a reference to one) at {@code depth}. */
        private void node(PdfObject item, int depth) {
            markSeen(item, seen, "DPart");
            if (depth > MAX_DEPTH) {
                throw new IllegalStateException("DPart nesting is deeper than " + MAX_DEPTH);
            }
            PdfObject resolved = pdf.resolve(item);
            if (resolved instanceof PdfArray) {
                throw new IllegalStateException("DParts arrays are nested more than one level"
                        + " deep (at depth " + depth + ")");
            }
            if (!(resolved instanceof PdfDictionary node)) {
                throw new IllegalStateException("A DPart at depth " + depth + " isn't a dictionary");
            }
            OptionalInt start = page(node, "Start");
            OptionalInt end = page(node, "End");
            int slot = out.size();
            out.add(null); // keeps file order: the node goes before its descendants
            children(node, depth + 1);
            List<DPart> descendants = out.subList(slot + 1, out.size());
            boolean hasChildren = node.get("DParts") != null;
            int first;
            int last;
            if (descendants.isEmpty()) {
                if (start.isEmpty()) {
                    throw new IllegalStateException("A DPart has no /Start page");
                }
                if (end.isEmpty()) {
                    throw new IllegalStateException("A DPart has no /End page");
                }
                first = start.getAsInt();
                last = end.getAsInt();
            } else {
                first = start.orElse(descendants.stream().mapToInt(DPart::startPage).min()
                        .orElseThrow());
                last = end.orElse(descendants.stream().mapToInt(DPart::endPage).max()
                        .orElseThrow());
            }
            if (last < first) {
                throw new IllegalStateException("A DPart ends on page " + last
                        + ", before it starts on page " + first);
            }
            for (DPart descendant : descendants) {
                if (descendant.startPage() < first || descendant.endPage() > last) {
                    throw new IllegalStateException("A DPart for pages " + first + "-" + last
                            + " contains a DPart for pages " + descendant.startPage() + "-"
                            + descendant.endPage() + ", which is outside its range");
                }
            }
            out.set(slot, new DPart(depth, first, last, hasChildren, dpm(node)));
        }

        private OptionalInt page(PdfDictionary node, String key) {
            PdfObject value = node.get(key);
            if (value == null) {
                return OptionalInt.empty();
            }
            if (!(value instanceof PdfReference ref)) {
                throw new IllegalStateException("A DPart's /" + key
                        + " must be a reference to a page");
            }
            Integer page = pageNumbers.get(ref);
            if (page == null) {
                throw new IllegalStateException("A DPart's /" + key + " points to object "
                        + ref.objectNumber() + ", which is not a page");
            }
            return OptionalInt.of(page);
        }

        private Map<String, String> dpm(PdfDictionary node) {
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
}
