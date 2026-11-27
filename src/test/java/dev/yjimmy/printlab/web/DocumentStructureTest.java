package dev.yjimmy.printlab.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yjimmy.printlab.testdata.StatementAfpGenerator;
import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The structure bodies stop at their item cap and say so. */
public class DocumentStructureTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void afpUnderTheCapIsNotTruncated() throws Exception {
        Path file = afpFile();

        var all = DocumentStructure.afp(file, 1_000_000);

        assertThat(all.truncated()).isFalse();
        assertThat(all.fields()).hasSizeGreaterThan(10);
    }

    @Test
    public void afpStopsAtTheCapAndSaysTruncated() throws Exception {
        Path file = afpFile();

        var capped = DocumentStructure.afp(file, 5);

        assertThat(capped.fields()).hasSize(5);
        assertThat(capped.truncated()).isTrue();
        assertThat(capped.fields().get(0).type()).isEqualTo("BDT");
    }

    @Test
    public void pdfStopsAtTheCapAndSaysTruncated() throws Exception {
        Path file = pdfFile();

        var all = DocumentStructure.pdf(file, 1_000_000);
        var capped = DocumentStructure.pdf(file, 3);

        assertThat(all.truncated()).isFalse();
        assertThat(capped.truncated()).isTrue();
        int operators = capped.pages().stream().mapToInt(p -> p.operators().size()).sum();
        assertThat(operators).isEqualTo(3);
    }

    @Test
    public void pdfExactlyAtTheCapIsNotTruncated() throws Exception {
        Path file = pdfFile();
        int total = DocumentStructure.pdf(file, 1_000_000).pages().stream()
                .mapToInt(p -> p.operators().size()).sum();

        var exact = DocumentStructure.pdf(file, total);

        assertThat(exact.truncated()).isFalse();
    }

    private Path afpFile() throws Exception {
        Path file = tmp.newFolder().toPath().resolve("statement.afp");
        new StatementAfpGenerator(1).generate(2, 0, file);
        return file;
    }

    private Path pdfFile() throws Exception {
        Path file = tmp.newFolder().toPath().resolve("statement.pdf");
        new StatementPdfGenerator(1).generate(1, 0, false,
                StatementPdfGenerator.Defects.NONE, file);
        return file;
    }
}
