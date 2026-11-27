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
    public void afpDocumentFailsTheJobRatherThanGoingOutUnmarked() throws Exception {
        Path afp = Files.writeString(tmp.resolve("a.afp"), "afp");
        job.documents().add(new Document(1, afp, Map.of(), 1));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new MarkReprintStep(Map.of()).execute(job));

        assertThat(e.getMessage()).contains("AFP");
        assertThat(job.documents().get(0).file()).isEqualTo(afp);
    }

    @Test
    public void positionMustBeTwoNonNegativeNumbers() {
        assertThrows(IllegalArgumentException.class,
                () -> new MarkReprintStep(Map.<String, Object>of("position", List.of("1"))));
        assertThrows(IllegalArgumentException.class,
                () -> new MarkReprintStep(Map.<String, Object>of("position", List.of("-1", "4"))));
    }
}
