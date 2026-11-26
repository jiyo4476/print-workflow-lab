package dev.yjimmy.printlab.steps;

import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.Step;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.datamatrix.DataMatrixWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

/**
 * Stamps a Data Matrix barcode on every page of each PDF document, for inserter control.
 *
 * <p>Params: {@code template} (the value; see {@link BarcodeTemplate}), {@code position} ({@code
 * [x, y]}, the symbol's top-left corner in points from the top-left of the page) and {@code size}
 * ({@code [width, height]} in points, the box the symbol is scaled into, keeping square modules).
 * {@code symbology} is optional and must be {@code datamatrix}, the only one so far.
 *
 * <p>The barcode is drawn as vector shapes: one filled rectangle per run of dark modules in each
 * row, in the page's content stream, so it prints with sharp edges at any size. The clear space
 * around the symbol is the workflow designer's to leave: nothing is drawn in it.
 *
 * <p>The step rewrites each document as a stamped copy in {@code <work>/barcode/}. A document
 * that isn't a PDF, a value too long for the box, or a template placeholder with no value fails
 * the job, naming the document's sequence.
 */
public final class AddBarcodeStep implements Step {

    /** The smallest module printed, in points. One point is about 0.35 mm. */
    static final double MIN_MODULE_POINTS = 1.0;

    private static final String DATA_MATRIX = "datamatrix";

    private final BarcodeTemplate template;
    private final double x;
    private final double y;
    private final double width;
    private final double height;

    public AddBarcodeStep(Map<String, Object> rawParams) {
        Params params = new Params(rawParams);
        String symbology = params.string("symbology", DATA_MATRIX);
        if (!DATA_MATRIX.equals(symbology)) {
            throw new IllegalArgumentException("symbology must be '" + DATA_MATRIX
                    + "' (the only one so far), not '" + symbology + "'");
        }
        this.template = new BarcodeTemplate(params.string("template"));
        double[] position = points(params, "position");
        double[] size = points(params, "size");
        if (position[0] < 0 || position[1] < 0) {
            throw new IllegalArgumentException("Step param 'position' must not be negative");
        }
        if (size[0] <= 0 || size[1] <= 0) {
            throw new IllegalArgumentException("Step param 'size' must be positive");
        }
        this.x = position[0];
        this.y = position[1];
        this.width = size[0];
        this.height = size[1];
    }

    @Override
    public void execute(Job job) throws IOException {
        // Stamp everything first, then swap the stamped copies in: if one document fails, the
        // job's document list is left exactly as it was.
        List<Document> stamped = new ArrayList<>();
        for (Document doc : job.documents()) {
            stamped.add(stamp(job, doc));
        }
        List<Document> documents = job.documents();
        for (int i = 0; i < documents.size(); i++) {
            documents.set(i, stamped.get(i));
        }
    }

    private Document stamp(Job job, Document doc) throws IOException {
        if (!".pdf".equals(WriteOutputStep.extension(doc.file()))) {
            throw new IllegalStateException("Document " + doc.sequence() + " is not a PDF; "
                    + "AddBarcode stamps PDF documents only");
        }
        // A new file each time, so a second AddBarcode in the same job reads the first stamped
        // copy and writes its own, never over the file it is reading.
        Path dir = job.workDir().resolve("barcode");
        Files.createDirectories(dir);
        Path out = Files.createTempFile(dir, String.format("%05d-", doc.sequence()), ".pdf");
        try (PDDocument pdf = Loader.loadPDF(doc.file().toFile())) {
            int pageCount = pdf.getNumberOfPages();
            for (int page = 1; page <= pageCount; page++) {
                Map<String, String> values = new HashMap<>(doc.properties());
                values.put("jobId", job.id());
                values.put("sequence", String.valueOf(doc.sequence()));
                values.put("page", String.valueOf(page));
                values.put("pageCount", String.valueOf(pageCount));
                String value = template.render(values, doc.sequence());
                stampPage(pdf, pdf.getPage(page - 1), encode(value, doc.sequence()), doc.sequence(),
                        page);
            }
            pdf.save(out.toFile());
        }
        return new Document(doc.sequence(), out, doc.properties(), doc.inputSequence());
    }

    /** Draws the symbol on {@code page}, top-left at ({@link #x}, {@link #y}). */
    private void stampPage(PDDocument pdf, PDPage page, BitMatrix matrix, int sequence,
            int pageNumber)
            throws IOException {
        int columns = matrix.getWidth();
        int rows = matrix.getHeight();
        double module = Math.min(width / columns, height / rows);
        if (module < MIN_MODULE_POINTS) {
            throw new IllegalStateException("Document " + sequence + ": barcode value is too long "
                    + "for a " + width + " x " + height + " pt box (Data Matrix needs " + columns
                    + " x " + rows + " modules, under " + MIN_MODULE_POINTS + " pt each)");
        }
        PDRectangle box = page.getCropBox();
        double symbolWidth = columns * module;
        double symbolHeight = rows * module;
        if (x + symbolWidth > box.getWidth() || y + symbolHeight > box.getHeight()) {
            throw new IllegalStateException("Document " + sequence + " page " + pageNumber
                    + ": the barcode box falls off the page");
        }
        // PDF's origin is bottom-left; the configured position is measured from the top-left.
        double left = box.getLowerLeftX() + x;
        double top = box.getUpperRightY() - y;

        try (PDPageContentStream content = new PDPageContentStream(pdf, page,
                PDPageContentStream.AppendMode.APPEND, true, true)) {
            content.saveGraphicsState();
            content.setNonStrokingColor(0f);
            for (int row = 0; row < rows; row++) {
                double rectTop = top - row * module;
                int column = 0;
                while (column < columns) {
                    if (!matrix.get(column, row)) {
                        column++;
                        continue;
                    }
                    int start = column;
                    while (column < columns && matrix.get(column, row)) {
                        column++;
                    }
                    content.addRect((float) (left + start * module), (float) (rectTop - module),
                            (float) ((column - start) * module), (float) module);
                }
            }
            content.fill();
            content.restoreGraphicsState();
        }
    }

    /** The Data Matrix symbol for {@code value}, with no quiet zone (see the class comment). */
    private static BitMatrix encode(String value, int sequence) {
        try {
            return new DataMatrixWriter().encode(value, BarcodeFormat.DATA_MATRIX, 0, 0,
                    Map.of(EncodeHintType.MARGIN, 0));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Document " + sequence + ": barcode value is too long "
                    + "for a Data Matrix symbol (" + value.length() + " characters)", e);
        }
    }

    /** Two numbers from a list param, in points. */
    private static double[] points(Params params, String key) {
        List<String> items = params.stringList(key);
        if (items.size() != 2) {
            throw new IllegalArgumentException("Step param '" + key + "' must be [a, b] in points");
        }
        double[] values = new double[2];
        for (int i = 0; i < 2; i++) {
            try {
                values[i] = Double.parseDouble(items.get(i));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Step param '" + key + "' must be numbers in points");
            }
        }
        return values;
    }
}
