package dev.yjimmy.printlab.steps;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;

/**
 * Copies a page into another document without what it points back to.
 *
 * <p>PDFBox's {@code importPage} copies the page's dictionary as it is, and everything the
 * dictionary refers to comes with it when the new document is saved. Some entries of a page refer
 * to the rest of the <em>source</em> file:
 * <ul>
 *   <li>{@code /DPart}: a PDF/VT page's node in the document-part tree, whose {@code /Parent}
 *       chain leads to the whole tree, with every other document's index values (names, account
 *       numbers, zips) and a reference to every other page;</li>
 *   <li>{@code /B}: the page's article beads, threaded through pages elsewhere in the file;</li>
 *   <li>{@code /PieceInfo}: private data of the application that wrote the file;</li>
 *   <li>link annotations, whose {@code /Dest} or action names another page. They draw nothing on
 *       paper.</li>
 * </ul>
 * Left in, each output document would carry copies of the other documents' pages and index
 * values, which for a mailing run means every customer's PDF holding every other customer's name
 * and address, and an output that grows with the square of the run.
 */
final class PdfPages {

    private static final COSName[] BACK_POINTERS = {
        COSName.getPDFName("DPart"), COSName.getPDFName("B"), COSName.getPDFName("PieceInfo"),
    };

    private PdfPages() {
    }

    /** Imports {@code page} into {@code target}, minus the entries that point into its source. */
    static PDPage importPage(PDDocument target, PDPage page) throws IOException {
        PDPage imported = target.importPage(page);
        COSDictionary dictionary = imported.getCOSObject();
        for (COSName key : BACK_POINTERS) {
            dictionary.removeItem(key);
        }
        dropLinkAnnotations(imported);
        return imported;
    }

    private static void dropLinkAnnotations(PDPage imported) {
        List<PDAnnotation> annotations;
        try {
            annotations = imported.getAnnotations();
        } catch (IOException | RuntimeException e) {
            return; // unreadable annotations are copied as they were
        }
        List<PDAnnotation> kept = new ArrayList<>(annotations);
        if (kept.removeIf(annotation -> annotation instanceof PDAnnotationLink)) {
            imported.setAnnotations(kept);
        }
    }
}
