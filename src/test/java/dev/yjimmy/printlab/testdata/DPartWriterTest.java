package dev.yjimmy.printlab.testdata;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yjimmy.printlab.pdf.DPartTree;
import dev.yjimmy.printlab.pdf.DPartTree.DPart;
import dev.yjimmy.printlab.pdf.PdfDocumentReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** What {@code generate --dpart} writes: one DPart per statement, covering every page once. */
public class DPartWriterTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void everyPageIsCoveredExactlyOnceInOrder() throws Exception {
        List<Statement> statements = new StatementDataGenerator(11).generate(40, 3);
        int totalPages = statements.stream().mapToInt(Statement::pageCount).sum();
        Path out = folder.getRoot().toPath().resolve("run.pdf");
        StatementPdfGenerator.write(statements, out, true);

        List<DPart> documents = tree(out).atLevel(1);

        assertThat(documents).hasSize(statements.size());
        int nextPage = 1;
        for (DPart document : documents) {
            assertThat(document.startPage()).as("first page").isEqualTo(nextPage);
            assertThat(document.endPage()).as("last page").isGreaterThanOrEqualTo(document.startPage());
            nextPage = document.endPage() + 1;
        }
        assertThat(nextPage - 1).as("pages covered").isEqualTo(totalPages);
    }

    @Test
    public void dpmEntriesMatchTheStatements() throws Exception {
        List<Statement> statements = new StatementDataGenerator(11).generate(12, 2);
        Path out = folder.getRoot().toPath().resolve("run.pdf");
        StatementPdfGenerator.write(statements, out, true);

        List<DPart> documents = tree(out).atLevel(1);

        for (int i = 0; i < statements.size(); i++) {
            Statement statement = statements.get(i);
            assertThat(documents.get(i).dpm()).isEqualTo(Map.of(
                    "AccountNumber", statement.accountNumber(),
                    "Name", statement.name(),
                    "Zip", statement.zip()));
            assertThat(documents.get(i).endPage() - documents.get(i).startPage() + 1)
                    .isEqualTo(statement.pageCount());
        }
    }

    @Test
    public void plainWriteHasNoDpartTree() throws Exception {
        Path out = folder.getRoot().toPath().resolve("plain.pdf");
        StatementPdfGenerator.write(new StatementDataGenerator(11).generate(3, 0), out);

        assertThat(DPartTree.read(PdfDocumentReader.open(out))).isEmpty();
    }

    private static DPartTree tree(Path file) throws Exception {
        return DPartTree.read(PdfDocumentReader.open(file)).orElseThrow();
    }
}
