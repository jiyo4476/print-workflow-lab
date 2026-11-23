package dev.yjimmy.printlab.pdf;

import dev.yjimmy.printlab.pdf.PdfLexer.Kind;
import dev.yjimmy.printlab.pdf.PdfLexer.Token;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The classic cross-reference table and trailer (ISO 32000-1, 7.5.4 and 7.5.5), and lazy lookup
 * of indirect objects through it.
 *
 * <p>A PDF is read from the end: {@code startxref} near the end of the file gives the offset of
 * the {@code xref} table, which gives each object's offset in one or more subsections
 * ({@code first count}, then {@code count} entries {@code offset generation n|f}). The
 * {@code trailer} dictionary follows the table.
 *
 * <p>{@link #resolve} reads an object only when it's asked for, and keeps it (NFR-1). A free
 * entry, an object number not in the table, or a reference with a different generation resolves
 * to {@link PdfNull}, as the spec says for a reference to an object that doesn't exist.
 *
 * <p>Cross-reference streams (PDF 1.5) and incremental updates ({@code /Prev}) are PR 8: they
 * fail here with a message saying so, rather than giving the wrong objects.
 */
public final class XrefReader {

    /** One row of the table: where object {@code objectNumber} is, or that it's free. */
    public record Entry(int objectNumber, int offset, int generation, boolean inUse) {
    }

    private static final byte[] STARTXREF = "startxref".getBytes(StandardCharsets.US_ASCII);

    private final byte[] data;
    private final int startxref;
    private final Map<Integer, Entry> entries = new TreeMap<>();
    private final PdfDictionary trailer;
    private final Map<PdfReference, PdfIndirectObject> cache = new HashMap<>();
    /**
     * Reading a stream reads its indirect {@code /Length}, which is read the same way, so a bad
     * file could chain thousands of reads one inside the other. A real length is a number, so one
     * level is all a valid file needs; past this, reading fails instead of overflowing the stack.
     */
    static final int MAX_NESTED_READS = 32;

    /** Objects being read now, to catch one whose stream /Length refers back to it. */
    private final Set<PdfReference> reading = new HashSet<>();

    /** Reads the table and trailer; objects are read later, by {@link #resolve}. */
    public XrefReader(byte[] data) {
        this.data = data;
        this.startxref = findStartxref(data);
        PdfLexer lexer = new PdfLexer(data, startxref);
        readTable(lexer);
        PdfParser parser = new PdfParser(data);
        parser.seek(lexer.position());
        int trailerOffset = parser.position();
        PdfObject object = parser.parseObject();
        if (!(object instanceof PdfDictionary dictionary)) {
            throw new PdfFormatException(trailerOffset,
                    "'trailer' must be followed by a dictionary, found " + object);
        }
        for (String key : new String[] {"Prev", "XRefStm"}) {
            if (dictionary.get(key) != null) {
                throw new PdfFormatException(trailerOffset, "the trailer has /" + key
                        + ": the file was updated incrementally or has a hybrid cross-reference"
                        + " stream, which PR 8 reads");
            }
        }
        this.trailer = dictionary;
    }

    /** The offset after the last {@code startxref} in the file. */
    private static int findStartxref(byte[] data) {
        int at = lastIndexOf(data, STARTXREF);
        if (at < 0) {
            throw new PdfFormatException(data.length,
                    "no 'startxref' found: the file isn't a PDF, or it was cut short");
        }
        Token offset = new PdfLexer(data, at + STARTXREF.length).next();
        int value = nonNegativeInt(offset, "the cross-reference table's offset after 'startxref'");
        if (value >= data.length) {
            throw new PdfFormatException(offset.offset(), "startxref " + value
                    + " is past the end of the file (" + data.length + " bytes)");
        }
        return value;
    }

    private static int lastIndexOf(byte[] data, byte[] word) {
        for (int i = data.length - word.length; i >= 0; i--) {
            int j = 0;
            while (j < word.length && data[i + j] == word[j]) {
                j++;
            }
            if (j == word.length) {
                return i;
            }
        }
        return -1;
    }

    /** Reads {@code xref} and its subsections, leaving the lexer after {@code trailer}. */
    private void readTable(PdfLexer lexer) {
        Token xref = lexer.next();
        if (!xref.isKeyword("xref")) {
            String found = xref.value() instanceof PdfNumber
                    ? "an object, probably a cross-reference stream (PDF 1.5), which PR 8 reads"
                    : "'" + xref + "'";
            throw new PdfFormatException(xref.offset(), "startxref points at " + found
                    + ", not an 'xref' table");
        }
        while (true) {
            Token first = lexer.next();
            if (first.isKeyword("trailer")) {
                return;
            }
            int start = nonNegativeInt(first, "a subsection's first object number, or 'trailer'");
            int count = nonNegativeInt(lexer.next(), "the subsection's entry count");
            for (int i = 0; i < count; i++) {
                Token offset = lexer.next();
                String entry = "object " + (start + i) + "'s entry";
                String what = entry + " 'offset generation n|f'";
                int position = nonNegativeInt(offset, what);
                int generation = nonNegativeInt(lexer.next(), what);
                Token type = lexer.next();
                if (!type.isKeyword("n") && !type.isKeyword("f")) {
                    throw new PdfFormatException(type.offset(),
                            "expected 'n' or 'f' ending " + entry + ", found '" + type + "'");
                }
                entries.put(start + i, new Entry(start + i, position, generation, type.isKeyword("n")));
            }
        }
    }

    private static int nonNegativeInt(Token token, String what) {
        if (token.kind() == Kind.VALUE && token.value() instanceof PdfNumber n && n.isInt()
                && n.intValue() >= 0) {
            return n.intValue();
        }
        throw new PdfFormatException(token.offset(), "expected " + what + ", found '" + token + "'");
    }

    /** Where the {@code xref} table starts: the number after {@code startxref}. */
    public int startxref() {
        return startxref;
    }

    public PdfDictionary trailer() {
        return trailer;
    }

    /** The table's rows, by object number. */
    public Collection<Entry> entries() {
        return Collections.unmodifiableCollection(entries.values());
    }

    public Optional<Entry> entry(int objectNumber) {
        return Optional.ofNullable(entries.get(objectNumber));
    }

    /** The object {@code ref} points to, or {@link PdfNull} if the table has no such object. */
    public PdfObject resolve(PdfReference ref) {
        return indirectObject(ref).map(PdfIndirectObject::object).orElse(PdfNull.INSTANCE);
    }

    /** {@code object} itself, or what it points to if it's a reference. */
    public PdfObject resolve(PdfObject object) {
        return object instanceof PdfReference ref ? resolve(ref) : object;
    }

    /**
     * The object with its number and offset, read the first time it's asked for; empty for a free
     * entry, an object not in the table, or a different generation.
     *
     * @throws PdfFormatException if the entry's offset doesn't hold that object
     */
    public Optional<PdfIndirectObject> indirectObject(PdfReference ref) {
        PdfIndirectObject cached = cache.get(ref);
        if (cached != null) {
            return Optional.of(cached);
        }
        Entry entry = entries.get(ref.objectNumber());
        if (entry == null || !entry.inUse() || entry.generation() != ref.generation()) {
            return Optional.empty();
        }
        if (reading.contains(ref)) {
            throw new PdfFormatException(entry.offset(), "object " + ref
                    + " can't be read: its stream /Length refers back to the object itself");
        }
        if (reading.size() >= MAX_NESTED_READS) {
            throw new PdfFormatException(entry.offset(), "object " + ref + " can't be read: objects"
                    + " nested more than " + MAX_NESTED_READS + " deep through indirect stream lengths");
        }
        reading.add(ref);
        try {
            PdfIndirectObject object = read(ref, entry);
            cache.put(ref, object);
            return Optional.of(object);
        } finally {
            reading.remove(ref);
        }
    }

    private PdfIndirectObject read(PdfReference ref, Entry entry) {
        if (entry.offset() >= data.length) {
            throw new PdfFormatException(startxref, "the xref entry for object " + ref + " has offset "
                    + entry.offset() + ", past the end of the file (" + data.length + " bytes)");
        }
        PdfParser parser = new PdfParser(data, this::resolve);
        parser.seek(entry.offset());
        PdfIndirectObject object;
        try {
            object = parser.parseIndirectObject();
        } catch (PdfFormatException e) {
            if (reading.size() > 1) {
                throw e; // a nested read: the outermost one adds the context
            }
            throw new PdfFormatException(e.offset(), "reading object " + ref + " at offset "
                    + entry.offset() + " from the xref table: " + e.detail());
        }
        if (!object.reference().equals(ref)) {
            throw new PdfFormatException(entry.offset(), "the xref entry for object " + ref
                    + " points at offset " + entry.offset() + ", but object " + object.reference()
                    + " is there");
        }
        return object;
    }
}
