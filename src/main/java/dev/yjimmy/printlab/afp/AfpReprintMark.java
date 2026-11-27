package dev.yjimmy.printlab.afp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Marks an AFP document as a reprint (PRD-15, FR-9 to FR-13). Every copy gets:
 * <ul>
 *   <li>a {@code Reprint} TLE after its first named page group's BNG, valued with the parent job's
 *   id, so inserters and archives can tell it's a reprint;</li>
 *   <li>on every page, one presentation text object with the mark, added after the page's
 *   existing objects and just before its EPG. The page's own text is never edited.</li>
 * </ul>
 * The mark uses the font the page already maps in its MCF. A page that maps none gets the
 * generator's font under an unused local ID. The mark's position is in inches from the page's
 * top-left corner, converted with the page's own units from its PGD.
 *
 * <p>The document is streamed one field at a time, and one page at a time is held in memory.
 * Every field other than the ones inserted is copied byte for byte.
 */
public final class AfpReprintMark {

    /** The name of the TLE that marks a reprint. */
    public static final String REPRINT_TLE_NAME = "Reprint";
    /** The margin a right-aligned mark keeps from the page's right edge, in inches. */
    public static final double RIGHT_MARGIN_INCHES = 2.5;
    /** The default distance of the mark from the page's top edge, in inches. */
    public static final double DEFAULT_TOP_INCHES = 0.2;

    /**
     * Where the mark sits, in inches from the page's top-left corner. A null {@code x} puts the
     * mark {@link #RIGHT_MARGIN_INCHES} in from the right edge, whatever the page's width.
     */
    public record Position(Double x, double y) {

        public static final Position DEFAULT = new Position(null, DEFAULT_TOP_INCHES);
    }

    /** One field of a page, either as read from the input or new. */
    private record Piece(FieldType type, byte[] data, StructuredField original) {

        static Piece read(StructuredField field) {
            return new Piece(field.type(), null, field);
        }

        static Piece of(FieldType type, byte[] data) {
            return new Piece(type, data, null);
        }

        void writeTo(AfpWriter out) throws IOException {
            if (original != null) {
                out.write(original);
            } else {
                out.write(type, data);
            }
        }

        byte[] bytes() {
            return original != null ? original.data() : data;
        }
    }

    private AfpReprintMark() {}

    /**
     * Copies the AFP document {@code in} to {@code out} with the reprint marks added. The output
     * is not closed.
     *
     * @param parentJobId the id of the job the reprint came from, which the TLE holds
     * @param text the mark's text, in the EBCDIC code page the generator uses
     * @throws IllegalStateException if the document has no page group to carry the TLE, or a page
     *     has no PGD, no environment group, or a mark that would fall off the page
     * @throws IOException if the input can't be read or the output can't be written
     */
    public static void mark(InputStream in, OutputStream out, String parentJobId, String text,
            Position position) throws IOException {
        AfpWriter writer = new AfpWriter(out);
        boolean tleWritten = false;
        List<Piece> page = null;
        try (AfpReader reader = new AfpReader(in)) {
            while (reader.hasNext()) {
                StructuredField field = reader.next();
                if (page != null) {
                    page.add(Piece.read(field));
                    if (field.type() == FieldType.EPG) {
                        for (Piece piece : markPage(page, text, position)) {
                            piece.writeTo(writer);
                        }
                        page = null;
                    }
                    continue;
                }
                if (field.type() == FieldType.BPG) {
                    page = new ArrayList<>();
                    page.add(Piece.read(field));
                    continue;
                }
                writer.write(field);
                if (field.type() == FieldType.BNG && !tleWritten) {
                    writer.write(FieldType.TLE, TleEncoder.encode(REPRINT_TLE_NAME, parentJobId));
                    tleWritten = true;
                }
            }
        }
        if (page != null) {
            throw new IllegalStateException("the AFP document ends inside a page group");
        }
        if (!tleWritten) {
            throw new IllegalStateException("the AFP document has no named page group to carry"
                    + " the Reprint TLE");
        }
        writer.flush();
    }

    /** The page's pieces with the mark added, in the order they are to be written. */
    private static List<Piece> markPage(List<Piece> page, String text, Position position) {
        PageBuilder.PageSize size = pageSize(page);
        int bagStart = indexOf(page, FieldType.BAG, 0);
        if (bagStart < 0) {
            throw new IllegalStateException("a page has no environment group (BAG), so it has no"
                    + " font to mark with");
        }
        int bagEnd = indexOf(page, FieldType.EAG, bagStart);
        if (bagEnd < 0) {
            throw new IllegalStateException("a page's environment group is not ended (EAG)");
        }

        List<Piece> marked = new ArrayList<>(page);
        int fontId;
        int mcf = indexOf(marked, FieldType.MCF, bagStart);
        if (mcf >= 0 && mcf < bagEnd) {
            OptionalInt existing = PageBuilder.codedFontLocalId(marked.get(mcf).bytes());
            if (existing.isPresent()) {
                fontId = existing.getAsInt();
            } else {
                // The page maps no font: add the generator's under an unused local ID.
                PageBuilder.McfWithFont added = PageBuilder.withFontAdded(marked.get(mcf).bytes());
                marked.set(mcf, Piece.of(FieldType.MCF, added.data()));
                fontId = added.localId();
            }
        } else {
            // No MCF in the environment: add one that maps the generator's font.
            PageBuilder.McfWithFont added = PageBuilder.withFontAdded(new byte[0]);
            marked.add(bagEnd, Piece.of(FieldType.MCF, added.data()));
            fontId = added.localId();
        }

        double xInches = position.x() != null ? position.x()
                : size.width() / size.xUnitsPerInch() - RIGHT_MARGIN_INCHES;
        int x = (int) Math.round(xInches * size.xUnitsPerInch());
        int y = (int) Math.round(position.y() * size.yUnitsPerInch());
        if (x < 0 || y < 0 || x >= size.width() || y >= size.height()) {
            throw new IllegalStateException("the reprint mark at (" + xInches + ", "
                    + position.y() + ") inches falls off the page");
        }
        byte[] ptoca = PageBuilder.ptoca(fontId, List.of(new PageBuilder.TextRun(x, y,
                Ebcdic.encodeStrict(text, Ebcdic.IBM037))));

        int end = marked.size() - 1;
        if (marked.get(end).type() != FieldType.EPG) {
            throw new IllegalStateException("a page does not end with EPG");
        }
        marked.add(end, Piece.of(FieldType.BPT, new byte[0]));
        marked.add(end + 1, Piece.of(FieldType.PTX, ptoca));
        marked.add(end + 2, Piece.of(FieldType.EPT, new byte[0]));
        return marked;
    }

    private static PageBuilder.PageSize pageSize(List<Piece> page) {
        int pgd = indexOf(page, FieldType.PGD, 0);
        if (pgd < 0) {
            throw new IllegalStateException("a page has no page descriptor (PGD), so its size and"
                    + " units are unknown");
        }
        return PageBuilder.decodePgd(page.get(pgd).bytes());
    }

    private static int indexOf(List<Piece> pieces, FieldType type, int from) {
        for (int i = from; i < pieces.size(); i++) {
            if (pieces.get(i).type() == type) {
                return i;
            }
        }
        return -1;
    }
}
