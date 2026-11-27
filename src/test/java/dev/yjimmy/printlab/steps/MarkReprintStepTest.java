package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MarkReprintStepTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path tmp;
    private Job job;

    @Before
    public void setUp() throws Exception {
        tmp = folder.getRoot().toPath();
        job = TestJobs.newJob(tmp, tmp.resolve("in.pdf"));
    }

    /** Writes a PDF with {@code pages} pages, each with the text "statement body". */
    static Path pdf(Path file, int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 600);
                    content.showText("statement body");
                    content.endText();
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /** The text of each page of the PDF, in order. */
    static String[] pageTexts(Path file) throws IOException {
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            String[] texts = new String[doc.getNumberOfPages()];
            for (int page = 1; page <= texts.length; page++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                texts[page - 1] = stripper.getText(doc);
            }
            return texts;
        }
    }

    private static int count(String text, String word) {
        return text.split(word, -1).length - 1;
    }

    @Test
    public void markIsOnEveryPageAndTheOriginalTextIsKept() throws Exception {
        job.documents().add(new Document(1, pdf(tmp.resolve("a.pdf"), 3), Map.of(), 1));

        new MarkReprintStep(Map.of()).execute(job);

        String[] pages = pageTexts(job.documents().get(0).file());
        assertThat(pages).hasSize(3);
        for (String page : pages) {
            assertThat(count(page, "REPRINT")).as("one mark per page").isEqualTo(1);
            assertThat(page).contains("statement body");
        }
    }

    @Test
    public void markingAgainDoesNotStampTheDocumentTwice() throws Exception {
        job.documents().add(new Document(1, pdf(tmp.resolve("a.pdf"), 2), Map.of(), 1));
        MarkReprintStep step = new MarkReprintStep(Map.of());
        step.execute(job);
        Path once = job.documents().get(0).file();

        step.execute(job);

        assertThat(job.documents().get(0).file()).as("the marked copy is not replaced")
                .isEqualTo(once);
        assertThat(pageTexts(once)[0]).containsOnlyOnce("REPRINT");
    }

    @Test
    public void theOriginalFileIsNotWrittenOver() throws Exception {
        Path original = pdf(tmp.resolve("a.pdf"), 1);
        byte[] before = Files.readAllBytes(original);
        job.documents().add(new Document(1, original, Map.of(), 1));

        new MarkReprintStep(Map.of()).execute(job);

        assertThat(Files.readAllBytes(original)).isEqualTo(before);
        assertThat(job.documents().get(0).file()).isNotEqualTo(original);
    }

    @Test
    public void afpDocumentIsMarkedWithTheReprintTleAndAPagePerMark() throws Exception {
        Path afp = afpFile(tmp.resolve("a.afp"));
        byte[] original = Files.readAllBytes(afp);
        job.documents().add(new Document(1, afp, Map.of(), 1));
        job.properties().put("parentJobId", "job-parent");

        new MarkReprintStep(Map.of()).execute(job);

        Path marked = job.documents().get(0).file();
        assertThat(marked).isNotEqualTo(afp);
        assertThat(Files.readAllBytes(afp)).as("the original is not written over")
                .isEqualTo(original);
        assertThat(job.documents().get(0).property("reprintMarked")).isEqualTo("yes");
        assertThat(marked.getFileName().toString()).endsWith(".afp");
        long reprintTles = reprintTles(Files.readAllBytes(marked));
        assertThat(reprintTles).isEqualTo(1);
    }

    @Test
    public void markingAfpAgainDoesNotMarkItTwice() throws Exception {
        job.documents().add(new Document(1, afpFile(tmp.resolve("a.afp")), Map.of(), 1));
        job.properties().put("parentJobId", "job-parent");
        MarkReprintStep step = new MarkReprintStep(Map.of());
        step.execute(job);
        Path once = job.documents().get(0).file();

        step.execute(job);

        assertThat(job.documents().get(0).file()).isEqualTo(once);
    }

    @Test
    public void afpReprintWithoutAParentJobFails() throws Exception {
        job.documents().add(new Document(1, afpFile(tmp.resolve("a.afp")), Map.of(), 1));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new MarkReprintStep(Map.of()).execute(job));

        assertThat(e.getMessage()).contains("parent");
    }

    @Test
    public void documentThatIsNeitherPdfNorAfpIsRefused() throws Exception {
        job.documents().add(new Document(1, Files.writeString(tmp.resolve("a.txt"), "x"),
                Map.of(), 1));

        assertThrows(IllegalStateException.class,
                () -> new MarkReprintStep(Map.of()).execute(job));
    }

    /** How many Reprint TLEs the AFP bytes hold. */
    private static long reprintTles(byte[] afp) throws java.io.IOException {
        long count = 0;
        try (dev.yjimmy.printlab.afp.AfpReader reader = new dev.yjimmy.printlab.afp.AfpReader(
                new java.io.ByteArrayInputStream(afp))) {
            while (reader.hasNext()) {
                var field = reader.next();
                if (field.type() == dev.yjimmy.printlab.afp.FieldType.TLE
                        && dev.yjimmy.printlab.afp.TleDecoder.decode(field).name()
                                .equals("Reprint")) {
                    count++;
                }
            }
        }
        return count;
    }

    /** A one-statement AFP document, as the generator writes it. */
    private static Path afpFile(Path file) throws IOException {
        try (java.io.OutputStream out = Files.newOutputStream(file)) {
            dev.yjimmy.printlab.testdata.StatementAfpGenerator.write(
                    new dev.yjimmy.printlab.testdata.StatementDataGenerator(5).generate(1, 0), out);
        }
        return file;
    }

    @Test
    public void positionMustBeTwoNonNegativeNumbers() {
        assertThrows(IllegalArgumentException.class,
                () -> new MarkReprintStep(Map.<String, Object>of("position", List.of("1"))));
        assertThrows(IllegalArgumentException.class,
                () -> new MarkReprintStep(Map.<String, Object>of("position", List.of("-1", "4"))));
    }
}
