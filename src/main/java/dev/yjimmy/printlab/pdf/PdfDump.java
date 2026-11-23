package dev.yjimmy.printlab.pdf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Prints a PDF's structure for {@code App dump-pdf}:
 * <pre>
 * PDF       1.4
 * Trailer   &lt;&lt;/Size 12 /Root 1 0 R&gt;&gt;
 * Objects   11 in use, 1 free; xref at offset 0x00000A1B (2587)
 * Catalog   1 0 R  &lt;&lt;/Type /Catalog /Pages 2 0 R&gt;&gt;
 * Pages     2
 * Page 1    3 0 R
 *   MediaBox  [0 0 612 792]
 *   Fonts     /F1 Helvetica, /F2 Helvetica-Bold
 *   Contents  4 0 R  /FlateDecode, 412 bytes, 1035 decoded
 * </pre>
 * or, for one object, its dictionary one entry per line and its stream decoded when that gives
 * text. Strings are printed as PDF syntax ({@code (text)} or {@code <hex>}), so a file's bytes
 * never reach the terminal as control characters. A stream that can't be decoded is reported on
 * its line and the dump goes on. Lines end with {@code \n} on every platform, so the output can
 * be compared with a golden file.
 */
public final class PdfDump {

    /** Dumps the document: version, trailer, catalog and every page. */
    public void dump(PdfDocumentReader pdf, Appendable out) {
        line(out, "PDF       " + version(pdf));
        if (pdf.isEncrypted()) {
            line(out, "Encrypted yes: strings and streams are shown as stored, not decrypted");
        }
        line(out, "Trailer   " + pdf.trailer());
        XrefReader xref = pdf.xref();
        long inUse = xref.entries().stream().filter(XrefReader.Entry::inUse).count();
        long compressed = xref.entries().stream().filter(e -> e instanceof XrefReader.Compressed).count();
        XrefReader.Section section = xref.sections().get(xref.revision() - 1);
        line(out, String.format("Objects   %d in use%s, %d free; xref %sat offset 0x%08X (%d)", inUse,
                compressed > 0 ? " (" + compressed + " in object streams)" : "",
                xref.entries().size() - inUse, section.isStream() ? section.kind() + " " : "",
                xref.startxref(), xref.startxref()));
        if (xref.revisions() > 1) {
            line(out, "Revisions " + xref.revisions() + ", showing " + xref.revision()
                    + "; dump-pdf --xref lists what each update changed");
        }
        line(out, "Catalog   " + pdf.trailer().get("Root") + "  " + pdf.catalog());
        List<PdfPage> pages = pdf.pages();
        line(out, "Pages     " + pages.size());
        for (PdfPage page : pages) {
            line(out, String.format("%-9s %s", "Page " + page.number(),
                    page.reference() == null ? "(direct)" : page.reference()));
            line(out, "  MediaBox  " + page.mediaBox().map(Object::toString).orElse("none"));
            line(out, "  Fonts     " + fonts(pdf, page));
            List<PdfIndirectObject> contents = pdf.contentStreams(page);
            if (contents.isEmpty()) {
                line(out, "  Contents  none");
            }
            for (PdfIndirectObject content : contents) {
                line(out, "  Contents  " + content.reference() + "  "
                        + describe(pdf, (PdfStream) content.object()).summary());
            }
        }
    }

    /**
     * Dumps object {@code objectNumber} and returns true, or prints why it can't and returns
     * false (a free object, or one the table doesn't have).
     */
    public boolean dumpObject(PdfDocumentReader pdf, int objectNumber, Appendable out) {
        Optional<XrefReader.Entry> entry = pdf.xref().entry(objectNumber);
        if (entry.isEmpty()) {
            line(out, "Object " + objectNumber + " is not in the cross-reference table");
            return false;
        }
        if (!entry.get().inUse()) {
            line(out, "Object " + objectNumber + " is free");
            return false;
        }
        PdfReference ref = new PdfReference(objectNumber, entry.get().generation());
        PdfIndirectObject object = pdf.xref().indirectObject(ref).orElseThrow();
        if (entry.get() instanceof XrefReader.Compressed compressed) {
            line(out, String.format("%d %d obj  in object stream %d, index %d", ref.objectNumber(),
                    ref.generation(), compressed.streamNumber(), compressed.index()));
        } else {
            line(out, String.format("%d %d obj  at offset 0x%08X (%d)", ref.objectNumber(),
                    ref.generation(), object.offset(), object.offset()));
        }
        PdfObject value = object.object();
        if (value instanceof PdfStream stream) {
            dictionary(stream.dictionary(), out);
            Decoded decoded = describe(pdf, stream);
            if (decoded.data() != null && isText(decoded.data())) {
                line(out, "stream  " + decoded.summary() + ":");
                String text = new String(decoded.data(), StandardCharsets.ISO_8859_1)
                        .replace("\r\n", "\n").replace('\r', '\n');
                append(out, text.endsWith("\n") ? text : text + "\n");
                line(out, "endstream");
            } else {
                line(out, "stream  " + decoded.summary()
                        + (decoded.data() != null ? ", binary, not shown" : ""));
            }
        } else if (value instanceof PdfDictionary dictionary) {
            dictionary(dictionary, out);
        } else {
            line(out, value.toString());
        }
        return true;
    }

