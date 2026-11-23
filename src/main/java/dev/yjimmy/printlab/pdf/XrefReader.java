package dev.yjimmy.printlab.pdf;

import dev.yjimmy.printlab.pdf.PdfLexer.Kind;
import dev.yjimmy.printlab.pdf.PdfLexer.Token;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The cross-reference sections and trailers of a PDF (ISO 32000-1, 7.5.4 to 7.5.8), and lazy
 * lookup of indirect objects through them.
 *
 * <p>A PDF is read from the end: {@code startxref} near the end of the file gives the offset of
 * the newest cross-reference section, which is either
 * <ul>
 *   <li>a classic {@code xref} table: subsections of {@code first count}, then {@code count}
 *       entries {@code offset generation n|f}, followed by the {@code trailer} dictionary; or</li>
 *   <li>a cross-reference stream (PDF 1.5): an object with {@code /Type /XRef}, whose decoded
 *       data is fixed-width binary rows ({@code /W}) for the object ranges in {@code /Index}, and
 *       whose dictionary is also the trailer.</li>
 * </ul>
 * A hybrid file's table trailer has {@code /XRefStm}, the offset of a stream with more entries
 * for the same section. Each incremental update appends a section whose trailer's {@code /Prev}
 * points at the one before; for any object the newest section that lists it wins.
 *
 * <p>Sections are numbered as {@link #revision revisions} from the oldest: revision 1 is the file
 * as first written. {@link #revision(int)} gives a reader that sees only the sections up to that
 * one, so an earlier version of an edited file stays readable.
 *
 * <p>An entry is {@link Free}, {@link InFile} (at a byte offset), or {@link Compressed} (inside
 * an object stream, {@code /Type /ObjStm}, read through the same {@link #resolve}). Objects are
 * read only when they're asked for, and kept; each object stream is decoded once. A free entry,
 * an object number no section lists, or a reference with a different generation resolves to
 * {@link PdfNull}, as the spec says for a reference to an object that doesn't exist.
 */
public final class XrefReader {

    /** One cross-reference entry: where object {@code objectNumber} is, or that it's free. */
    public sealed interface Entry permits Free, InFile, Compressed {

        int objectNumber();

        int generation();

        default boolean inUse() {
            return !(this instanceof Free);
        }
    }

    /** A free (deleted or never used) object number. */
    public record Free(int objectNumber, int generation) implements Entry {
    }

    /** An object at a byte offset in the file. */
    public record InFile(int objectNumber, int offset, int generation) implements Entry {
    }

    /**
     * An object stored inside object stream {@code streamNumber}, the {@code index}th object in
     * it. Its generation is always 0.
     */
    public record Compressed(int objectNumber, int streamNumber, int index) implements Entry {

        @Override
        public int generation() {
            return 0;
        }
    }

    /**
     * One cross-reference section: a classic table ({@code stream} null) or a cross-reference
     * stream, with its entries and trailer.
     *
     * @param offset where the section starts: {@code startxref}, or the {@code /Prev} pointing at it
     * @param stream the cross-reference stream's object, or null for a table
     * @param hybridStreamOffset a hybrid table's {@code /XRefStm} offset, or -1
     */
    public record Section(int offset, PdfReference stream, int hybridStreamOffset,
            Map<Integer, Entry> entries, PdfDictionary trailer) {

        public Section {
            entries = Collections.unmodifiableMap(new TreeMap<>(entries));
        }

        public boolean isStream() {
            return stream != null;
        }

        /** {@code table}, {@code stream 61 0 R}, or {@code table + stream at offset N}. */
        public String kind() {
            if (stream != null) {
                return "stream " + stream;
            }
            return hybridStreamOffset >= 0
                    ? String.format("table + stream at 0x%08X", hybridStreamOffset) : "table";
        }
    }

    private static final byte[] STARTXREF = "startxref".getBytes(StandardCharsets.US_ASCII);

    /** More sections than any real file has; a /Prev chain longer than this is broken. */
    static final int MAX_SECTIONS = 10_000;

    /**
     * The most indirect objects a PDF may have (ISO 32000-1, Annex C.2). A section or object
     * stream claiming more is broken, and reading it would fill memory: a small stream can
     * inflate to 256 MiB of 1-byte rows, 268 million entries.
     */
    static final int MAX_OBJECTS = 8_388_607;

    /**
     * Reading a stream reads its indirect {@code /Length}, which is read the same way, so a bad
     * file could chain thousands of reads one inside the other. A real length is a number, so one
     * level is all a valid file needs (two for an object in an object stream); past this, reading
     * fails instead of overflowing the stack.
     */
    static final int MAX_NESTED_READS = 32;

    private final byte[] data;
    /** Oldest first: sections.get(0) is revision 1. */
    private final List<Section> sections;
    private final int revision;
    /** The entries this revision sees: each object's newest entry up to it. */
    private final Map<Integer, Entry> entries = new TreeMap<>();
    private final Map<PdfReference, PdfIndirectObject> cache = new HashMap<>();
    private final Map<Integer, ObjectStream> objectStreams = new HashMap<>();
    /** Objects being read now, to catch one whose stream /Length refers back to it. */
    private final Set<PdfReference> reading = new HashSet<>();
    private final StreamDecoder decoder =
            new StreamDecoder(this::resolve, StreamDecoder.DEFAULT_MAX_DECODED_BYTES);

    /** Reads every section and trailer; objects are read later, by {@link #resolve}. */
    public XrefReader(byte[] data) {
        this(data, readSections(data), -1);
    }

    private XrefReader(byte[] data, List<Section> sections, int revision) {
        this.data = data;
        this.sections = sections;
        this.revision = revision < 0 ? sections.size() : revision;
        for (Section section : sections.subList(0, this.revision)) {
            entries.putAll(section.entries());
        }
    }

    /** The file as of revision {@code n}: 1 is as first written, {@link #revisions()} is now. */
    public XrefReader revision(int n) {
        if (n < 1 || n > sections.size()) {
            throw new IllegalArgumentException("revision " + n + " doesn't exist: the file has "
                    + sections.size());
        }
        return n == revision ? this : new XrefReader(data, sections, n);
    }

    /** Which revision this reader sees. */
    public int revision() {
        return revision;
    }

    /** How many revisions (cross-reference sections) the file has. */
    public int revisions() {
        return sections.size();
    }

    /** Every section, oldest first: {@code sections().get(0)} is revision 1. */
    public List<Section> sections() {
        return sections;
    }

    // ---------------------------------------------------------------- reading the sections

    private static List<Section> readSections(byte[] data) {
        Deque<Section> newestFirst = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        int offset = findStartxref(data);
        String from = "startxref";
        while (true) {
            if (!seen.add(offset)) {
                throw new PdfFormatException(offset, "the /Prev chain loops back to the section at"
                        + " offset " + offset);
            }
            if (seen.size() > MAX_SECTIONS) {
                throw new PdfFormatException(offset, "more than " + MAX_SECTIONS
                        + " cross-reference sections");
            }
            Section section = readSection(data, offset, from);
            newestFirst.addLast(section);
            PdfObject prev = section.trailer().get("Prev");
            if (prev == null) {
                break;
            }
            offset = offsetIn(data, prev, section.offset(), "/Prev");
            from = "/Prev of the section at offset " + section.offset();
        }
        List<Section> oldestFirst = new ArrayList<>(newestFirst);
        Collections.reverse(oldestFirst);
        return List.copyOf(oldestFirst);
    }

    /** {@code value} as an offset in the file, or a message naming where it came from. */
    private static int offsetIn(byte[] data, PdfObject value, int at, String what) {
        if (value instanceof PdfNumber n && n.isInt() && n.intValue() >= 0 && n.intValue() < data.length) {
            return n.intValue();
        }
        throw new PdfFormatException(at, "the trailer's " + what + " must be an offset in the file ("
                + data.length + " bytes), found " + value);
    }

    /** The offset after the last {@code startxref} in the file. */
    private static int findStartxref(byte[] data) {
        int at = lastIndexOf(data, STARTXREF);
        if (at < 0) {
            throw new PdfFormatException(data.length,
                    "no 'startxref' found: the file isn't a PDF, or it was cut short");
        }
        Token offset = new PdfLexer(data, at + STARTXREF.length).next();
        int value = nonNegativeInt(offset, "the cross-reference section's offset after 'startxref'");
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

    private static Section readSection(byte[] data, int offset, String from) {
        Token first = new PdfLexer(data, offset).next();
        if (first.isKeyword("xref")) {
            return readTable(data, offset);
        }
        if (first.value() instanceof PdfNumber) {
            StreamSection stream = readStream(data, offset);
            return new Section(offset, stream.reference(), -1, stream.entries(), stream.dictionary());
        }
        throw new PdfFormatException(first.offset(), from + " points at '" + first
                + "', not an 'xref' table or a cross-reference stream");
    }

    private static Section readTable(byte[] data, int offset) {
        PdfLexer lexer = new PdfLexer(data, offset);
        lexer.next(); // xref
        Map<Integer, Entry> table = new LinkedHashMap<>();
        while (true) {
            Token first = lexer.next();
            if (first.isKeyword("trailer")) {
                break;
            }
            int start = nonNegativeInt(first, "a subsection's first object number, or 'trailer'");
            Token countToken = lexer.next();
            int count = nonNegativeInt(countToken, "the subsection's entry count");
            if (count > MAX_OBJECTS - table.size()) {
                throw new PdfFormatException(countToken.offset(), "a subsection of " + count
                        + " entries is more than the " + MAX_OBJECTS + " objects a PDF may have");
            }
            for (int i = 0; i < count; i++) {
                Token position = lexer.next();
                String entry = "object " + (start + i) + "'s entry";
                String what = entry + " 'offset generation n|f'";
                int value = nonNegativeInt(position, what);
                int generation = nonNegativeInt(lexer.next(), what);
                Token type = lexer.next();
                if (!type.isKeyword("n") && !type.isKeyword("f")) {
                    throw new PdfFormatException(type.offset(),
                            "expected 'n' or 'f' ending " + entry + ", found '" + type + "'");
                }
                int number = start + i;
                table.put(number, type.isKeyword("n") ? new InFile(number, value, generation)
                        : new Free(number, generation));
            }
        }
        PdfParser parser = new PdfParser(data);
        parser.seek(lexer.position());
        int trailerOffset = parser.position();
        PdfObject object = parser.parseObject();
        if (!(object instanceof PdfDictionary trailer)) {
            throw new PdfFormatException(trailerOffset,
                    "'trailer' must be followed by a dictionary, found " + object);
        }
        int hybrid = -1;
        if (trailer.get("XRefStm") != null) {
            // A hybrid file: the stream lists the objects a pre-1.5 reader can't see (those in
            // object streams). Entries the table has in use win; the stream fills in the rest.
            hybrid = offsetIn(data, trailer.get("XRefStm"), trailerOffset, "/XRefStm");
            readStream(data, hybrid).entries().forEach((number, entry) -> {
                if (!(table.get(number) instanceof InFile)) {
                    table.put(number, entry);
                }
            });
        }
        return new Section(offset, null, hybrid, table, trailer);
    }

    private record StreamSection(PdfReference reference, Map<Integer, Entry> entries,
            PdfDictionary dictionary) {
    }

    /**
     * Reads the cross-reference stream at {@code offset} (7.5.8). Its dictionary entries must be
     * direct, so it's parsed without a resolver: no table exists yet to resolve through.
     */
    private static StreamSection readStream(byte[] data, int offset) {
        PdfParser parser = new PdfParser(data);
        parser.seek(offset);
        PdfIndirectObject object = parser.parseIndirectObject();
        String name = "cross-reference stream " + object.reference();
        if (!(object.object() instanceof PdfStream stream)
                || !PdfName.of("XRef").equals(stream.dictionary().get("Type"))) {
            throw new PdfFormatException(offset, "object " + object.reference() + " at offset "
                    + offset + " is not a cross-reference stream (/Type /XRef)");
        }
        PdfDictionary dictionary = stream.dictionary();
        byte[] rows;
        try {
            rows = new StreamDecoder().decode(stream);
        } catch (UnsupportedFilterException e) {
            throw new PdfFormatException(offset, name + " can't be decoded: " + e.getMessage());
        }
        int[] widths = widths(dictionary.get("W"), offset, name);
        int size = nonNegativeInt(dictionary.get("Size"), offset, name + " /Size");
        List<long[]> ranges = ranges(dictionary.get("Index"), size, offset, name);
        int rowLength = widths[0] + widths[1] + widths[2];
        if (rowLength == 0) {
            throw new PdfFormatException(offset, name + " has /W [0 0 0]: rows of no bytes");
        }
        long rowCount = ranges.stream().mapToLong(range -> range[1]).sum();
        if (rowCount > MAX_OBJECTS) {
            throw new PdfFormatException(offset, name + " lists " + rowCount + " objects, more than the "
                    + MAX_OBJECTS + " a PDF may have");
        }
        if (rowCount * rowLength > rows.length) {
            throw new PdfFormatException(offset, name + " has " + rowCount + " rows of " + rowLength
                    + " bytes in /Index, but only " + rows.length + " decoded bytes");
        }
        Map<Integer, Entry> entries = new LinkedHashMap<>();
        int at = 0;
        for (long[] range : ranges) {
            for (long i = 0; i < range[1]; i++) {
                long number = range[0] + i;
                if (number > Integer.MAX_VALUE) {
                    throw new PdfFormatException(offset, name + " lists object " + number
                            + ", past the largest object number");
                }
                // A missing type field means type 1 (7.5.8.2, Table 17).
                long type = widths[0] == 0 ? 1 : field(rows, at, widths[0]);
                long second = field(rows, at + widths[0], widths[1]);
                long third = field(rows, at + widths[0] + widths[1], widths[2]);
                at += rowLength;
                int n = (int) number;
                entries.put(n, switch ((int) Math.min(type, 3)) {
                    case 1 -> new InFile(n, intField(second, offset, name, n), intField(third, offset, name, n));
                    case 2 -> new Compressed(n, intField(second, offset, name, n), intField(third, offset, name, n));
                    // Type 0 is free; any other type is a reference to the null object (Table 18).
                    default -> new Free(n, (int) Math.min(third, Integer.MAX_VALUE));
                });
            }
        }
        return new StreamSection(object.reference(), entries, dictionary);
    }

    private static int[] widths(PdfObject value, int offset, String name) {
        if (value instanceof PdfArray array && array.size() == 3) {
            int[] widths = new int[3];
            for (int i = 0; i < 3; i++) {
                widths[i] = nonNegativeInt(array.get(i), offset, name + " /W");
                if (widths[i] > 8) {
                    throw new PdfFormatException(offset, name + " has a /W field of " + widths[i]
                            + " bytes; at most 8 are read");
                }
            }
            return widths;
        }
        throw new PdfFormatException(offset, name + " needs /W, an array of three widths, found " + value);
    }

    /** {@code /Index [first count ...]} as {first, count} pairs; {@code [0 Size]} if absent. */
    private static List<long[]> ranges(PdfObject value, int size, int offset, String name) {
        List<long[]> ranges = new ArrayList<>();
        if (value == null) {
            ranges.add(new long[] {0, size});
            return ranges;
        }
        if (!(value instanceof PdfArray array) || array.size() % 2 != 0) {
            throw new PdfFormatException(offset, name + " needs /Index, pairs of 'first count', found " + value);
        }
        for (int i = 0; i < array.size(); i += 2) {
            ranges.add(new long[] {nonNegativeInt(array.get(i), offset, name + " /Index"),
                    nonNegativeInt(array.get(i + 1), offset, name + " /Index")});
        }
        return ranges;
    }

    /** A big-endian field of {@code width} bytes (0 to 8) starting at {@code at}. */
    private static long field(byte[] rows, int at, int width) {
        long value = 0;
        for (int i = 0; i < width; i++) {
            value = value << 8 | rows[at + i] & 0xFF;
        }
        return value;
    }

    private static int intField(long value, int offset, String name, int object) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new PdfFormatException(offset, name + " has a value too large for object " + object
                    + "'s entry: " + Long.toUnsignedString(value));
        }
        return (int) value;
    }

    private static int nonNegativeInt(PdfObject value, int offset, String what) {
        if (value instanceof PdfNumber n && n.isInt() && n.intValue() >= 0) {
            return n.intValue();
        }
        throw new PdfFormatException(offset, what + " must be a non-negative integer, found " + value);
    }

    private static int nonNegativeInt(Token token, String what) {
        if (token.kind() == Kind.VALUE && token.value() instanceof PdfNumber n && n.isInt()
                && n.intValue() >= 0) {
            return n.intValue();
        }
        throw new PdfFormatException(token.offset(), "expected " + what + ", found '" + token + "'");
    }

    // ---------------------------------------------------------------- what this revision sees

    private Section section() {
        return sections.get(revision - 1);
    }

    /** Where this revision's section starts (for the newest, the number after {@code startxref}). */
    public int startxref() {
        return section().offset();
    }

    /** This revision's trailer: a table's {@code trailer}, or a cross-reference stream's dictionary. */
    public PdfDictionary trailer() {
        return section().trailer();
    }

    /** Each object's newest entry up to this revision, by object number. */
    public Collection<Entry> entries() {
        return Collections.unmodifiableCollection(entries.values());
    }

    public Optional<Entry> entry(int objectNumber) {
        return Optional.ofNullable(entries.get(objectNumber));
    }

    /**
     * Where object {@code ref} is in the file: its own offset, or its object stream's for an
     * object stored in one; {@link #startxref()} if neither is known.
     */
    public int offset(PdfReference ref) {
        Entry entry = ref == null ? null : entries.get(ref.objectNumber());
        if (entry instanceof Compressed compressed) {
            entry = entries.get(compressed.streamNumber());
        }
        return entry instanceof InFile inFile ? inFile.offset() : startxref();
    }

    /** The object {@code ref} points to, or {@link PdfNull} if no section has such an object. */
    public PdfObject resolve(PdfReference ref) {
        return indirectObject(ref).map(PdfIndirectObject::object).orElse(PdfNull.INSTANCE);
    }

    /** {@code object} itself, or what it points to if it's a reference. */
    public PdfObject resolve(PdfObject object) {
        return object instanceof PdfReference ref ? resolve(ref) : object;
    }

    /**
     * The object with its number and offset, read the first time it's asked for; empty for a free
     * entry, an object no section lists, or a different generation. For an object in an object
     * stream, the offset is the object stream's.
     *
     * @throws PdfFormatException if the entry doesn't lead to that object
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
        int at = offset(ref);
        if (reading.contains(ref)) {
            throw new PdfFormatException(at, "object " + ref
                    + " can't be read: its stream /Length refers back to the object itself");
        }
        if (reading.size() >= MAX_NESTED_READS) {
            throw new PdfFormatException(at, "object " + ref + " can't be read: objects"
                    + " nested more than " + MAX_NESTED_READS + " deep through indirect stream lengths");
        }
        reading.add(ref);
        try {
            PdfIndirectObject object = switch (entry) {
                case InFile inFile -> read(ref, inFile);
                case Compressed compressed -> readCompressed(ref, compressed);
                case Free free -> throw new IllegalStateException("free entry " + free);
            };
            cache.put(ref, object);
            return Optional.of(object);
        } finally {
            reading.remove(ref);
        }
    }

    private PdfIndirectObject read(PdfReference ref, InFile entry) {
        if (entry.offset() >= data.length) {
            throw new PdfFormatException(startxref(), "the xref entry for object " + ref + " has offset "
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

    // ---------------------------------------------------------------- object streams

    /**
     * A decoded object stream (7.5.7): {@code N} pairs of object number and offset, then the
     * objects, each at {@code /First} plus its offset.
     */
    private record ObjectStream(PdfReference reference, int fileOffset, byte[] data, int first,
            int[] numbers, int[] offsets) {
    }

    private PdfIndirectObject readCompressed(PdfReference ref, Compressed entry) {
        ObjectStream stream = objectStream(entry.streamNumber());
        String where = "object " + ref + " in object stream " + stream.reference();
        if (entry.index() >= stream.numbers().length) {
            throw new PdfFormatException(stream.fileOffset(), where + " is at index " + entry.index()
                    + ", but the stream holds " + stream.numbers().length + " objects");
        }
        int number = stream.numbers()[entry.index()];
        if (number != ref.objectNumber()) {
            throw new PdfFormatException(stream.fileOffset(), "object stream " + stream.reference()
                    + " holds object " + number + " at index " + entry.index() + ", not object "
                    + ref.objectNumber());
        }
        int at = stream.first() + stream.offsets()[entry.index()];
        PdfParser parser = new PdfParser(stream.data(), this::resolve);
        try {
            parser.seek(at);
            return new PdfIndirectObject(ref, parser.parseObject(), stream.fileOffset());
        } catch (PdfFormatException e) {
            throw new PdfFormatException(stream.fileOffset(), where + ", at offset " + at
                    + " of its decoded data: " + e.detail());
        }
    }

    private ObjectStream objectStream(int number) {
        ObjectStream cached = objectStreams.get(number);
        if (cached != null) {
            return cached;
        }
        Entry entry = entries.get(number);
        if (entry instanceof Compressed) {
            throw new PdfFormatException(startxref(), "object stream " + number
                    + " is itself inside an object stream, which isn't allowed (7.5.7)");
        }
        if (!(entry instanceof InFile inFile)) {
            throw new PdfFormatException(startxref(), "object stream " + number
                    + " isn't in the file: its entry is " + entry);
        }
        PdfReference ref = new PdfReference(number, inFile.generation());
        PdfObject object = resolve(ref);
        String name = "object stream " + ref;
        if (!(object instanceof PdfStream stream)
                || !PdfName.of("ObjStm").equals(stream.dictionary().get("Type"))) {
            throw new PdfFormatException(inFile.offset(), "object " + ref
                    + " holds compressed objects, but is not an object stream (/Type /ObjStm)");
        }
        int count = nonNegativeInt(stream.dictionary().get("N"), inFile.offset(), name + " /N");
        if (count > MAX_OBJECTS) {
            throw new PdfFormatException(inFile.offset(), name + " has /N " + count + ", more than the "
                    + MAX_OBJECTS + " objects a PDF may have");
        }
        int first = nonNegativeInt(stream.dictionary().get("First"), inFile.offset(), name + " /First");
        byte[] decoded;
        try {
            decoded = decoder.decode(stream);
        } catch (UnsupportedFilterException e) {
            throw new PdfFormatException(inFile.offset(), name + " can't be decoded: " + e.getMessage());
        }
        if (first > decoded.length) {
            throw new PdfFormatException(inFile.offset(), name + " has /First " + first
                    + ", past the end of its " + decoded.length + " decoded bytes");
        }
        List<Integer> numbers = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();
        PdfLexer lexer = new PdfLexer(decoded);
        try {
            for (int i = 0; i < count; i++) {
                numbers.add(nonNegativeInt(lexer.next(), name + " header's object number"));
                int offset = nonNegativeInt(lexer.next(), name + " header's object offset");
                if (offset > decoded.length - first) {
                    throw new PdfFormatException(lexer.position(), "offset " + offset
                            + " is past the end of the decoded data");
                }
                offsets.add(offset);
                if (lexer.position() > first) {
                    throw new PdfFormatException(lexer.position(), "the header runs past /First " + first);
                }
            }
        } catch (PdfFormatException e) {
            throw new PdfFormatException(inFile.offset(), name + " has a bad header, at offset "
                    + e.offset() + " of its decoded data: " + e.detail());
        }
        ObjectStream read = new ObjectStream(ref, inFile.offset(), decoded, first,
                numbers.stream().mapToInt(Integer::intValue).toArray(),
                offsets.stream().mapToInt(Integer::intValue).toArray());
        objectStreams.put(number, read);
        return read;
    }
}
