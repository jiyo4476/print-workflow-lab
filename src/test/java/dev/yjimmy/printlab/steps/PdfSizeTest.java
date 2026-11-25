package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yjimmy.printlab.pdf.PdfDocumentReader;
import dev.yjimmy.printlab.pdf.PdfNumber;
import dev.yjimmy.printlab.testdata.StatementDataGenerator;
import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import dev.yjimmy.printlab.testsupport.PdfBytes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Reading a PDF's object count from its last trailer, without loading it. */
public class PdfSizeTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path write(byte[] bytes) throws Exception {
        return Files.write(folder.newFile().toPath(), bytes);
    }

    private PdfBytes pdf() {
        return new PdfBytes("1.7")
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                .object(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>");
    }

    @Test
    public void readsTheSizeOfAClassicTrailer() throws Exception {
        Path file = write(pdf().build("/Root 1 0 R"));

        assertThat(PdfSize.objects(file)).hasValue(4); // objects 0 to 3
    }

    @Test
    public void readsTheNewestTrailerOfAnIncrementallyUpdatedFile() throws Exception {
        PdfBytes pdf = pdf();
        pdf.build("/Root 1 0 R");
        pdf.object(9, "<< >>");
        Path file = write(pdf.update("/Root 1 0 R"));

        assertThat(PdfSize.objects(file)).hasValue(10);
    }

    @Test
    public void readsTheDictionaryOfACrossReferenceStreamWhenItIsOutsideTheTail() throws Exception {
        // 700 rows of 7 bytes put the stream's dictionary well before the last 4 KB of the file.
        PdfBytes pdf = pdf();
        for (int n = 10; n < 710; n++) {
            pdf.object(n, "null");
        }
        byte[] bytes = pdf.buildWithXrefStream(710, "/Root 1 0 R");
        assertThat(bytes.length).isGreaterThan(5000);
        assertThat(new String(bytes, bytes.length - 4096, 4096, StandardCharsets.ISO_8859_1))
                .as("the dictionary isn't in the tail").doesNotContain("/Size");

        assertThat(PdfSize.objects(write(bytes))).hasValue(711);
    }

    @Test
    public void agreesWithTheProjectReaderOnAGeneratedFile() throws Exception {
        Path file = folder.getRoot().toPath().resolve("run.pdf");
        StatementPdfGenerator.write(new StatementDataGenerator(3).generate(200, 0), file, true);
        PdfNumber size = (PdfNumber) PdfDocumentReader.open(file).trailer().get("Size");

        assertThat(PdfSize.objects(file)).hasValue(size.intValue());
        assertThat(size.intValue()).isGreaterThan(1000);
    }

    @Test
    public void givesUpQuietlyOnAnythingItCannotRead() throws Exception {
        assertThat(PdfSize.objects(write(new byte[0]))).isEmpty();
        assertThat(PdfSize.objects(write("not a pdf at all".getBytes(StandardCharsets.US_ASCII))))
                .isEmpty();
        assertThat(PdfSize.objects(folder.getRoot().toPath().resolve("missing.pdf"))).isEmpty();
        // startxref pointing past the end, or at something with no /Size
        assertThat(PdfSize.objects(write("%PDF-1.7\nstartxref\n99999\n%%EOF"
                .getBytes(StandardCharsets.US_ASCII)))).isEmpty();
        assertThat(PdfSize.objects(write("%PDF-1.7\nstartxref\n3\n%%EOF"
                .getBytes(StandardCharsets.US_ASCII)))).isEmpty();
    }

    @Test
    public void aSizeTooLargeToBeRealIsNotTaken() throws Exception {
        Path file = write(("%PDF-1.7\ntrailer\n<< /Size 99999999999999999999 /Root 1 0 R >>\n"
                + "startxref\n0\n%%EOF").getBytes(StandardCharsets.US_ASCII));

        assertThat(PdfSize.objects(file)).isEmpty();
    }

    @Test
    public void aTrailerSizeInsideAnEarlierPartOfTheFileIsNotTheLastOne() throws Exception {
        // Two trailers in the tail: the later one wins.
        Path file = write(("%PDF-1.7\ntrailer\n<< /Size 5 >>\nstartxref\n0\n%%EOF\n"
                + "trailer\n<< /Size 9 /Prev 0 >>\nstartxref\n0\n%%EOF").getBytes(
                StandardCharsets.US_ASCII));

        assertThat(PdfSize.objects(file)).hasValue(9);
    }
}
