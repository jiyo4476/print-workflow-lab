package dev.yjimmy.printlab.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A PDF file's document structure (ISO 32000-1, 7.7): the version, the trailer, the catalog, and
 * the page tree flattened into {@link PdfPage}s with their content streams.
 *
 * <ul>
 *   <li>The version is the header's ({@code %PDF-1.4}), raised by the catalog's {@code /Version}
 *       when that's later. No version is rejected (PRD-07, FR-9).</li>
 *   <li>The page tree is walked in order. A page inherits {@link #INHERITABLE} attributes from
 *       the nodes above it when it doesn't set them itself (Table 30).</li>
 *   <li>Objects are read through {@link XrefReader} as they're needed.</li>
 * </ul>
 */
public final class PdfDocumentReader {

    /** Page attributes a page inherits from its ancestors (ISO 32000-1, Table 30). */
    public static final List<String> INHERITABLE = List.of("Resources", "MediaBox", "CropBox", "Rotate");

    /** The header may follow other bytes, but must start within the first 1024 (Annex H). */
    private static final int HEADER_SEARCH = 1024;

    private static final byte[] HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private final XrefReader xref;
    private final PdfVersion headerVersion;
    private final PdfDictionary catalog;
    private final StreamDecoder decoder;
    private List<PdfPage> pages;

    public PdfDocumentReader(byte[] data) {
        this(header(data), new XrefReader(data));
    }

    private PdfDocumentReader(PdfVersion headerVersion, XrefReader xref) {
        this.headerVersion = headerVersion;
        this.xref = xref;
        this.decoder = new StreamDecoder(xref::resolve, StreamDecoder.DEFAULT_MAX_DECODED_BYTES);
        PdfObject root = xref.resolve(xref.trailer().get("Root"));
        if (!(root instanceof PdfDictionary dictionary)) {
            throw new PdfFormatException(xref.startxref(),
                    "the trailer's /Root must be the catalog dictionary, found " + root);
        }
        this.catalog = dictionary;
    }

    public static PdfDocumentReader open(Path file) throws IOException {
        return new PdfDocumentReader(Files.readAllBytes(file));
    }

    /**
     * The document as of revision {@code n} (PRD-08, FR-5): 1 is the file as first written,
     * {@link #revisions()} is how it is now. Each incremental update adds a revision.
     */
    public PdfDocumentReader revision(int n) {
        return n == xref.revision() ? this : new PdfDocumentReader(headerVersion, xref.revision(n));
    }

    /** How many revisions the file has: 1, plus one per incremental update. */
    public int revisions() {
        return xref.revisions();
    }

    /** Which revision this reader sees. */
    public int revision() {
        return xref.revision();
    }

    private static PdfVersion header(byte[] data) {
        int limit = Math.min(data.length, HEADER_SEARCH) - HEADER.length;
        for (int at = 0; at <= limit; at++) {
            if (startsWith(data, at, HEADER)) {
                int end = at + HEADER.length;
                while (end < data.length && (Character.isDigit(data[end]) || data[end] == '.')) {
                    end++;
                }
                String text = new String(data, at + HEADER.length, end - at - HEADER.length,
                        StandardCharsets.US_ASCII);
                int headerAt = at;
                return PdfVersion.parse(text).orElseThrow(() -> new PdfFormatException(headerAt,
                        "the header '%PDF-" + text + "' has no version 'major.minor'"));
            }
        }
        throw new PdfFormatException(0, "no '%PDF-' header in the first " + HEADER_SEARCH
                + " bytes: not a PDF file");
    }

    private static boolean startsWith(byte[] data, int at, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (data[at + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** The version in the {@code %PDF-x.y} header. */
    public PdfVersion headerVersion() {
        return headerVersion;
    }

    /** The catalog's {@code /Version}, if it has one that parses. */
    public Optional<PdfVersion> catalogVersion() {
        return xref.resolve(catalog.get("Version")) instanceof PdfName name
                ? PdfVersion.parse(name.name()) : Optional.empty();
    }

    /** The file's version: the header's, or the catalog's if that is later (7.7.2). */
    public PdfVersion version() {
        return catalogVersion().filter(v -> v.compareTo(headerVersion) > 0).orElse(headerVersion);
    }

    public PdfDictionary trailer() {
        return xref.trailer();
    }

    public PdfDictionary catalog() {
        return catalog;
    }

    public XrefReader xref() {
        return xref;
    }

    /** True when the trailer has {@code /Encrypt}: strings and streams are then not readable as is. */
    public boolean isEncrypted() {
        return xref.trailer().get("Encrypt") != null;
    }

    /** {@code object} itself, or what it points to if it's a reference. */
    public PdfObject resolve(PdfObject object) {
        return xref.resolve(object);
    }

    /** The pages in order, read from the page tree the first time they're asked for. */
    public List<PdfPage> pages() {
        if (pages == null) {
            pages = List.copyOf(readPageTree());
        }
        return pages;
    }

    /** A page-tree node still to visit, with the attributes its ancestors pass down. */
    private record Pending(PdfObject node, Map<String, PdfObject> inherited) {
    }

    /**
     * Walks the tree with an explicit stack rather than recursion, so a deep or looping tree in a
     * bad file fails cleanly instead of overflowing the stack.
     */
    private List<PdfPage> readPageTree() {
        List<PdfPage> found = new ArrayList<>();
        Set<PdfReference> visited = new HashSet<>();
        Deque<Pending> stack = new ArrayDeque<>();
        stack.push(new Pending(catalog.get("Pages"), Map.of()));
        while (!stack.isEmpty()) {
            Pending pending = stack.pop();
            PdfReference reference = pending.node() instanceof PdfReference ref ? ref : null;
            long offset = offset(reference);
            if (reference != null && !visited.add(reference)) {
                throw new PdfFormatException(offset, "object " + reference
                        + " appears more than once in the page tree");
            }
            PdfObject node = xref.resolve(pending.node());
            if (!(node instanceof PdfDictionary dictionary)) {
                throw new PdfFormatException(offset, "page tree node "
                        + (reference != null ? reference : pending.node()) + " is not a dictionary: " + node);
            }
            Map<String, PdfObject> inherited = new HashMap<>(pending.inherited());
            for (String key : INHERITABLE) {
                if (dictionary.get(key) != null) {
                    inherited.put(key, xref.resolve(dictionary.get(key)));
                }
            }
            PdfObject type = dictionary.get("Type");
            boolean isNode = PdfName.of("Pages").equals(type) || type == null && dictionary.get("Kids") != null;
            if (isNode) {
                if (!(xref.resolve(dictionary.get("Kids")) instanceof PdfArray kids)) {
                    throw new PdfFormatException(offset, "page tree node " + reference
                            + " has no /Kids array");
                }
                for (int i = kids.size() - 1; i >= 0; i--) {
                    stack.push(new Pending(kids.get(i), inherited));
                }
            } else {
                Map<String, PdfObject> entries = new LinkedHashMap<>(dictionary.entries());
                entries.putAll(inherited);
                found.add(new PdfPage(found.size() + 1, reference, new PdfDictionary(entries)));
            }
        }
        return found;
    }

    private long offset(PdfReference reference) {
        return xref.offset(reference);
    }

    /**
     * The page's content streams in order: {@code /Contents} is one stream or an array of them,
     * which together are the page's content (7.8.2). Empty for a page with no content.
     */
    public List<PdfIndirectObject> contentStreams(PdfPage page) {
        PdfObject contents = page.dictionary().get("Contents");
        List<PdfObject> parts = new ArrayList<>();
        if (contents instanceof PdfReference ref && xref.resolve(ref) instanceof PdfArray array) {
            parts.addAll(array.items());
        } else if (contents instanceof PdfArray array) {
            parts.addAll(array.items());
        } else if (contents != null) {
            parts.add(contents);
        }
        List<PdfIndirectObject> streams = new ArrayList<>();
        for (PdfObject part : parts) {
            Optional<PdfIndirectObject> object = part instanceof PdfReference ref
                    ? xref.indirectObject(ref) : Optional.empty();
            if (object.isEmpty() || !(object.get().object() instanceof PdfStream)) {
                throw new PdfFormatException(offset(page.reference()), "page " + page.number()
                        + "'s /Contents must be streams, found " + part);
            }
            streams.add(object.get());
        }
        return streams;
    }

    /**
     * The page's content, decoded: its content streams joined with a newline between them, as
     * 7.8.2 says to read them (a split may fall between any two tokens, never inside one).
     * Empty for a page with no content.
     *
     * @throws UnsupportedFilterException if a stream uses a filter {@link StreamDecoder} lacks
     */
    public byte[] content(PdfPage page) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        List<PdfIndirectObject> streams = contentStreams(page);
        for (int i = 0; i < streams.size(); i++) {
            byte[] part = decode((PdfStream) streams.get(i).object());
            if ((long) joined.size() + part.length + 1 > StreamDecoder.DEFAULT_MAX_DECODED_BYTES) {
                throw new PdfFormatException(streams.get(i).offset(), "page " + page.number()
                        + "'s content is more than " + StreamDecoder.DEFAULT_MAX_DECODED_BYTES + " bytes decoded");
            }
            if (i > 0) {
                joined.write('\n');
            }
            joined.writeBytes(part);
        }
        return joined.toByteArray();
    }

    /** The stream's filter names, resolved through the xref. */
    public List<String> filters(PdfStream stream) {
        return decoder.filters(stream);
    }

    /**
     * The stream's data with its filters undone.
     *
     * @throws UnsupportedFilterException if a filter isn't one {@link StreamDecoder} knows
     */
    public byte[] decode(PdfStream stream) {
        return decoder.decode(stream);
    }
}