    /**
     * Lists the cross-reference sections, one per revision, oldest first:
     * <pre>
     * Revision 1  table at 0x000011DB (4571): 57 entries
     * Revision 2  stream 60 0 R at 0x00001A2B (6699): 3 entries, /Prev 4571
     *   Changed   2
     *   Added     58
     *   Freed     none
     * </pre>
     * An update's entries are sorted into objects it changed (in use before), added (new or
     * reused numbers) and freed (in use before, free now).
     */
    public void dumpXref(PdfDocumentReader pdf, Appendable out) {
        List<XrefReader.Section> sections = pdf.xref().sections();
        Map<Integer, XrefReader.Entry> before = new TreeMap<>();
        for (int i = 0; i < sections.size(); i++) {
            XrefReader.Section section = sections.get(i);
            long compressed = section.entries().values().stream()
                    .filter(e -> e instanceof XrefReader.Compressed).count();
            line(out, String.format("Revision %d  %s at 0x%08X (%d): %d entries%s%s", i + 1,
                    section.kind(), section.offset(), section.offset(), section.entries().size(),
                    compressed > 0 ? " (" + compressed + " in object streams)" : "",
                    section.trailer().get("Prev") != null ? ", /Prev " + section.trailer().get("Prev") : ""));
            if (i > 0) {
                List<String> changed = new ArrayList<>();
                List<String> added = new ArrayList<>();
                List<String> freed = new ArrayList<>();
                section.entries().forEach((number, entry) -> {
                    boolean wasInUse = before.get(number) != null && before.get(number).inUse();
                    if (entry.inUse()) {
                        (wasInUse ? changed : added).add(String.valueOf(number));
                    } else if (wasInUse) {
                        freed.add(String.valueOf(number));
                    }
                });
                line(out, "  Changed   " + list(changed));
                line(out, "  Added     " + list(added));
                line(out, "  Freed     " + list(freed));
            }
            before.putAll(section.entries());
        }
    }

    private static String list(List<String> numbers) {
        return numbers.isEmpty() ? "none" : String.join(", ", numbers);
    }

    private static String version(PdfDocumentReader pdf) {
        PdfVersion version = pdf.version();
        StringBuilder text = new StringBuilder(version.toString());
        if (!version.equals(pdf.headerVersion())) {
            text.append(" (header ").append(pdf.headerVersion()).append(", raised by the catalog's /Version)");
        }
        if (!version.isKnown()) {
            text.append(" (not a version this reader knows; read as PDF ")
                    .append(PdfVersion.LATEST_KNOWN).append(")");
        }
        return text.toString();
    }

    /** {@code /F1 Helvetica, /F2 Helvetica-Bold}: each font resource's name and base font. */
    private static String fonts(PdfDocumentReader pdf, PdfPage page) {
        if (!(pdf.resolve(page.resources().get("Font")) instanceof PdfDictionary fonts)
                || fonts.entries().isEmpty()) {
            return "none";
        }
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, PdfObject> font : fonts.entries().entrySet()) {
            String name = "/" + font.getKey();
            if (pdf.resolve(font.getValue()) instanceof PdfDictionary dictionary
                    && pdf.resolve(dictionary.get("BaseFont")) instanceof PdfName base) {
                name += " " + base.name();
            }
            names.add(name);
        }
        return String.join(", ", names);
    }

    /** A stream's decoded data (null if it couldn't be decoded) and a one-line summary. */
    private record Decoded(byte[] data, String summary) {
    }

    private static Decoded describe(PdfDocumentReader pdf, PdfStream stream) {
        StringBuilder summary = new StringBuilder();
        try {
            List<String> filters;
            try {
                filters = pdf.filters(stream);
            } catch (PdfFormatException e) {
                return new Decoded(null, stream.length() + " bytes, can't decode: " + e.getMessage());
            }
            for (String filter : filters) {
                summary.append('/').append(filter).append(", ");
            }
            summary.append(stream.length()).append(" bytes");
            byte[] data = pdf.decode(stream);
            if (!filters.isEmpty()) {
                summary.append(", ").append(data.length).append(" decoded");
            }
            return new Decoded(data, summary.toString());
        } catch (UnsupportedFilterException e) {
            return new Decoded(null, summary + ", not decoded: " + e.getMessage());
        } catch (PdfFormatException e) {
            return new Decoded(null, summary + ", can't decode: " + e.getMessage());
        }
    }

    /** True when every byte is printable ASCII, tab, CR or LF. */
    static boolean isText(byte[] data) {
        for (byte b : data) {
            if ((b < 0x20 || b > 0x7E) && b != '\t' && b != '\r' && b != '\n') {
                return false;
            }
        }
        return true;
    }

    /** One entry per line, so a page or font dictionary is easy to read. */
    private static void dictionary(PdfDictionary dictionary, Appendable out) {
        if (dictionary.entries().isEmpty()) {
            line(out, "<<>>");
            return;
        }
        line(out, "<<");
        dictionary.entries().forEach((key, value) -> line(out, "  /" + key + " " + value));
        line(out, ">>");
    }

    private static void line(Appendable out, String text) {
        append(out, text + "\n");
    }

    private static void append(Appendable out, String text) {
        try {
            out.append(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
