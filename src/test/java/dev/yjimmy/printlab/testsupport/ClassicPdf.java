package dev.yjimmy.printlab.testsupport;

import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Writes a generated statement PDF with a classic cross-reference table and no object streams
 * ({@code CompressParameters.NO_COMPRESSION}): the structure PR 7 reads. Content streams stay
 * Flate-compressed. The {@code /ID} comes from the seed, so the same seed gives the same bytes,
 * and {@link Change} can alter the document first (a PDF 2.0 header, an indirect /Length).
 *
 * <p>{@code main} rewrites the checked-in fixture:
 * <pre>
 * ./mvnw -q test-compile exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=dev.yjimmy.printlab.testsupport.ClassicPdf \
 *     -Dexec.args="7 2 src/test/resources/fixtures/pdf/two-statements.pdf"
 * </pre>
 */
public final class ClassicPdf {

    /** Changes a loaded document before it's saved. */
    @FunctionalInterface
    public interface Change {
        void apply(PDDocument document) throws IOException;
    }

    private ClassicPdf() {
    }

    public static void main(String[] args) throws IOException {
        write(Long.parseLong(args[0]), Integer.parseInt(args[1]), Path.of(args[2]), doc -> { });
    }

    public static void write(long seed, int statements, Path out) throws IOException {
        write(seed, statements, out, doc -> { });
    }

    /** Generates {@code statements} for {@code seed}, applies {@code change}, and saves to {@code out}. */
    public static void write(long seed, int statements, Path out, Change change) throws IOException {
        try (PDDocument doc = new StatementPdfGenerator(seed).document(statements)) {
            Random random = new Random(seed);
            byte[] id = new byte[16];
            random.nextBytes(id);
            COSArray ids = new COSArray();
            ids.add(new COSString(id));
            ids.add(new COSString(id));
            doc.getDocument().getTrailer().setItem(COSName.ID, ids);
            change.apply(doc);
            doc.save(out.toFile(), CompressParameters.NO_COMPRESSION);
        }
    }
}
